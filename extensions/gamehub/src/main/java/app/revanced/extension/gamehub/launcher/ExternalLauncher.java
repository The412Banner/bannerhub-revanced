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
 * <p>Action name is variant-aware: {@code <activity.getPackageName()>.LAUNCH_GAME}.
 * That matches the existing per-variant Beacon convention shipped in BannerHub
 * 5.3.5 (Lite → {@code gamehub.lite.LAUNCH_GAME}, PuBG →
 * {@code com.tencent.ig.LAUNCH_GAME}, etc.) so existing frontend configs keep
 * working without per-user edits.
 *
 * <p>External frontends (ES-DE, Daijishou, Beacon, …) keep firing the 5.3.5
 * intent shape unchanged; we rewrite the extras in place before 6.0.4's
 * existing dispatch reads them, so the rest of {@code onCreate} behaves as if
 * Xiaoji's own deep-link surface had been hit.
 */
public final class ExternalLauncher {
    private static final String TAG = "BhExternalLauncher";
    private static final String ACTION_SUFFIX = ".LAUNCH_GAME";

    private ExternalLauncher() {}

    public static void rewriteIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null || !action.endsWith(ACTION_SUFFIX)) return;

        // The action prefix is the variant package
        // ("gamehub.lite", "com.tencent.ig", …). We don't need the Activity
        // to derive that — endsWith matches every per-variant action AND
        // the literal "gamehub.lite.LAUNCH_GAME" forgiveness fallback for
        // stale 5.3.5-Lite-style Beacon configs against a renamed variant.

        // External frontends typically launch via `am launch --es <key> <value>`
        // (Beacon, ES-DE), which puts STRING extras. Read both kinds and parse —
        // String args win when present because they're the documented Beacon
        // contract; fall through to Int extras for any future caller using --ei.
        int localGameId = readIdExtra(intent, "localGameId");
        int steamAppId  = readIdExtra(intent, "steamAppId");
        boolean autoStart = readBoolExtra(intent, "autoStartGame", false);

        // localGameId wins; fall back to steamAppId. Frontends typically send
        // one or the other.
        int gameId = localGameId > 0 ? localGameId : steamAppId;
        if (gameId <= 0) {
            Log.w(TAG, "LAUNCH_GAME intent with no usable id "
                + "(localGameId=" + localGameId + ", steamAppId=" + steamAppId + ")");
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
        // Fall back to Int extras for callers using --ei.
        return intent.getIntExtra(key, -1);
    }

    private static boolean readBoolExtra(Intent intent, String key, boolean def) {
        // --ez gives a real boolean; --es gives a String like "true"/"false".
        // The default-value form of getBooleanExtra returns def if the extra is
        // missing OR the wrong type, so check the String form first.
        String s = intent.getStringExtra(key);
        if (s != null) return Boolean.parseBoolean(s.trim());
        return intent.getBooleanExtra(key, def);
    }
}
