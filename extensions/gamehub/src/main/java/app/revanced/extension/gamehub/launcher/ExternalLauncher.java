package app.revanced.extension.gamehub.launcher;

import android.content.Intent;
import android.util.Log;

/**
 * Bridges PlayDay's GameHub 5.3.5 external-launcher contract
 * ({@code <variant_pkg>.LAUNCH_GAME} intent + {@code steamAppId} /
 * {@code localGameId} / {@code autoStartGame} / {@code type} extras) onto
 * GameHub 6.0.4's native {@code DeepLinkActivity} deep-link dispatcher,
 * which already understands {@code app_nav_target=game_detail} +
 * {@code app_nav_game_id} + {@code app_nav_auto_start_game} (and more).
 *
 * <p>Three game-source paths covered by the same Beacon command shape:
 * <ul>
 *   <li><b>PC-imported games</b> ({@code source_type=0}, {@code id="local_…"}):
 *       Beacon passes {@code --es localGameId <numeric server_game_id>}.</li>
 *   <li><b>Steam-library games</b> ({@code source_type=1}, {@code id == steam_app_id}):
 *       Beacon passes {@code --es localGameId <numeric server_game_id>} OR
 *       {@code --es steamAppId <appid>} (server_game_id equals the Steam appid
 *       for these rows).</li>
 *   <li><b>Epic-library games</b> ({@code source_type=2}, {@code id} is a 32-char
 *       hex UUID matching {@code epic_app_name}, {@code server_game_id=0}):
 *       Beacon passes {@code --es localGameId <uuid>} — we auto-detect the
 *       non-numeric value and route to the Epic dispatch branch. The explicit
 *       {@code --es epicAppName <uuid>} form is also accepted for callers that
 *       prefer separating concerns.</li>
 * </ul>
 *
 * <p>Action name is variant-aware: any action ending in {@code .LAUNCH_GAME}
 * matches, so per-variant builds ({@code gamehub.lite.LAUNCH_GAME},
 * {@code com.tencent.ig.LAUNCH_GAME}, …) all dispatch through the same
 * extension without per-variant codegen.
 */
public final class ExternalLauncher {
    private static final String TAG = "BhExternalLauncher";
    private static final String ACTION_SUFFIX = ".LAUNCH_GAME";

    /** GameHub's source_type enum value for Epic-library entries. */
    private static final int SOURCE_TYPE_EPIC = 2;

    private ExternalLauncher() {}

    public static void rewriteIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null || !action.endsWith(ACTION_SUFFIX)) return;

        // Read the raw String forms first — Beacon / ES-DE / Daijishou all use
        // --es. Only fall back to --ei via the getIntExtra default for any
        // future caller using the typed form.
        String localRaw = intent.getStringExtra("localGameId");
        String epicAppName = intent.getStringExtra("epicAppName");
        boolean autoStart = readBoolExtra(intent, "autoStartGame", false);

        // Try to parse localGameId as Int (PC-import / Steam-library path).
        // If it fails AND the String looks like an Epic UUID, fall through to
        // the Epic path. Numeric values can't be Epic ids and vice versa, so
        // there's no ambiguity.
        int localGameId = -1;
        if (localRaw != null) {
            try {
                localGameId = Integer.parseInt(localRaw.trim());
            } catch (NumberFormatException ignored) {
                if (epicAppName == null && looksLikeEpicUuid(localRaw.trim())) {
                    epicAppName = localRaw.trim();
                }
            }
        } else {
            localGameId = intent.getIntExtra("localGameId", -1);
        }

        // ---- Epic branch ------------------------------------------------
        if (epicAppName != null && !epicAppName.isEmpty()) {
            // Native 6.0.4 dispatch consumes these extras together when
            // navigating to game_detail for an Epic-library entry. game_id
            // must be a parseable Int — 0 is the documented "no catalog id"
            // sentinel that hands lookup off to the epic_app_name resolver.
            intent.putExtra("target_type", "game_detail");
            intent.putExtra("app_nav_target", "game_detail");
            intent.putExtra("app_nav_game_id", "0");
            intent.putExtra("app_nav_epic_app_name", epicAppName);
            intent.putExtra("app_nav_source_type", SOURCE_TYPE_EPIC);
            intent.putExtra("app_nav_auto_start_game", autoStart);

            Log.i(TAG, "rewrote " + action + " → epic game_detail epicAppName="
                + epicAppName + " autoStart=" + autoStart);
            return;
        }

        // ---- PC-import / Steam-library branch --------------------------
        int steamAppId = readIdExtra(intent, "steamAppId");

        // localGameId wins; fall back to steamAppId. Frontends typically send
        // one or the other.
        int gameId = localGameId > 0 ? localGameId : steamAppId;
        if (gameId <= 0) {
            Log.w(TAG, "LAUNCH_GAME intent with no usable id "
                + "(localGameId=" + localGameId + ", steamAppId=" + steamAppId
                + ", epicAppName=" + epicAppName + ")");
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
        // getBooleanExtra(key, def) returns def if the extra is missing OR
        // the wrong type, so try the String form first.
        String s = intent.getStringExtra(key);
        if (s != null) return Boolean.parseBoolean(s.trim());
        return intent.getBooleanExtra(key, def);
    }

    /**
     * Heuristic: Epic app names observed in 6.0.4's
     * {@code t_game_library_base.epic_app_name} column are 32-character
     * lowercase hex strings (UUIDs without dashes). Numeric ids and Steam
     * appids would already have parsed as Int upstream, so any non-numeric
     * String that fits the hex shape is treated as an Epic candidate.
     */
    private static boolean looksLikeEpicUuid(String s) {
        if (s.length() < 8) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}
