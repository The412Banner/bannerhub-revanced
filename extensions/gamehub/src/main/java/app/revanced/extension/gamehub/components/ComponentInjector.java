package app.revanced.extension.gamehub.components;

import android.app.Application;
import android.content.Context;

import app.revanced.extension.gamehub.debug.DebugTrace;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Merges sidecar-registered (manager-injected) components into the host app's
 * server-supplied component list at the boundary of {@code Lgxh;->a(RepoCategory, Continuation)}.
 *
 * <p>The single public method {@link #append(Object, List)} is invoked by the
 * {@code ComponentInjectionPatch} bytecode patch immediately before the suspend
 * function's final {@code return-object}. The patch passes the original method's
 * first parameter ({@code RepoCategory}, in {@code p1}) and the about-to-be-returned
 * list (in {@code v0}). We append our matching sidecar entries — only when the
 * caller asked for {@code COMPONENT}, since that's the category Settings dropdowns
 * pull from — then return the merged list.</p>
 *
 * <p>Reflection strategy mirrors the pattern proven by {@code FakeAuthToken} /
 * {@code FakeUserAccount}: we don't hardcode the R8-renamed inner-class names
 * ({@code EnvLayerEntity} et al.). Instead we anchor on the first element of
 * {@code serverList} to discover the {@code WinEmuRepo} class, then use its
 * reflective constructor with values pulled from the sidecar JSON.</p>
 */
public final class ComponentInjector {
    private static final String TAG = "GH600-DEBUG";
    private static final String REPO_CLASS = "com.xiaoji.egggame.common.winemu.bean.WinEmuRepo";
    private static final String STATE_CLASS = "com.xiaoji.egggame.common.winemu.bean.State";
    private static final String CATEGORY_CLASS = "com.xiaoji.egggame.common.winemu.bean.RepoCategory";
    private static final String ENTRY_CLASS = "com.xiaoji.egggame.common.winemu.bean.EnvLayerEntity";

    /** RepoCategory member name we want sidecar entries to be appended for. */
    private static final String CATEGORY_COMPONENT = "COMPONENT";

    /** Cache of the {@code WinEmuRepo} reflective constructor (resolved once). */
    private static volatile Constructor<?> repoCtorCache;

    private ComponentInjector() {
        // utility class
    }

    /**
     * Append sidecar entries to the host app's category-filtered component list.
     *
     * @param category the {@code RepoCategory} enum instance (in {@code p1} of
     *                 the patched method). Type-erased to {@code Object} so the
     *                 patch doesn't need to import the host class.
     * @param serverList the list the host method was about to return (typically
     *                   {@code ArrayList<WinEmuRepo>}). Type-erased; we operate
     *                   on it as {@code List<Object>}.
     * @return a new list containing every server entry plus matching sidecar
     *         entries, in that order. Never null. On any failure we log to
     *         {@code GH600-DEBUG} and return the {@code serverList} unchanged
     *         so the patched method's behaviour is at-worst a no-op.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<Object> append(Object category, List serverList) {
        try {
            // Defensive copy first so the caller never sees a partially-mutated list.
            List<Object> merged = serverList == null
                    ? new ArrayList<>()
                    : new ArrayList<>(serverList);

            if (!isComponentCategory(category)) {
                // Settings dropdowns pull from RepoCategory.COMPONENT only;
                // CONTAINER and IMAGE_FS lookups skip the merge entirely.
                return merged;
            }

            Context ctx = appContext();
            if (ctx == null) {
                DebugTrace.write("ComponentInjector.append: no app context, skipping merge");
                return merged;
            }

            // Pull all sidecar entries regardless of inner type — the host UI
            // does its own per-entry.type filtering after we hand back the merged
            // RepoCategory.COMPONENT list. We just need to hand over every
            // sidecar entry in this category.
            List<JSONObject> sidecar = SidecarRegistry.getAllByType(ctx, /*type*/ -1);
            if (sidecar.isEmpty()) return merged;

            // De-dupe: never shadow a server entry with a sidecar entry of the same name.
            Set<String> serverNames = collectNames(merged);
            Constructor<?> ctor = resolveRepoCtor(merged);
            if (ctor == null) {
                DebugTrace.write("ComponentInjector.append: cannot resolve WinEmuRepo ctor, skipping " + sidecar.size() + " sidecar entries");
                return merged;
            }

            int appended = 0;
            for (JSONObject json : sidecar) {
                String name = json.optString("name", null);
                if (name == null || serverNames.contains(name)) continue;
                Object repo = buildRepo(ctor, json);
                if (repo == null) continue;
                merged.add(repo);
                appended++;
            }
            DebugTrace.write("ComponentInjector.append: merged " + appended + " sidecar entries (server=" + (serverList == null ? 0 : serverList.size()) + ")");
            return merged;
        } catch (Throwable t) {
            DebugTrace.write("ComponentInjector.append: top-level failure", t);
            return serverList == null ? new ArrayList<>() : new ArrayList<>(serverList);
        }
    }

    /** True iff {@code category} is the RepoCategory.COMPONENT enum constant. */
    private static boolean isComponentCategory(Object category) {
        if (category == null) return false;
        try {
            // Two reliable checks: name() and the runtime class name.
            // We avoid loading the host class explicitly so the extension
            // doesn't fail at class-load time if R8 renames the package.
            Method nameMethod = category.getClass().getMethod("name");
            Object name = nameMethod.invoke(category);
            return CATEGORY_COMPONENT.equals(String.valueOf(name));
        } catch (Throwable t) {
            DebugTrace.write("ComponentInjector.isComponentCategory: reflection failure", t);
            return false;
        }
    }

    /**
     * Resolves and caches the {@code WinEmuRepo} canonical constructor. Tries:
     * 1. Anchor on {@code serverList.get(0).getClass()} (most reliable — survives R8 mangling).
     * 2. Fall back to {@code Class.forName(REPO_CLASS)}.
     * Returns null if neither works.
     */
    private static Constructor<?> resolveRepoCtor(List<?> serverList) {
        Constructor<?> cached = repoCtorCache;
        if (cached != null) return cached;
        synchronized (ComponentInjector.class) {
            if (repoCtorCache != null) return repoCtorCache;
            Class<?> repoClass = null;
            if (serverList != null && !serverList.isEmpty()) {
                Object sample = null;
                for (Object o : serverList) {
                    if (o != null) { sample = o; break; }
                }
                if (sample != null) repoClass = sample.getClass();
            }
            if (repoClass == null) {
                try {
                    repoClass = Class.forName(REPO_CLASS);
                } catch (Throwable t) {
                    DebugTrace.write("ComponentInjector.resolveRepoCtor: Class.forName failed", t);
                    return null;
                }
            }
            // Pick the constructor with the most parameters — Kotlin data classes
            // get a primary ctor + a synthetic varargs/default-supplier ctor. We
            // want the primary, which is also the longest one.
            Constructor<?> best = null;
            for (Constructor<?> c : repoClass.getDeclaredConstructors()) {
                if (best == null || c.getParameterCount() > best.getParameterCount()) {
                    best = c;
                }
            }
            if (best != null) {
                best.setAccessible(true);
                repoCtorCache = best;
            }
            return repoCtorCache;
        }
    }

    /**
     * Build a {@code WinEmuRepo} instance from a sidecar JSON entry. Uses the
     * canonical primary constructor signature documented in §6.5 of the design
     * report:
     *   <pre>(name: String, version: String, state: State, entry: EnvLayerEntity,
     *         category: RepoCategory, isBase: boolean, isDep: boolean, depInfo: d54)</pre>
     * For unknown / non-trivially-instantiable parameters ({@code EnvLayerEntity},
     * {@code d54}) we pass {@code null} — the Component Manager UI only reads the
     * top-level fields ({@code name}, {@code version}, {@code state}, {@code category},
     * {@code isBase}, {@code isDep}) for rendering, and per-game settings dropdowns
     * read the same plus the inner entry which we leave null on first cut. If a
     * sidecar entry needs the inner {@code EnvLayerEntity} populated for downstream
     * filtering (for instance the per-{@code entry.type} settings dropdowns), Job 12
     * will revisit and synthesize it via the same reflection pattern.
     */
    private static Object buildRepo(Constructor<?> ctor, JSONObject json) {
        try {
            Class<?>[] paramTypes = ctor.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                args[i] = defaultForParam(paramTypes[i], json, i);
            }
            return ctor.newInstance(args);
        } catch (Throwable t) {
            DebugTrace.write("ComponentInjector.buildRepo: ctor invocation failed for " + json.optString("name", "?"), t);
            return null;
        }
    }

    /** Map a parameter slot to a sensible default extracted from the sidecar JSON. */
    private static Object defaultForParam(Class<?> paramType, JSONObject json, int idx) {
        String typeName = paramType.getName();
        if (paramType == String.class) {
            // The first two String params of the primary ctor are name + version.
            if (idx == 0) return json.optString("name", "");
            if (idx == 1) return json.optString("version", "1.0.0");
            return "";
        }
        if (paramType == boolean.class || paramType == Boolean.class) {
            // Best-effort: param 5 is isBase, param 6 is isDep in the canonical sig.
            if (idx == 5) return json.optBoolean("isBase", false);
            if (idx == 6) return json.optBoolean("isDep", false);
            return false;
        }
        if (paramType == int.class || paramType == Integer.class) return 0;
        if (paramType == long.class || paramType == Long.class) return 0L;
        if (paramType == float.class || paramType == Float.class) return 0f;
        if (paramType == double.class || paramType == Double.class) return 0d;
        // Enums: try to coerce State and RepoCategory by name, otherwise null.
        if (paramType.isEnum()) {
            if (typeName.equals(STATE_CLASS)) {
                String stateName = json.optString("state", "None");
                return enumValueOf(paramType, stateName, "None");
            }
            if (typeName.equals(CATEGORY_CLASS)) {
                String catName = json.optString("category", CATEGORY_COMPONENT);
                return enumValueOf(paramType, catName, CATEGORY_COMPONENT);
            }
            return null;
        }
        // Reference types we don't know how to construct (EnvLayerEntity, d54): null.
        // Kotlin's null-asserts in the WinEmuRepo ctor are TBD — if the host class
        // throws on null entry, we'll need to synthesize an EnvLayerEntity here in
        // a follow-up. For v1 we assume null is acceptable for these slots and
        // accept the risk that a sidecar-only flow may NPE on first dropdown render
        // (will be caught + logged, falling back to a skip).
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValueOf(Class<?> enumClass, String name, String fallbackName) {
        try {
            return Enum.valueOf((Class<? extends Enum>) enumClass, name);
        } catch (Throwable ignore) {
            try {
                return Enum.valueOf((Class<? extends Enum>) enumClass, fallbackName);
            } catch (Throwable t) {
                DebugTrace.write("ComponentInjector.enumValueOf: " + enumClass.getName() + "." + name + " failed", t);
                return null;
            }
        }
    }

    /** Collect the {@code name} field of every entry in the merged list, for de-dupe. */
    private static Set<String> collectNames(List<?> list) {
        Set<String> out = new HashSet<>();
        if (list == null) return out;
        for (Object o : list) {
            if (o == null) continue;
            String name = readName(o);
            if (name != null) out.add(name);
        }
        return out;
    }

    private static String readName(Object repo) {
        try {
            // WinEmuRepo has both a public final field 'name' (Kotlin data class
            // accessor pattern) and a getName() method. Try the method first.
            try {
                Method getName = repo.getClass().getMethod("getName");
                Object v = getName.invoke(repo);
                if (v instanceof String) return (String) v;
            } catch (NoSuchMethodException ignore) { /* fall through */ }
            Field nameField = repo.getClass().getField("name");
            Object v = nameField.get(repo);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolve the Application context without leaning on Koin. Uses
     * {@code ActivityThread.currentApplication()} via reflection — the standard
     * trick for "I'm in a static context with no Context handed in".
     */
    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable t) {
            DebugTrace.write("ComponentInjector.appContext: reflection failure", t);
            return null;
        }
    }
}
