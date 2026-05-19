package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

/**
 * PHASE-1 STUB — the GameHub-library/launch bridge (WS5) is deferred to Phase 2.
 *
 * Phase 1 scope (GOG_LIBRARY_TAB_DESIGN §20/§21) proves login + library + download
 * ONLY. Registering a downloaded GOG game into GameHub's library so it launches via
 * LaunchType.GogGameByPcEmulator is Phase 2 (§19: re-implement as a programmatic
 * `xm7.u` import on 6.0.4; the 5.3.5 `B3()` reflection trick is dead on the KMP base).
 *
 * The public API of the original BannerHub-3.7.x GogLaunchHelper is preserved verbatim
 * so GogGamesActivity / GogGameDetailActivity compile and run unchanged. In Phase 1
 * triggerLaunch() just confirms the install location and returns the user to the GOG
 * library (no SharedPrefs hand-off, no activity.finish(), no reflection).
 *
 * Phase 2 TODO: replace triggerLaunch/checkPendingLaunch bodies with the §19 bridge —
 * hook com.xiaoji.egggame.MainActivity.onResume + programmatic xm7.u import.
 */
public final class GogLaunchHelper {

    private static final String TAG = "BannerHub";

    private GogLaunchHelper() {}

    /**
     * Phase 1: a GOG game finished installing at {@code exePath}. We do NOT yet
     * register it with GameHub (Phase 2). Confirm the location and stay in the
     * GOG library so download/library can be validated standalone.
     */
    public static void triggerLaunch(Activity activity, String exePath) {
        Log.i(TAG, "GogLaunchHelper[Phase1]: installed exe=" + exePath
                + " — GameHub-library bridge deferred to Phase 2 (no launch yet)");
        if (activity != null && exePath != null) {
            try {
                Toast.makeText(activity,
                        "GOG game installed.\nIn-app launch arrives in a later update.",
                        Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
                // Toast off the main thread / detached activity — non-fatal in Phase 1.
            }
        }
    }

    /**
     * Phase 1: no-op. API kept for the Phase-2 launcher-onResume hook
     * (com.xiaoji.egggame.MainActivity, see design doc §19).
     */
    public static void checkPendingLaunch(Activity activity) {
        // Intentionally empty in Phase 1 — the onResume smali hook is not injected yet.
    }
}
