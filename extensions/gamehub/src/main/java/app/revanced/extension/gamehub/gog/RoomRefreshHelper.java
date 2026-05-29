package app.revanced.extension.gamehub.gog;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * WS5 §37 — kick GameHub's Room InvalidationTracker after a raw-SQLite write to
 * db_game_library.db.
 *
 * §40 CORRECTION (device-verified 2026-05-29): the earlier premise here —
 * "the SQL triggers Room installed fire for any writer" — was WRONG. Room's
 * invalidation triggers AND its room_table_modification_log are connection-local
 * TEMP objects (neither is persisted in db_game_library.db's sqlite_master).
 * A write on a SEPARATE SQLiteDatabase.openDatabase connection therefore never
 * fires them, so refreshLibrary() scans an empty log and notifies nobody — it
 * was a guaranteed no-op for foreign-connection writes.
 *
 * Two coordinated parts now make in-session refresh work:
 *   1. {@link #getRoomConnection(Context)} hands GogLaunchHelper Room's OWN live
 *      android.database.sqlite.SQLiteDatabase so the INSERT fires Room's TEMP
 *      triggers and marks the (TEMP) modification log dirty.
 *   2. {@link #refreshLibrary(Context)} then invokes the InvalidationTracker's
 *      refresh — which now finds dirty tables and re-emits the library Flow.
 *
 * Both reach a live GameLibraryDatabase via a reflective field walk from the
 * Application (the @Database class name survives R8 — schema hash depends on
 * the FQN). The tracker's refresh method is R8-renamed but uniquely identified
 * by signature (no-arg, void).
 *
 * Fail-safe: any reflection miss logs+returns null/no-op; GogLaunchHelper then
 * falls back to a foreign-connection write (row lands, needs restart to show).
 * Never throws past this class.
 */
public final class RoomRefreshHelper {

    private static final String TAG = "BannerHub";
    private static final String DB_CLASS_SIMPLE = "GameLibraryDatabase";
    private static final int VISIT_BUDGET = 4000;

    private static volatile Object cachedTracker;
    private static volatile Method cachedRefresh;

    private RoomRefreshHelper() {}

    /** Notify Room's InvalidationTracker that t_game_library_base /
     *  t_game_launch_method changed under it. Best-effort; safe to call from
     *  any thread. */
    public static void refreshLibrary(Context ctx) {
        try {
            Object tracker = cachedTracker;
            Method refresh = cachedRefresh;
            if (tracker == null || refresh == null) {
                if (!resolve(ctx)) return;
                tracker = cachedTracker;
                refresh = cachedRefresh;
            }
            refresh.invoke(tracker);
            Log.i(TAG, "RoomRefresh: notified Room InvalidationTracker");
        } catch (Throwable t) {
            Log.w(TAG, "RoomRefresh: notify failed (non-fatal)", t);
            // Drop the cache in case the tracker was GC'd / process recycled.
            cachedTracker = null;
            cachedRefresh = null;
        }
    }

    /** Return Room's own live SQLite connection for GameLibraryDatabase, or null
     *  if it can't be reached. Callers MUST NOT close the returned connection —
     *  it belongs to Room. Writing through it fires Room's connection-local TEMP
     *  invalidation triggers, which is the only way a subsequent refreshLibrary()
     *  can re-emit the library Flow in-session. */
    public static SQLiteDatabase getRoomConnection(Context ctx) {
        try {
            Object db = findRoomDatabase(ctx);
            if (db == null) {
                Log.w(TAG, "RoomRefresh: GameLibraryDatabase not reachable — no Room connection");
                return null;
            }
            SQLiteDatabase conn = bfsForSqlite(db);
            if (conn == null) {
                Log.w(TAG, "RoomRefresh: no open android SQLiteDatabase reachable from Room db");
                return null;
            }
            Log.i(TAG, "RoomRefresh: resolved Room SQLiteDatabase connection (open="
                    + conn.isOpen() + ")");
            return conn;
        } catch (Throwable t) {
            Log.w(TAG, "RoomRefresh: getRoomConnection failed (non-fatal)", t);
            return null;
        }
    }

    /** BFS over instance fields (and array/collection/map elements) from the
     *  Room database instance, returning the first OPEN framework SQLiteDatabase.
     *  android.database.sqlite.SQLiteDatabase is a framework type so R8 can't
     *  rename it — the match is exact and obfuscation-proof. Room wraps exactly
     *  this object inside FrameworkSQLiteDatabase.mDelegate; writes on it route
     *  to the primary connection where the TEMP triggers live. */
    private static SQLiteDatabase bfsForSqlite(Object root) {
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Object> queue = new ArrayDeque<>();
        queue.add(root);
        visited.put(root, Boolean.TRUE);

        int budget = VISIT_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            Object cur = queue.poll();
            if (cur instanceof SQLiteDatabase) {
                SQLiteDatabase d = (SQLiteDatabase) cur;
                if (d.isOpen()) return d;
                continue;
            }

            Class<?> cc = cur.getClass();
            // Containers: traverse elements rather than treating them as POJOs.
            if (cur instanceof Iterable<?>) {
                try {
                    for (Object e : (Iterable<?>) cur) enqueueSqlite(e, queue, visited);
                } catch (Throwable ignored) {}
                continue;
            }
            if (cur instanceof Map<?, ?>) {
                try {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) cur).entrySet()) {
                        enqueueSqlite(e.getValue(), queue, visited);
                    }
                } catch (Throwable ignored) {}
                continue;
            }
            if (cc.isArray() && !cc.getComponentType().isPrimitive()) {
                Object[] arr;
                try { arr = (Object[]) cur; } catch (Throwable t) { continue; }
                for (Object e : arr) enqueueSqlite(e, queue, visited);
                continue;
            }

            for (Class<?> c = cc; c != null && c != Object.class; c = c.getSuperclass()) {
                Field[] fields;
                try { fields = c.getDeclaredFields(); } catch (Throwable t) { continue; }
                for (Field f : fields) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (isLeafType(f.getType())) continue;
                    try { f.setAccessible(true); } catch (Throwable t) { continue; }
                    Object v;
                    try { v = f.get(cur); } catch (Throwable t) { continue; }
                    enqueueSqlite(v, queue, visited);
                }
            }
        }
        return null;
    }

    private static void enqueueSqlite(Object v, ArrayDeque<Object> queue,
                                      IdentityHashMap<Object, Boolean> visited) {
        if (v == null || visited.containsKey(v)) return;
        if (!(v instanceof SQLiteDatabase) && isLeafType(v.getClass())) return;
        visited.put(v, Boolean.TRUE);
        // Prioritise direct hits so we don't exhaust the budget elsewhere first.
        if (v instanceof SQLiteDatabase) queue.addFirst(v);
        else queue.add(v);
    }

    private static boolean resolve(Context ctx) {
        Object db = findRoomDatabase(ctx);
        if (db == null) {
            Log.w(TAG, "RoomRefresh: GameLibraryDatabase not reachable from Application graph");
            return false;
        }
        Method getTracker = null;
        for (Class<?> c = db.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                getTracker = c.getDeclaredMethod("getInvalidationTracker");
                break;
            } catch (NoSuchMethodException ignored) {}
        }
        if (getTracker == null) {
            Log.w(TAG, "RoomRefresh: getInvalidationTracker() not found on " + db.getClass());
            return false;
        }
        getTracker.setAccessible(true);
        Object tracker;
        try {
            tracker = getTracker.invoke(db);
        } catch (Throwable t) {
            Log.w(TAG, "RoomRefresh: getInvalidationTracker call failed", t);
            return false;
        }
        if (tracker == null) return false;

        Method refresh = pickNoArgVoid(tracker.getClass());
        if (refresh == null) {
            Log.w(TAG, "RoomRefresh: no no-arg void method on " + tracker.getClass());
            return false;
        }
        refresh.setAccessible(true);

        cachedTracker = tracker;
        cachedRefresh = refresh;
        Log.i(TAG, "RoomRefresh: resolved tracker=" + tracker.getClass().getName()
                + " refresh=" + refresh.getName());
        return true;
    }

    /** Pick the single declared no-arg, non-static, void-returning method on
     *  the InvalidationTracker. R8 typically renames it ("a"), but the
     *  signature is unique among the class's declared methods. */
    private static Method pickNoArgVoid(Class<?> trackerCls) {
        Method[] methods;
        try {
            methods = trackerCls.getDeclaredMethods();
        } catch (Throwable t) {
            return null;
        }
        for (Method m : methods) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != void.class) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            // Skip synthetic / bridge to be safe; not strictly necessary here.
            if (m.isSynthetic() || m.isBridge()) continue;
            return m;
        }
        return null;
    }

    /** BFS over instance fields starting at the Application, looking for any
     *  object whose runtime class is the (un-obfuscated) GameLibraryDatabase. */
    private static Object findRoomDatabase(Context ctx) {
        Object app;
        try { app = ctx.getApplicationContext(); } catch (Throwable t) { app = null; }
        if (app == null) return null;

        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Object> queue = new ArrayDeque<>();
        queue.add(app);
        visited.put(app, Boolean.TRUE);

        int budget = VISIT_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            Object cur = queue.poll();
            if (isOurDatabase(cur)) return cur;

            // Walk fields up the class hierarchy.
            for (Class<?> c = cur.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                Field[] fields;
                try { fields = c.getDeclaredFields(); } catch (Throwable t) { continue; }
                for (Field f : fields) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (isLeafType(ft)) continue;
                    try { f.setAccessible(true); } catch (Throwable t) { continue; }
                    Object v;
                    try { v = f.get(cur); } catch (Throwable t) { continue; }
                    enqueue(v, queue, visited);
                }
            }
        }
        return null;
    }

    private static void enqueue(Object v, ArrayDeque<Object> queue,
                                IdentityHashMap<Object, Boolean> visited) {
        if (v == null) return;
        if (visited.containsKey(v)) return;

        // Direct hit at enqueue-time too, so we don't waste a poll cycle.
        if (isOurDatabase(v)) {
            visited.put(v, Boolean.TRUE);
            queue.addFirst(v);
            return;
        }

        Class<?> vc = v.getClass();
        if (isLeafType(vc)) return;

        visited.put(v, Boolean.TRUE);

        // Containers: iterate elements / entries instead of treating as POJO.
        if (v instanceof Iterable<?>) {
            try {
                for (Object e : (Iterable<?>) v) {
                    if (e != null && !visited.containsKey(e) && !isLeafType(e.getClass())) {
                        if (isOurDatabase(e)) { visited.put(e, Boolean.TRUE); queue.addFirst(e); return; }
                        visited.put(e, Boolean.TRUE);
                        queue.add(e);
                    }
                }
            } catch (Throwable ignored) {}
            return;
        }
        if (v instanceof Map<?, ?>) {
            try {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    Object val = e.getValue();
                    if (val != null && !visited.containsKey(val) && !isLeafType(val.getClass())) {
                        if (isOurDatabase(val)) { visited.put(val, Boolean.TRUE); queue.addFirst(val); return; }
                        visited.put(val, Boolean.TRUE);
                        queue.add(val);
                    }
                }
            } catch (Throwable ignored) {}
            return;
        }
        if (vc.isArray() && !vc.getComponentType().isPrimitive()) {
            Object[] arr;
            try { arr = (Object[]) v; } catch (Throwable t) { return; }
            for (Object e : arr) {
                if (e != null && !visited.containsKey(e) && !isLeafType(e.getClass())) {
                    if (isOurDatabase(e)) { visited.put(e, Boolean.TRUE); queue.addFirst(e); return; }
                    visited.put(e, Boolean.TRUE);
                    queue.add(e);
                }
            }
            return;
        }

        queue.add(v);
    }

    private static boolean isOurDatabase(Object o) {
        if (o == null) return false;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getName();
            // Match the un-obfuscated Room @Database class. We compare simple
            // name (suffix) so the package path doesn't matter — the only
            // class named "GameLibraryDatabase" in this APK is GameHub's.
            int dot = n.lastIndexOf('.');
            String simple = (dot >= 0) ? n.substring(dot + 1) : n;
            if (simple.equals(DB_CLASS_SIMPLE) || simple.equals(DB_CLASS_SIMPLE + "_Impl")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLeafType(Class<?> c) {
        if (c == null) return true;
        if (c.isPrimitive()) return true;
        if (c == String.class || c == Class.class) return true;
        if (Number.class.isAssignableFrom(c)) return true;
        if (c == Boolean.class || c == Character.class) return true;
        if (c.isArray() && c.getComponentType() != null && c.getComponentType().isPrimitive()) return true;
        return false;
    }
}
