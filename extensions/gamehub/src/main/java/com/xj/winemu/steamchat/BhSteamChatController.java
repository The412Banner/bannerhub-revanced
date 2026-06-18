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
    public static final String KEY_PILL_Y = "pill_y";
    /** Pill opacity as a percent 5..100 (alpha 0.05..1.0) — faded while gaming
     *  but never fully invisible. Default 100 = opaque. */
    public static final String KEY_PILL_OPACITY = "pill_opacity";
    public static final int PILL_OPACITY_MIN = 5;
    public static final int PILL_OPACITY_DEFAULT = 100;

    /** Incoming-call ringtone selection token. One of:
     *  "silent", "synth:<classic|chime|trill|beep>", "asset:<file.mp3>"
     *  (bundled, from assets/bh_ringtones), or "uri:<content-uri>" (user MP3). */
    public static final String KEY_RINGTONE = "ringtone";
    public static final String RINGTONE_DEFAULT = "asset:basic.mp3";
    /** Vibrate while an incoming call is ringing (default on). */
    public static final String KEY_VIBRATE = "ringtone_vibrate";
    /** Ringtone volume as a percent 0..100 (default 100). */
    public static final String KEY_RINGTONE_VOLUME = "ringtone_volume";
    public static final int RINGTONE_VOLUME_DEFAULT = 100;

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

    // ── pill position (persisted Y) ─────────────────────────────────────────

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
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
    }

    // ── incoming-call ringtone + vibrate ────────────────────────────────────

    public String getRingtone(Context ctx) {
        try {
            String v = prefs(ctx).getString(KEY_RINGTONE, RINGTONE_DEFAULT);
            return (v == null || v.isEmpty()) ? RINGTONE_DEFAULT : v;
        } catch (Throwable t) {
            return RINGTONE_DEFAULT;
        }
    }

    public void setRingtone(Context ctx, String token) {
        try {
            prefs(ctx).edit().putString(KEY_RINGTONE, token == null ? RINGTONE_DEFAULT : token).apply();
        } catch (Throwable ignored) {}
    }

    public boolean isVibrate(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_VIBRATE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public void setVibrate(Context ctx, boolean on) {
        try {
            prefs(ctx).edit().putBoolean(KEY_VIBRATE, on).apply();
        } catch (Throwable ignored) {}
    }

    public int getRingtoneVolume(Context ctx) {
        try {
            int v = prefs(ctx).getInt(KEY_RINGTONE_VOLUME, RINGTONE_VOLUME_DEFAULT);
            if (v < 0) v = 0; if (v > 100) v = 100;
            return v;
        } catch (Throwable t) {
            return RINGTONE_VOLUME_DEFAULT;
        }
    }

    public void setRingtoneVolume(Context ctx, int percent) {
        if (percent < 0) percent = 0; if (percent > 100) percent = 100;
        try {
            prefs(ctx).edit().putInt(KEY_RINGTONE_VOLUME, percent).apply();
        } catch (Throwable ignored) {}
    }

    // ── room-code voice identity (cross-compatible with BannerHub 3.7.5) ─────
    // A room-code call doesn't go through Steam friends, so it needs its own
    // display name + a stable peer id. Both are independent of Steam sign-in so a
    // user who isn't signed in can still join a friend's room.

    /** Display name shown in a code room's roster. Empty until the user sets one
     *  (the room-code screen prefills a default). */
    public static final String KEY_VOICE_NAME = "voice_room_name";
    /** Stable per-install client id used as the self peer id in code rooms. */
    public static final String KEY_VOICE_CLIENT = "voice_client_id";

    public String getVoiceName(Context ctx) {
        try {
            String v = prefs(ctx).getString(KEY_VOICE_NAME, "");
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    public void setVoiceName(Context ctx, String name) {
        try {
            prefs(ctx).edit().putString(KEY_VOICE_NAME, name == null ? "" : name.trim()).apply();
        } catch (Throwable ignored) {}
    }

    /** Stable client id for code rooms; generated and persisted on first use.
     *  Matches the worker's VOICE_ID_RE ([A-Za-z0-9_-], ≤40). */
    public String getVoiceClientId(Context ctx) {
        try {
            String id = prefs(ctx).getString(KEY_VOICE_CLIENT, "");
            if (id == null || id.isEmpty()) {
                id = "bh-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                prefs(ctx).edit().putString(KEY_VOICE_CLIENT, id).apply();
            }
            return id;
        } catch (Throwable t) {
            return "bh-" + System.currentTimeMillis();
        }
    }
}
