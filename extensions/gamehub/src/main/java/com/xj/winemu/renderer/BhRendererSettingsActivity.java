package com.xj.winemu.renderer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * BhRendererSettingsActivity — per-game renderer choice.
 *
 * Mode: New (Vulkan, 6.0.4 stock) | Legacy (GLES2, 6.0.2 pair). Compact
 * dialog styled like {@code BhGpuSpoofSettingsActivity}.
 */
public class BhRendererSettingsActivity extends Activity {

    public static final String EXTRA_GAME_ID   = "gameId";
    public static final String EXTRA_GAME_NAME = "gameName";

    private static final String[] MODE_LABELS = {
        "New (Vulkan) — default", "Legacy (GLES2)",
    };

    private float density = 1f;

    public static void launch(Context ctx, String gameId, String gameName) {
        Intent it = new Intent(ctx, BhRendererSettingsActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (gameId != null)   it.putExtra(EXTRA_GAME_ID, gameId);
        if (gameName != null) it.putExtra(EXTRA_GAME_NAME, gameName);
        ctx.startActivity(it);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        getWindow().setBackgroundDrawable(new ColorDrawable(0xCC000000));

        String gameId   = getIntent() != null ? getIntent().getStringExtra(EXTRA_GAME_ID)   : null;
        String gameName = getIntent() != null ? getIntent().getStringExtra(EXTRA_GAME_NAME) : null;

        final BhRendererController ctl = BhRendererController.getInstance();
        ctl.init(this);
        ctl.setContainerForSettings(gameId);

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
        title.setText("Renderer");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView subtitle = new TextView(this);
        if (gameName != null && !gameName.isEmpty())  subtitle.setText(gameName);
        else if (gameId != null && !gameId.isEmpty()) subtitle.setText("Game " + gameId);
        else                                          subtitle.setText("Global");
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
        root.addView(label("Renderer"));
        final Spinner modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(smallAdapter(MODE_LABELS));
        root.addView(modeSpinner);

        // Warning / tip
        TextView desc = new TextView(this);
        desc.setText("New = stock 6.0.4 Vulkan (default). Legacy = the older "
                + "6.0.2 GLES2 renderer; can help some titles, but AI "
                + "frame-gen, HDR and deep GPU-spoof are inactive in Legacy, "
                + "and some 32-bit GFWL games may not start. Per-game; "
                + "takes effect next launch.");
        desc.setTextColor(0xFF999999);
        desc.setTextSize(10);
        desc.setLayoutParams(topMargin(dp(8)));
        root.addView(desc);

        // Close
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setLayoutParams(topMargin(dp(8)));
        // Cancel = discard; Save = single per-game commit. No live writes,
        // so the seed-time setSelection() callback can't persist a default.
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ctl.setMode(clampMode(modeSpinner.getSelectedItemPosition()));
                finish();
            }
        });
        btnRow.addView(cancel);
        btnRow.addView(save);
        root.addView(btnRow);

        // Restore only — no listener (mode spinner has no UI side-effect;
        // persistence is the Save button's single commit).
        modeSpinner.setSelection(clampMode(ctl.getMode()));

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

    private int clampMode(int v) {
        if (v < 0 || v > BhRendererController.MODE_MAX) return 0;
        return v;
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }
}
