package com.xj.winemu.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared per-game id capture for BannerHub's injected menu rows
 * (Renderer / GPU Spoof / PC Vibration).
 *
 * GameHub resolves a row's gameId from a *running* WineActivity. From a
 * pre-launch More Menu / library popup there is none, so all our rows used
 * to fall back to global prefs. {@code MenuGameIdCapturePatch} injects a
 * single {@code captureGameId(menuData)} call at index 0 of the two menu
 * builders ({@code Lx57;->a} and {@code Lted;->f}, both static, p0 = the
 * menu-data param). This runs once per menu open and stashes the id here;
 * every feature's row click reads {@link #getCaptured()}.
 *
 * The id is parsed from {@code menuData.toString()}: GameHub's Kotlin
 * data/value classes render a stable {@code ServerGameId(value=<int>)} (or
 * {@code gameId=<int>}) token regardless of R8 field renaming — and that
 * int == the {@code pc_g_setting<id>} prefs key / WineActivity gameId.
 *
 * One shared capture (not one per feature) avoids stacking three index-0
 * head-blocks into the same hot menu methods.
 */
public final class BhMenuGameId {

    private static final String TAG = "BhMenuGameId";

    private static final Pattern P_SERVER =
        Pattern.compile("ServerGameId\\(value=(-?\\d+)\\)");
    private static final Pattern P_GAMEID =
        Pattern.compile("gameId=(\\d+)");

    private static volatile String sCapturedGameId;

    // The menu builders (Lx57;->a / Lted;->f / Lpzc;->j0) run in the MAIN UI
    // process, but the launch-time consumers (BhGpuSpoofController via the
    // Lbg5;->a env builder, etc.) run inside com.xj.winemu.WineActivity, which
    // AndroidManifest pins to a SEPARATE ":wine" process. A static field does
    // not cross that boundary, so getCaptured() was always null at launch and
    // the per-game spoof silently no-op'd (store correct, dxvk.conf never
    // rewritten). SharedPreferences DO cross processes (same as the per-game
    // store itself), so mirror the captured id to disk on every menu open and
    // fall back to it when the in-process static is empty.
    private static final String PREFS_FILE = "bh_menu_gameid";
    private static final String PREFS_KEY  = "id";

    private BhMenuGameId() { }

    /** Injected at index 0 of the menu builders with the menu-data param. */
    public static void captureGameId(Object menuData) {
        try {
            String id = resolve(menuData);
            sCapturedGameId = id;
            if (id != null && !id.isEmpty()) persist(id);
        } catch (Throwable t) {
            Log.w(TAG, "captureGameId failed", t);
        }
    }

    /**
     * Last captured per-game id, or null (caller falls back to its sniff).
     * In-process static first; on a miss (e.g. the ":wine" launch process,
     * where the static was never set) read the disk mirror written by the
     * menu open in the main process.
     */
    public static String getCaptured() {
        String id = sCapturedGameId;
        if (id != null && !id.isEmpty()) return id;
        id = readPersisted();
        if (id != null && !id.isEmpty()) {
            sCapturedGameId = id;   // cache for subsequent calls in this process
            return id;
        }
        return null;
    }

    private static void persist(String id) {
        try {
            SharedPreferences sp = prefs();
            if (sp != null) sp.edit().putString(PREFS_KEY, id).commit();
        } catch (Throwable t) {
            Log.w(TAG, "persist failed", t);
        }
    }

    private static String readPersisted() {
        try {
            SharedPreferences sp = prefs();
            if (sp != null) return sp.getString(PREFS_KEY, null);
        } catch (Throwable ignored) { }
        return null;
    }

    /** App context via ActivityThread — no Context is plumbed into the row. */
    private static SharedPreferences prefs() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Context) {
                return ((Context) app).getApplicationContext()
                        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static final String GAMEINFO_CLS =
        "com.xiaoji.egggame.game.di.model.game.GameInfo";

    private static String resolve(Object menuData) {
        if (menuData == null) return null;

        // 1) toString token — works for Lf37 GameDetailArgs (More Menu /
        //    tile popup): renders a stable ServerGameId(value=<int>) /
        //    gameId=<int> regardless of R8 field renaming.
        try {
            String s = String.valueOf(menuData);
            if (s != null) {
                Matcher m = P_SERVER.matcher(s);
                if (m.find()) return m.group(1);
                m = P_GAMEID.matcher(s);
                if (m.find()) return m.group(1);
            }
        } catch (Throwable ignored) { }

        // 2) GameInfo.getServerGameId() — works for Laub (library-list
        //    popup) which holds a kept-name GameInfo. The class name is
        //    kept by R8 so this is stable; field/accessor names are not, so
        //    we locate the GameInfo by VALUE type, not by name.
        try {
            Class<?> giCls = Class.forName(GAMEINFO_CLS);
            Object gi = (giCls.isInstance(menuData)) ? menuData
                                                     : findFieldOfType(menuData, giCls);
            if (gi != null) {
                java.lang.reflect.Method g = giCls.getMethod("getServerGameId");
                Object id = g.invoke(gi);
                if (id != null) return String.valueOf(id);
            }
        } catch (Throwable ignored) { }

        return null;
    }

    /** Shallow scan of host's declared fields for an instance of {@code type}. */
    private static Object findFieldOfType(Object host, Class<?> type) {
        try {
            for (java.lang.reflect.Field f : host.getClass().getDeclaredFields()) {
                if (!type.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(host);
                if (type.isInstance(v)) return v;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
