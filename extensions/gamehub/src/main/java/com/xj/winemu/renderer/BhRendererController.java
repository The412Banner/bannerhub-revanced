package com.xj.winemu.renderer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.xj.winemu.common.BhMenuGameId;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * BhRendererController — per-game choice of display renderer.
 *
 * GameHub 6.0.4 rewrote its X-server renderer GLES2→Vulkan (libxserver.so)
 * and removed the libwinemu ASurfaceTransaction plane compositor. The
 * "Legacy" mode swaps in the 6.0.2 GLES2-era libxserver.so + libwinemu.so
 * pair (the JNI bridge: an added native setRenderingEnabled(Z)V on XServer +
 * the setFlipEnabled call sites redirected to it). "New" leaves stock 6.0.4
 * entirely untouched (zero regression).
 *
 * Device-confirmed (GoW, 2026-05-18): the full 6.0.2 pair loads on 6.0.4 and
 * the GLES2 renderer self-drives once {@code setRenderingEnabled(true)} is
 * called — no restoration of the deleted {@code DirectRendering}
 * orchestration is required.
 *
 * Storage mirrors {@code BhGpuSpoofController}: per-game value in the stock
 * {@code pc_g_setting<gameId>} prefs under {@code bh_renderer_mode}; a global
 * default in {@code bh_renderer_prefs}. Absent key → MODE_NEW → stock.
 */
public final class BhRendererController {

    private static final String TAG = "BhRenderer";

    /** Stock 6.0.4 Vulkan renderer — default, zero patch effect. */
    public static final int MODE_NEW    = 0;
    /** 6.0.2 GLES2 libxserver+libwinemu pair (via the JNI bridge). */
    public static final int MODE_LEGACY = 1;
    public static final int MODE_MAX    = 1;

    public static final String GLOBAL_PREFS_FILE  = "bh_renderer_prefs";
    public static final String PER_GAME_PREFS_FMT = "pc_g_setting%s";
    public static final String KEY_MODE           = "bh_renderer_mode";

    private static final int DEFAULT_MODE = MODE_NEW;

    private static volatile BhRendererController INSTANCE;

    private Context appContext;
    private String  containerGameId;
    private int     cachedMode = DEFAULT_MODE;

    public static BhRendererController getInstance() {
        BhRendererController i = INSTANCE;
        if (i == null) {
            synchronized (BhRendererController.class) {
                i = INSTANCE;
                if (i == null) {
                    i = new BhRendererController();
                    INSTANCE = i;
                }
            }
        }
        return i;
    }

    // ── Settings API (BhRendererSettingsActivity) ────────────────────────

    public void init(Context ctx) {
        if (ctx != null && this.appContext == null) {
            this.appContext = ctx.getApplicationContext();
        }
        reloadSettings();
    }

    public void setContainerForSettings(String gameId) {
        this.containerGameId = (gameId == null || gameId.isEmpty()) ? null : gameId;
        reloadSettings();
        Log.i(TAG, "container=" + (containerGameId != null ? containerGameId : "(global)")
                + " mode=" + cachedMode);
    }

    public int getMode() { return cachedMode; }

    // STRICT PER-GAME: written ONLY to our own bh_renderer_prefs, keyed
    // per-game (KEY + "__" + gameId). Never global, never the host-owned
    // pc_g_setting<id> (host rewrites that → reset bug). No game ⇒ no write.
    public void setMode(int mode) {
        if (mode < 0 || mode > MODE_MAX) return;
        this.cachedMode = mode;
        if (containerGameId != null) writeIntOwned(pgKey(KEY_MODE, containerGameId), mode);
    }

    // ── Is Legacy selected for the launching game? ───────────────────────
    // STRICTLY per-game. No global fallback: a game with no per-game value
    // is stock (New). Drives the conditional lib-swap.
    public boolean isLegacyForGame(String gameId) {
        if (gameId == null || gameId.isEmpty()) return false;
        return resolveModeForGame(gameId) == MODE_LEGACY;
    }

    /** Convenience for a launch-time hook: resolves the gameId itself. */
    public boolean isLegacyForLaunchingGame() {
        return isLegacyForGame(launchGameId());
    }

    // ── Conditional native-lib load + flip dispatch ──────────────────────
    //
    // The renderer choice is FROZEN the moment libxserver is loaded
    // (XServer.<clinit>). libxserver cannot be reloaded, so flip() must use
    // the exact same decision the loader used — otherwise it would invoke a
    // native the loaded .so never bound. {@link #legacyActive} caches that
    // frozen decision; {@link #legacyDecided} guards the pre-load window.

    private static volatile boolean legacyActive = false;
    private static volatile boolean legacyDecided = false;
    /** libwinemu loads first, via several early clinit loaders; this guards
     *  the one-shot legacy swap. Independent of legacyActive/legacyDecided,
     *  which stay owned by loadXserver so flip()'s frozen-decision contract
     *  is unchanged. */
    private static volatile boolean winemuLoaded = false;

    /**
     * Replaces {@code System.loadLibrary("xserver")} in XServer's static
     * initializer. When the launching game's renderer pref is Legacy, loads
     * the bundled 6.0.2 {@code libxserver_legacy.so}; otherwise loads stock
     * {@code "xserver"} bit-identically (zero regression in New mode). Any
     * failure on the legacy path falls back to the stock lib so the app can
     * never be bricked by this feature.
     */
    public static void loadXserver(String name) {
        boolean legacy = false;
        try {
            legacy = getInstance().isLegacyForGame(launchGameId());
        } catch (Throwable t) {
            Log.w(TAG, "loadXserver: legacy resolve failed; using stock", t);
        }
        if (legacy) {
            try {
                File so = resolveLegacyLib("libxserver_legacy.so");
                if (so != null && so.isFile()) {
                    System.load(so.getAbsolutePath());
                    legacyActive = true;
                    legacyDecided = true;
                    Log.i(TAG, "loaded LEGACY libxserver: " + so.getAbsolutePath());
                    return;
                }
                Log.w(TAG, "legacy libxserver unavailable; falling back to stock");
            } catch (Throwable t) {
                Log.w(TAG, "legacy libxserver load failed; falling back to stock", t);
            }
        }
        System.loadLibrary(name);
        legacyActive = false;
        legacyDecided = true;
    }

    /**
     * Replaces every {@code System.loadLibrary("winemu")} early loader. When
     * the launching game's pref is Legacy, swaps in the bundled 6.0.2
     * {@code libwinemu_legacy.so} — the proven pair with the 6.0.2
     * libxserver — otherwise loads stock {@code "winemu"} bit-identically.
     *
     * Idempotent: libwinemu is pulled by several early {@code <clinit>}
     * loaders; only the first call performs the load, the rest no-op (the
     * native lib is process-global once loaded). Decision ownership stays
     * with {@link #loadXserver} (it sets {@link #legacyActive}/
     * {@link #legacyDecided} for flip()); loadWinemu only mirrors the same
     * per-launch pref so the pair stays consistent. Any failure on the
     * legacy path falls back to stock so New mode and a missing/failed
     * legacy lib never regress.
     */
    public static void loadWinemu(String name) {
        if (winemuLoaded) return;
        boolean legacy = false;
        try {
            legacy = getInstance().isLegacyForGame(launchGameId());
        } catch (Throwable t) {
            Log.w(TAG, "loadWinemu: legacy resolve failed; using stock", t);
        }
        if (legacy) {
            try {
                File so = resolveLegacyLib("libwinemu_legacy.so");
                if (so != null && so.isFile()) {
                    System.load(so.getAbsolutePath());
                    winemuLoaded = true;
                    Log.i(TAG, "loaded LEGACY libwinemu: " + so.getAbsolutePath());
                    return;
                }
                Log.w(TAG, "legacy libwinemu unavailable; falling back to stock");
            } catch (Throwable t) {
                Log.w(TAG, "legacy libwinemu load failed; falling back to stock", t);
            }
        }
        System.loadLibrary(name);
        winemuLoaded = true;
    }

    /**
     * Replaces {@code XServer.setFlipEnabled(Z)V} call sites. Routes to the
     * native the loaded libxserver actually binds: stock 6.0.4 binds
     * {@code setFlipEnabled}, the 6.0.2 legacy lib binds
     * {@code setRenderingEnabled}. Reflective so the extension need not
     * compile-time reference the host {@code com.winemu.core.server.XServer}.
     *
     * <p>The two natives are NOT semantically the same despite the
     * version-rename: 6.0.4 {@code setFlipEnabled(Z)} is the GPU-passthrough
     * flip (default OFF → driven with {@code false}); 6.0.2
     * {@code setRenderingEnabled(Z)} is the master switch that turns the
     * GLES2 renderer ON, formerly driven by the 6.0.4-deleted
     * {@code com.winemu.core.DirectRendering}. Passing 6.0.4's passthrough
     * flag ({@code false}) into the 6.0.2 enable switch loads the libs but
     * never composites (black screen, process alive). So in Legacy mode the
     * enable bit is forced {@code true} — device-confirmed (GoW, 2026-05-18)
     * to light the screen; the 6.0.2 renderer self-drives from there. New
     * mode is unaffected (stock {@code setFlipEnabled} with the real flag).
     */
    public static void flip(Object xserver, boolean enabled) {
        if (xserver == null) return;
        boolean legacy = legacyDecided
                ? legacyActive
                : safeIsLegacyForLaunchingGame();
        String fnName = legacy ? "setRenderingEnabled" : "setFlipEnabled";
        boolean effEnabled = legacy ? true : enabled;
        try {
            Method fn = xserver.getClass().getMethod(fnName, boolean.class);
            fn.invoke(xserver, effEnabled);
        } catch (Throwable t) {
            Log.w(TAG, "flip(" + fnName + ") failed", t);
        }
    }

    private static boolean safeIsLegacyForLaunchingGame() {
        try {
            return getInstance().isLegacyForLaunchingGame();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Resolves the bundled legacy .so to an absolute path. Prefers the
     * extracted nativeLibraryDir; falls back to extracting it from the APK
     * zip into the cache dir (covers android:extractNativeLibs="false").
     */
    private static File resolveLegacyLib(String soName) {
        BhRendererController c = getInstance();
        c.ensureContext();
        Context ctx = c.appContext;
        if (ctx == null) return null;
        ApplicationInfo ai = ctx.getApplicationInfo();

        File extracted = new File(ai.nativeLibraryDir, soName);
        if (extracted.isFile()) return extracted;

        File out = new File(ctx.getCacheDir(), soName);
        try {
            if (out.isFile() && out.length() > 0) return out;
            ZipFile zf = new ZipFile(ai.sourceDir);
            try {
                ZipEntry e = zf.getEntry("lib/arm64-v8a/" + soName);
                if (e == null) {
                    Log.w(TAG, "legacy .so not in APK: lib/arm64-v8a/" + soName);
                    return null;
                }
                InputStream is = zf.getInputStream(e);
                FileOutputStream os = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
                } finally {
                    os.close();
                    is.close();
                }
            } finally {
                zf.close();
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "extract " + soName + " failed", t);
            return null;
        }
    }

    // ── Settings I/O — mirrors BhGpuSpoofController ──────────────────────

    /** Per-game key inside our OWN bh_renderer_prefs file. */
    private static String pgKey(String base, String gameId) {
        return base + "__" + gameId;
    }

    /**
     * Resolve a game's mode from our OWN store only. One-time migration:
     * adopt the legacy host-owned pc_g_setting&lt;id&gt; value if our store
     * has none yet. No global fallback — absent ⇒ DEFAULT_MODE (stock).
     */
    private int resolveModeForGame(String gameId) {
        ensureContext();
        Context ctx = appContext;
        if (ctx == null || gameId == null || gameId.isEmpty()) return DEFAULT_MODE;
        SharedPreferences own =
                ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE);
        String k = pgKey(KEY_MODE, gameId);
        if (!own.contains(k)) {
            try {
                SharedPreferences legacy = ctx.getSharedPreferences(
                        String.format(PER_GAME_PREFS_FMT, gameId), Context.MODE_PRIVATE);
                if (legacy.contains(KEY_MODE)) {
                    int lv = legacy.getInt(KEY_MODE, DEFAULT_MODE);
                    own.edit().putInt(k, lv).apply();
                    return lv;
                }
            } catch (Throwable ignored) {
            }
            return DEFAULT_MODE;
        }
        return own.getInt(k, DEFAULT_MODE);
    }

    private void reloadSettings() {
        ensureContext();
        Context ctx = appContext;
        if (ctx == null) return;
        // No game ⇒ stock default; nothing global exists or is consulted.
        cachedMode = (containerGameId == null)
                ? DEFAULT_MODE
                : resolveModeForGame(containerGameId);
    }

    private void writeIntOwned(String fullKey, int val) {
        Context ctx = ctxOrNull();
        if (ctx == null) return;
        ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putInt(fullKey, val).apply();
    }

    private Context ctxOrNull() {
        ensureContext();
        return appContext;
    }

    private void ensureContext() {
        if (appContext != null) return;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Context) {
                appContext = ((Context) app).getApplicationContext();
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureContext failed", t);
        }
    }

    /**
     * Launch-time gameId resolver. The renderer lib swap happens in
     * {@code XServer.<clinit>} inside the ":wine" process (WineActivity is
     * android:process=":wine"), and that runs BEFORE WineActivity is
     * registered in that process's ActivityThread.mActivities — so
     * {@link #sniffGameIdFromStack()} returns null there and the per-game
     * Legacy choice silently no-ops (stock libs load). The pre-launch menu
     * stashed the id via MenuGameIdCapturePatch → BhMenuGameId, which mirrors
     * it to SharedPreferences (crosses the main↔:wine process boundary the
     * same way the per-game store does). Prefer that; fall back to the stack
     * sniff for the in-game sidebar path. Identical order to
     * BhGpuSpoofController.applyGpuSpoofImpl.
     */
    static String launchGameId() {
        String gid = BhMenuGameId.getCaptured();
        if (gid == null || gid.isEmpty()) gid = sniffGameIdFromStack();
        return gid;
    }

    /** If a WineActivity is in the stack, grab its gameId Intent extra. */
    static String sniffGameIdFromStack() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method cur = atCls.getMethod("currentActivityThread");
            Object at = cur.invoke(null);
            if (at == null) return null;
            Field fActs = atCls.getDeclaredField("mActivities");
            fActs.setAccessible(true);
            Object acts = fActs.get(at);
            if (!(acts instanceof Map)) return null;
            for (Object record : ((Map<?, ?>) acts).values()) {
                if (record == null) continue;
                Field fAct = record.getClass().getDeclaredField("activity");
                fAct.setAccessible(true);
                Object a = fAct.get(record);
                if (!(a instanceof Activity)) continue;
                if (!a.getClass().getName().endsWith(".WineActivity")) continue;
                Intent it = ((Activity) a).getIntent();
                if (it == null) continue;
                String gid = it.getStringExtra("gameId");
                if (gid != null && !gid.isEmpty()) return gid;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
