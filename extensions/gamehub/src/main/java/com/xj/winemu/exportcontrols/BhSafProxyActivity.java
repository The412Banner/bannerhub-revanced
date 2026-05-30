package com.xj.winemu.exportcontrols;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import android.os.ParcelFileDescriptor;

/**
 * Translucent Activity hosting SAF (Storage Access Framework) requests on
 * behalf of the bytecode-hooked VJoy share/apply flows.
 *
 * Modes:
 *  - {@link #MODE_EXPORT_FROM_URL}: fire-and-forget export. Caller supplies
 *    a CDN URL and a suggested filename; the activity launches
 *    ACTION_CREATE_DOCUMENT, then on user-pick spawns a background thread
 *    that HTTP-fetches the URL and streams the body to the chosen content
 *    URI. No CompletableFuture, no caller blocking — this is the path
 *    used by the VJoy share hijack (the layout JSON has already been
 *    uploaded to Xiaoji's CDN by the time {@code Lrqn;->i} fires; we just
 *    pull it back down and let the user save it locally).
 *  - {@link #MODE_IMPORT}: synchronous (CompletableFuture-based) import.
 *    Caller calls {@link #startImport(android.content.Context)}, SAF picks
 *    a file, contents are read into a string and returned via the future.
 *
 * Cross-process state for the import path uses a static
 * {@link CompletableFuture} keyed by an {@link AtomicReference} — single
 * SAF request in flight at a time.
 */
public final class BhSafProxyActivity extends Activity {

    private static final String TAG = "BhSafProxy";

    public static final String EXTRA_MODE = "bh_export.mode";
    public static final String EXTRA_BYTES = "bh_export.bytes";
    public static final String EXTRA_FILENAME = "bh_export.filename";
    public static final String MODE_EXPORT_FROM_URL = "export_from_url";
    public static final String MODE_EXPORT_FROM_BYTES = "export_from_bytes";
    public static final String MODE_IMPORT = "import";

    private static final int REQ_EXPORT_FROM_URL = 0xB3;
    private static final int REQ_EXPORT_FROM_BYTES = 0xB4;
    private static final int REQ_IMPORT = 0xB2;

    /** Per-process pending request (import path only — export is fire-and-forget). */
    private static final AtomicReference<PendingImport> PENDING_IMPORT = new AtomicReference<>();

    /** Per-process pending export-from-URL state (URL + suggested filename). */
    private static final AtomicReference<PendingExportFromUrl> PENDING_EXPORT_URL =
        new AtomicReference<>();

    /** Per-process pending export-from-bytes state (pre-loaded bytes + filename). */
    private static final AtomicReference<PendingExportFromBytes> PENDING_EXPORT_BYTES =
        new AtomicReference<>();

    static final class PendingImport {
        // Binary bytes — .gtheme files are ZIP archives, not text.
        final CompletableFuture<byte[]> future = new CompletableFuture<>();
    }

    static final class PendingExportFromUrl {
        final String url;
        final String suggestedFilename;
        PendingExportFromUrl(String url, String suggestedFilename) {
            this.url = url;
            this.suggestedFilename = suggestedFilename;
        }
    }

    static final class PendingExportFromBytes {
        final byte[] bytes;
        final String suggestedFilename;
        PendingExportFromBytes(byte[] bytes, String suggestedFilename) {
            this.bytes = bytes;
            this.suggestedFilename = suggestedFilename;
        }
    }

    // -------- public callable API (used from BhVjoyShareHook) -----------------

    /**
     * Fire-and-forget export. Stages the URL + filename, launches the
     * proxy activity, returns immediately. Caller is expected to return
     * from its hook without blocking.
     */
    public static void startExportFromUrl(
            android.content.Context launcher,
            String url,
            String suggestedFilename
    ) {
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "startExportFromUrl: empty url, ignoring");
            return;
        }
        PendingExportFromUrl req = new PendingExportFromUrl(
            url,
            suggestedFilename == null ? "vjoy_layout.gtheme" : suggestedFilename
        );
        PENDING_EXPORT_URL.set(req);
        Intent it = new Intent(launcher, BhSafProxyActivity.class);
        it.putExtra(EXTRA_MODE, MODE_EXPORT_FROM_URL);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launcher.startActivity(it);
    }

    /**
     * Fire-and-forget export from pre-loaded bytes. Caller has read the
     * clean layout file (pre-CDN-upload) into a byte[]; we just save it to
     * the user's SAF location. Avoids the CDN round-trip that mangles
     * binary bytes in {@link #startExportFromUrl}.
     *
     * Pass the bytes via the Intent extras (NOT via the
     * PENDING_EXPORT_BYTES static) because when called from inside an
     * in-game session (WineActivity is in a separate `:wine` process),
     * the SAF proxy activity launches in the main banner.hub process
     * while the hook fires in `:wine`. Static state doesn't cross
     * process boundaries; Intent extras do. Bundle size limit (~1MB)
     * comfortably accommodates a multi-MB layout.
     */
    public static void startExportFromBytes(
            android.content.Context launcher,
            byte[] bytes,
            String suggestedFilename
    ) {
        if (bytes == null || bytes.length == 0) {
            Log.w(TAG, "startExportFromBytes: empty bytes, ignoring");
            return;
        }
        String fname = suggestedFilename == null
            ? "vjoy_layout.gtheme" : suggestedFilename;
        Intent it = new Intent(launcher, BhSafProxyActivity.class);
        it.putExtra(EXTRA_MODE, MODE_EXPORT_FROM_BYTES);
        it.putExtra(EXTRA_BYTES, bytes);
        it.putExtra(EXTRA_FILENAME, fname);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launcher.startActivity(it);
    }

    /**
     * Fire an import request. Returns a future that completes with the
     * raw file bytes on success, or null on cancel / read failure.
     */
    public static CompletableFuture<byte[]> startImport(android.content.Context launcher) {
        PendingImport existing = PENDING_IMPORT.get();
        if (existing != null && !existing.future.isDone()) {
            CompletableFuture<byte[]> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("Import already in flight"));
            return f;
        }
        PendingImport req = new PendingImport();
        PENDING_IMPORT.set(req);
        Intent it = new Intent(launcher, BhSafProxyActivity.class);
        it.putExtra(EXTRA_MODE, MODE_IMPORT);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launcher.startActivity(it);
        return req.future;
    }

    // -------- Activity lifecycle ---------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_EXPORT_FROM_URL.equals(mode)) {
            PendingExportFromUrl req = PENDING_EXPORT_URL.get();
            if (req == null) {
                Log.w(TAG, "onCreate: export-from-url with no pending request");
                finish();
                return;
            }
            // MIME application/octet-stream so SAF does NOT auto-append an
            // extension. The body is technically a ZIP (Xiaoji `.gtheme`
            // wraps a `layout.json` entry), but setting MIME=application/zip
            // makes SAF rewrite our suggested `<name>.gtheme` to
            // `<name>.gtheme.zip`. octet-stream treats the file as opaque
            // and preserves whatever extension we suggest.
            Intent saf = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, req.suggestedFilename);
            startActivityForResult(saf, REQ_EXPORT_FROM_URL);
        } else if (MODE_EXPORT_FROM_BYTES.equals(mode)) {
            byte[] bytes = getIntent().getByteArrayExtra(EXTRA_BYTES);
            String filename = getIntent().getStringExtra(EXTRA_FILENAME);
            if (bytes == null || bytes.length == 0) {
                Log.w(TAG, "onCreate: export-from-bytes with no bytes payload");
                finish();
                return;
            }
            if (filename == null || filename.isEmpty()) {
                filename = "vjoy_layout.gtheme";
            }
            // Stash on the static for the result handler (which runs in
            // this same activity instance, so the static IS visible).
            PENDING_EXPORT_BYTES.set(new PendingExportFromBytes(bytes, filename));
            Intent saf = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, filename);
            startActivityForResult(saf, REQ_EXPORT_FROM_BYTES);
        } else if (MODE_IMPORT.equals(mode)) {
            PendingImport req = PENDING_IMPORT.get();
            if (req == null) {
                Log.w(TAG, "onCreate: import with no pending request");
                finish();
                return;
            }
            Intent saf = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            startActivityForResult(saf, REQ_IMPORT);
        } else {
            Log.w(TAG, "unknown mode: " + mode);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                if (requestCode == REQ_IMPORT) completeImportAndFinish(null);
                else {
                    PENDING_EXPORT_URL.set(null);
                    PENDING_EXPORT_BYTES.set(null);
                    finish();
                }
                return;
            }
            Uri uri = data.getData();

            if (requestCode == REQ_EXPORT_FROM_URL) {
                handleExportFromUrl(uri);
            } else if (requestCode == REQ_EXPORT_FROM_BYTES) {
                handleExportFromBytes(uri);
            } else if (requestCode == REQ_IMPORT) {
                handleImport(uri);
            } else {
                finish();
            }
        } catch (Throwable t) {
            Log.w(TAG, "SAF result handling failed", t);
            Toast.makeText(this, "Operation failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
            if (requestCode == REQ_IMPORT) completeImportAndFinish(null);
            else {
                PENDING_EXPORT_URL.set(null);
                PENDING_EXPORT_BYTES.set(null);
                finish();
            }
        }
    }

    /** Write pre-loaded bytes to the picked URI via PFD (avoids the
     *  ContentResolver UTF-8 mangling that bit pre6/7/8/9 exports). */
    private void handleExportFromBytes(Uri uri) {
        PendingExportFromBytes req = PENDING_EXPORT_BYTES.getAndSet(null);
        if (req == null) {
            Log.w(TAG, "handleExportFromBytes: no staged bytes");
            finish();
            return;
        }
        final byte[] bytes = req.bytes;
        final String displayName = displayNameOf(uri);
        try (ParcelFileDescriptor pfd =
                getContentResolver().openFileDescriptor(uri, "w");
             FileOutputStream os = pfd == null ? null :
                new FileOutputStream(pfd.getFileDescriptor())) {
            if (os == null) {
                Toast.makeText(this, "Export failed: could not open output",
                    Toast.LENGTH_LONG).show();
            } else {
                os.write(bytes);
                os.flush();
                Toast.makeText(this, "Exported to " +
                    (displayName == null ? uri.toString() : displayName) +
                    " (" + bytes.length + " bytes)", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "handleExportFromBytes failed", t);
            Toast.makeText(this, "Export failed: " + t.getMessage(),
                Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /** Spawns a background thread that fetches the URL and streams it to
     *  the picked URI. The activity stays alive (translucent) until the
     *  fetch finishes, then toasts + finishes. */
    private void handleExportFromUrl(Uri uri) {
        PendingExportFromUrl req = PENDING_EXPORT_URL.getAndSet(null);
        if (req == null) {
            Log.w(TAG, "handleExportFromUrl: no staged URL");
            finish();
            return;
        }
        final String url = req.url;
        final String displayName = displayNameOf(uri);

        new Thread(() -> {
            String resultMsg;
            boolean ok = false;
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                Log.i(TAG, "HTTP " + code + " Content-Type=" + conn.getContentType() +
                    " Content-Encoding=" + conn.getContentEncoding() +
                    " Content-Length=" + conn.getContentLength());
                if (code < 200 || code >= 300) {
                    resultMsg = "Export failed: HTTP " + code;
                } else {
                    // Open the SAF URI via ParcelFileDescriptor → FileOutputStream
                    // to get a raw binary handle. Empirically, the higher-level
                    // ContentResolver.openOutputStream(uri, "w") path goes through
                    // a document provider that on this device (Samsung One UI 7?)
                    // corrupts non-UTF-8 bytes — every byte ≥ 0x80 in the stream
                    // gets replaced with the UTF-8 sequence for U+FFFD
                    // (0xEF 0xBF 0xBD), which mangles ZIP CRC32 / size fields and
                    // breaks the archive. Verified: pre6/7/8/9 .gtheme files all
                    // had `ef bf bd` runs where the ZIP header bytes should be;
                    // native `unzip` confirmed "please check that you have
                    // transferred or created the zipfile in the appropriate
                    // BINARY mode."
                    long total = 0;
                    try (InputStream is = conn.getInputStream();
                         ParcelFileDescriptor pfd =
                            getContentResolver().openFileDescriptor(uri, "w");
                         FileOutputStream os = pfd == null ? null :
                            new FileOutputStream(pfd.getFileDescriptor())) {
                        if (os == null) {
                            resultMsg = "Export failed: could not open output stream";
                        } else {
                            byte[] tmp = new byte[8192];
                            int n;
                            boolean loggedFirst = false;
                            while ((n = is.read(tmp)) > 0) {
                                if (!loggedFirst) {
                                    StringBuilder hex = new StringBuilder();
                                    for (int i = 0; i < Math.min(32, n); i++) {
                                        hex.append(String.format("%02x ", tmp[i] & 0xff));
                                    }
                                    Log.i(TAG, "first " + Math.min(32,n) +
                                        " bytes from HTTP: " + hex);
                                    loggedFirst = true;
                                }
                                os.write(tmp, 0, n);
                                total += n;
                            }
                            os.flush();
                            resultMsg = "Exported to " +
                                (displayName == null ? uri.toString() : displayName) +
                                " (" + total + " bytes)";
                            ok = true;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "fetch+save failed for " + url, t);
                resultMsg = "Export failed: " + t.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String msg = resultMsg;
            final boolean success = ok;
            runOnUiThread(() -> {
                Toast.makeText(BhSafProxyActivity.this, msg,
                        success ? Toast.LENGTH_LONG : Toast.LENGTH_LONG).show();
                finish();
            });
        }, "BhSafFetchAndSave").start();
    }

    private void handleImport(Uri uri) {
        // Same ParcelFileDescriptor trick as handleExportFromUrl — the
        // ContentResolver openInputStream(uri) path on this device runs
        // bytes through a UTF-8 decode/re-encode that corrupts binary
        // content. Skip it; open the raw fd.
        try (ParcelFileDescriptor pfd =
                getContentResolver().openFileDescriptor(uri, "r");
             FileInputStream is = pfd == null ? null :
                new FileInputStream(pfd.getFileDescriptor())) {
            if (is == null) {
                completeImportAndFinish(null);
                return;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            completeImportAndFinish(bo.toByteArray());
        } catch (Throwable t) {
            Log.w(TAG, "handleImport read failed", t);
            completeImportAndFinish(null);
        }
    }

    private void completeImportAndFinish(byte[] result) {
        PendingImport req = PENDING_IMPORT.getAndSet(null);
        if (req != null) req.future.complete(result);
        finish();
    }

    private String displayNameOf(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
