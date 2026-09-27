package app.revanced.extension.gamehub.gog;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Single reader of GOG's native install record for the storefront surfaces (the Library tab,
 * the store rails, the detail page primary button). Wraps {@link GogInstallPath#checkState}
 * (the unified INSTALLED / PARTIAL / NONE model every BH surface already reads) and adds the
 * one thing the tiles need on top: the "Update available" marker.
 *
 * Update marker: {@code gog_update_avail_<id>} (boolean). Set by {@link #checkForUpdate} when
 * GOG's builds feed reports a build id different from the recorded {@code gog_build_<id>};
 * cleared whenever an install completes (the engine rewrites {@code gog_build_}) or the game
 * is uninstalled. Purely a cache of the last network answer — never a promise.
 */
public final class GogInstallState {

    private static final String PREFS = "bh_gog_prefs";
    private static final String UPDATE_PREFIX = "gog_update_avail_";

    private GogInstallState() {}

    public enum Badge { NONE, INSTALLED, PARTIAL, UPDATE }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    /** Disk-truth "installed" — the same record the detail page reads. */
    public static boolean isInstalled(Context ctx, String gameId) {
        return GogInstallPath.checkState(prefs(ctx), gameId) == GogInstallPath.State.INSTALLED;
    }

    /** Games with a recorded launch exe AND install dir — what the tiles call "installed". */
    public static Set<String> installedIds(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("gog_exe_")) continue;
            String id = key.substring("gog_exe_".length());
            if (p.getString("gog_dir_" + id, null) != null) out.add(id);
        }
        return out;
    }

    /** The one-word badge a library tile shows for this game. */
    public static Badge badge(Context ctx, String gameId) {
        SharedPreferences p = prefs(ctx);
        GogInstallPath.State s = GogInstallPath.checkState(p, gameId);
        if (s == GogInstallPath.State.INSTALLED) {
            return p.getBoolean(UPDATE_PREFIX + gameId, false) ? Badge.UPDATE : Badge.INSTALLED;
        }
        if (s == GogInstallPath.State.PARTIAL) return Badge.PARTIAL;
        return Badge.NONE;
    }

    public static boolean isUpdateAvailable(Context ctx, String gameId) {
        return prefs(ctx).getBoolean(UPDATE_PREFIX + gameId, false);
    }

    public static void setUpdateAvailable(Context ctx, String gameId, boolean available) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (available) ed.putBoolean(UPDATE_PREFIX + gameId, true);
        else ed.remove(UPDATE_PREFIX + gameId);
        ed.apply();
    }

    /**
     * Remove every per-game install record for {@code gameId} plus the update marker. Leaves the
     * user's cloud-save folder ({@code gog_save_dir_}) and owned-DLC list ({@code gog_dlcs_}) alone:
     * those are user data / entitlement, not install state.
     */
    public static void purge(Context ctx, String gameId) {
        SharedPreferences p = prefs(ctx);
        GogInstallPath.clearAll(p, gameId);
        p.edit()
                .remove("gog_size_" + gameId)
                .remove("gog_build_" + gameId)
                .remove(UPDATE_PREFIX + gameId)
                .apply();
    }

    /**
     * Ask GOG's builds feed for the newest Windows build id. Returns it (may be null when the
     * server did not answer) and records the update marker against {@code gog_build_<id>}.
     * Blocking; worker thread only.
     */
    public static String checkForUpdate(Context ctx, String gameId, String token) {
        String body = BhStoreNet.get(
                "https://content-system.gog.com/products/" + gameId + "/os/windows/builds?generation=2",
                token, BhStoreNet.GALAXY_UA, 15000, null);
        if (body == null) return null;
        String latest = null;
        try {
            org.json.JSONArray items = new org.json.JSONObject(body).optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject item = items.optJSONObject(i);
                if (item != null && "windows".equals(item.optString("os"))) {
                    latest = item.optString("build_id");
                    break;
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (latest == null || latest.isEmpty()) return null;
        SharedPreferences p = prefs(ctx);
        String stored = p.getString("gog_build_" + gameId, null);
        if (stored == null) {
            // First check — store as baseline.
            p.edit().putString("gog_build_" + gameId, latest).apply();
            setUpdateAvailable(ctx, gameId, false);
        } else {
            setUpdateAvailable(ctx, gameId, !stored.equals(latest));
        }
        return latest;
    }

    /** True when the install dir still exists on disk (self-heal check for the Library tab). */
    public static boolean installDirExists(Context ctx, String gameId) {
        String dir = prefs(ctx).getString("gog_dir_" + gameId, null);
        return dir != null && new File(dir).exists();
    }
}
