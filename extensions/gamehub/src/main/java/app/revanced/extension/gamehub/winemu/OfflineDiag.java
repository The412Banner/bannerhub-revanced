package app.revanced.extension.gamehub.winemu;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * THROWAWAY diagnostic sink for the offline-component-picker investigation.
 *
 * <p>Deliberately does NOT use {@code DebugTrace} — that class is the
 * suspected blind spot (its {@code getExternalFilesDir} path may be failing
 * silently). This writes to the app's <em>internal</em> files dir
 * ({@code /data/data/&lt;pkg&gt;/files/bh_offline_diag.log}), which is always
 * writable by the app itself and root-readable via the logcat-bridge. Every
 * marker is also emitted to logcat at {@code Log.i} under a unique tag.
 *
 * <p>All marker methods are static, no-arg, void → trivially and
 * register-safely bytecode-injectable into the host coroutine.
 *
 * <p>Remove before any real ship.
 */
public final class OfflineDiag {
    private OfflineDiag() {}

    private static final String TAG = "BH-OFFLINE-DIAG";
    private static final String FILE = "bh_offline_diag.log";
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    public static void mark(String what) {
        // logcat first — survives even if the file write fails.
        try { Log.i(TAG, what); } catch (Throwable ignored) {}
        try {
            Context c = ctx();
            File dir = (c != null) ? c.getFilesDir() : null;
            if (dir == null) return;
            File f = new File(dir, FILE);
            byte[] line = (TS.format(new Date()) + "  " + what + "\n")
                    .getBytes("UTF-8");
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(line);
                fos.flush();
            }
        } catch (Throwable ignored) {
            // diagnostic only — never disturb host flow
        }
    }

    // ---- zero-arg injection targets (bytecode-injected into mci.a) ----

    /** First instruction of mci.a — did the picker even reach this method? */
    public static void mciEnter() { mark("mci.a ENTER"); }

    // ---- 6.0.4 real-picker-path probes (myo.w / j7o.<init>) ----

    /** Entry of myo.w(RepoCategory) — the in-memory category list source. */
    public static void myoW() { mark("myo.w ENTER (picker category-list read)"); }

    /** Value myo.w is about to return — size tells us if the in-memory map is populated. */
    public static void myoWReturn(Object list) {
        int n = -1;
        try { if (list instanceof java.util.Collection) n = ((java.util.Collection<?>) list).size(); } catch (Throwable ignored) {}
        mark("myo.w RETURN size=" + n + " type=" + (list == null ? "null" : list.getClass().getName()));
    }

    /** Entry of j7o.<init> — does the repo (which disk-hydrates myo.c) get constructed before the picker? */
    public static void j7oCtor() { mark("j7o.<init> ENTER (disk-hydrator constructor)"); }

    /**
     * Right after the coroutine-resume {@code Lkl5;->V} (Kotlin
     * throwOnFailure). If ENTER logs but this does NOT, the offline network
     * fetch threw and was rethrown here — i.e. control never reaches
     * :goto_2, so the EmptyList-return premise is wrong.
     */
    public static void mciPostResume() { mark("mci.a POST_RESUME (throwOnFailure passed, network did NOT throw)"); }

    private static Context ctx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
