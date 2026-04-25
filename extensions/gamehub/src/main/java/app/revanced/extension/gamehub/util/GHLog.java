package app.revanced.extension.gamehub.util;

import android.util.Log;

public enum GHLog {
    TOKEN("Token"),
    PREFS("Prefs"),
    BATTERY("Battery"),
    GAME_ID("GameId"),
    CURRENCY("Currency"),
    COMPAT("Compat"),
    FILE_MGR("FileMgr"),
    STORAGE("Storage"),
    NET("Net"),
    CDN("CDN"),
    CPU("CPU"),
    PERF("Perf"),
    PLAYTIME("Playtime"),
    SOUND("Sound"),
    CREDITS("Credits"),
    RTS("RTS");

    private static final String PREFIX = "GHL/";
    private final String tag;

    // spotless:off

    GHLog(String tag) { this.tag = PREFIX + tag; }

    public void d(String msg) { Log.d(tag, msg); }
    public void w(String msg) { Log.w(tag, msg); }
    public void w(String msg, Throwable t) { Log.w(tag, msg, t); }
    public void e(String msg, Throwable t) { Log.e(tag, msg, t); }

    // spotless:on
}
