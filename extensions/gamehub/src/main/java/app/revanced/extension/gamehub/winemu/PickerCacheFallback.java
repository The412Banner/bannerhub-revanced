package app.revanced.extension.gamehub.winemu;

import app.revanced.extension.gamehub.debug.DebugTrace;

import java.lang.reflect.Field;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Map;

/**
 * Picker offline-cache fallback for the GPU driver / DXVK / VKD3D / FEXCore /
 * Box64 / container pickers.
 *
 * <p>The pickers read through {@code eci.a(category, cont)}, which delegates
 * to a network-aware {@code Uaa} flow (Lv43; / Lag3; / Lxca; per
 * RepoCategory). Online the Uaa emits the live catalog; offline it emits an
 * empty list, and {@code eci.a} short-circuits to Kotlin's EmptyList — so
 * the picker shows only the embedded built-in versions.
 *
 * <p>Meanwhile, {@code u6o.<init>} has already hydrated the host registry's
 * in-memory ConcurrentHashMap from the on-disk
 * {@code sp_winemu_unified_resources} XML at app start (unconditional,
 * happens before any network call). Every WinEmuRepo the user has ever
 * seen — including downloaded components — is sitting in memory.
 *
 * <p>This helper builds a list out of that map filtered by the requested
 * category. It's hooked at the tail of {@code eci.a} where the original
 * method would have returned EmptyList, so online behavior is unchanged
 * (the hook only fires when the original would have returned empty).
 *
 * <p>Field name conventions (host code, NOT R8-mangled symbols we need to
 * track):
 * <ul>
 *   <li>{@code eci.a} — field on the eci instance whose runtime type is xxo.
 *       Selected by being the only field on the receiver whose declared
 *       type is the host's registry container class.</li>
 *   <li>{@code xxo.c} — Object field on xxo holding the category-keyed
 *       ConcurrentHashMap. Selected by being the only field on xxo whose
 *       runtime value is a Map.</li>
 * </ul>
 * Both are looked up by reflection on the receiver's class, with the
 * resolved {@link Field} objects cached. The lookup uses field-by-name
 * ("a", "c") plus a runtime type sanity check so the patch survives R8
 * letter shuffles between minor base APK bumps as long as those single-
 * letter field names stay the same shape.
 *
 * <p>Cache keys are formatted {@code "CATEGORY_NAME:entry_name"} by the
 * host's key builder (e.g. {@code xxo.y(RepoCategory, String) → String}),
 * so we filter by enum {@link Enum#name()} prefix without ever calling a
 * method on the WinEmuRepo values themselves.
 */
public final class PickerCacheFallback {
    private PickerCacheFallback() {}

    private static final Class<?> CLS = PickerCacheFallback.class;

    private static volatile Field eciToRegistryField;
    private static volatile Field registryToMapField;

    /**
     * Returns a {@link Serializable} list of cached WinEmuRepo entries for
     * the requested category. Replaces the {@code Lz85;->a:Lz85;}
     * (EmptyList) sentinel that the original {@code eci.a} returned at
     * its {@code :goto_2} block.
     *
     * @param eci      the eci receiver (passed in as p0 from the smali
     *                 call site; declared as Object so the extension
     *                 doesn't depend on the R8-mangled class name).
     * @param category the RepoCategory enum the picker is asking for.
     * @return a non-empty ArrayList&lt;WinEmuRepo&gt; if cached entries
     *         exist; an empty ArrayList otherwise. Both are
     *         Serializable, which is what the method's return type
     *         demands. Never returns {@code null} — falling through to
     *         empty matches the original method's contract.
     */
    public static Serializable fromXxo(Object eci, Object category) {
        if (eci == null || category == null) return new ArrayList<>();
        try {
            Object registry = readField(eciToRegistryField, eci, "a", "eciToRegistryField");
            if (registry == null) return new ArrayList<>();

            Object mapObj = readField(registryToMapField, registry, "c", "registryToMapField");
            if (!(mapObj instanceof Map)) return new ArrayList<>();
            Map<?, ?> map = (Map<?, ?>) mapObj;
            if (map.isEmpty()) return new ArrayList<>();

            // Keys are "<RepoCategory.name()>:<entry_name>" — see
            // host xxo.y(category, name) String-builder.
            String prefix = ((Enum<?>) category).name() + ":";

            ArrayList<Object> out = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object k = e.getKey();
                if (k instanceof String && ((String) k).startsWith(prefix)) {
                    Object v = e.getValue();
                    if (v != null) out.add(v);
                }
            }
            if (!out.isEmpty()) {
                DebugTrace.write("PickerCacheFallback hit category=" + prefix.substring(0, prefix.length() - 1) + " size=" + out.size());
            }
            return out;
        } catch (Throwable t) {
            DebugTrace.write("PickerCacheFallback failed", t);
            return new ArrayList<>();
        }
    }

    private static Object readField(Field cached, Object instance, String name, String slot) throws Exception {
        Field f = cached;
        if (f == null) {
            synchronized (CLS) {
                // Re-read after acquiring lock.
                f = slot.equals("eciToRegistryField") ? eciToRegistryField : registryToMapField;
                if (f == null) {
                    f = instance.getClass().getDeclaredField(name);
                    f.setAccessible(true);
                    if (slot.equals("eciToRegistryField")) eciToRegistryField = f;
                    else registryToMapField = f;
                }
            }
        }
        return f.get(instance);
    }
}
