package com.xj.winemu.steamchat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * Transparent, internal-only helper that lets the Steam chat call-settings screen
 * pick a custom MP3 as the incoming-call ringtone. The overlay is a WindowManager
 * view with no Activity-result plumbing, so it delegates the pick here:
 * ACTION_OPEN_DOCUMENT (so we can take a persistable read grant that survives
 * reboots), then stores {@code "uri:<content-uri>"} as the ringtone selection and
 * finishes. Registered (exported=false) by steamChatRingtonePickerManifestPatch.
 */
public final class BhRingtonePickerActivity extends Activity {

    private static final String TAG = "BhSteamChat";
    private static final int REQ_PICK = 0xB403;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("audio/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{ "audio/mpeg", "audio/*" });
            startActivityForResult(Intent.createChooser(pick, "Pick a ringtone MP3"), REQ_PICK);
        } catch (Throwable t) {
            Log.w(TAG, "ringtone chooser failed", t);
            toast("No audio picker available");
            finish();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable t) {
                Log.w(TAG, "persist uri permission failed", t);
            }
            BhSteamChatController.get().setRingtone(getApplicationContext(), "uri:" + uri.toString());
            toast("Custom ringtone set");
        }
        finish();
    }

    private void toast(final String msg) {
        try { Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
}
