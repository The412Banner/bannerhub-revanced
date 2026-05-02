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

    /** Cache of the {@code EnvLayerEntity} class (R8-mangled at runtime). */
    private static volatile Class<?> entryClassCache;

    /** Cache of the {@code EnvLayerEntity} reflective constructor. */
    private static volatile Constructor<?> entryCtorCache;

    private ComponentInjector() {
        // utility class
    }

    /**
     * Append sidecar entries to the host's component list at the
     * {@code Lm13;->b(Lexh;)} boundary. {@code m13} is the {@code x0a}
     * implementation hardcoded to {@code RepoCategory.COMPONENT}, so no category
     * check is needed — every call to this method gets the merge.
     *
     * @param serverListObj the awaited result from the m13 coroutine — typed
     *                      as {@code Object} because the smali hook hands us
     *                      whatever was in {@code p0} after {@code move-result-object}.
     *                      In practice this is {@code ArrayList<WinEmuRepo>}.
     * @return a new list containing every server entry plus our sidecar entries.
     *         Returned as {@code Object} so the smali patch's
     *         {@code move-result-object p0} can flow it straight back into the
     *         original {@code return-object p0}. On any failure we return
     *         {@code serverListObj} unchanged so the patched method is a no-op.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object appendComponents(Object serverListObj) {
        if (!(serverListObj instanceof List)) {
            // Coroutine returned a Throwable wrapper or COROUTINE_SUSPENDED on
            // some pathological code paths — hand it straight back, don't touch.
            return serverListObj;
        }
        List serverList = (List) serverListObj;
        try {
            return doMerge(serverList);
        } catch (Throwable t) {
            DebugTrace.write("ComponentInjector.appendComponents: top-level failure", t);
            return serverList;
        }
    }

    /**
     * Read-side hook for {@code Ll9o;->z(RepoCategory)Ljava/util/ArrayList;}.
     * Merges sidecar entries into the returned ArrayList only when the requested
     * category is {@code COMPONENT}; for {@code CONTAINER} / {@code IMAGE_FS}
     * we hand the list back unchanged.
     *
     * <p>Returns {@code ArrayList} (not just {@code List}) so the smali patch can
     * flow the result back into {@code v0} and the original
     * {@code return-object v0} keeps its declared return type.</p>
     *
     * @param category the {@code RepoCategory} (in {@code p1} of {@code l9o.z}).
     *                 Type-erased to {@code Object}.
     * @param serverList the ArrayList l9o.z assembled by filtering its in-memory
     *                   registry cache. Type-erased to {@code List}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ArrayList appendByCategory(Object category, List serverList) {
        ArrayList<Object> passthrough = serverList instanceof ArrayList
                ? (ArrayList<Object>) serverList
                : (serverList == null ? new ArrayList<>() : new ArrayList<>(serverList));
        try {
            if (!isComponentCategory(category)) return passthrough;
            List<Object> merged = doMerge(passthrough);
            return merged instanceof ArrayList
                    ? (ArrayList<Object>) merged
                    : new ArrayList<>(merged);
        } catch (Throwable t) {
            DebugTrace.write("ComponentInjector.appendByCategory: top-level failure", t);
            return passthrough;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> doMerge(List serverList) {
        try {
            List<Object> merged = serverList == null
                    ? new ArrayList<>()
                    : new ArrayList<>(serverList);

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

            // Resolve EnvLayerEntity class+ctor by anchoring on a server entry's
            // getEntry()/entry field — survives R8 renaming. May be null if every
            // server entry has a null inner entry; we fall back to Class.forName.
            Class<?> entryClass = resolveEntryClass(merged);
            Constructor<?> entryCtor = resolveEntryCtor(entryClass);

            int appended = 0;
            for (JSONObject json : sidecar) {
                String name = json.optString("name", null);
                if (name == null || serverNames.contains(name)) continue;
                Object repo = buildRepo(ctor, json, entryClass, entryCtor);
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
     *
     * <p>Per-game settings dropdowns filter by inner {@code entry.type} (int), so
     * the {@code EnvLayerEntity} slot must be populated — passing null silently
     * drops the row from the type-filtered dropdown (manager-side rendering still
     * works because that reads top-level fields). We synthesize the entity via
     * the resolved {@code entryCtor}, then write the {@code type} field directly
     * from the sidecar JSON. {@code d54} (depInfo) stays null — it's only consulted
     * for dependency resolution, which sidecar entries don't participate in.</p>
     */
    private static Object buildRepo(Constructor<?> ctor, JSONObject json,
                                    Class<?> entryClass, Constructor<?> entryCtor) {
        try {
            Class<?>[] paramTypes = ctor.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                args[i] = defaultForParam(paramTypes[i], json, i, entryClass, entryCtor);
            }
            return ctor.newInstance(args);
        } catch (Throwable t) {
            DebugTrace.write("ComponentInjector.buildRepo: ctor invocation failed for " + json.optString("name", "?"), t);
            return null;
        }
    }

    /** Map a parameter slot to a sensible default extracted from the sidecar JSON. */
    private static Object defaultForParam(Class<?> paramType, JSONObject json, int idx,
                                          Class<?> entryClass, Constructor<?> entryCtor) {
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
        // EnvLayerEntity slot: synthesize so per-game settings dropdowns can
        // filter our row by entry.type. Other reference types (d54/depInfo): null.
        if (entryClass != null && entryClass.isAssignableFrom(paramType)) {
            return buildEntity(entryClass, entryCtor, json);
        }
        return null;
    }

    /**
     * Resolve the {@code EnvLayerEntity} class. Preferred path: pull a sample from
     * a server {@code WinEmuRepo}'s {@code getEntry()} (or {@code entry} field) so
     * we capture the R8-mangled runtime class. Fallback: {@code Class.forName} on
     * the design-time FQN. Returns null if neither works — callers must tolerate.
     */
    private static Class<?> resolveEntryClass(List<?> serverList) {
        Class<?> cached = entryClassCache;
        if (cached != null) return cached;
        synchronized (ComponentInjector.class) {
            if (entryClassCache != null) return entryClassCache;
            Class<?> found = null;
            if (serverList != null) {
                for (Object o : serverList) {
                    if (o == null) continue;
                    Object e = readEntry(o);
                    if (e != null) { found = e.getClass(); break; }
                }
            }
            if (found == null) {
                try { found = Class.forName(ENTRY_CLASS); }
                catch (Throwable t) {
                    DebugTrace.write("ComponentInjector.resolveEntryClass: Class.forName failed", t);
                }
            }
            entryClassCache = found;
            return found;
        }
    }

    /** Pick the longest constructor for the resolved EnvLayerEntity class. */
    private static Constructor<?> resolveEntryCtor(Class<?> entryClass) {
        if (entryClass == null) return null;
        Constructor<?> cached = entryCtorCache;
        if (cached != null) return cached;
        synchronized (ComponentInjector.class) {
            if (entryCtorCache != null) return entryCtorCache;
            Constructor<?> best = null;
            for (Constructor<?> c : entryClass.getDeclaredConstructors()) {
                if (best == null || c.getParameterCount() > best.getParameterCount()) {
                    best = c;
                }
            }
            if (best != null) best.setAccessible(true);
            entryCtorCache = best;
            return entryCtorCache;
        }
    }

    /**
     * Build an {@code EnvLayerEntity} for a sidecar row. Construct via the
     * longest ctor with primitive zeros / null refs / empty strings, then
     * overwrite {@code type} (Integer in the host class — see
     * {@code l13.smali:1615}'s {@code getType()Ljava/lang/Integer;}), {@code name}
     * and {@code version} from the sidecar JSON. Downstream filtering reads
     * {@code entity.getType()} for the per-dropdown bucket and {@code getName()}
     * / {@code getVersion()} for display + name-prefix discrimination
     * (translator type=1 splits FEX vs Box64 by name).
     */
    private static Object buildEntity(Class<?> entryClass, Constructor<?> entryCtor, JSONObject json) {
        if (entryCtor == null || entryClass == null) {
            DebugTrace.write("ComponentInjector.buildEntity: no entry class/ctor, returning null entry");
            return null;
        }
        try {
            Class<?>[] paramTypes = entryCtor.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                args[i] = primitiveOrNullDefault(paramTypes[i]);
            }
            Object instance = entryCtor.newInstance(args);
            int typeInt = readSidecarType(json);
            writeTypeField(entryClass, instance, typeInt);
            writeStringField(entryClass, instance, "name", json.optString("name", ""));
            writeStringField(entryClass, instance, "version", json.optString("version", ""));
            return instance;
        } catch (Throwable t) {
            DebugTrace.write("ComponentInjector.buildEntity: ctor invocation failed", t);
            return null;
        }
    }

    /** Write a String field on the entity by name; silent no-op if absent. */
    private static void writeStringField(Class<?> entryClass, Object instance, String fieldName, String value) {
        try {
            Field f = entryClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            if (f.getType() == String.class) f.set(instance, value);
        } catch (NoSuchFieldException ignore) {
            // R8 may rename — best effort. Settings UI degrades gracefully.
        } catch (Throwable t) {
            DebugTrace.write("ComponentInjector.writeStringField(" + fieldName + ") failed", t);
        }
    }

    /** Read inner {@code entry.type}; fall back to top-level {@code type}; default 6 (runtime dep). */
    private static int readSidecarType(JSONObject json) {
        JSONObject inner = json.optJSONObject("entry");
        if (inner != null && inner.has("type")) return inner.optInt("type", 6);
        return json.optInt("type", 6);
    }

    /**
     * Write the {@code type} int onto the entity. Kotlin data classes expose
     * primary-ctor properties as same-named fields; if R8 renamed it we walk
     * declared fields and pick the first int field whose name contains "type".
     */
    private static void writeTypeField(Class<?> entryClass, Object instance, int typeInt) {
        try {
            Field tf = entryClass.getDeclaredField("type");
            tf.setAccessible(true);
            if (tf.getType() == int.class) { tf.setInt(instance, typeInt); return; }
            if (tf.getType() == Integer.class) { tf.set(instance, Integer.valueOf(typeInt)); return; }
        } catch (NoSuchFieldException ignore) { /* fall through to scan */ }
        catch (Throwable t) {
            DebugTrace.write("ComponentInjector.writeTypeField: direct set failed", t);
        }
        // R8-renamed fallback: first int field whose name contains "type".
        try {
            for (Field f : entryClass.getDeclaredFields()) {
                if (f.getType() == int.class && f.getName().toLowerCase().contains("type")) {
                    f.setAccessible(true);
                    f.setInt(instance, typeInt);
                    return;
                }
            }
            DebugTrace.write("ComponentInjector.writeTypeField: no int 'type' field on " + entryClass.getName());
        } catch (Throwable t) {
            DebugTrace.write("ComponentInjector.writeTypeField: scan failed", t);
        }
    }

    /** Read entry from a WinEmuRepo via getEntry() / entry field; null if absent. */
    private static Object readEntry(Object repo) {
        try {
            try {
                Method m = repo.getClass().getMethod("getEntry");
                Object v = m.invoke(repo);
                if (v != null) return v;
            } catch (NoSuchMethodException ignore) { /* try field */ }
            Field f = repo.getClass().getField("entry");
            return f.get(repo);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** Zero / null / empty default by Class — for filling unknown ctor slots. */
    private static Object primitiveOrNullDefault(Class<?> t) {
        if (t == String.class) return "";
        if (t == boolean.class || t == Boolean.class) return false;
        if (t == int.class || t == Integer.class) return 0;
        if (t == long.class || t == Long.class) return 0L;
        if (t == float.class || t == Float.class) return 0f;
        if (t == double.class || t == Double.class) return 0d;
        if (t == byte.class || t == Byte.class) return (byte) 0;
        if (t == short.class || t == Short.class) return (short) 0;
        if (t == char.class || t == Character.class) return (char) 0;
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
