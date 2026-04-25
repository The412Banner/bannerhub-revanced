package com.xj.winemu.sidebar;

import android.content.Context;
import android.view.View;
import android.widget.Toast;
import app.revanced.extension.gamehub.BhWineLaunchHelper;

public class BhExeLaunchListener implements View.OnClickListener {
    final BhTaskManagerFragment fragment;
    final String exePath;
    public BhExeLaunchListener(BhTaskManagerFragment f, String p) { fragment = f; exePath = p; }

    @Override public void onClick(View v) {
        Context ctx = fragment.bhContext;
        if (ctx != null) {
            String name = exePath.substring(exePath.lastIndexOf('/') + 1);
            Toast.makeText(ctx, "Launching: " + name, Toast.LENGTH_SHORT).show();
        }
        BhWineLaunchHelper.launchExe(ctx, exePath);
    }
}
