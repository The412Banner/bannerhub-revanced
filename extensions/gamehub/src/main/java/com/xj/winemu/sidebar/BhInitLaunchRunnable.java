package com.xj.winemu.sidebar;

import android.os.Handler;
import android.os.Looper;
import app.revanced.extension.gamehub.BhWineLaunchHelper;
import java.io.File;

public class BhInitLaunchRunnable implements Runnable {
    final BhTaskManagerFragment fragment;
    public BhInitLaunchRunnable(BhTaskManagerFragment f) { fragment = f; }

    @Override public void run() {
        String prefix = BhWineLaunchHelper.getWinePrefix();
        if (prefix == null) prefix = "/";
        fragment.wineRootPath = prefix;

        File dosdevices = new File(prefix, "dosdevices");
        String startPath = dosdevices.isDirectory() ? dosdevices.getAbsolutePath() : prefix;

        new Handler(Looper.getMainLooper()).post(new BhBrowseToRunnable(fragment, startPath));
    }
}
