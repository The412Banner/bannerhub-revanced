package com.xj.winemu.audio;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * BhAudioSettingsActivity — global "Recording-compatible audio" toggle.
 *
 * Styled identically to {@code BhRendererSettingsActivity}: a dimmed window
 * over a rounded dark card with a bold title + subtitle, a Spinner for the
 * choice, a gray description, and Cancel/Save. Custom content view with
 * explicit colors on every widget (the activity runs under
 * Theme.Translucent.NoTitleBar, where un-styled widgets render invisibly).
 *
 * <p>GLOBAL, not per-game (subtitle "All games"). Mode 0 = Low latency
 * (stock, default), 1 = Recording-compatible (appends pm=0 → no MMAP →
 * captured by screen recording). Save commits to {@link BhAudioController}.
 */
public class BhAudioSettingsActivity extends Activity {

    private static final String[] MODE_LABELS = {
        "Low latency (default)", "Recording-compatible",
    };

    private float density = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        getWindow().setBackgroundDrawable(new ColorDrawable(0xCC000000));

        final BhAudioController ctl = BhAudioController.getInstance();
        ctl.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B1B1B);
        bg.setCornerRadius(dp(10));
        root.setBackground(bg);

        // Title row
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Audio");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView subtitle = new TextView(this);
        subtitle.setText("All games");
        subtitle.setTextColor(0xFFFFD54F);
        subtitle.setTextSize(11);
        subtitle.setSingleLine(true);
        subtitle.setMaxWidth(dp(150));
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(subtitle);

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(8);
        titleRow.setLayoutParams(titleLp);
        root.addView(titleRow);

        // Mode
        root.addView(label("Recording mode"));
        final Spinner modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(smallAdapter(MODE_LABELS));
        root.addView(modeSpinner);

        // Tip
        TextView desc = new TextView(this);
        desc.setText("Low latency = stock PulseAudio (default). "
                + "Recording-compatible lets Android screen recording capture "
                + "PulseAudio game audio — it adds a little audio latency, so "
                + "switch back when you're done recording. Takes effect next "
                + "launch; ALSA is unaffected.");
        desc.setTextColor(0xFF999999);
        desc.setTextSize(10);
        desc.setLayoutParams(topMargin(dp(8)));
        root.addView(desc);

        // Close row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setLayoutParams(topMargin(dp(8)));
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ctl.setRecordingMode(modeSpinner.getSelectedItemPosition() == 1);
                finish();
            }
        });
        btnRow.addView(cancel);
        btnRow.addView(save);
        root.addView(btnRow);

        // Restore current selection (no listener — Save is the single commit).
        modeSpinner.setSelection(ctl.isRecordingMode() ? 1 : 0);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.addView(root);

        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setBackgroundColor(0x00000000);
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int dialogW = Math.min(dp(340), (int) (screenW * 0.92f));
        FrameLayout.LayoutParams scLp = new FrameLayout.LayoutParams(
                dialogW, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.gravity = Gravity.CENTER;
        wrapper.addView(scroller, scLp);
        scroller.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        final ScrollView finalScroller = scroller;
        scroller.post(new Runnable() {
            @Override public void run() {
                if (finalScroller.getHeight() > maxH) {
                    ViewGroup.LayoutParams lp = finalScroller.getLayoutParams();
                    lp.height = maxH;
                    finalScroller.setLayoutParams(lp);
                }
            }
        });

        setContentView(wrapper);
    }

    private ArrayAdapter<String> smallAdapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextSize(12);
                t.setPadding(0, dp(3), 0, dp(3));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                return t;
            }
            @Override public View getDropDownView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getDropDownView(pos, cv, parent);
                t.setTextSize(12);
                t.setPadding(dp(10), dp(5), dp(10), dp(5));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setPadding(0, dp(4), 0, dp(2));
        return t;
    }

    private LinearLayout.LayoutParams topMargin(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px;
        return lp;
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }
}
