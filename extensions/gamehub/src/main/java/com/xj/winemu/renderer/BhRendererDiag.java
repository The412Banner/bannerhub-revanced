package com.xj.winemu.renderer;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * BhRendererDiag — THROWAWAY diagnostic instrumentation for the M2
 * Legacy-renderer device test. NOT FOR MERGE. Revert before any M2 ship.
 *
 * Purpose: the device data showed GoW under M2 Legacy bootstraps, runs ~40 s
 * @ 600 %% CPU, then {@code :wine} dies fg-TOP with no tombstone and no fresh
 * DXVK log. That leaves four indistinguishable hypotheses (lib never loads /
 * loads-but-JNI-faults / loads-but-never-composites-and-watchdog-kills /
 * FEX-side death unrelated to the renderer). This writes a persisted,
 * per-line fsync'd trace through the exact M2 decision points + a heartbeat,
 * so a single GoW run resolves which one is true.
 *
 * All work is wrapped so a logging failure can never affect the frozen
 * renderer decision or the stock fallback (can't-brick guarantee intact).
 *
 * Gate: {@link #DIAG}. Flip to {@code false} (or delete this class + its
 * callers + RendererDiagEnvPatch) to neutralise.
 */
public final class BhRendererDiag {

    /** Master kill switch. THROWAWAY — must be false for any real ship. */
    public static final boolean DIAG = true;

    private static final String TAG  = "BH-RENDERER-DIAG";
    private static final String FILE = "bh_renderer_diag.log";

    /** 6.0.2 md5s the bundle patch ships (RendererLibBundlePatch). */
    public static final String EXPECTED_XSERVER_MD5 = "e8eb894825da66cca0fc59b242ac0ad5";
    public static final String EXPECTED_WINEMU_MD5  = "407f274d998335dbce03b2074a187e9f";

    private static volatile File logFile;
    private static volatile long  loadEpochMs;
    private static volatile boolean beating = false;

    private BhRendererDiag() { }

    // ── File logger ──────────────────────────────────────────────────────

    /** Append one fsync'd line; mirror to logcat. Never throws. */
    public static void log(String tag, String msg) {
        if (!DIAG) return;
        String line = stamp() + " " + Process.myPid() + "/" + Process.myTid()
                + " [" + tag + "] " + msg;
        try {
            Log.i(TAG, line);
        } catch (Throwable ignored) { }
        try {
            File f = file();
            if (f == null) return;
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                OutputStreamWriter w = new OutputStreamWriter(fos);
                w.write(line);
                w.write('\n');
                w.flush();
                fos.getFD().sync();
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) { }
    }

    public static void log(String tag, String msg, Throwable t) {
        if (!DIAG) return;
        String trace;
        try {
            StringBuilder sb = new StringBuilder(String.valueOf(t));
            for (StackTraceElement e : t.getStackTrace()) {
                sb.append(" | ").append(e);
            }
            trace = sb.toString();
        } catch (Throwable x) {
            trace = String.valueOf(t);
        }
        log(tag, msg + " :: " + trace);
    }

    private static File file() {
        File f = logFile;
        if (f != null) return f;
        try {
            Context ctx = BhRendererController.getInstance() != null
                    ? appCtx() : null;
            if (ctx == null) return null;
            f = new File(ctx.getFilesDir(), FILE);
            logFile = f;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context appCtx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            return (app instanceof Context)
                    ? ((Context) app).getApplicationContext() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stamp() {
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    // ── md5 of the resolved legacy .so (confirm bundle integrity) ────────

    public static String md5(File so, String expectedMd5) {
        if (!DIAG || so == null) return "(diag-off)";
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[1 << 16];
            java.io.FileInputStream in = new java.io.FileInputStream(so);
            try {
                int r;
                while ((r = in.read(buf)) != -1) d.update(buf, 0, r);
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest()) sb.append(String.format("%02x", b));
            String hex = sb.toString();
            return hex + (hex.equalsIgnoreCase(expectedMd5)
                    ? " (MATCHES expected 6.0.2)" : " (MISMATCH! expected "
                    + expectedMd5 + ")");
        } catch (Throwable t) {
            return "(md5 failed: " + t + ")";
        }
    }

    // ── Heartbeat: how far past the swap did it get before :wine death ──

    /** Starts a 1 Hz daemon beat once the legacy lib is loaded. Idempotent. */
    public static synchronized void startHeartbeat() {
        if (!DIAG || beating) return;
        beating = true;
        loadEpochMs = System.currentTimeMillis();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long n = 0;
                while (beating) {
                    log("BEAT", "n=" + n + " elapsedMs="
                            + (System.currentTimeMillis() - loadEpochMs));
                    n++;
                    try { Thread.sleep(1000L); }
                    catch (InterruptedException e) { return; }
                }
            }
        }, "bh-renderer-diag-beat");
        t.setDaemon(true);
        t.start();
    }

    // ── Patch B target: WINEDEBUG / FEX log env injection ────────────────
    //
    // Injected by RendererDiagEnvPatch at the same Lbg5;->a env-builder
    // anchor GpuSpoofPatch uses (after the ZINK_DESCRIPTORS setter). Sets
    // diagnostic env ONLY when Legacy is active for the launching game, so
    // New mode and non-legacy games are untouched. Reflective EnvVars#a
    // mirrors BhGpuSpoofController.putEnv exactly.

    public static void applyDiagEnv(Object envVars) {
        if (!DIAG || envVars == null) return;
        try {
            boolean legacy = BhRendererController.getInstance()
                    .isLegacyForLaunchingGame();
            log("ENV", "applyDiagEnv reached; legacyForLaunchingGame=" + legacy);
            if (!legacy) return;

            String fexLog = "stderr";
            try {
                Context ctx = appCtx();
                if (ctx != null) {
                    fexLog = new File(ctx.getFilesDir(), "fex_diag.log")
                            .getAbsolutePath();
                }
            } catch (Throwable ignored) { }

            Method a = envVars.getClass()
                    .getMethod("a", String.class, Object.class);
            // Wine loader trace — targeted; NO +relay (would 10x slow + itself
            // change the failure). loaddll/module/process/seh pinpoint the
            // stage; pid/timestamp make it correlatable with the diag log.
            a.invoke(envVars, "WINEDEBUG",
                    "+loaddll,+module,+process,+seh,+pid,+timestamp,fixme-all");
            // FEX is the translator (launch log: fexPath set, box64 empty).
            // Config showed silentLog=true — un-silence + persist to a file
            // FEX writes itself (survives kill -9, unlike host-swallowed
            // wine stderr).
            a.invoke(envVars, "FEX_SILENTLOG", "0");
            a.invoke(envVars, "FEX_OUTPUTLOG", fexLog);
            log("ENV", "set WINEDEBUG + FEX_SILENTLOG=0 + FEX_OUTPUTLOG="
                    + fexLog);
        } catch (Throwable t) {
            log("ENV", "applyDiagEnv failed (non-fatal)", t);
        }
    }
}
