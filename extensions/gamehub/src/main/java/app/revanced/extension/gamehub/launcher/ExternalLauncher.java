package app.revanced.extension.gamehub.launcher;

import android.app.Activity;
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

    public static void rewriteIntent(Activity activity, Intent intent) {
        if (activity == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        // Match either "<our_package>.LAUNCH_GAME" (per-variant native) or
        // playday's literal "gamehub.lite.LAUNCH_GAME" — so users running old
        // 5.3.5-style Beacon configs against a renamed BannerHub variant still
        // dispatch correctly. The native filter still publishes only the
        // per-variant action; the literal is purely a runtime fallback.
        String expected = activity.getPackageName() + ACTION_SUFFIX;
        if (!action.equals(expected) && !action.equals("gamehub.lite" + ACTION_SUFFIX)) {
            return;
        }

        int localGameId = intent.getIntExtra("localGameId", -1);
        int steamAppId  = intent.getIntExtra("steamAppId", -1);
        boolean autoStart = intent.getBooleanExtra("autoStartGame", false);

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
}
