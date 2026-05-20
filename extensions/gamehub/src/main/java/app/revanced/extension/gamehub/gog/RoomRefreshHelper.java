package app.revanced.extension.gamehub.gog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

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
 * Why this exists: GogLaunchHelper.registerInLibrary() uses
 * SQLiteDatabase.openDatabase on the file directly (matches the proven
 * retired-seeder pattern; zero new deps). That opens a SEPARATE SQLite
 * connection from the one Room owns. Room's InvalidationTracker is wired to
 * Room's own connection only — so the SQL triggers Room installed do populate
 * room_table_modification_log for our writes (triggers are SQL-level and
 * fire for any writer), but Room never polls them. Library Flow/LiveData
 * observers stay stuck on the pre-write snapshot → game appears only after a
 * full app restart (cold path re-reads from disk).
 *
 * Fix: reach a live GameLibraryDatabase instance via reflective field walk
 * from the Application, call getInvalidationTracker() (name preserved by Room
 * codegen — *_Impl overrides), and invoke the tracker's no-arg void method
 * (R8-renamed but uniquely identified by signature). That forces Room to
 * re-read the modification log, see our dirty tables, and notify observers —
 * library UI refreshes in-session.
 *
 * Fail-safe: any reflection miss logs+returns; behavior degrades to the
 * pre-pre15 state (restart works). Never throws past this class.
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
            diagToast(ctx, "RR: invoke OK on " + refresh.getName());
        } catch (Throwable t) {
            Log.w(TAG, "RoomRefresh: notify failed (non-fatal)", t);
            diagToast(ctx, "RR: invoke FAILED " + t.getClass().getSimpleName()
                    + ": " + String.valueOf(t.getMessage()));
            // Drop the cache in case the tracker was GC'd / process recycled.
            cachedTracker = null;
            cachedRefresh = null;
        }
    }

    private static boolean resolve(Context ctx) {
        diagToast(ctx, "RR: walk start");
        Object db = findRoomDatabase(ctx);
        if (db == null) {
            Log.w(TAG, "RoomRefresh: GameLibraryDatabase not reachable from Application graph");
            diagToast(ctx, "RR: DB NOT REACHABLE in Application graph");
            return false;
        }
        diagToast(ctx, "RR: DB found = " + db.getClass().getSimpleName());
        Method getTracker = null;
        for (Class<?> c = db.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                getTracker = c.getDeclaredMethod("getInvalidationTracker");
                break;
            } catch (NoSuchMethodException ignored) {}
        }
        if (getTracker == null) {
            Log.w(TAG, "RoomRefresh: getInvalidationTracker() not found on " + db.getClass());
            diagToast(ctx, "RR: getInvalidationTracker NOT FOUND on " + db.getClass().getSimpleName());
            return false;
        }
        getTracker.setAccessible(true);
        Object tracker;
        try {
            tracker = getTracker.invoke(db);
        } catch (Throwable t) {
            Log.w(TAG, "RoomRefresh: getInvalidationTracker call failed", t);
            diagToast(ctx, "RR: getTracker invoke FAILED " + t.getClass().getSimpleName());
            return false;
        }
        if (tracker == null) {
            diagToast(ctx, "RR: getTracker returned null");
            return false;
        }
        diagToast(ctx, "RR: tracker = " + tracker.getClass().getName());

        Method refresh = pickNoArgVoid(tracker.getClass());
        if (refresh == null) {
            Log.w(TAG, "RoomRefresh: no no-arg void method on " + tracker.getClass());
            diagToast(ctx, "RR: no no-arg void method on tracker");
            return false;
        }
        refresh.setAccessible(true);

        cachedTracker = tracker;
        cachedRefresh = refresh;
        Log.i(TAG, "RoomRefresh: resolved tracker=" + tracker.getClass().getName()
                + " refresh=" + refresh.getName());
        diagToast(ctx, "RR: picked method " + refresh.getName() + "() on " + tracker.getClass().getName());
        return true;
    }

    private static void diagToast(Context ctx, String msg) {
        if (ctx == null) return;
        try {
            Log.i(TAG, "RR-TOAST: " + msg);
            Handler h = new Handler(Looper.getMainLooper());
            h.post(() -> {
                try {
                    Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
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
