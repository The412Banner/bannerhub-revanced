package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;

/**
 * One-shot migration prompt for users upgrading from <= v3.5.0, where the BannerHub
 * SD-card toggle wrote into GameHub's native steam_storage_pref and unintentionally
 * moved Steam games to SD as well. From v3.5.1 onward the toggle is BannerHub-only;
 * this dialog gives existing users the choice to revert Steam to internal.
 */
public final class BhStorageMigration {

    private BhStorageMigration() {}

    public static void maybeShowDialog(final Activity activity) {
        final SharedPreferences bhPrefs = activity.getSharedPreferences(BhStorageHelper.PREFS, 0);
        if (bhPrefs.getBoolean(BhStorageHelper.KEY_DIALOG_SHOWN, false)) return;

        final SharedPreferences steamPrefs = activity.getSharedPreferences("steam_storage_pref", 0);
        boolean steamWasOnSd = steamPrefs.getBoolean("use_custom_storage", false);

        // Seed bh_storage_pref from the legacy steam_storage_pref BEFORE any branch
        // that may clear the legacy values (positive button below). This ensures
        // existing GOG/Epic/Amazon installs keep resolving to their original SD
        // path even after the user picks "Switch Steam to internal".
        if (!bhPrefs.contains(BhStorageHelper.KEY_USE_CUSTOM)) {
            String legacyPath = steamPrefs.getString("steam_storage_path", null);
            bhPrefs.edit()
                    .putBoolean(BhStorageHelper.KEY_USE_CUSTOM, steamWasOnSd)
                    .putString(BhStorageHelper.KEY_PATH, legacyPath != null ? legacyPath : "")
                    .apply();
        }

        if (!steamWasOnSd) {
            // Nothing to ask about; mark done so we never check again.
            bhPrefs.edit().putBoolean(BhStorageHelper.KEY_DIALOG_SHOWN, true).apply();
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Move Steam games back to internal?")
                .setMessage("Previous BannerHub versions accidentally moved Steam games to SD card alongside GOG, Epic, and Amazon. The 'Save Store Games to External Storage' toggle now only affects GOG/Epic/Amazon as intended.\n\nFor Steam, would you like to switch back to internal storage? Already-installed Steam games will stay where they are — you would need to reinstall them to actually move them.")
                .setPositiveButton("Switch Steam to internal", (d, w) -> {
                    steamPrefs.edit()
                            .putBoolean("use_custom_storage", false)
                            .remove("steam_storage_path")
                            .apply();
                    bhPrefs.edit().putBoolean(BhStorageHelper.KEY_DIALOG_SHOWN, true).apply();
                })
                .setNegativeButton("Keep Steam on SD card", (d, w) -> {
                    bhPrefs.edit().putBoolean(BhStorageHelper.KEY_DIALOG_SHOWN, true).apply();
                })
                .setCancelable(false)
                .show();
    }
}
