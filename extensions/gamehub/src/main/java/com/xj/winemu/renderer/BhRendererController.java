package com.xj.winemu.renderer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

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
