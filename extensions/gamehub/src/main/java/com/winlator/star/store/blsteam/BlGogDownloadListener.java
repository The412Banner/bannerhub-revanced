// JNI binds to this package + class name AND to the exact method names/descriptors below
// (libblsteam.so calls GetMethodID on the listener's runtime class). Do not rename or reorder.
package com.winlator.star.store.blsteam;

/**
 * Callbacks from the native GOG download engine ({@code libblsteam.so}). Every method runs on a
 * native worker / process-pool thread — never touch Views directly.
 *
 * {@link app.revanced.extension.gamehub.gog.GogDownloadManager} turns these into exactly the same
 * {@code Callback.onProgress} strings / percentages and {@code bh_gog_debug.txt} lines its own
 * thread-pool loop produces, so the notification / downloads screen see no difference.
 *
 * Descriptors the native side resolves (keep in sync with the Rust JNI shim):
 * <pre>
 *   onProgress  (JJIILjava/lang/String;JZ)V
 *   onLog       (Ljava/lang/String;)V
 *   onComplete  (ZZZLjava/lang/String;JI)V
 * </pre>
 */
public interface BlGogDownloadListener {

    /**
     * One file reached its final state. {@code verified} = resume-skip (the existing file passed
     * size+MD5; no bytes credited — the Java loop's "Resuming…" branch); otherwise a freshly
     * assembled, size+MD5-verified and renamed file ({@code fileBytes} = its decompressed size —
     * the "Downloading: …" branch). {@code filesDone} counts both; {@code bytesDone} counts
     * assembled bytes only.
     */
    void onProgress(long bytesDone, long bytesTotal, int filesDone, int filesTotal,
                    String file, long fileBytes, boolean verified);

    /** Engine diagnostics (the native side already wrote them to logcat under {@code BL_GOG_DL}). */
    void onLog(String line);

    /**
     * Fired exactly once per {@link BlGogDownload#start} that returned a non-zero handle.
     * {@code linkExpiry} = the run died on an HTTP 401/403/404/500 — the codes the Java chunk loop
     * treats as an expired secure link; the manager refreshes / rotates the CDN base and re-runs.
     */
    void onComplete(boolean success, boolean cancelled, boolean linkExpiry, String error,
                    long bytesWritten, int filesDone);
}
