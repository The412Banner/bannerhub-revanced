package app.revanced.extension.gamehub.playtime;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Replacement for XiaoJi's server-side playtime heartbeat. Tracks per-game session time
 * to SharedPreferences ({@code bh_playtime_prefs}) so nothing leaves the device, and
 * reconstructs {@code PcGamePlayTimeEntity} instances on demand for the UI.
 *
 * <p>The smali patch replaces the bodies of {@code Lieo}'s SuspendLambda children
 * (heartbeat-start/update/end) and {@code Lse7;->c} (getUserPlayTimeList) with single
 * invoke-static calls into this class.
 *
 * <p>Field-mapping note: the 5 String fields on the {@code Lieo} tracker (a..e in 6.0.4)
 * weren't trivially mappable to PcGamePlayTimeEntity's (gameName / coverImage / squareImage /
 * sourceType / sourceGameId / steamAppid) without deeper recon. We pass all 5 through and
 * use heuristics: first integer-parseable string is gameId, the rest go in their fields
 * in declared order. Worst case the UI shows a blank cover or wrong-slot string for some
 * games — the playtime number itself (the user-visible "Hours played" value) is correct
 * regardless.
 */
public final class BhPlayTimeTracker {
    private static final String PREFS = "bh_playtime_prefs";
    private static final String TAG = "BhPlayTime";
    private static final long DAY_MILLIS = 86_400_000L;
    private static final long WINDOW_14_DAYS_MILLIS = 14L * DAY_MILLIS;

    private BhPlayTimeTracker() {}

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            return (Application) m.invoke(null);
        } catch (Throwable t) {
            Log.w(TAG, "appContext lookup failed", t);
            return null;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void recordStart(String a, String b, String c, String d, String e) {
        Context ctx = appContext();
        if (ctx == null) return;
        try {
            String key = pickGameKey(a, b, c, d, e);
            SharedPreferences sp = prefs(ctx);
            JSONObject obj = readEntry(sp, key);
            obj.put("gameKey", key);
            obj.put("a", str(a));
            obj.put("b", str(b));
            obj.put("c", str(c));
            obj.put("d", str(d));
            obj.put("e", str(e));
            long now = System.currentTimeMillis();
            obj.put("sessionStart", now);
            obj.put("lastStartTime", now);
            sp.edit().putString(key, obj.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "recordStart failed", t);
        }
    }

    public static void tick(String a, String b, String c, String d, String e) {
        Context ctx = appContext();
        if (ctx == null) return;
        try {
            String key = pickGameKey(a, b, c, d, e);
            SharedPreferences sp = prefs(ctx);
            JSONObject obj = readEntry(sp, key);
            long sessionStart = obj.optLong("sessionStart", 0);
            if (sessionStart <= 0) return;

            long now = System.currentTimeMillis();
            long elapsedSec = Math.max(0L, (now - sessionStart) / 1000L);
            long total = obj.optLong("totalSeconds", 0L) + elapsedSec;

            obj.put("totalSeconds", total);
            obj.put("sessionStart", now);

            JSONArray sessions = obj.optJSONArray("sessions");
            if (sessions == null) sessions = new JSONArray();
            JSONObject s = new JSONObject();
            s.put("at", now);
            s.put("dur", elapsedSec);
            sessions.put(s);
            pruneOldSessions(sessions, now - WINDOW_14_DAYS_MILLIS);
            obj.put("sessions", sessions);
            obj.put("last14DaysSeconds", sum14Days(sessions, now - WINDOW_14_DAYS_MILLIS));

            sp.edit().putString(key, obj.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "tick failed", t);
        }
    }

    public static void recordEnd(String a, String b, String c, String d, String e) {
        // Finalize the current session first.
        tick(a, b, c, d, e);
        Context ctx = appContext();
        if (ctx == null) return;
        try {
            String key = pickGameKey(a, b, c, d, e);
            SharedPreferences sp = prefs(ctx);
            JSONObject obj = readEntry(sp, key);
            obj.remove("sessionStart");
            sp.edit().putString(key, obj.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "recordEnd failed", t);
        }
    }

    /**
     * Returns an {@code ArrayList<PcGamePlayTimeEntity>} suitable as a drop-in
     * replacement for the network response. Reflectively constructs the entity via its
     * documented 10-arg constructor: {@code (int gameId, String gameName, String
     * coverImage, String squareImage, String sourceType, String sourceGameId, String
     * steamAppid, long last14DaysSeconds, long lastStartTime, long totalSeconds)}.
     */
    public static Object getPcEntityList() {
        ArrayList<Object> out = new ArrayList<>();
        Context ctx = appContext();
        if (ctx == null) return out;
        try {
            Class<?> entCls = Class.forName(
                "com.xiaoji.egggame.game.data.remote.PcGamePlayTimeEntity"
            );
            Constructor<?> ctor = entCls.getDeclaredConstructor(
                int.class,
                String.class, String.class, String.class, String.class, String.class, String.class,
                long.class, long.class, long.class
            );
            ctor.setAccessible(true);
            Map<String, ?> all = prefs(ctx).getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                try {
                    JSONObject obj = new JSONObject(String.valueOf(e.getValue()));
                    int gameId = safeParseInt(obj.optString("gameKey", "0"));
                    Object inst = ctor.newInstance(
                        gameId,
                        obj.optString("a", ""),
                        obj.optString("b", ""),
                        obj.optString("c", ""),
                        obj.optString("d", ""),
                        obj.optString("e", ""),
                        "",
                        obj.optLong("last14DaysSeconds", 0L),
                        obj.optLong("lastStartTime", 0L),
                        obj.optLong("totalSeconds", 0L)
                    );
                    out.add(inst);
                } catch (Throwable t) {
                    Log.w(TAG, "skip entry " + e.getKey(), t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getPcEntityList failed", t);
        }
        return out;
    }

    // ----------------------------------------------------------------- helpers

    private static JSONObject readEntry(SharedPreferences sp, String key) {
        String raw = sp.getString(key, null);
        if (raw == null) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static String pickGameKey(String... candidates) {
        // First integer-parseable string is the gameId (and our key). Fall back to first
        // non-empty string. Fall back further to a constant — better to merge unknowns
        // than scatter them.
        for (String c : candidates) {
            if (c == null || c.isEmpty()) continue;
            try {
                Integer.parseInt(c);
                return c;
            } catch (NumberFormatException ignored) {
            }
        }
        for (String c : candidates) {
            if (c != null && !c.isEmpty()) return c;
        }
        return "unknown";
    }

    private static int safeParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static void pruneOldSessions(JSONArray sessions, long cutoffMillis) throws JSONException {
        for (int i = sessions.length() - 1; i >= 0; i--) {
            if (sessions.getJSONObject(i).optLong("at", 0L) < cutoffMillis) {
                sessions.remove(i);
            }
        }
    }

    private static long sum14Days(JSONArray sessions, long cutoffMillis) throws JSONException {
        long sum = 0L;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.getJSONObject(i);
            if (s.optLong("at", 0L) >= cutoffMillis) {
                sum += s.optLong("dur", 0L);
            }
        }
        return sum;
    }
}
