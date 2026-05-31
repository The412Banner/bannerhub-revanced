package com.xj.winemu.perf;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * BhPerfController — applies/reverts the two root-gated in-game performance
 * toggles and owns their state. All sysfs writes go through {@link BhPerfRoot}
 * (a {@code su} shell) on a dedicated background thread; the UI never blocks.
 *
 * Toggle 1 — Sustained Performance Mode:
 *   ON  : every CPU core's cpufreq governor -> "performance" (no downclock)
 *   OFF : restore "schedutil"
 *
 * Toggle 2 — Max Adreno Clocks:
 *   ON  : KGSL devfreq min_freq := max_freq (GPU can't drop below ceiling)
 *   OFF : min_freq := 0 (driver resumes normal DVFS)
 *
 * SAFETY: these force CPU/GPU to max system-wide and generate heat. Both are
 * auto-reverted when the game exits (WineActivity.onDestroy ->
 * {@link BhPerfOverlay#revertAndDetach}). The "applied" flags are in-memory and
 * per-session only: every game launch starts with both OFF and the hardware at
 * defaults, so a forgotten toggle can never carry across sessions.
 *
 * Only {@code root_granted} and {@code pill_y} persist (prefs file
 * {@value #PREFS}); the toggle states deliberately do not.
 */
public final class BhPerfController {

    private static final String TAG = "BhPerf";

    public static final String PREFS = "bh_perf_overlay";
    public static final String KEY_ROOT_GRANTED = "root_granted";
    public static final String KEY_PILL_Y = "pill_y";
    /** Master on/off for whether the in-game overlay pill attaches at all.
     *  Default OFF — the user opts in via Banner Tools → In-game Performance
     *  Overlay (which itself stays greyed until root is granted). */
    public static final String KEY_OVERLAY_ENABLED = "overlay_enabled";
    /** Pill opacity as a percent 5..100 (applied as alpha 0.05..1.0 so the
     *  pill can be made nearly invisible while gaming but never fully vanishes
     *  and become impossible to find). Default 100 = fully opaque. */
    public static final String KEY_PILL_OPACITY = "pill_opacity";
    public static final int PILL_OPACITY_MIN = 5;
    public static final int PILL_OPACITY_DEFAULT = 100;

    // sysfs paths
    private static final String CPU_GOV_GLOB =
            "/sys/devices/system/cpu/cpu*/cpufreq/scaling_governor";
    private static final String KGSL_MIN =
            "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq";
    private static final String KGSL_MAX =
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq";

    private static final String GOV_PERFORMANCE = "performance";
    private static final String GOV_DEFAULT = "schedutil";

    private static volatile BhPerfController INSTANCE;

    public static BhPerfController get() {
        BhPerfController local = INSTANCE;
        if (local == null) {
            synchronized (BhPerfController.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new BhPerfController();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    private final HandlerThread workerThread;
    private final Handler worker;

    // In-memory, per-session applied state. Reset to false on revertAll().
    private volatile boolean sustainedApplied = false;
    private volatile boolean maxAdrenoApplied = false;

    private BhPerfController() {
        workerThread = new HandlerThread("BhPerfWorker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    public boolean isSustainedApplied() { return sustainedApplied; }
    public boolean isMaxAdrenoApplied() { return maxAdrenoApplied; }

    // ── root grant state (persisted) ────────────────────────────────────────

    public boolean isRootGranted(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_ROOT_GRANTED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private void setRootGranted(Context ctx, boolean granted) {
        try {
            prefs(ctx).edit().putBoolean(KEY_ROOT_GRANTED, granted).apply();
        } catch (Throwable ignored) {
        }
    }

    // ── overlay master enable (persisted, default OFF) ──────────────────────

    public boolean isOverlayEnabled(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_OVERLAY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public void setOverlayEnabled(Context ctx, boolean enabled) {
        try {
            prefs(ctx).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply();
        } catch (Throwable ignored) {
        }
    }

    // ── pill opacity (persisted percent 5..100) ─────────────────────────────

    public int getPillOpacity(Context ctx) {
        try {
            int v = prefs(ctx).getInt(KEY_PILL_OPACITY, PILL_OPACITY_DEFAULT);
            if (v < PILL_OPACITY_MIN) v = PILL_OPACITY_MIN;
            if (v > 100) v = 100;
            return v;
        } catch (Throwable t) {
            return PILL_OPACITY_DEFAULT;
        }
    }

    public void setPillOpacity(Context ctx, int percent) {
        if (percent < PILL_OPACITY_MIN) percent = PILL_OPACITY_MIN;
        if (percent > 100) percent = 100;
        try {
            prefs(ctx).edit().putInt(KEY_PILL_OPACITY, percent).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Revoke cached root: revert any active boost to hardware defaults FIRST
     *  (never leave the device pinned with the toggles about to grey out),
     *  then clear the granted flag. Reports completion on the reply Handler. */
    public void revokeRoot(final Context ctx, final Handler reply,
                           final ResultCallback cb) {
        revertAll(reply, new ResultCallback() {
            @Override public void onResult(boolean ok) {
                setRootGranted(ctx, false);
                if (cb != null) cb.onResult(ok);
            }
        });
    }

    public int getPillY(Context ctx, int def) {
        try {
            return prefs(ctx).getInt(KEY_PILL_Y, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public void setPillY(Context ctx, int y) {
        try {
            prefs(ctx).edit().putInt(KEY_PILL_Y, y).apply();
        } catch (Throwable ignored) {
        }
    }

    private SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── root grant (one-time) ───────────────────────────────────────────────

    public interface ResultCallback {
        void onResult(boolean ok);
    }

    /** Runs {@code su -c id} off-thread; caches & reports the result on the
     *  given Handler (typically the main thread). No-op-safe. */
    public void requestRootGrant(final Context ctx, final Handler reply,
                                 final ResultCallback cb) {
        worker.post(new Runnable() {
            @Override public void run() {
                final boolean ok = BhPerfRoot.checkRoot();
                setRootGranted(ctx, ok);
                Log.i(TAG, "root grant check -> " + ok);
                postBack(reply, cb, ok);
            }
        });
    }

    // ── toggle apply (off-thread) ───────────────────────────────────────────

    public void setSustained(final boolean on, final Handler reply,
                             final ResultCallback cb) {
        worker.post(new Runnable() {
            @Override public void run() {
                boolean ok = on ? applySustained() : revertSustained();
                if (ok) sustainedApplied = on;
                postBack(reply, cb, ok);
            }
        });
    }

    public void setMaxAdreno(final boolean on, final Handler reply,
                             final ResultCallback cb) {
        worker.post(new Runnable() {
            @Override public void run() {
                boolean ok = on ? applyMaxAdreno() : revertMaxAdreno();
                if (ok) maxAdrenoApplied = on;
                postBack(reply, cb, ok);
            }
        });
    }

    /** Revert BOTH toggles to hardware defaults. Called on game exit. Blocks
     *  on the caller's thread is avoided by posting to the worker; pass a
     *  reply Handler+cb only if you need to know it finished. */
    public void revertAll(final Handler reply, final ResultCallback cb) {
        final boolean wasSustained = sustainedApplied;
        final boolean wasMax = maxAdrenoApplied;
        worker.post(new Runnable() {
            @Override public void run() {
                boolean ok = true;
                if (wasSustained) ok &= revertSustained();
                if (wasMax) ok &= revertMaxAdreno();
                sustainedApplied = false;
                maxAdrenoApplied = false;
                Log.i(TAG, "revertAll (sustained=" + wasSustained
                        + " maxAdreno=" + wasMax + ") ok=" + ok);
                postBack(reply, cb, ok);
            }
        });
    }

    // ── sysfs ops (run on worker thread only) ───────────────────────────────

    private boolean applySustained() {
        // for f in cpu*/.../scaling_governor; do echo performance > "$f"; done
        return BhPerfRoot.runScript(
                "for f in " + CPU_GOV_GLOB + "; do echo " + GOV_PERFORMANCE
                        + " > \"$f\"; done");
    }

    private boolean revertSustained() {
        return BhPerfRoot.runScript(
                "for f in " + CPU_GOV_GLOB + "; do echo " + GOV_DEFAULT
                        + " > \"$f\"; done");
    }

    private boolean applyMaxAdreno() {
        return BhPerfRoot.runScript("cat " + KGSL_MAX + " > " + KGSL_MIN);
    }

    private boolean revertMaxAdreno() {
        return BhPerfRoot.runScript("echo 0 > " + KGSL_MIN);
    }

    private static void postBack(Handler reply, final ResultCallback cb,
                                 final boolean ok) {
        if (cb == null) return;
        if (reply != null) {
            reply.post(new Runnable() {
                @Override public void run() { cb.onResult(ok); }
            });
        } else {
            cb.onResult(ok);
        }
    }
}
