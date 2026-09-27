// JNI symbols depend on this package path and class name:
//   Java_com_winlator_star_store_blsteam_BlGogDownload_native{Probe,Start,Cancel,Release}
// The host app (GameHub) has no com.winlator classes; the extension dex supplies these and the
// GogEngineLibBundlePatch ships lib/arm64-v8a/libblsteam.so next to them.
package com.winlator.star.store.blsteam;

import android.util.Log;

/**
 * JVM-side facade of the GOG download engine inside {@code libblsteam.so} (Rust; gen2 chunk
 * fetch + inflate + MD5 + assemble, and the gen1 Range-GET stream mode). One {@link #start} = one
 * Java pool loop (a gen2 base install or a gen1 install); the manager owns everything before and
 * after it (token, builds, manifests, secure link, CDN pick, markers, exe resolution).
 *
 * Symbol guard: {@link #isAvailable()} loads the library once and resolves one export, so a
 * packaging / symbol regression degrades to the Java loop at download START instead of throwing
 * {@link UnsatisfiedLinkError} mid-download. Nothing here ever throws.
 */
public final class BlGogDownload {

    private static final String TAG = "BH_GOG";
    private static final String LIB = "blsteam";

    /** gen2: depot manifests → chunk fetch + inflate + MD5 (base install). */
    public static final int KIND_GEN2_CHUNKS = 0;

    /** gen1: build manifest → per-file HTTP Range GET streamed to disk. */
    public static final int KIND_GEN1_RANGES = 1;

    private static final Object LOCK = new Object();
    private static volatile Boolean available;
    /** Set once the probe resolved; {@link #unavailableReason()} explains a {@code false}. */
    private static volatile String reason = "";

    private BlGogDownload() {}

    /**
     * True when {@code libblsteam.so} loads and the GOG JNI exports bind. Cached after the first
     * call (both outcomes). Never throws.
     */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (LOCK) {
            cached = available;
            if (cached != null) return cached;
            boolean ok;
            try {
                System.loadLibrary(LIB);
                int probe = nativeProbe();
                ok = probe == 1;
                if (!ok) reason = "probe returned " + probe;
            } catch (Throwable t) {
                ok = false;
                reason = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.w(TAG, "native GOG engine unavailable — " + reason);
            }
            available = ok;
            return ok;
        }
    }

    /** Why {@link #isAvailable()} is false ("" when it is true or was never probed). */
    public static String unavailableReason() {
        return reason == null ? "" : reason;
    }

    /**
     * Starts one download loop on a native thread and returns its handle (0 = not started; the
     * listener then receives NO callbacks).
     *
     * @param kind            {@link #KIND_GEN2_CHUNKS} or {@link #KIND_GEN1_RANGES}
     * @param depotManifests  gen2: inflated depot-manifest JSON strings, in fetch order, already
     *                        filtered by language in Java; gen1: the inflated build manifest
     * @param cdnBase         gen2: the resolved secure-link base (query string intact — it is the
     *                        auth); gen1: "" (file URLs are in the manifest)
     * @param installDir      absolute install directory
     * @param skipPaths       files already completed by an earlier run of this same download
     *                        (CDN-rotation / secure-link-refresh re-run): counted done without
     *                        re-hashing, no progress event
     * @param caBundlePath    PEM trust bundle from {@link CaBundleExtractor}; "" = webpki roots
     * @param maxWorkers      concurrent HTTP window ceiling (BH's per-install thread count)
     * @param processWorkers  inflate / hash / write threads
     * @param sortLargestFirst base install = true (LPT order)
     * @param label           free-form tag echoed in the engine's log lines
     */
    public static long start(int kind, String[] depotManifests, String cdnBase, String installDir,
                             String[] skipPaths, String caBundlePath, int maxWorkers,
                             int processWorkers, boolean sortLargestFirst, String label,
                             BlGogDownloadListener listener) {
        if (!isAvailable()) return 0L;
        try {
            return nativeStart(kind, depotManifests, cdnBase, installDir, skipPaths, caBundlePath,
                    maxWorkers, processWorkers, sortLargestFirst, label, listener);
        } catch (Throwable t) {
            Log.e(TAG, "nativeStart threw — " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return 0L;
        }
    }

    /** Requests cancellation; {@code onComplete(cancelled = true)} follows. Idempotent, 0 = no-op. */
    public static void cancel(long handle) {
        if (handle == 0L) return;
        try {
            nativeCancel(handle);
        } catch (Throwable t) {
            Log.w(TAG, "nativeCancel threw — " + t);
        }
    }

    /** Releases the handle. Call once after {@code onComplete} (the native run keeps itself alive). */
    public static void release(long handle) {
        if (handle == 0L) return;
        try {
            nativeRelease(handle);
        } catch (Throwable t) {
            Log.w(TAG, "nativeRelease threw — " + t);
        }
    }

    // ── native (static → JNI receives jclass, matching the Rust `_class: JClass` signature) ──

    private static native int nativeProbe();

    private static native long nativeStart(int kind, String[] depotManifests, String cdnBase,
                                           String installDir, String[] skipPaths,
                                           String caBundlePath, int maxWorkers,
                                           int processWorkers, boolean sortLargestFirst,
                                           String label, Object listener);

    private static native void nativeCancel(long handle);

    private static native void nativeRelease(long handle);
}
