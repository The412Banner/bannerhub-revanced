package app.revanced.extension.gamehub.playtime;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Replacement for XiaoJi's server-side playtime heartbeat. Records per-game session
 * time to SharedPreferences ({@code bh_playtime_prefs}) so nothing leaves the device,
 * and reconstructs the UI's domain entity on demand.
 *
 * <p>Target entity is {@code Lekf;} (R8-renamed in 6.0.4 — not the network DTO
 * {@code PcGamePlayTimeEntity}). Same 10-arg ctor shape. Field {@code d} must be
 * the string {@code "2"} or the UI's iteration in {@code f4d.invokeSuspend} filters
 * the row out before display.
 *
 * <p>The tick path used to parse + serialize a growing JSON blob and write to disk on
 * every heartbeat (every few seconds during gameplay). It now updates in-memory state
 * only, flushing to prefs at most once per minute. Completed sessions are appended on
 * {@code recordEnd}, capped at {@link #MAX_SESSIONS_PER_GAME}, and pruned to the
 * 14-day window. Reflection results (app context, Lekf ctor) are cached.
 */
public final class BhPlayTimeTracker {
    private static final String PREFS = "bh_playtime_prefs";
    private static final String TAG = "BhPlayTime";
    private static final long DAY_MILLIS = 86_400_000L;
    private static final long WINDOW_14_DAYS_MILLIS = 14L * DAY_MILLIS;
    private static final long FLUSH_INTERVAL_MILLIS = 60_000L;
    private static final int MAX_SESSIONS_PER_GAME = 32;

    private static final String SOURCE_TYPE_PC = "2";

    // R8-renamed UI entity in 6.0.4. Will reshuffle on minor base bumps —
    // re-derive: find the class with ctor (I, String x6, J, J, J)V whose
    // field `d` the UI in f4d.invokeSuspend filters against the string "2".
    private static final String LEKF_CLASS_NAME = "ekf";

    private static volatile Context appContext;
    private static volatile Constructor<?> lekfCtor;

    private static final Map<String, ActiveSession> active = new HashMap<>();

    private BhPlayTimeTracker() {}

    private static final class ActiveSession {
        String a, b, c, d, e;
        long sessionStartMillis;
        long lastFlushMillis;
        long persistedSeconds;
    }

    private static Context appContext() {
        Context cached = appContext;
        if (cached != null) return cached;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Context c = (Application) m.invoke(null);
            appContext = c;
            return c;
        } catch (Throwable t) {
            Log.w(TAG, "appContext lookup failed", t);
            return null;
        }
    }

    private static Constructor<?> lekfCtor() {
        Constructor<?> cached = lekfCtor;
        if (cached != null) return cached;
        try {
            Class<?> cls = Class.forName(LEKF_CLASS_NAME);
            Constructor<?> ctor = cls.getDeclaredConstructor(
                    int.class,
                    String.class, String.class, String.class, String.class, String.class, String.class,
                    long.class, long.class, long.class
            );
            ctor.setAccessible(true);
            lekfCtor = ctor;
            return ctor;
        } catch (Throwable t) {
            Log.w(TAG, "Lekf ctor lookup failed", t);
            return null;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void recordStart(String a, String b, String c, String d, String e) {
        try {
            String key = pickGameKey(a, b, c, d, e);
            long now = System.currentTimeMillis();
            ActiveSession s = new ActiveSession();
            s.a = str(a); s.b = str(b); s.c = str(c); s.d = str(d); s.e = str(e);
            s.sessionStartMillis = now;
            s.lastFlushMillis = now;
            s.persistedSeconds = 0L;
            active.put(key, s);

            Context ctx = appContext();
            if (ctx == null) return;
            SharedPreferences sp = prefs(ctx);
            JSONObject obj = readEntry(sp, key);
            obj.put("gameKey", key);
            obj.put("a", s.a); obj.put("b", s.b); obj.put("c", s.c);
            obj.put("d", s.d); obj.put("e", s.e);
            obj.put("lastStartTime", now);
            sp.edit().putString(key, obj.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "recordStart failed", t);
        }
    }

    public static synchronized void tick(String a, String b, String c, String d, String e) {
        try {
            String key = pickGameKey(a, b, c, d, e);
            ActiveSession s = active.get(key);
            if (s == null) {
                recordStart(a, b, c, d, e);
                return;
            }
            long now = System.currentTimeMillis();
            if (now - s.lastFlushMillis < FLUSH_INTERVAL_MILLIS) return;
            s.lastFlushMillis = now;
            flushDelta(key, s, now, false);
        } catch (Throwable t) {
            Log.w(TAG, "tick failed", t);
        }
    }

    public static synchronized void recordEnd(String a, String b, String c, String d, String e) {
        try {
            String key = pickGameKey(a, b, c, d, e);
            ActiveSession s = active.remove(key);
            if (s == null) return;
            flushDelta(key, s, System.currentTimeMillis(), true);
        } catch (Throwable t) {
            Log.w(TAG, "recordEnd failed", t);
        }
    }

    private static void flushDelta(String key, ActiveSession s, long nowMillis, boolean ending) throws JSONException {
        Context ctx = appContext();
        if (ctx == null) return;
        long elapsed = Math.max(0L, (nowMillis - s.sessionStartMillis) / 1000L);
        long delta = Math.max(0L, elapsed - s.persistedSeconds);
        s.persistedSeconds = elapsed;

        SharedPreferences sp = prefs(ctx);
        JSONObject obj = readEntry(sp, key);
        obj.put("gameKey", key);
        obj.put("a", s.a); obj.put("b", s.b); obj.put("c", s.c);
        obj.put("d", s.d); obj.put("e", s.e);
        obj.put("totalSeconds", obj.optLong("totalSeconds", 0L) + delta);

        if (ending && elapsed > 0L) {
            JSONArray sessions = obj.optJSONArray("sessions");
            if (sessions == null) sessions = new JSONArray();
            JSONObject row = new JSONObject();
            row.put("at", nowMillis);
            row.put("dur", elapsed);
            sessions.put(row);
            pruneAndCap(sessions, nowMillis - WINDOW_14_DAYS_MILLIS);
            obj.put("sessions", sessions);
        }

        sp.edit().putString(key, obj.toString()).apply();
    }

    /**
     * Returns an {@code ArrayList<Lekf;>} matching what {@code Lse7;->c} would have
     * returned from the network. Caller is the bytecode hook in
     * {@code DisableHeartbeatLocalTrackerPatch} which wraps this in {@code Ln55;}.
     */
    public static Object getPcEntityList() {
        ArrayList<Object> out = new ArrayList<>();
        Context ctx = appContext();
        if (ctx == null) return out;
        Constructor<?> ctor = lekfCtor();
        if (ctor == null) return out;

        try {
            long now = System.currentTimeMillis();
            long cutoff = now - WINDOW_14_DAYS_MILLIS;
            Map<String, ?> all = prefs(ctx).getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                try {
                    JSONObject obj = new JSONObject(String.valueOf(e.getValue()));
                    String key = obj.optString("gameKey", e.getKey());
                    int gameId = safeParseInt(key);
                    long total = obj.optLong("totalSeconds", 0L);
                    long lastStart = obj.optLong("lastStartTime", 0L);

                    long last14 = 0L;
                    JSONArray sessions = obj.optJSONArray("sessions");
                    if (sessions != null) {
                        for (int i = 0; i < sessions.length(); i++) {
                            JSONObject row = sessions.optJSONObject(i);
                            if (row == null) continue;
                            if (row.optLong("at", 0L) >= cutoff) {
                                last14 += row.optLong("dur", 0L);
                            }
                        }
                    }

                    // Live-session top-up: include the in-progress elapsed seconds.
                    ActiveSession liveActive;
                    synchronized (BhPlayTimeTracker.class) {
                        liveActive = active.get(key);
                    }
                    if (liveActive != null) {
                        long live = Math.max(0L, (now - liveActive.sessionStartMillis) / 1000L
                                              - liveActive.persistedSeconds);
                        total += live;
                        last14 += live;
                    }

                    Object inst = ctor.newInstance(
                            gameId,
                            obj.optString("a", ""),
                            key,                          // c = unique map key (game id string)
                            SOURCE_TYPE_PC,               // d MUST be "2" or the UI filters it out
                            obj.optString("b", ""),
                            obj.optString("e", ""),
                            "",
                            last14,
                            lastStart,
                            total
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

    private static void pruneAndCap(JSONArray sessions, long cutoffMillis) throws JSONException {
        for (int i = sessions.length() - 1; i >= 0; i--) {
            if (sessions.getJSONObject(i).optLong("at", 0L) < cutoffMillis) {
                sessions.remove(i);
            }
        }
        while (sessions.length() > MAX_SESSIONS_PER_GAME) {
            sessions.remove(0);
        }
    }
}
