package app.revanced.extension.gamehub.components;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.gamehub.debug.DebugTrace;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BannerHub Component Manager sidecar registry.
 *
 * Stores user-injected components in a SharedPreferences XML file separate from the
 * official {@code sp_winemu_unified_resources.xml}, so our entries survive API resyncs
 * and md5 mismatches that would otherwise wipe or invalidate them. Entries follow
 * the same schema as the official registry — key {@code COMPONENT:<name>}, value is
 * a JSON string deserialized into {@code Lcom/xiaoji/egggame/common/winemu/bean/WinEmuRepo;}
 * by {@link ComponentInjector} via reflection — plus three namespaced extension
 * fields under {@code entry}:
 * <ul>
 *   <li>{@code _bh_injected: true} — discriminator so the merge layer knows this came from us</li>
 *   <li>{@code _bh_skip_md5: true} — opts out of integrity verification (no server-issued hash)</li>
 *   <li>{@code _bh_source_uri} — original local file path / content URI for re-import after wipe</li>
 * </ul>
 *
 * <p>This class is pure data access — no UI, no app integration. It can be unit-tested
 * by a Robolectric harness that supplies a {@code Context} with mock prefs.</p>
 */
public final class SidecarRegistry {
    private static final String TAG = "GH600-DEBUG";
    private static final String PREFS_NAME = "sp_bh_components";
    private static final String KEY_PREFIX = "COMPONENT:";

    /** ISO-8601-shaped timestamp written into entries on first put for audit. */
    public static final String FIELD_BH_INJECTED = "_bh_injected";
    public static final String FIELD_BH_SKIP_MD5 = "_bh_skip_md5";
    public static final String FIELD_BH_SOURCE_URI = "_bh_source_uri";
    public static final String FIELD_BH_ADDED_AT = "_bh_added_at";

    private SidecarRegistry() {
        // utility class
    }

    /** Returns the SharedPreferences instance backing the sidecar registry. */
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Returns every component entry the sidecar holds whose inner {@code entry.type}
     * matches the requested type int. Type ints map per the report:
     *   2 = GPU driver, 3 = DXVK, 4 = VKD3D, 5 = settings pack, 6 = runtime dep.
     *
     * @param ctx  Application context (sidecar prefs are app-private; any Context works).
     * @param type EnvLayerEntity type filter; pass -1 to skip the filter.
     * @return list of JSON entries (defensive copies). Never null.
     */
    public static List<JSONObject> getAllByType(Context ctx, int type) {
        Map<String, ?> all = prefs(ctx).getAll();
        if (all.isEmpty()) return Collections.emptyList();

        List<JSONObject> out = new ArrayList<>(all.size());
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (!e.getKey().startsWith(KEY_PREFIX)) continue;
            Object v = e.getValue();
            if (!(v instanceof String)) continue;
            JSONObject entry = parseSilently((String) v);
            if (entry == null) continue;
            if (type < 0 || matchesType(entry, type)) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * Look up a single entry by its component name (the part after {@code COMPONENT:}).
     * Returns null if absent or unparseable.
     */
    public static JSONObject get(Context ctx, String name) {
        if (name == null || name.isEmpty()) return null;
        String raw = prefs(ctx).getString(KEY_PREFIX + name, null);
        return raw == null ? null : parseSilently(raw);
    }

    /**
     * Write or overwrite a sidecar entry. Stamps {@code _bh_injected=true} and
     * {@code _bh_added_at=<wallclockMillis>} on the inner {@code entry} object if
     * not already present.
     */
    public static void put(Context ctx, String name, JSONObject entry) {
        if (name == null || name.isEmpty() || entry == null) return;
        try {
            JSONObject inner = entry.optJSONObject("entry");
            if (inner == null) {
                inner = new JSONObject();
                entry.put("entry", inner);
            }
            if (!inner.has(FIELD_BH_INJECTED)) {
                inner.put(FIELD_BH_INJECTED, true);
            }
            if (!inner.has(FIELD_BH_ADDED_AT)) {
                inner.put(FIELD_BH_ADDED_AT, System.currentTimeMillis());
            }
            prefs(ctx).edit().putString(KEY_PREFIX + name, entry.toString()).apply();
        } catch (JSONException jx) {
            DebugTrace.write("SidecarRegistry.put failed for " + name, jx);
        }
    }

    /** Removes the entry for the given name. No-op if absent. */
    public static void remove(Context ctx, String name) {
        if (name == null || name.isEmpty()) return;
        prefs(ctx).edit().remove(KEY_PREFIX + name).apply();
    }

    /**
     * Returns the set of component names (without the {@code COMPONENT:} prefix)
     * currently in the sidecar. Useful for de-duplication when rendering merged lists.
     */
    public static Set<String> allNames(Context ctx) {
        Map<String, ?> all = prefs(ctx).getAll();
        if (all.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>(all.size());
        for (String k : all.keySet()) {
            if (k.startsWith(KEY_PREFIX)) {
                out.add(k.substring(KEY_PREFIX.length()));
            }
        }
        return out;
    }

    /**
     * Reads the inner {@code entry.type} field defensively. Returns -1 if
     * the field is missing or non-numeric (such entries are still findable by
     * {@code getAllByType(ctx, -1)} but never match a positive type filter).
     */
    private static boolean matchesType(JSONObject entry, int type) {
        JSONObject inner = entry.optJSONObject("entry");
        if (inner == null) return false;
        return inner.optInt("type", -1) == type;
    }

    private static JSONObject parseSilently(String raw) {
        try {
            return new JSONObject(raw);
        } catch (JSONException jx) {
            DebugTrace.write("SidecarRegistry parse failure: " + raw, jx);
            return null;
        }
    }
}
