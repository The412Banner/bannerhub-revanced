package app.revanced.extension.gamehub.launcher;

import android.content.Intent;
import android.util.Log;

/**
 * Bridges PlayDay's GameHub 5.3.5 external-launcher contract
 * ({@code <variant_pkg>.LAUNCH_GAME} intent + {@code steamAppId} /
 * {@code localGameId} / {@code autoStartGame} / {@code type} extras) onto
 * GameHub 6.0.4's native {@code DeepLinkActivity} deep-link dispatcher,
 * which already understands {@code app_nav_target=game_detail} +
 * {@code app_nav_game_id} + {@code app_nav_auto_start_game}.
 *
 * <p><b>Supported game sources</b> (device-verified on 1.4.0-604-extlaunch-pre5):
 * <ul>
 *   <li><b>PC-imported games</b> ({@code source_type=0}): Beacon passes
 *       {@code --es localGameId <numeric server_game_id>}.</li>
 *   <li><b>Steam-library games</b> ({@code source_type=1}): Beacon passes
 *       {@code --es localGameId <numeric server_game_id>}; the value happens to
 *       equal the Steam appid for these rows.</li>
 * </ul>
 *
 * <p><b>Not supported</b>: Epic-library ({@code source_type=2}) and GOG-imported
 * games. Their unique handle is the TEXT {@code id} column (32-char hex UUID or
 * {@code gog_*} prefix) and {@code server_game_id=0}. The 6.0.4 dispatch parses
 * {@code app_nav_game_id} as Integer, so a UUID can't ride this surface, and
 * {@code server_game_id=0} matches no catalog entry. Tested with DOOMBLADE
 * (Epic): the {@code app_nav_epic_app_name} / {@code app_nav_source_type} extras
 * we set were silently ignored by {@code GameDetailViewModel}, whose lookup
 * chain is keyed by {@code app_nav_game_id} alone. The in-app library-tile path
 * uses a completely separate Compose route through {@code MainActivity}, not
 * {@code DeepLinkActivity} — supporting Epic via Beacon would require hooking
 * that separate surface (queued as a future patch).
 *
 * <p>Action name is variant-aware: any action ending in {@code .LAUNCH_GAME}
 * matches, so per-variant builds ({@code gamehub.lite.LAUNCH_GAME},
 * {@code com.tencent.ig.LAUNCH_GAME}, …) all dispatch through the same
 * extension without per-variant codegen.
 */
public final class ExternalLauncher {
    private static final String TAG = "BhExternalLauncher";
    private static final String ACTION_SUFFIX = ".LAUNCH_GAME";

    private ExternalLauncher() {}

    public static void rewriteIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null || !action.endsWith(ACTION_SUFFIX)) return;

        // External frontends typically launch via `am launch --es <key> <value>`
        // (Beacon, ES-DE, Daijishou), which puts STRING extras. Read both kinds
        // and parse — String args win when present (documented contract); fall
        // back to Int extras for any future caller using --ei.
        int localGameId = readIdExtra(intent, "localGameId");
        int steamAppId  = readIdExtra(intent, "steamAppId");
        boolean autoStart = readBoolExtra(intent, "autoStartGame", false);

        // localGameId wins; fall back to steamAppId. Frontends typically send
        // one or the other.
        int gameId = localGameId > 0 ? localGameId : steamAppId;
        if (gameId <= 0) {
            Log.w(TAG, "LAUNCH_GAME intent with no usable id "
                + "(localGameId=" + localGameId + ", steamAppId=" + steamAppId + "). "
                + "Epic and GOG games are not supported via this surface — launch from "
                + "GameHub's library tile directly.");
            return;
        }

        intent.putExtra("target_type", "game_detail");
        intent.putExtra("app_nav_target", "game_detail");
        intent.putExtra("app_nav_game_id", String.valueOf(gameId));
        intent.putExtra("app_nav_auto_start_game", autoStart);
        if (steamAppId > 0) intent.putExtra("app_nav_steam_app_id", steamAppId);

        Log.i(TAG, "rewrote " + action + " → game_detail id=" + gameId
            + " autoStart=" + autoStart);
    }

    private static int readIdExtra(Intent intent, String key) {
        // Prefer the String form — Beacon / ES-DE / Daijishou all use --es.
        String s = intent.getStringExtra(key);
        if (s != null) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                Log.w(TAG, "ignoring non-numeric " + key + "=" + s);
                return -1;
            }
        }
        return intent.getIntExtra(key, -1);
    }

    private static boolean readBoolExtra(Intent intent, String key, boolean def) {
        // --ez gives a real boolean; --es gives a String like "true"/"false".
        // getBooleanExtra(key, def) returns def if the extra is missing OR the
        // wrong type, so try the String form first.
        String s = intent.getStringExtra(key);
        if (s != null) return Boolean.parseBoolean(s.trim());
        return intent.getBooleanExtra(key, def);
    }
}
