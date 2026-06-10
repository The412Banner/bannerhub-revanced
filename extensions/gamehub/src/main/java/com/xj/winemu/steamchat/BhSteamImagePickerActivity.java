package com.xj.winemu.steamchat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Transparent, internal-only helper that lets the Steam chat overlay send an
 * image. The overlay is a WindowManager view with no Activity-result plumbing of
 * its own, so it delegates the gallery pick here: this Activity fires
 * ACTION_GET_CONTENT, reads the chosen image's bytes, and uploads them to the
 * conversation via {@code friends.upload_chat_image} (UploadChatImageRequest:
 * steamId / fileName / mimeType / bytesBase64), then finishes immediately.
 *
 * Registered in the manifest by BhSteamImagePickerManifestPatch
 * (android:exported="false", no intent-filter).
 */
public final class BhSteamImagePickerActivity extends Activity {

    private static final String TAG = "BhSteamChat";
    private static final int REQ_PICK = 0xB401;
    // 8 MB ceiling — Steam chat images are small; avoid OOM on a huge pick.
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private long steamId;

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
        final String mime = firstNonNull(getContentResolver().getType(uri), "image/jpeg");
        final String fileName = displayName(uri, mime);
        toast("Sending image…");
        new Thread(new Runnable() {
            public void run() {
                String result;
                try {
                    byte[] bytes = readBytes(uri);
                    if (bytes == null) result = "Couldn't read image";
                    else {
                        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                        String payload = new JSONObject()
                                .put("steamId", steamId)
                                .put("fileName", fileName)
                                .put("mimeType", mime)
                                .put("bytesBase64", b64)
                                .toString();
                        String resp = BhSteamBridge.request("friends.upload_chat_image", payload, 30000);
                        result = (resp != null) ? "Image sent"
                                : "Image send failed · " + BhSteamBridge.getLastError();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "upload_chat_image failed", t);
                    result = "Image send failed";
                }
                final String r = result;
                runOnUiThread(new Runnable() { public void run() { toast(r); finish(); } });
            }
        }, "bh-steam-image-upload").start();
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

    private String displayName(Uri uri, String mime) {
        String name = null;
        try {
            android.database.Cursor c = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try { if (c.moveToFirst()) name = c.getString(0); } finally { c.close(); }
            }
        } catch (Throwable ignored) {}
        if (name == null || name.isEmpty()) {
            String ext = mime != null && mime.contains("/") ? mime.substring(mime.indexOf('/') + 1) : "jpg";
            name = "image_" + System.currentTimeMillis() + "." + ext;
        }
        return name;
    }

    private static String firstNonNull(String a, String b) { return a != null ? a : b; }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
    private void toastUi(final String s) {
        runOnUiThread(new Runnable() { public void run() { toast(s); } });
    }
}
