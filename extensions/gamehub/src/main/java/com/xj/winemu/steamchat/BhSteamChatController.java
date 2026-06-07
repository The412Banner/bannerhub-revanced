package com.xj.winemu.steamchat;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Persistent on/off state for the in-game Steam chat overlay (read-only
 * prototype). Mirrors {@code BhPerfController}'s minimal toggle store: a
 * single boolean in a dedicated SharedPreferences file, default OFF.
 *
 * The overlay's {@code attach()} consults {@link #isEnabled(Context)} on every
 * WineActivity.onResume, so flipping the toggle from the Banner Tools dialog
 * takes effect the next time a game is opened (the "live" behaviour the perf
 * overlay also has).
 */
public final class BhSteamChatController {

    private static final String TAG = "BhSteamChat";

    public static final String PREFS = "bh_steam_chat";
    public static final String KEY_ENABLED = "overlay_enabled";

    private static final BhSteamChatController INSTANCE = new BhSteamChatController();

    private BhSteamChatController() {}

    public static BhSteamChatController get() { return INSTANCE; }

    private SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            Log.w(TAG, "isEnabled failed", t);
            return false;
        }
    }

    public void setEnabled(Context ctx, boolean enabled) {
        try {
            prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
        } catch (Throwable t) {
            Log.w(TAG, "setEnabled failed", t);
        }
    }
}
