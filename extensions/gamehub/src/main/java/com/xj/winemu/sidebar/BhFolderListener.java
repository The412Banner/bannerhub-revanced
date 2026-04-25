package com.xj.winemu.sidebar;

import android.view.View;

public class BhFolderListener implements View.OnClickListener {
    final BhTaskManagerFragment fragment;
    final String path;
    public BhFolderListener(BhTaskManagerFragment f, String p) { fragment = f; path = p; }
    @Override public void onClick(View v) { fragment.browseTo(path); }
}
