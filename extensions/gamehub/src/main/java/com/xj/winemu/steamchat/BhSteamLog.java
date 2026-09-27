package com.xj.winemu.steamchat;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * {@code BH_STEAM} logging that ALSO lands in a file. In the {@code :pcengine}
 * process Wine's console output floods logcat by thousands of lines per second,
 * so by the time anyone reads the buffer the bridge's own lines are gone
 * (device-seen 2026-09-27: an ANR triage found zero BH_STEAM lines although the
 * overlay had clearly attached). Mirrors the GOG store's {@code bh_gog_debug.txt}
 * convention: {@code <externalFilesDir>/bh_steam_debug.txt}, appended from both
 * processes (the relay service lives in the main process), truncated when it
 * grows past {@link #MAX_BYTES}. Never throws.
 */
public final class BhSteamLog {
    private static final String TAG = "BH_STEAM";
    private static final String FILE = "bh_steam_debug.txt";
    private static final long MAX_BYTES = 256L * 1024L;
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private BhSteamLog() {}

    public static void i(String msg) { Log.i(TAG, msg); append("I", msg, null); }
    public static void w(String msg) { Log.w(TAG, msg); append("W", msg, null); }
    public static void w(String msg, Throwable t) { Log.w(TAG, msg, t); append("W", msg, t); }
    public static void e(String msg) { Log.e(TAG, msg); append("E", msg, null); }
    public static void e(String msg, Throwable t) { Log.e(TAG, msg, t); append("E", msg, t); }

    private static synchronized void append(String lvl, String msg, Throwable t) {
        try {
            Context ctx = BhSteamBridge.appContext();
            if (ctx == null) return;
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            File f = new File(dir, FILE);
            if (f.length() > MAX_BYTES) { //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            StringBuilder sb = new StringBuilder(160);
            sb.append(TS.format(new Date())).append(' ').append(lvl).append(' ')
              .append(android.os.Process.myPid()).append(' ').append(msg);
            if (t != null) sb.append(" — ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            sb.append('\n');
            FileOutputStream out = new FileOutputStream(f, true);
            try { out.write(sb.toString().getBytes("UTF-8")); } finally { out.close(); }
        } catch (Throwable ignored) {}
    }
}
