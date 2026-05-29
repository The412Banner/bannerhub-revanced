package com.xj.winemu.audio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Compact global "Recording-compatible audio" toggle. Launched by
 * {@link BhAudioMenuRowClick} from the Banner Tools dialog's "Audio" tile.
 *
 * <p>A single Switch + one-line description (built programmatically — we
 * can't ship an XML layout into the foreign GameHub package), bound to
 * {@link BhAudioController}. Translucent activity (theme set in the manifest
 * by {@code audioManifestPatch}); {@code finish()} on dismiss.
 */
public final class BhAudioSettingsActivity extends Activity {

    private static final String TAG = "BhAudioSettings";

    private static final String DESC =
        "Lets screen recording capture PulseAudio game audio. Adds a little "
      + "audio latency — turn it off when you're not recording. Applies "
      + "on next launch. ALSA is unaffected.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            final BhAudioController ctrl = BhAudioController.getInstance();
            ctrl.init(getApplicationContext());

            new AlertDialog.Builder(this)
                .setTitle("PulseAudio recording mode")
                .setView(buildContent(ctrl))
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

    private View buildContent(final BhAudioController ctrl) {
        final float density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(density, 24);
        root.setPadding(padH, dp(density, 12), padH, 0);

        // Row: label (weighted) + switch.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("Recording-compatible audio");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(lblLp);

        Switch sw = new Switch(this);
        sw.setChecked(ctrl.isRecordingMode());
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                ctrl.setRecordingMode(checked);
            }
        });

        row.addView(label);
        row.addView(sw);
        root.addView(row);

        // Sub-description.
        TextView desc = new TextView(this);
        desc.setText(DESC);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setTextColor(Color.parseColor("#99FFFFFF"));
        desc.setPadding(0, dp(density, 8), 0, 0);
        root.addView(desc);

        return root;
    }

    private static int dp(float density, int v) {
        return Math.round(density * v);
    }
}
