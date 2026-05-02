package app.revanced.extension.gamehub.components;

import android.content.Context;

import app.revanced.extension.gamehub.debug.DebugTrace;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atomic write-through into the host's live registry via reflection-call to
 * {@code Ll9o;->G(WinEmuRepo)V} — the host's own internal write method that
 * synchronously updates BOTH the in-memory {@code l9o.c}
 * {@code ConcurrentHashMap} (which the per-game settings picker reads) AND
 * the {@code sp_winemu_unified_resources.xml} on disk via {@code y99.b}.
 *
 * <p>This replaces the v0.4.0/v0.4.1 approach of writing only to disk —
 * disk writes are invisible to the picker because {@code l9o.c} hydrates from
 * the XML only at app start. Calling {@code l9o.G} closes that gap: the
 * picker sees our entry on the very next dropdown open, no process restart
 * needed.</p>
 *
 * <p>Confirmed at {@code l9o.smali:1517–1584}: {@code G(WinEmuRepo)V} runs
 * {@code l9o.c.put(key, repo)} at line 1566, then {@code y99.b(repo)} at
 * line 1579. Same atomic write-through the API refresh path uses for
 * server-supplied entries.</p>
 */
public final class HostCache {
    private static final String L9O_CLASS = "l9o";
    private static final String REPO_CLASS_FQN = "com.xiaoji.egggame.common.winemu.bean.WinEmuRepo";

    private HostCache() {}

    /**
     * Build a {@code WinEmuRepo} from {@code entry} (JSON shaped like a host
     * registry record) and call {@code Ll9o;->G(WinEmuRepo)V} on the live
     * singleton. Updates both cache and disk in one shot.
     *
     * @return {@code true} if {@code l9o.G} was invoked, {@code false} if
     *         any reflection step failed (cause logged to {@code GH600-DEBUG}).
     */
    public static boolean injectViaL9oG(Context ctx, JSONObject entry) {
        try {
            if (entry == null) return false;

            Class<?> l9oClass = Class.forName(L9O_CLASS);
            Object l9o = getL9oSingleton(l9oClass);
            if (l9o == null) {
                DebugTrace.write("HostCache: l9o singleton not initialized yet");
                return false;
            }

            Class<?> repoClass = discoverRepoClass(l9o, l9oClass);
            if (repoClass == null) {
                DebugTrace.write("HostCache: WinEmuRepo class undiscoverable");
                return false;
            }

            Object repo = ComponentInjector.buildRepoForJson(entry);
            if (repo == null) {
                DebugTrace.write("HostCache: buildRepoForJson returned null for "
                        + entry.optString("name"));
                return false;
            }

            // l9o.G takes the host's WinEmuRepo class — the parameter type
            // must match exactly. Look up the method by single-arg signature
            // matching the discovered class.
            Method g = findMethodG(l9oClass, repoClass);
            if (g == null) {
                DebugTrace.write("HostCache: l9o.G method not found");
                return false;
            }
            g.setAccessible(true);
            g.invoke(l9o, repo);
            DebugTrace.write("HostCache.injectViaL9oG: l9o.G OK for "
                    + entry.optString("name"));
            return true;
        } catch (Throwable t) {
            DebugTrace.write("HostCache.injectViaL9oG failed for "
                    + (entry == null ? "null" : entry.optString("name")), t);
            return false;
        }
    }

    /**
     * Re-poke every sidecar entry into the live registry. Called from
     * {@link ComponentManagerActivity#onCreate} as self-heal insurance —
     * if the host's API refresh has clobbered our entries from
     * {@code sp_winemu_unified_resources.xml} since last boot, this restores
     * them in both cache and disk in a single pass.
     */
    public static void rehydrateFromSidecar(Context ctx) {
        if (ctx == null) return;
        try {
            Map<String, JSONObject> entries = SidecarRegistry.getAllEntries(ctx);
            if (entries.isEmpty()) return;
            int n = 0;
            for (Map.Entry<String, JSONObject> e : entries.entrySet()) {
                if (injectViaL9oG(ctx, toHostShape(e.getValue()))) n++;
            }
            DebugTrace.write("HostCache.rehydrateFromSidecar: poked " + n
                    + " of " + entries.size() + " entries");
        } catch (Throwable t) {
            DebugTrace.write("HostCache.rehydrateFromSidecar failed", t);
        }
    }

    /** Strip our {@code _bh_*} markers and force {@code state:"None"} so the
     * resulting JSON matches a server entry exactly. The picker filter that
     * rejects non-{@code None}-state entries is what kept us hidden. */
    static JSONObject toHostShape(JSONObject src) throws Exception {
        JSONObject clone = new JSONObject(src.toString());
        removeBhKeys(clone);
        clone.put("state", "None");
        JSONObject inner = clone.optJSONObject("entry");
        if (inner != null) {
            removeBhKeys(inner);
            inner.remove("category");
            inner.put("state", "None");
        }
        return clone;
    }

    private static void removeBhKeys(JSONObject obj) {
        java.util.List<String> kill = new java.util.ArrayList<>();
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith("_bh_")) kill.add(k);
        }
        for (String k : kill) obj.remove(k);
    }

    private static Object getL9oSingleton(Class<?> l9oClass) throws Exception {
        // Prefer the public static accessor o() if present.
        try {
            Method o = l9oClass.getDeclaredMethod("o");
            o.setAccessible(true);
            return o.invoke(null);
        } catch (NoSuchMethodException ignore) {
            // Fall back to the static singleton field d.
            Field d = l9oClass.getDeclaredField("d");
            d.setAccessible(true);
            return d.get(null);
        }
    }

    /** Anchor on a sample WinEmuRepo from l9o.c to capture the R8-mangled
     * runtime class. Falls back to {@code Class.forName} on the FQN. */
    @SuppressWarnings("rawtypes")
    private static Class<?> discoverRepoClass(Object l9o, Class<?> l9oClass) throws Exception {
        Field cField = l9oClass.getDeclaredField("c");
        cField.setAccessible(true);
        Object cache = cField.get(l9o);
        if (cache instanceof ConcurrentHashMap) {
            ConcurrentHashMap map = (ConcurrentHashMap) cache;
            for (Object v : map.values()) {
                if (v != null) return v.getClass();
            }
        }
        try { return Class.forName(REPO_CLASS_FQN); }
        catch (Throwable t) { return null; }
    }

    private static Method findMethodG(Class<?> l9oClass, Class<?> repoClass) {
        for (Method m : l9oClass.getDeclaredMethods()) {
            if (!"G".equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1) continue;
            if (params[0].isAssignableFrom(repoClass) || repoClass.isAssignableFrom(params[0])) {
                return m;
            }
        }
        return null;
    }
}
