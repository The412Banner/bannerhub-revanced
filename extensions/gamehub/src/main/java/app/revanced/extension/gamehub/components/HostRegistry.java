package app.revanced.extension.gamehub.components;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.gamehub.debug.DebugTrace;

import org.json.JSONObject;

import java.util.HashMap;
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
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(CATEGORY_PREFIX + name, entry.toString()).apply();
        } catch (Throwable t) {
            DebugTrace.write("HostRegistry.put failed for " + name, t);
        }
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
                editor.putString(CATEGORY_PREFIX + e.getKey(), e.getValue().toString());
                n++;
            }
            editor.apply();
            DebugTrace.write("HostRegistry.rehydrateFromSidecar: re-wrote " + n + " entries");
        } catch (Throwable t) {
            DebugTrace.write("HostRegistry.rehydrateFromSidecar failed", t);
        }
    }
}
