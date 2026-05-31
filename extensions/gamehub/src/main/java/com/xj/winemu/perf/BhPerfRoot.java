package com.xj.winemu.perf;

import android.util.Log;

import java.io.DataOutputStream;
import java.io.OutputStream;

/**
 * BhPerfRoot — minimal {@code su} executor for the performance overlay.
 *
 * Each call spawns {@code su} and feeds it a shell script on stdin, then waits
 * for exit. Intended to be called ONLY from {@link BhPerfController}'s worker
 * thread (never the UI thread). Keeps no persistent shell — the toggles fire
 * infrequently (user taps), so per-call {@code su} is simpler and avoids a
 * long-lived rooted process hanging around.
 *
 * No dependency on libsu / Shizuku — direct {@code Runtime.exec("su")} so it
 * works on any rooted device without extra components.
 */
final class BhPerfRoot {

    private static final String TAG = "BhPerf";

    private BhPerfRoot() {}

    /** {@code su -c id} style probe. Returns true if a root shell ran. */
    static boolean checkRoot() {
        return runScript("id >/dev/null 2>&1");
    }

    /**
     * Run {@code script} in a root shell. Returns true iff su launched and the
     * script exited 0. Never throws.
     */
    static boolean runScript(String script) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            OutputStream os = p.getOutputStream();
            DataOutputStream dos = new DataOutputStream(os);
            dos.writeBytes(script + "\n");
            dos.writeBytes("exit\n");
            dos.flush();
            dos.close();
            int code = p.waitFor();
            if (code != 0) {
                Log.w(TAG, "su script exit=" + code + " : " + script);
            }
            return code == 0;
        } catch (Throwable t) {
            Log.w(TAG, "su exec failed: " + script, t);
            return false;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }
}
