package com.xj.winemu.gpuspoof;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * BhGpuSpoofSettingsActivity — per-game GPU-identity spoof dialog.
 *
 * Mode: Off | GTX 1060 | GTX 1080 | RX 580 | UHD 630 | Custom.
 * When Custom is selected, vendor/device (hex) + an optional adapter-name
 * field become editable. Settings save immediately to this game's
 * {@code pc_g_setting<gameId>} file (export/import compatible), mirroring
 * {@code BhVibrationSettingsActivity}.
 */
public class BhGpuSpoofSettingsActivity extends Activity {

    public static final String EXTRA_GAME_ID   = "gameId";
    public static final String EXTRA_GAME_NAME = "gameName";

    private static final String[] MODE_LABELS = {
            "Off", "NVIDIA GTX 1060", "NVIDIA GTX 1080",
            "AMD RX 580", "Intel UHD 630", "Custom",
    };

    private float density = 1f;

    public static void launch(Context ctx, String gameId, String gameName) {
        Intent it = new Intent(ctx, BhGpuSpoofSettingsActivity.class);
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

        final BhGpuSpoofController ctl = BhGpuSpoofController.getInstance();
        ctl.init(this);
        ctl.setContainerForSettings(gameId);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B1B1B);
        bg.setCornerRadius(dp(12));
        root.setBackground(bg);

        // Title row
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("GPU Spoof");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView subtitle = new TextView(this);
        if (gameName != null && !gameName.isEmpty())      subtitle.setText(gameName);
        else if (gameId != null && !gameId.isEmpty())     subtitle.setText("Game " + gameId);
        else                                              subtitle.setText("Global");
        subtitle.setTextColor(0xFFFFD54F);
        subtitle.setTextSize(12);
        subtitle.setSingleLine(true);
        subtitle.setMaxWidth(dp(160));
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(subtitle);

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(10);
        titleRow.setLayoutParams(titleLp);
        root.addView(titleRow);

        // Mode
        TextView modeLabel = new TextView(this);
        modeLabel.setText("Reported GPU");
        modeLabel.setTextColor(Color.WHITE);
        modeLabel.setTextSize(13);
        modeLabel.setPadding(0, 0, 0, dp(4));
        root.addView(modeLabel);

        final Spinner modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, MODE_LABELS));
        modeSpinner.setSelection(clampMode(ctl.getMode()));
        root.addView(modeSpinner);

        // Custom fields (vendor / device / name) — shown only for Custom.
        final LinearLayout customBox = new LinearLayout(this);
        customBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = dp(10);
        customBox.setLayoutParams(cbLp);

        final EditText vendorIn = hexField("Vendor ID (hex, e.g. 10de)", ctl.getVendor());
        final EditText deviceIn = hexField("Device ID (hex, e.g. 1c03)", ctl.getDevice());
        final EditText nameIn   = new EditText(this);
        nameIn.setHint("Adapter name (optional)");
        nameIn.setText(ctl.getName());
        nameIn.setTextColor(Color.WHITE);
        nameIn.setHintTextColor(0xFF777777);
        nameIn.setTextSize(13);
        nameIn.setSingleLine(true);

        customBox.addView(vendorIn);
        customBox.addView(deviceIn);
        customBox.addView(nameIn);
        root.addView(customBox);
        customBox.setVisibility(clampMode(ctl.getMode()) == BhGpuSpoofController.MODE_CUSTOM
                ? View.VISIBLE : View.GONE);

        // One-line tip
        TextView desc = new TextView(this);
        desc.setText("Overrides the GPU vendor/device games see (DXVK). Fixes "
                + "CryEngine \"Unsupported video card\". Saves to this game's PC config.");
        desc.setTextColor(0xFF999999);
        desc.setTextSize(11);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(8);
        desc.setLayoutParams(descLp);
        root.addView(desc);

        // Close
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = dp(8);
        btnRow.setLayoutParams(btnRowLp);
        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                persistCustom(ctl, vendorIn, deviceIn, nameIn);
                finish();
            }
        });
        btnRow.addView(close);
        root.addView(btnRow);

        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                ctl.setMode(pos);
                customBox.setVisibility(pos == BhGpuSpoofController.MODE_CUSTOM
                        ? View.VISIBLE : View.GONE);
                if (pos == BhGpuSpoofController.MODE_CUSTOM) {
                    persistCustom(ctl, vendorIn, deviceIn, nameIn);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.addView(root);

        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setBackgroundColor(0x00000000);
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.85f);
        FrameLayout.LayoutParams scLp = new FrameLayout.LayoutParams(
                dp(480), ViewGroup.LayoutParams.WRAP_CONTENT);
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

    private void persistCustom(BhGpuSpoofController ctl,
                               EditText vendor, EditText device, EditText name) {
        ctl.setCustom(
                vendor.getText().toString(),
                device.getText().toString(),
                name.getText().toString());
    }

    private EditText hexField(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF777777);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    private int clampMode(int v) {
        if (v < 0) return 0;
        if (v > BhGpuSpoofController.MODE_MAX) return 0;
        return v;
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }
}
