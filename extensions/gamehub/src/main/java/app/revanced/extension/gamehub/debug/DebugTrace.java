package app.revanced.extension.gamehub.debug;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.blankj.utilcode.util.Utils;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostic trace that bypasses logcat readers (which the user's setup filters
 * heavily) by appending lines to a file on external storage. Path:
 *   /storage/emulated/0/Android/data/com.xiaoji.egggame/files/gh600-debug.log
 *
 * That directory is always app-writable on every Android version without
 * runtime permissions, and any file manager can read it.
 *
 * All write paths swallow exceptions and fall back to Log.e — we don't want
 * a debug helper to break the host app.
 */
public final class DebugTrace {
    private static final String TAG = "GH600-DEBUG";
    private static final String FILE_NAME = "gh600-debug.log";

    private static volatile File logFile;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static void write(String message) {
        write(message, null);
    }

    /**
     * Zero-argument marker so smali probes can be inserted into methods with
     * `.locals 0` without needing to materialize a string register. Routes to
     * write() with the caller-derived tag so the trace file still reads
     * naturally.
     */
    public static void markY4iUpsert() { write("y4i.b ENTRY (retro upsert)"); }
    public static void markFakeAuth()  { write("FakeAuthToken.get() called"); }
    public static void markEl7Entry()       { write("el7.invokeSuspend ENTRY"); }
    public static void markLaunchInsert()   { write("GameLaunchMethodDao.insert PRE"); }
    public static void markLibraryInsert()  { write("GameLibraryBaseDao.insert PRE"); }

    public static void write(String message, Throwable t) {
        try {
            File f = ensureFile();
            if (f == null) throw new IllegalStateException("no writable trace dir yet");
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                pw.print(TS.format(new Date()));
                pw.print(' ');
                pw.println(message);
                if (t != null) {
                    t.printStackTrace(pw);
                }
            }
        } catch (Throwable e) {
            // Log the MESSAGE and, crucially, the caller's throwable -- not just our
            // own file error. Previously the cause only went through the Log.i path
            // below, which does not surface on this device, so failures were
            // effectively invisible.
            Log.e(TAG, message, t != null ? t : e);
            return;
        }
        // Use Log.i — this device's logcat filter strips app-tagged Log.e
        // for non-system uids, so .e silently disappears from `getlog`.
        if (t != null) {
            Log.i(TAG, message, t);
        } else {
            Log.i(TAG, message);
        }
    }

    private static File ensureFile() {
        File f = logFile;
        if (f != null) return f;
        synchronized (DebugTrace.class) {
            if (logFile != null) return logFile;
            File chosen = null;
            try {
                Context c = Utils.a();
                if (c != null) {
                    File dir = c.getExternalFilesDir(null);
                    if (dir != null) {
                        if (!dir.exists()) dir.mkdirs();
                        chosen = new File(dir, FILE_NAME);
                    }
                }
            } catch (Throwable ignored) {
            }
            // Internal storage as the second choice: always writable, no scoped-storage
            // rules, and unaffected by the package rename.
            if (chosen == null) {
                try {
                    Context c = Utils.a();
                    if (c != null) {
                        File dir = c.getFilesDir();
                        if (dir != null) {
                            if (!dir.exists()) dir.mkdirs();
                            chosen = new File(dir, FILE_NAME);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // Last resort: derive the external path from the ACTUAL package.
            //
            // This used to hardcode "Android/data/com.xiaoji.egggame/files", which is
            // wrong for EVERY build we ship -- all nine variants rename the package,
            // so it pointed at a directory belonging to a different (absent) app.
            // Under scoped storage an app may only create its own Android/data dir, so
            // every write failed with ENOENT... and the broken path was then cached
            // permanently, silently disabling tracing for the whole process. That is
            // how the 6.1.0 login investigation lost its diagnostics: the real
            // exception was swallowed and only DebugTrace's own failure was visible.
            if (chosen == null) {
                try {
                    Context c = Utils.a();
                    String pkg = (c != null) ? c.getPackageName() : null;
                    if (pkg != null) {
                        File dir = new File(
                                Environment.getExternalStorageDirectory(),
                                "Android/data/" + pkg + "/files");
                        if (!dir.exists()) dir.mkdirs();
                        chosen = new File(dir, FILE_NAME);
                    }
                } catch (Throwable ignored) {
                }
            }
            // Do NOT cache a null/unusable choice -- without a Context this early we
            // would otherwise poison the field for the process lifetime. Returning
            // null lets the logcat path still carry the message, and the next call
            // retries once a Context exists.
            if (chosen == null) return null;
            logFile = chosen;
            return chosen;
        }
    }
}
