package app.revanced.extension.gamehub.components;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.gamehub.debug.DebugTrace;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Direct write into GameHub 6.0's component registry SharedPreferences
 * ({@code sp_winemu_unified_resources.xml}) at the same key format the host
 * uses ({@code "<RepoCategory>:<componentName>"}).
 *
 * <p>This is the v0.3.7 pivot: after four read-side bytecode hook attempts
 * produced no observable effect on the per-game settings dropdown, the
 * empirical conclusion is that direct registry write is the only reliable
 * path. Mirrors BannerHub 5.3.5's original approach (write into
 * {@code banners_sources} SP) — the host's API is additive per-key, not
 * bulk-replace, so a custom {@code COMPONENT:<userName>} key with a
 * server-unfilled name survives refreshes indefinitely.</p>
 *
 * <p>Sidecar pref ({@link SidecarRegistry}) remains as our own bookkeeping
 * for what we injected (uninstall, badging UX), but is no longer relied on
 * for dropdown visibility.</p>
 */
public final class HostRegistry {
    private static final String PREF_NAME = "sp_winemu_unified_resources";
    private static final String CATEGORY_PREFIX = "COMPONENT:";

    private HostRegistry() {}

    /**
     * Write a component entry to the host registry. The entry's JSON shape
     * matches the host's exactly so the host's deserializer / cache hydrator
     * cannot tell our entry apart from a server-supplied one.
     *
     * @param ctx    application context
     * @param name   component name (also forms the registry key suffix)
     * @param entry  full JSON entry — top-level
     *               {@code {category, depInfo, entry{...}, isBase, isDep, name, state, version}}
     */
    public static void put(Context ctx, String name, JSONObject entry) {
        if (ctx == null || name == null || name.isEmpty() || entry == null) return;
        try {
            // Host's EnvLayerEntity deserializer is kotlinx.serialization with
            // ignoreUnknownKeys=false (default). Any field outside its declared
            // schema throws SerializationException and aborts the whole COMPONENT
            // cache hydration — so we strip our _bh_* sidecar markers before
            // writing into the host registry. Sidecar keeps the markers; the
            // host XML gets a shape identical to a server-supplied entry.
            JSONObject hostShape = stripBhMarkers(entry);
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(CATEGORY_PREFIX + name, hostShape.toString()).apply();
        } catch (Throwable t) {
            DebugTrace.write("HostRegistry.put failed for " + name, t);
        }
    }

    /** Deep clone {@code src} and reshape into the host's exact JSON form:
     * (1) remove every {@code _bh_*} marker key, (2) drop the duplicate inner
     * {@code entry.category} field (server entries only carry {@code category}
     * at the top level), (3) force {@code state} to {@code "None"} (server
     * registry always uses {@code "None"}; the host tracks extraction
     * dynamically via {@code dxh.queryReadyState} filesystem check, so the
     * pref-level state must mimic server's), (4) coerce {@code id} to a
     * stable positive int so any {@code id > 0} filter passes, (5) bump
     * {@code versionCode} to {@code 2} matching server format.
     *
     * <p>Diff vs server's {@code Fex_20260428} entry confirmed via direct XML
     * inspection — these are every functional difference. Cosmetic deltas
     * ({@code blurb}, {@code displayName}, empty {@code downloadUrl}/
     * {@code fileMd5}/{@code logo}, {@code fileSize:0}) are left alone since
     * the picker isn't filtering on those.</p> */
    private static JSONObject stripBhMarkers(JSONObject src) throws Exception {
        JSONObject clone = new JSONObject(src.toString());
        removeBhKeys(clone);
        clone.put("state", "None");
        JSONObject inner = clone.optJSONObject("entry");
        if (inner != null) {
            removeBhKeys(inner);
            inner.remove("category");
            inner.put("state", "None");
            inner.put("versionCode", 2);
            if (inner.optInt("id", -1) <= 0) {
                inner.put("id", syntheticId(inner.optString("name", "")));
            }
        }
        return clone;
    }

    /** Stable positive int derived from the component name. Range is well
     * above the server's observed id range (server uses 1–500ish), so we
     * never collide. Same name always gets the same id across rehydrates. */
    private static int syntheticId(String name) {
        int h = name == null ? 0 : name.hashCode();
        if (h == Integer.MIN_VALUE) h = 0;
        return 90000 + (Math.abs(h) % 90000);
    }

    private static void removeBhKeys(JSONObject obj) {
        List<String> kill = new ArrayList<>();
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith("_bh_")) kill.add(k);
        }
        for (String k : kill) obj.remove(k);
    }

    /** Remove a component entry from the host registry by name. */
    public static void remove(Context ctx, String name) {
        if (ctx == null || name == null || name.isEmpty()) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().remove(CATEGORY_PREFIX + name).apply();
        } catch (Throwable t) {
            DebugTrace.write("HostRegistry.remove failed for " + name, t);
        }
    }

    /**
     * Re-write every sidecar entry into the host registry. Called on
     * Component Manager activity open as self-heal insurance — cheap,
     * idempotent, defends against any hypothetical future API refresh
     * that prunes unknown keys.
     */
    public static void rehydrateFromSidecar(Context ctx) {
        if (ctx == null) return;
        try {
            Map<String, JSONObject> sidecarEntries = SidecarRegistry.getAllEntries(ctx);
            if (sidecarEntries.isEmpty()) return;
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            int n = 0;
            for (Map.Entry<String, JSONObject> e : sidecarEntries.entrySet()) {
                JSONObject hostShape = stripBhMarkers(e.getValue());
                editor.putString(CATEGORY_PREFIX + e.getKey(), hostShape.toString());
                n++;
            }
            editor.apply();
            DebugTrace.write("HostRegistry.rehydrateFromSidecar: re-wrote " + n + " entries");
        } catch (Throwable t) {
            DebugTrace.write("HostRegistry.rehydrateFromSidecar failed", t);
        }
    }
}
