package com.xj.winemu.steamchat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Transparent, internal-only helper that lets the Steam chat overlay send an
 * image. The overlay is a WindowManager view with no Activity-result plumbing of
 * its own, so it delegates the gallery pick here: this Activity fires
 * ACTION_GET_CONTENT, reads the chosen image's bytes, hosts them, and sends the
 * resulting URL to the friend as a normal chat message, then finishes.
 *
 * <p>Steam's native {@code friends.upload_chat_image} returns 401 (the session's
 * web access-token is expired/absent), so instead of the native byte-upload we
 * POST the image to our own bannerhub-api worker ({@code /chat/upload-image} →
 * R2, 7-day retention) and send the returned {@code /chat/i/<id>.jpg} URL via
 * {@code friends.send_message}. Steam clients embed image URLs inline, so the
 * friend sees the picture, not a bare link.
 *
 * Registered in the manifest by BhSteamImagePickerManifestPatch
 * (android:exported="false", no intent-filter).
 */
public final class BhSteamImagePickerActivity extends Activity {

    private static final String TAG = "BhSteamChat";
    private static final int REQ_PICK = 0xB401;
    // 8 MB ceiling — Steam chat images are small; avoid OOM on a huge pick.
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    // Our bannerhub-api worker image host (R2-backed, 7-day retention).
    private static final String UPLOAD_URL =
            "https://bannerhub-api.the412banner.workers.dev/chat/upload-image";
    private static final String UPLOAD_KEY = "bh6img";   // matches the worker's x-bh-chat gate

    private long steamId;
    private volatile String uploadErr = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        steamId = getIntent() != null ? getIntent().getLongExtra("steamId", 0) : 0;
        if (steamId == 0) { finish(); return; }
        try {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(pick, "Send image to Steam chat"), REQ_PICK);
        } catch (Throwable t) {
            Log.w(TAG, "image chooser failed", t);
            toast("No image picker available");
            finish();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        final Uri uri = data.getData();
        toast("Sending image…");
        new Thread(new Runnable() {
            public void run() {
                String result;
                try {
                    // Normalise to a sized JPEG (raw fallback), host it on our R2
                    // worker, then send the URL as a normal chat message — Steam
                    // embeds image URLs inline. Sidesteps the 401 on the native
                    // byte-upload path entirely.
                    byte[] bytes;
                    String mime;
                    byte[] jpeg = encodeJpeg(uri);
                    if (jpeg != null) {
                        bytes = jpeg;
                        mime = "image/jpeg";
                    } else {
                        bytes = readBytes(uri);
                        mime = firstNonNull(getContentResolver().getType(uri), "image/jpeg");
                    }
                    if (bytes == null) {
                        result = "Couldn't read image";
                    } else {
                        String hostedUrl = uploadToHost(bytes, mime);
                        if (hostedUrl == null) {
                            result = "Image upload failed · " + uploadErr;
                        } else {
                            // SendMessageRequest: clientMessageId MUST be a String.
                            String payload = new JSONObject()
                                    .put("steamId", steamId)
                                    .put("message", hostedUrl)
                                    .put("clientMessageId", String.valueOf(System.currentTimeMillis()))
                                    .toString();
                            String resp = BhSteamBridge.request("friends.send_message", payload, 15000);
                            result = (resp != null) ? "Image sent"
                                    : "Hosted, but chat send failed · " + BhSteamBridge.getLastError();
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "image send failed", t);
                    result = "Image send failed";
                }
                final String r = result;
                final boolean ok = "Image sent".equals(result);
                runOnUiThread(new Runnable() { public void run() {
                    if (ok) { toast(r); finish(); }
                    else showError(r);   // full, selectable error (toast truncates the status)
                } });
            }
        }, "bh-steam-image-upload").start();
    }

    /** Show the complete failure text in a dismissable dialog so the whole native
     *  error (incl. any status code the toast would clip) is readable. */
    private void showError(String msg) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Steam image upload failed")
                    .setMessage(msg)
                    .setCancelable(true)
                    .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { finish(); }
                    })
                    .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                        public void onDismiss(android.content.DialogInterface d) { finish(); }
                    })
                    .show();
        } catch (Throwable t) { toast(msg); finish(); }
    }

    /** POST the image bytes to the bannerhub-api worker and return the hosted
     *  public URL ({@code …/chat/i/<id>.jpg}), or null on failure (reason in
     *  {@link #uploadErr}). */
    private String uploadToHost(byte[] bytes, String mime) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(UPLOAD_URL).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Content-Type", mime);
            c.setRequestProperty("x-bh-chat", UPLOAD_KEY);
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream();
            try { os.write(bytes); os.flush(); } finally { os.close(); }
            int code = c.getResponseCode();
            if (code != 200) { uploadErr = "host HTTP " + code; return null; }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            } finally { in.close(); }
            String url = new JSONObject(new String(bo.toByteArray(), "UTF-8")).optString("url", "");
            if (url.isEmpty()) { uploadErr = "host gave no url"; return null; }
            return url;
        } catch (Throwable t) {
            uploadErr = t.getClass().getSimpleName();
            Log.w(TAG, "uploadToHost failed", t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Decode the picked image (downscaled to ≤ MAX_DIM on the long edge) and
     *  re-encode it to a JPEG byte[]. Returns null if it can't be decoded. */
    private byte[] encodeJpeg(Uri uri) {
        final int MAX_DIM = 2048;
        try {
            // First pass: bounds only, to pick an inSampleSize.
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream b0 = getContentResolver().openInputStream(uri);
            if (b0 == null) return null;
            try { android.graphics.BitmapFactory.decodeStream(b0, null, bounds); } finally { b0.close(); }
            int sample = 1;
            int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
            while (longEdge / sample > MAX_DIM) sample *= 2;

            android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
            opt.inSampleSize = sample;
            InputStream b1 = getContentResolver().openInputStream(uri);
            if (b1 == null) return null;
            android.graphics.Bitmap bm;
            try { bm = android.graphics.BitmapFactory.decodeStream(b1, null, opt); } finally { b1.close(); }
            if (bm == null) return null;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
            bm.recycle();
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "encodeJpeg failed", t);
            return null;
        }
    }

    private byte[] readBytes(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) { toastUi("Image too large (max 8 MB)"); return null; }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "readBytes failed", t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private static String firstNonNull(String a, String b) { return a != null ? a : b; }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
    private void toastUi(final String s) {
        runOnUiThread(new Runnable() { public void run() { toast(s); } });
    }
}
