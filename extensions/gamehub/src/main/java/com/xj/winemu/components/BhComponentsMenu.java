package com.xj.winemu.components;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

/**
 * Banner Tools "Components" tile → opens the Component Manager. Global (no
 * per-game id), so it stays usable from the Explore page too. Same
 * Class.forName launch as {@link com.xj.winemu.gog.BhGogMenuRowClick}: the
 * activity lives in the {@code app.revanced.extension.gamehub.components}
 * package and is registered by {@code componentManagerManifestPatch}.
 */
public final class BhComponentsMenu {

    private static final String TAG = "BhComponentsMenu";
    private static final String MANAGER_ACTIVITY =
        "app.revanced.extension.gamehub.components.ComponentManagerActivity";

    private BhComponentsMenu() {}

    public static void open(Activity host) {
        try {
            Class<?> cls = Class.forName(MANAGER_ACTIVITY);
            Intent intent = new Intent(host, cls);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            host.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "open failed", t);
        }
    }
}
