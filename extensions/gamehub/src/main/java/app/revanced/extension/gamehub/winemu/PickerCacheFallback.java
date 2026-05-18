package app.revanced.extension.gamehub.winemu;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.gamehub.debug.DebugTrace;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

/**
 * Picker offline-cache fallback for the GPU driver / DXVK / VKD3D / FEXCore /
 * Box64 / container pickers.
 *
 * <h2>Why the previous (in-memory) implementation was inert on 6.0.4</h2>
 *
 * <p>The pickers read through {@code mci.a(RepoCategory, Continuation) ->
 * Serializable} (was {@code eci.a} in 6.0.2). That method is
 * <em>network-spine</em> by design: it reads the registry's in-memory
 * {@code ConcurrentHashMap} but only enters its cache-merge loop when the
 * network list is non-empty; an empty network list (offline) jumps straight
 * to the {@code :goto_2} block and returns Kotlin's {@code EmptyList}. The
 * patch swaps that EmptyList return for a call into this helper.
 *
 * <p>The old body reflected into that same in-memory map ({@code mci.a ->
 * myo.c}). Verified against a fresh 6.0.4 decompile (2026-05-18): that
 * reflective path still resolves correctly — but on a <strong>cold offline
 * launch the map is empty</strong>. 6.0.4 has no startup disk-hydrator (the
 * {@code u6o.<init>} the old Javadoc assumed does not exist); the map is
 * {@code new}'d empty and filled lazily via network-access paths. So the
 * hook fired, found an empty map, and returned an empty list — "applies
 * cleanly, does nothing", exactly as reported.
 *
 * <h2>What this implementation does instead</h2>
 *
 * <p>It reads the <strong>durable on-disk store directly</strong> at query
 * time. The host persists every downloaded/seen component into the
 * {@code "sp_winemu_unified_resources"} SharedPreferences via
 * {@code dj9.b(WinEmuRepo)}: key = {@code "<RepoCategory.name()>:<name>"},
 * value = the host's Gson {@code toJson(WinEmuRepo)}
 * ({@code new GsonBuilder().serializeNulls().disableHtmlEscaping().create()}
 * — both options are serialize-only, so a plain {@code Gson.fromJson}
 * round-trips faithfully).
 *
 * <p>Every anchor used here is intentionally <strong>non-obfuscated</strong>
 * so this survives base-APK R8 reshuffles without re-derivation:
 * <ul>
 *   <li>the literal SharedPreferences name {@code "sp_winemu_unified_resources"};</li>
 *   <li>the key shape {@code "<RepoCategory.name()>:..."} (enum constant
 *       names are stable);</li>
 *   <li>{@code com.google.gson.Gson} (kept library class);</li>
 *   <li>{@code com.xiaoji.egggame.common.winemu.bean.WinEmuRepo} (host bean,
 *       not obfuscated; Gson maps by its own non-obfuscated field names).</li>
 * </ul>
 * No reflection into obfuscated host state, no writes to the host registry.
 * Online behaviour is unchanged: the hook only fires where the host would
 * have returned EmptyList anyway.
 */
public final class PickerCacheFallback {
    private PickerCacheFallback() {}

    private static final String PREFS = "sp_winemu_unified_resources";
    private static final String GSON_CLASS = "com.google.gson.Gson";
    private static final String WINEMU_REPO_CLASS =
            "com.xiaoji.egggame.common.winemu.bean.WinEmuRepo";

    private static volatile Object gson;            // com.google.gson.Gson
    private static volatile Method gsonFromJson;    // Gson#fromJson(String, Class)
    private static volatile Class<?> winEmuRepoCls;

    /**
     * Returns a {@link Serializable} list of cached {@code WinEmuRepo}
     * entries for the requested category, read from the on-disk
     * {@code sp_winemu_unified_resources} prefs. Replaces the
     * {@code Lw85;->a:Lw85;} (Kotlin EmptyList) sentinel the original
     * {@code mci.a} returned at its {@code :goto_2} block.
     *
     * @param eci      the {@code mci} receiver (passed as p0 by the smali
     *                 call site; unused now — kept so the patched
     *                 {@code invoke-static} signature is unchanged).
     * @param category the {@code RepoCategory} enum the picker is asking for.
     * @return a non-empty {@code ArrayList<WinEmuRepo>} if cached entries
     *         exist for the category; an empty {@code ArrayList} otherwise.
     *         Both are {@link Serializable} (the method's return type) and
     *         never {@code null} — empty matches the original contract.
     */
    public static Serializable fromXxo(Object eci, Object category) {
        if (!(category instanceof Enum)) return new ArrayList<>();
        try {
            Context ctx = currentContext();
            if (ctx == null) {
                DebugTrace.write("PickerCacheFallback: no Context");
                return new ArrayList<>();
            }

            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Map<String, ?> all = prefs.getAll();
            if (all == null || all.isEmpty()) return new ArrayList<>();

            String prefix = ((Enum<?>) category).name() + ":";

            Object g = gson();
            Method fromJson = gsonFromJson;
            Class<?> repoCls = winEmuRepoCls;
            if (g == null || fromJson == null || repoCls == null) return new ArrayList<>();

            ArrayList<Object> out = new ArrayList<>();
            int bad = 0;
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                if (k == null || !k.startsWith(prefix)) continue;
                Object v = e.getValue();
                if (!(v instanceof String)) continue;
                try {
                    Object repo = fromJson.invoke(g, (String) v, repoCls);
                    if (repo != null) out.add(repo);
                } catch (Throwable perEntry) {
                    bad++; // one corrupt entry must not drop the rest
                }
            }
            DebugTrace.write("PickerCacheFallback category=" + ((Enum<?>) category).name()
                    + " hit=" + out.size() + " scanned=" + all.size() + " bad=" + bad);
            return out;
        } catch (Throwable t) {
            DebugTrace.write("PickerCacheFallback failed", t);
            return new ArrayList<>();
        }
    }

    /** Lazily build (and cache) a plain host-compatible Gson + fromJson handle. */
    private static Object gson() {
        Object g = gson;
        if (g != null) return g;
        synchronized (PickerCacheFallback.class) {
            if (gson != null) return gson;
            try {
                Class<?> gsonCls = Class.forName(GSON_CLASS);
                Object instance = gsonCls.getConstructor().newInstance();
                gsonFromJson = gsonCls.getMethod("fromJson", String.class, Class.class);
                winEmuRepoCls = Class.forName(WINEMU_REPO_CLASS);
                gson = instance;
                return gson;
            } catch (Throwable t) {
                DebugTrace.write("PickerCacheFallback: Gson/WinEmuRepo resolve failed", t);
                return null;
            }
        }
    }

    /**
     * Application context via {@code ActivityThread.currentApplication()} —
     * the same hidden-API path the other BannerHub extension controllers
     * use (see {@code BhVibrationController}). Application extends Context.
     */
    private static Context currentContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return (Context) app;
        } catch (Throwable t) {
            return null;
        }
    }
}
