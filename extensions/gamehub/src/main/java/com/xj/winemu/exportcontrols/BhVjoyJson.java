package com.xj.winemu.exportcontrols;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection bridge to kotlinx-serialization for VJoyLayout JSON <-> object
 * conversion, without compile-time access to the (R8-mangled, dynamically-
 * named) generated serializer classes.
 *
 * Strategy:
 *  1. Locate the {@code kotlinx.serialization.json.Json} class. kotlinx
 *     keeps its FQN by convention; R8 in this app does NOT rename
 *     kotlinx.serialization core types because they're referenced by
 *     reflection-generated companion code that uses string class names.
 *  2. Locate the {@code Json.Default} (or another singleton) instance of
 *     the Json format. Either via the {@code Json$Default} inner class or
 *     by constructing a default Json on the fly.
 *  3. Call {@code Json.encodeToString(serializer, value)} /
 *     {@code Json.decodeFromString(serializer, string)} reflectively.
 *  4. Locate the layout class's {@code serializer()} static via reflection
 *     on the kotlinx-generated companion ({@code Companion} inner class).
 *
 * The actual VJoyLayout class FQN is documented in
 * gamehub_reports/GAMEHUB_600_MASTER_MAP.md §9.8 as
 * {@code com.xiaoji.egggame.common.ui.vjoy.model.VJoyLayout} (kept by R8
 * keep-rule because it's @Serializable + named-resolved at runtime). Verify
 * the FQN against the 6.0.4 smali before relying on it.
 *
 * On any failure we log + return null and the caller is expected to fall
 * back to a sensible default (typically: cancel the operation with a
 * toast).
 */
public final class BhVjoyJson {

    private static final String TAG = "BhVjoyJson";

    /**
     * VJoyLayout FQN as documented in the GameHub 6.0.0 master map.
     *
     * REALITY CHECK (6.0.4 device test 2026-05-21): kotlinx-@Serializable
     * data class is renamed by R8 in this build to {@code Lsrn;}. The FQN
     * lookup will throw ClassNotFoundException at runtime. We keep the
     * string as a best-effort fallback (some 6.0.x builds may yet keep it)
     * but the primary path is {@link #sCachedLayoutCls} populated from the
     * first {@link #encodeLayout(Object)} call.
     *
     * If neither path resolves, decode silently returns null and the user
     * sees a "could not parse" toast — same UX as a corrupt input file.
     */
    private static final String VJOY_LAYOUT_FQN =
        "com.xiaoji.egggame.common.ui.vjoy.model.VJoyLayout";

    /**
     * Layout class captured on the first successful encode call. Lets the
     * decode path work even when the original FQN has been R8-mangled out
     * of existence — the user must export at least one layout before they
     * can import one, in a fresh app process.
     */
    private static volatile Class<?> sCachedLayoutCls = null;

    private BhVjoyJson() { }

    /** Returns the layout class if the encode-path has run at least once
     *  in this process (and so memoised the class), else null. */
    public static Class<?> cachedLayoutClass() { return sCachedLayoutCls; }

    /**
     * Encode a VJoyLayout instance to its JSON string representation.
     * Returns null on failure.
     */
    public static String encodeLayout(Object layout) {
        if (layout == null) return null;
        try {
            Class<?> layoutCls = layout.getClass();
            // Match by inheritance: the runtime instance may be a kotlinx-
            // generated subclass; walk up until we find one whose name ends
            // in "VJoyLayout".
            Class<?> serializerHolder = findSerializerHolder(layoutCls);
            if (serializerHolder == null) {
                Log.w(TAG, "no kotlinx Companion found on " + layoutCls.getName());
                return null;
            }
            // Cache for the decode path so import works even when the FQN
            // lookup fails (R8 mangling in 6.0.4 renames VJoyLayout to Lsrn;).
            sCachedLayoutCls = serializerHolder;
            Object companion = serializerHolder.getDeclaredField("Companion").get(null);
            Method serializerM = companion.getClass().getMethod("serializer");
            Object serializer = serializerM.invoke(companion);

            Object json = defaultJson();
            if (json == null) return null;

            // Json.encodeToString(SerializationStrategy, T) — accept the
            // SerializationStrategy supertype, value as Object.
            Method encodeM = findMethod(json.getClass(),
                "encodeToString",
                "kotlinx.serialization.SerializationStrategy",
                "java.lang.Object");
            if (encodeM == null) {
                Log.w(TAG, "Json.encodeToString(serializer, T) not found");
                return null;
            }
            Object result = encodeM.invoke(json, serializer, layout);
            return result instanceof String ? (String) result : null;
        } catch (Throwable t) {
            Log.w(TAG, "encodeLayout failed", t);
            return null;
        }
    }

    /**
     * Decode a JSON string into a VJoyLayout instance.
     * Returns null on failure.
     */
    public static Object decodeLayout(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            Class<?> layoutCls = sCachedLayoutCls;
            if (layoutCls == null) {
                try {
                    layoutCls = Class.forName(VJOY_LAYOUT_FQN);
                } catch (ClassNotFoundException cnf) {
                    Log.w(TAG, "VJoyLayout class not resolvable (R8-mangled?)");
                    return null;
                }
            }
            // VJoyLayout$Companion is unmangled (held intact by R8 keep-rules
            // for @Serializable). Get its serializer():
            Class<?> companionCls = Class.forName(layoutCls.getName() + "$Companion");
            Field companionField = layoutCls.getDeclaredField("Companion");
            Object companion = companionField.get(null);
            Method serializerM = companionCls.getDeclaredMethod("serializer");
            Object serializer = serializerM.invoke(companion);

            // Find the host's Json instance. kotlinx.serialization.json.Json
            // was renamed by R8 — try a list of candidate R8 letters until
            // we find an abstract class with a static `d` field pointing at
            // its Default singleton (the kotlinx convention).
            //
            // 2026-05-23 device build: Lzeb; (abstract Json) with field
            // d:Lyeb; (Json.Default instance). These letters will reshuffle
            // every R8 build — re-derive via:
            //   grep -rn 'VJoyLayout\$Companion;->serializer\(\)' classes*_smali/
            //   → find the smali file using it
            //   → look at iget-object on field that holds the Json instance
            //   → that class is the Json holder; its abstract Json type is
            //     whatever ->b(SerializationStrategy, Object) String lives on
            Object jsonInstance = resolveHostJson();
            if (jsonInstance == null) {
                Log.w(TAG, "could not resolve host Json instance");
                return null;
            }

            // Call json.decodeFromString-equivalent. The method is renamed
            // too; find by signature (returns Object, takes Serializer + String).
            Method decodeM = findDecodeMethod(jsonInstance.getClass());
            if (decodeM == null) {
                Log.w(TAG, "no decodeFromString-shaped method on " +
                    jsonInstance.getClass().getName());
                return null;
            }
            return decodeM.invoke(jsonInstance, serializer, json);
        } catch (Throwable t) {
            Log.w(TAG, "decodeLayout failed", t);
            return null;
        }
    }

    /**
     * Resolve the host's CONFIGURED Json instance for VJoy layouts.
     *
     * The bare {@code kotlinx.serialization.json.Json.Default} singleton
     * does NOT have the polymorphic SerializersModule needed to decode
     * VJoyLayout — InputMapping is a sealed class with a `{"class":"..."}`
     * discriminator, and the polymorphic registrations live in the host's
     * own pre-built Json instances on
     * {@code com.xiaoji.egggame.common.ui.vjoy.model.VJoyLayoutJson}
     * (R8-keep-listed; FQN is stable).
     *
     * VJoyLayoutJson has THREE Json fields:
     *   - {@code Default} — used for runtime parse/encode
     *   - {@code Export}  — pretty-printed for sharing
     *   - {@code Snapshot}— state diffs
     *
     * We want `Default` for import; it has the same SerializersModule as
     * the others but no extra formatting.
     */
    private static Object resolveHostJson() {
        try {
            Class<?> cls = Class.forName(
                "com.xiaoji.egggame.common.ui.vjoy.model.VJoyLayoutJson");
            Field f = cls.getDeclaredField("Default");
            f.setAccessible(true);
            Object v = f.get(null);
            if (v != null) {
                Log.i(TAG, "host Json: VJoyLayoutJson.Default -> " +
                    v.getClass().getName());
                return v;
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not resolve VJoyLayoutJson.Default", t);
        }
        return null;
    }

    /**
     * Find a method shaped like {@code decode(SerializationStrategy, String): Object}.
     * R8 renames kotlinx's DeserializationStrategy too, so we identify
     * the method by its parameter shape (one non-primitive non-String,
     * one String, returns Object).
     */
    private static Method findDecodeMethod(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 2) continue;
            if (params[1] != String.class) continue;
            if (params[0].isPrimitive() || params[0] == String.class) continue;
            if (m.getReturnType() != Object.class) continue;
            // Skip our own helpers if any
            if (m.getName().equals("equals")) continue;
            // Found a candidate.
            return m;
        }
        return null;
    }

    /**
     * Locate a kotlinx-generated Companion on {@code cls} or one of its
     * supertypes. The Companion typically lives on the @Serializable class
     * itself, but R8 sometimes inlines/moves it.
     */
    private static Class<?> findSerializerHolder(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("Companion");
                if (f != null) return c;
            } catch (NoSuchFieldException ignored) { }
            c = c.getSuperclass();
        }
        return null;
    }

    /** Locate the singleton {@code Json.Default} or build a default Json. */
    private static Object defaultJson() {
        // Path A: Json.Default inner class with INSTANCE static field.
        try {
            Class<?> jsonCls = Class.forName("kotlinx.serialization.json.Json");
            try {
                Class<?> defaultCls = Class.forName("kotlinx.serialization.json.Json$Default");
                Field inst = defaultCls.getDeclaredField("INSTANCE");
                Object def = inst.get(null);
                if (def != null) return def;
            } catch (Throwable ignored) { }

            // Path B: Json(JsonBuilder.() -> Unit) factory — try with a no-op lambda.
            // This is more brittle; skip unless A failed. (Kotlin top-level factory
            // lives as Json__JsonKt or similar; we don't bother — Json.Default is
            // sufficient for serialize/deserialize without custom config.)
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "kotlinx.serialization.json.Json not on classpath", t);
            return null;
        }
    }

    /** Find a method by name + parameter type names (handles boxed/generic erasure). */
    private static Method findMethod(Class<?> cls, String name, String... paramTypeNames) {
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != paramTypeNames.length) continue;
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                if (!paramTypeNames[i].equals(params[i].getName())) {
                    ok = false;
                    break;
                }
            }
            if (ok) return m;
        }
        return null;
    }
}
