package app.revanced.extension.gamehub.components;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plain {@code HttpURLConnection} download for the "Download components"
 * screen — one file, streamed to {@code <externalFilesDir>/component_downloads/},
 * progress callback, cooperative cancel. No resume (the archives are a few
 * MB; a retry is a fresh download). Worker thread only.
 */
final class BhComponentDownloader {

    static final String DOWNLOAD_SUBDIR = "component_downloads";
    private static final int TIMEOUT_MS = 20000;

    interface Progress {
        /** {@code total} is -1 when the server sent no Content-Length. */
        void onProgress(long done, long total);
    }

    private BhComponentDownloader() {}

    /** {@code <externalFilesDir>/component_downloads} (filesDir when external storage is missing). */
    static File downloadDir(Context ctx) {
        File base = null;
        try {
            base = ctx.getExternalFilesDir(null);
        } catch (Throwable ignored) { }
        if (base == null) base = ctx.getFilesDir();
        return new File(base, DOWNLOAD_SUBDIR);
    }

    /**
     * Streams {@code url} into {@code dest} (through a {@code .part} file,
     * renamed on completion). Throws {@link InterruptedIOException} when
     * {@code cancel} flips; the partial file is removed on any failure.
     */
    static void download(String url, File dest, Progress progress, AtomicBoolean cancel) throws IOException {
        File dir = dest.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File part = new File(dir, dest.getName() + ".part");
        HttpURLConnection conn = null;
        boolean ok = false;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", BhComponentRepo.USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("HTTP " + code);
            long total = conn.getContentLengthLong();
            long done = 0;
            byte[] buf = new byte[1 << 16];
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.get()) throw new InterruptedIOException("cancelled");
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress(done, total);
                }
                out.flush();
            }
            if (total > 0 && done != total) throw new IOException("short read: " + done + " of " + total);
            if (done == 0) throw new IOException("empty response");
            if (dest.exists() && !dest.delete()) throw new IOException("cannot replace " + dest);
            if (!part.renameTo(dest)) throw new IOException("cannot rename to " + dest);
            ok = true;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
            if (!ok) part.delete();
        }
    }
}
