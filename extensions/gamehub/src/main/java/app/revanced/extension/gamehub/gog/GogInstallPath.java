package app.revanced.extension.gamehub.gog;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

public final class GogInstallPath {

    public enum State { INSTALLED, PARTIAL, NONE }

    private GogInstallPath() {}

    public static File getInstallDir(Context ctx, String dirName) {
        return BhStoragePath.getInstallDir(ctx, "gog_games", dirName);
    }

    // ── Unified install-state model ───────────────────────────────────────────
    //
    // Three sites read this: GogGameDetailActivity (detail page), GogGamesActivity
    // (library tiles + dialogs), BhDownloadService (download manager notification).
    // All must see the same state so the Install/Uninstall buttons behave
    // identically across them.
    //
    // INSTALLED — gog_exe_<id> AND gog_dir_<id> both set. Download succeeded;
    //             game is launchable; Uninstall removes the install dir + clears prefs.
    //
    // PARTIAL   — gog_partial_<id> set without a matching gog_dir_<id>. Download
    //             started and wrote at least some files, then failed (any toast
    //             other than success) or the process died mid-download. The user
    //             sees Uninstall to clean up the orphaned files. Resume install
    //             is also valid (existing resume logic skips files that exist).
    //
    // NONE      — neither set. Show Install only.

    public static State checkState(SharedPreferences prefs, String gameId) {
        String exe = prefs.getString("gog_exe_" + gameId, null);
        String dir = prefs.getString("gog_dir_" + gameId, null);
        if (exe != null && dir != null) return State.INSTALLED;
        String partial = prefs.getString("gog_partial_" + gameId, null);
        if (partial != null) return State.PARTIAL;
        return State.NONE;
    }

    /** Path to delete on uninstall — works for both INSTALLED and PARTIAL states. */
    public static String getInstallOrPartialPath(SharedPreferences prefs, String gameId) {
        String dir = prefs.getString("gog_dir_" + gameId, null);
        if (dir != null) return dir;
        return prefs.getString("gog_partial_" + gameId, null);
    }

    /** Called by BhDownloadService at download start, before any files are written. */
    public static void markPartial(SharedPreferences prefs, String gameId, String installPath) {
        prefs.edit().putString("gog_partial_" + gameId, installPath).apply();
    }

    /** Called by BhDownloadService on successful onComplete (gog_dir_ now authoritative). */
    public static void clearPartial(SharedPreferences prefs, String gameId) {
        prefs.edit().remove("gog_partial_" + gameId).apply();
    }

    /**
     * Atomic uninstall — clears all 4 pref keys (dir, exe, partial, cover).
     * Callers should delete the on-disk install folder separately via
     * getInstallOrPartialPath() BEFORE invoking this.
     */
    public static void clearAll(SharedPreferences prefs, String gameId) {
        prefs.edit()
                .remove("gog_dir_" + gameId)
                .remove("gog_exe_" + gameId)
                .remove("gog_partial_" + gameId)
                .remove("gog_cover_" + gameId)
                .apply();
    }
}
