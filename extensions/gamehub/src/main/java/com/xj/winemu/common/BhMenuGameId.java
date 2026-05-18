package com.xj.winemu.common;

import android.util.Log;

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

    private BhMenuGameId() { }

    /** Injected at index 0 of the menu builders with the menu-data param. */
    public static void captureGameId(Object menuData) {
        try {
            sCapturedGameId = resolve(menuData);
        } catch (Throwable t) {
            Log.w(TAG, "captureGameId failed", t);
        }
    }

    /** Last captured per-game id, or null (caller falls back to its sniff). */
    public static String getCaptured() {
        return sCapturedGameId;
    }

    private static String resolve(Object menuData) {
        if (menuData == null) return null;
        String s;
        try { s = String.valueOf(menuData); } catch (Throwable t) { return null; }
        if (s == null) return null;
        Matcher m = P_SERVER.matcher(s);
        if (m.find()) return m.group(1);
        m = P_GAMEID.matcher(s);
        if (m.find()) return m.group(1);
        return null;
    }
}
