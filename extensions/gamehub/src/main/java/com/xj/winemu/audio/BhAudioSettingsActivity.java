package com.xj.winemu.audio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;

/**
 * Global "Recording-compatible audio" toggle. Launched by
 * {@link BhAudioMenuRowClick} from the Banner Tools dialog's "Audio" tile.
 *
 * <p>Uses a single NATIVE checkable list row (not a custom widget view): the
 * activity runs under Theme.Translucent.NoTitleBar where a custom
 * {@code Switch} renders near-invisibly, whereas native AlertDialog items
 * render correctly (same as the other Banner Tools feature dialogs). One
 * compact, self-describing, tappable row bound to {@link BhAudioController}.
 * {@code finish()} on dismiss.
 */
public final class BhAudioSettingsActivity extends Activity {

    private static final String TAG = "BhAudioSettings";

    private static final CharSequence[] ITEMS = new CharSequence[] {
        "Recording-compatible audio\n"
      + "Fixes silent screen recordings with the PulseAudio driver. Adds a "
      + "little audio latency, so turn it off when you're not recording. "
      + "Applies on next launch; ALSA is unaffected."
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            final BhAudioController ctrl = BhAudioController.getInstance();
            ctrl.init(getApplicationContext());

            final boolean[] checked = new boolean[] { ctrl.isRecordingMode() };

            new AlertDialog.Builder(this)
                .setTitle("PulseAudio recording mode")
                .setMultiChoiceItems(ITEMS, checked,
                    new DialogInterface.OnMultiChoiceClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which, boolean isChecked) {
                            ctrl.setRecordingMode(isChecked);
                        }
                    })
                .setPositiveButton("Done", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) { finish(); }
                })
                .show();
        } catch (Throwable t) {
            Log.w(TAG, "onCreate failed", t);
            finish();
        }
    }
}
