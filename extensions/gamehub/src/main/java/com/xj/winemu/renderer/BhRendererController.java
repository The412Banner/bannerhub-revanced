package com.xj.winemu.renderer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.util.Log;

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
 * pair (with the JNI bridge: an added native setRenderingEnabled(Z)V on
 * XServer + the setFlipEnabled call sites redirected to it). "New" leaves
 * stock 6.0.4 entirely untouched (zero regression).
 *
 * Milestone 1 = this controller + the settings dialog + the menu row. The
 * conditional swap that consults {@link #isLegacyForGame} is Milestone 2.
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

    public void setMode(int mode) {
        if (mode < 0 || mode > MODE_MAX) return;
        this.cachedMode = mode;
        writeIntGlobal(KEY_MODE, mode);
        if (containerGameId != null) writeIntPerGame(containerGameId, KEY_MODE, mode);
    }

    // ── Milestone-2 entry: is Legacy selected for the launching game? ────
    // Per-game value, falling back to the global default. Used by the
    // (future) conditional lib-swap so New mode is provably stock.

    public boolean isLegacyForGame(String gameId) {
        ensureContext();
        Context ctx = appContext;
        if (ctx == null) return false;
        int global = ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, DEFAULT_MODE);
        if (gameId == null || gameId.isEmpty()) return global == MODE_LEGACY;
        int v = ctx.getSharedPreferences(String.format(PER_GAME_PREFS_FMT, gameId),
                Context.MODE_PRIVATE).getInt(KEY_MODE, global);
        return v == MODE_LEGACY;
    }

    /** Convenience for a launch-time hook: resolves the gameId itself. */
    public boolean isLegacyForLaunchingGame() {
        return isLegacyForGame(sniffGameIdFromStack());
    }

    // ── Milestone 2: conditional native-lib load + flip dispatch ─────────
    //
    // The renderer choice is FROZEN the moment libxserver is loaded
    // (XServer.<clinit>). libxserver cannot be reloaded, so flip() must use
    // the exact same decision the loader used — otherwise it would invoke a
    // native the loaded .so never bound. {@link #legacyActive} caches that
    // frozen decision; {@link #legacyDecided} guards the pre-load window.

    private static volatile boolean legacyActive = false;
    private static volatile boolean legacyDecided = false;
    /** FULL-PAIR (throwaway): libwinemu loads first, via several early
     *  clinit loaders; this guards the one-shot legacy swap. Independent of
     *  legacyActive/legacyDecided, which stay owned by loadXserver so flip()'s
     *  frozen-decision contract is unchanged. */
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
        String sniffedGid = null;
        try {
            sniffedGid = sniffGameIdFromStack();
            legacy = getInstance().isLegacyForGame(sniffedGid);
        } catch (Throwable t) {
            Log.w(TAG, "loadXserver: legacy resolve failed; using stock", t);
            BhRendererDiag.log("LOAD", "legacy resolve threw; using stock", t);
        }
        BhRendererDiag.log("LOAD", "loadXserver entry: name=" + name
                + " sniffedGameId=" + sniffedGid + " legacyForGame=" + legacy
                + " legacyDecided(pre)=" + legacyDecided);
        if (legacy) {
            try {
                File so = resolveLegacyLib("libxserver_legacy.so");
                BhRendererDiag.log("LOAD", "resolveLegacyLib -> "
                        + (so == null ? "null" : so.getAbsolutePath()
                        + " isFile=" + so.isFile()
                        + " len=" + (so.isFile() ? so.length() : -1)
                        + " md5=" + BhRendererDiag.md5(so,
                            BhRendererDiag.EXPECTED_XSERVER_MD5)));
                if (so != null && so.isFile()) {
                    System.load(so.getAbsolutePath());
                    legacyActive = true;
                    legacyDecided = true;
                    Log.i(TAG, "loaded LEGACY libxserver: " + so.getAbsolutePath());
                    BhRendererDiag.log("LOAD",
                            "LOAD_OK legacy libxserver -> legacyActive=true");
                    BhRendererDiag.startHeartbeat();
                    BhRendererDiag.log("LOAD", "XSERVER_CLINIT_DONE (legacy)");
                    return;
                }
                Log.w(TAG, "legacy libxserver unavailable; falling back to stock");
                BhRendererDiag.log("LOAD",
                        "legacy libxserver unavailable; FALLBACK->stock");
            } catch (Throwable t) {
                Log.w(TAG, "legacy libxserver load failed; falling back to stock", t);
                BhRendererDiag.log("LOAD",
                        "LOAD_FAIL legacy libxserver; FALLBACK->stock", t);
            }
        }
        System.loadLibrary(name);
        legacyActive = false;
        legacyDecided = true;
        BhRendererDiag.log("LOAD", "STOCK loadLibrary(\"" + name
                + "\") -> legacyActive=false");
        BhRendererDiag.log("LOAD", "XSERVER_CLINIT_DONE (stock)");
    }

    /**
     * FULL-PAIR (THROWAWAY, user decision 2026-05-18). Replaces every
     * {@code System.loadLibrary("winemu")} early loader. When the launching
     * game's pref is Legacy, swaps in the bundled 6.0.2
     * {@code libwinemu_legacy.so} — test3's proven pair with the 6.0.2
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
        if (winemuLoaded) {
            BhRendererDiag.log("WINEMU", "loadWinemu re-entry ignored "
                    + "(already loaded) name=" + name);
            return;
        }
        boolean legacy = false;
        String sniffedGid = null;
        try {
            sniffedGid = sniffGameIdFromStack();
            legacy = getInstance().isLegacyForGame(sniffedGid);
        } catch (Throwable t) {
            Log.w(TAG, "loadWinemu: legacy resolve failed; using stock", t);
            BhRendererDiag.log("WINEMU", "legacy resolve threw; using stock", t);
        }
        BhRendererDiag.log("WINEMU", "loadWinemu entry: name=" + name
                + " sniffedGameId=" + sniffedGid + " legacyForGame=" + legacy);
        if (legacy) {
            try {
                File so = resolveLegacyLib("libwinemu_legacy.so");
                BhRendererDiag.log("WINEMU", "resolveLegacyLib -> "
                        + (so == null ? "null" : so.getAbsolutePath()
                        + " isFile=" + so.isFile()
                        + " len=" + (so.isFile() ? so.length() : -1)
                        + " md5=" + BhRendererDiag.md5(so,
                            BhRendererDiag.EXPECTED_WINEMU_MD5)));
                if (so != null && so.isFile()) {
                    System.load(so.getAbsolutePath());
                    winemuLoaded = true;
                    Log.i(TAG, "loaded LEGACY libwinemu: " + so.getAbsolutePath());
                    BhRendererDiag.log("WINEMU", "WINEMU_LOAD_OK legacy libwinemu");
                    BhRendererDiag.startHeartbeat();
                    return;
                }
                Log.w(TAG, "legacy libwinemu unavailable; falling back to stock");
                BhRendererDiag.log("WINEMU",
                        "legacy libwinemu unavailable; FALLBACK->stock");
            } catch (Throwable t) {
                Log.w(TAG, "legacy libwinemu load failed; falling back to stock", t);
                BhRendererDiag.log("WINEMU",
                        "WINEMU_LOAD_FAIL legacy libwinemu; FALLBACK->stock", t);
            }
        }
        System.loadLibrary(name);
        winemuLoaded = true;
        BhRendererDiag.log("WINEMU", "STOCK loadLibrary(\"" + name + "\")");
    }

    /**
     * Replaces {@code XServer.setFlipEnabled(Z)V} call sites. Routes to the
     * native the loaded libxserver actually binds: stock 6.0.4 binds
     * {@code setFlipEnabled}, the 6.0.2 legacy lib binds
     * {@code setRenderingEnabled} (same function, renamed across versions).
     * Reflective so the extension need not compile-time reference the host
     * {@code com.winemu.core.server.XServer} class.
     */
    public static void flip(Object xserver, boolean enabled) {
        if (xserver == null) return;
        boolean legacy = legacyDecided
                ? legacyActive
                : safeIsLegacyForLaunchingGame();
        String fnName = legacy ? "setRenderingEnabled" : "setFlipEnabled";
        // ── THROWAWAY M3 forced-enable experiment (2026-05-18) ───────────
        // Root cause from the full-pair device run: 6.0.4 setFlipEnabled =
        // GPU-passthrough flip (default OFF → false); 6.0.2
        // setRenderingEnabled = the master switch that turns the GLES2
        // renderer ON, formerly driven by the 6.0.4-DELETED
        // com.winemu.core.DirectRendering. We pass 6.0.4's passthrough flag
        // (false) into 6.0.2's renderer-enable switch → libs load, never
        // composites, black screen + alive forever. Cheap test: on the
        // legacy branch, force the enable bit true regardless of the
        // 6.0.4-side flag. If 6.0.2's drive loop is self-contained in the
        // lib pair this lights the screen with one line; if still black the
        // full DirectRendering orchestration port is required. REVERT before
        // any M2 ship.
        boolean effEnabled = legacy ? true : enabled;
        BhRendererDiag.log("FLIP", "flip enter: branch=" + fnName
                + " enabled=" + enabled
                + (legacy ? " FORCED->true (M3 experiment)" : "")
                + " legacyDecided=" + legacyDecided
                + " legacyActive=" + legacyActive
                + " caller=" + flipCaller());
        try {
            Method fn = xserver.getClass().getMethod(fnName, boolean.class);
            fn.invoke(xserver, effEnabled);
            BhRendererDiag.log("FLIP", "flip(" + fnName + ") OK"
                    + " eff=" + effEnabled);
        } catch (Throwable t) {
            Log.w(TAG, "flip(" + fnName + ") failed", t);
            BhRendererDiag.log("FLIP", "flip(" + fnName + ") FAILED", t);
        }
    }

    /** Diagnostic-only: which redirected setFlipEnabled site drove this. */
    private static String flipCaller() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            // [0]=getStackTrace [1]=flipCaller [2]=flip [3]=real caller
            return st.length > 3 ? st[3].toString() : "(unknown)";
        } catch (Throwable t) {
            return "(unknown)";
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

    private void reloadSettings() {
        ensureContext();
        Context ctx = appContext;
        if (ctx == null) return;

        int gMode = ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, DEFAULT_MODE);
        if (containerGameId == null) {
            cachedMode = gMode;
            return;
        }
        cachedMode = ctx.getSharedPreferences(
                String.format(PER_GAME_PREFS_FMT, containerGameId), Context.MODE_PRIVATE)
                .getInt(KEY_MODE, gMode);
    }

    private void writeIntGlobal(String key, int val) {
        Context ctx = ctxOrNull();
        if (ctx == null) return;
        ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putInt(key, val).apply();
    }

    private void writeIntPerGame(String gameId, String key, int val) {
        Context ctx = ctxOrNull();
        if (ctx == null || gameId == null || gameId.isEmpty()) return;
        ctx.getSharedPreferences(String.format(PER_GAME_PREFS_FMT, gameId), Context.MODE_PRIVATE)
                .edit().putInt(key, val).apply();
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
