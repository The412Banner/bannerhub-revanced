package com.xj.winemu.audio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;

/**
 * Tiny translucent settings screen for the global "Recording-compatible
 * audio" toggle. Launched by {@link BhAudioMenuRowClick} from the Banner
 * Tools dialog's "Audio" tile. Shows a 2-choice list (Low latency /
 * Recording-compatible) bound to {@link BhAudioController}.
 *
 * <p>Registered in the manifest by {@code audioManifestPatch} with the
 * Translucent.NoTitleBar theme, so the dialog floats over whatever is behind
 * it. {@code finish()} on dismiss.
 */
public final class BhAudioSettingsActivity extends Activity {

    private static final String TAG = "BhAudioSettings";

    private static final String[] OPTIONS = new String[] {
        "Low latency (default)",
        "Recording-compatible",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            final BhAudioController ctrl = BhAudioController.getInstance();
            ctrl.init(getApplicationContext());
            int current = ctrl.isRecordingMode() ? 1 : 0;

            new AlertDialog.Builder(this)
                .setTitle("PulseAudio recording mode")
                .setSingleChoiceItems(OPTIONS, current, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        ctrl.setRecordingMode(which == 1);
                    }
                })
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("What's this?", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        showInfo();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) { finish(); }
                })
                .show();
        } catch (Throwable t) {
            Log.w(TAG, "onCreate failed", t);
            finish();
        }
    }

    private void showInfo() {
        try {
            new AlertDialog.Builder(this)
                .setTitle("Recording-compatible audio")
                .setMessage(
                    "With the PulseAudio driver, Android screen recording "
                  + "captures video but NO sound, because PulseAudio uses a "
                  + "low-latency audio path the recorder can't tap.\n\n"
                  + "Turn on \"Recording-compatible\" before recording. It "
                  + "adds a little audio latency, so turn it back off when "
                  + "you're done.\n\n"
                  + "Takes effect on the next game launch. The ALSA driver "
                  + "always records fine and is unaffected.")
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) { finish(); }
                })
                .show();
        } catch (Throwable t) {
            Log.w(TAG, "showInfo failed", t);
            finish();
        }
    }
}
