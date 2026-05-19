package app.revanced.extension.gamehub.gog;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

public final class BhStoragePath {

    private BhStoragePath() {}

    /**
     * SD card on:  {sdCardRoot}/bannerhub/{storeFolder}/{gameName}
     * SD card off: {filesDir}/{storeFolder}/{gameName}
     */
    public static File getInstallDir(Context ctx, String storeFolder, String gameName) {
        return new File(getStoreBase(ctx, storeFolder), gameName);
    }

    public static File getStoreBase(Context ctx, String storeFolder) {
        SharedPreferences bhPrefs = ctx.getSharedPreferences(BhStorageHelper.PREFS, 0);

        boolean useCustom;
        String sdPath;
        if (bhPrefs.contains(BhStorageHelper.KEY_USE_CUSTOM)) {
            useCustom = bhPrefs.getBoolean(BhStorageHelper.KEY_USE_CUSTOM, false);
            sdPath = bhPrefs.getString(BhStorageHelper.KEY_PATH, null);
        } else {
            // First read after upgrade from <= v3.5.0: seed bh_storage_pref from the
            // legacy steam_storage_pref so existing GOG/Epic/Amazon installs keep
            // resolving to the same path. Steam's pref is left intact — Steam is
            // addressed separately by the one-shot dialog in BhStorageMigration.
            SharedPreferences legacy = ctx.getSharedPreferences("steam_storage_pref", 0);
            useCustom = legacy.getBoolean("use_custom_storage", false);
            sdPath = legacy.getString("steam_storage_path", null);
            bhPrefs.edit()
                    .putBoolean(BhStorageHelper.KEY_USE_CUSTOM, useCustom)
                    .putString(BhStorageHelper.KEY_PATH, sdPath != null ? sdPath : "")
                    .apply();
        }

        if (useCustom && sdPath != null && !sdPath.isEmpty()) {
            return new File(new File(sdPath, "bannerhub"), storeFolder);
        }
        return new File(ctx.getFilesDir(), storeFolder);
    }
}
