package app.revanced.extension.gamehub.winemu;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * THROWAWAY diagnostic sink for the offline-component-picker investigation.
 *
 * <p>HARD LESSON (2026-05-18): the prior OfflineDiag relied solely on
 * {@code ActivityThread.currentApplication()} for a Context (null at DI /
 * coroutine sites), and {@code DebugTrace} relied on
 * {@code com.blankj.utilcode.util.Utils.a()} — a class R8 STRIPPED from the
 * host (renamed to {@code hu5}), so that reference throws
 * {@code NoClassDefFoundError} and DebugTrace's hardcoded fallback targets the
 * WRONG package's external dir. Net: BOTH sinks were structurally incapable of
 * writing on {@code banner.hub}, which is why every probe across 3 rounds was
 * silent — NOT because the methods weren't called.
 *
 * <p>This sink uses NO Context at all. The app's own uid always owns
 * {@code /data/data/banner.hub/files/}; a direct FileOutputStream there can
 * only fail if the process isn't running. Root-readable via logcat-bridge.
 * Also mirrors every marker to logcat ({@code Log.i}, Context-free).
 *
 * <p>All marker methods are static, no-arg (or one already-live reg) → bytecode
 * injectable into host coroutines/ctors register-safely.
 *
 * <p>Remove before any real ship. Diagnostic targets banner.hub Normal-Lite
 * ONLY, so the hardcoded package path is intentional and correct.
 */
public final class OfflineDiag {
    private OfflineDiag() {}

    private static final String TAG = "BH-OFFLINE-DIAG";
    // Context-free, app-owned, root-readable. Hardcoded pkg is deliberate:
    // this throwaway probe only ever runs on banner.hub Normal-Lite.
    private static final String LOG_PATH =
            "/data/data/banner.hub/files/bh_offline_diag.log";
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    public static void mark(String what) {
        // logcat first — Context-free, survives even if the file write fails.
        try { Log.i(TAG, what); } catch (Throwable ignored) {}
        try {
            byte[] line = (TS.format(new Date()) + "  " + what + "\n")
                    .getBytes("UTF-8");
            // No Context, no getFilesDir — the directory always exists for a
            // running app and is owned by its own uid.
            try (FileOutputStream fos = new FileOutputStream(LOG_PATH, true)) {
                fos.write(line);
                fos.flush();
            }
        } catch (Throwable t) {
            // Last-ditch: if /data/data/banner.hub/files somehow isn't there,
            // try the dir's parent path being created — still Context-free.
            try {
                File f = new File(LOG_PATH);
                File dir = f.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(f, true)) {
                    fos.write((TS.format(new Date()) + "  " + what
                            + " (recovered)\n").getBytes("UTF-8"));
                    fos.flush();
                }
            } catch (Throwable ignored) {
                // diagnostic only — never disturb host flow
            }
        }
    }

    // ---- CONTROL: guaranteed-fire startup beacon -----------------------------

    /**
     * Injected right after {@code super.onCreate()} in
     * {@code BaseAndroidApp.onCreate} — the host Application, runs once per
     * process before any UI/picker. If THIS is silent, our code isn't running
     * or the sink itself is broken (the unambiguous control). If it fires but
     * picker probes don't, the negatives are real and we keep hunting the feed.
     */
    public static void beacon() { mark("BEACON BaseAndroidApp.onCreate (our code IS running)"); }

    // ---- repo / hydrator path probes ----------------------------------------

    /** Entry of kek.o(Application) — the lazy j7o DCL singleton factory.
     *  Fires only on the FIRST repo access; tells us if anything constructed
     *  the repo (⟹ disk hydration) before the picker opened. */
    public static void kekO() { mark("kek.o ENTER (lazy j7o singleton factory — hydration trigger)"); }

    /** Entry of j7o.<init> — the disk hydrator constructor itself. */
    public static void j7oCtor() { mark("j7o.<init> ENTER (disk-hydrator constructor ran)"); }

    // ---- picker feed probes -------------------------------------------------

    /** Entry of f4o.a(f4o,RepoCategory,Cont) — the picker's category feed
     *  (c4o/a4o → f4o.a → j7o.b → myo.w). Confirms the picker actually uses
     *  this path. */
    public static void f4oA() { mark("f4o.a ENTER (picker category feed)"); }

    /** Entry of myo.w(RepoCategory) — the in-memory category list source. */
    public static void myoW() { mark("myo.w ENTER (picker category-list read)"); }

    /** Value myo.w is about to return — size tells us if the in-memory map is
     *  populated (0 ⟹ cause i: not hydrated; >0 but picker empty ⟹ cause ii:
     *  downstream filter). */
    public static void myoWReturn(Object list) {
        int n = -1;
        try {
            if (list instanceof java.util.Collection) {
                n = ((java.util.Collection<?>) list).size();
            }
        } catch (Throwable ignored) {}
        mark("myo.w RETURN size=" + n + " type="
                + (list == null ? "null" : list.getClass().getName()));
    }
}
