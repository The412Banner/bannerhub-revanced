package com.xj.winemu.sidebar;

public class BhBrowseToRunnable implements Runnable {
    final BhTaskManagerFragment fragment;
    final String path;
    public BhBrowseToRunnable(BhTaskManagerFragment f, String p) { fragment = f; path = p; }
    @Override public void run() { fragment.browseTo(path); }
}
