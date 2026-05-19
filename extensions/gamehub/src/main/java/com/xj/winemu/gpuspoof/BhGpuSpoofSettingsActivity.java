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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * BhGpuSpoofSettingsActivity — per-game GPU-identity spoof dialog.
 *
 * Mode: Off | Spoof a GPU | Custom.
 *
 * "Spoof a GPU" shows two cascading spinners (Option 1 UX): a Vendor spinner
 * (NVIDIA / AMD / Intel) and a Model spinner that repopulates with just that
 * vendor's cards from {@link BhGpuCards} (313 entries, the GameNative/Winlator
 * list + a modern RTX/RX/Arc set). Picking a model writes its vendor/device
 * hex + name into the stock {@code pc_g_setting<gameId>} prefs via the same
 * {@code bh_gpuspoof_*} keys Custom uses — so storage/export is unchanged and
 * Spoof vs Custom differ only in which editor is shown.
 *
 * No ListView / no eager 313-view inflation — native Spinner popups only,
 * keeping the dialog ANR-safe on slow devices.
 */
public class BhGpuSpoofSettingsActivity extends Activity {

    public static final String EXTRA_GAME_ID   = "gameId";
    public static final String EXTRA_GAME_NAME = "gameName";

    private static final String[] MODE_LABELS = { "Off", "Spoof a GPU", "Custom" };

    private float density = 1f;

    /**
     * Vendor index the Model spinner was last (re)built for. Spinner.setSelection()
     * fires onItemSelected asynchronously on the next layout pass — AFTER we
     * attach the listener — so the restore-time vendorSpinner.setSelection(vSel)
     * still triggers a spurious callback that would rebuild the model list with
     * sel=0 and lose the restored card. Guarding the listener on an actual
     * vendor change makes that deferred callback a no-op (vIdx == lastVendorIdx),
     * regardless of listener-attach timing.
     */
    private int lastVendorIdx = -1;


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
        title.setText("GPU Spoof");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView subtitle = new TextView(this);
        if (gameName != null && !gameName.isEmpty())      subtitle.setText(gameName);
        else if (gameId != null && !gameId.isEmpty())     subtitle.setText("Game " + gameId);
        else                                              subtitle.setText("Global");
        subtitle.setTextColor(0xFFFFD54F);
        subtitle.setTextSize(11);
        subtitle.setSingleLine(true);
        subtitle.setMaxWidth(dp(140));
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(subtitle);

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(8);
        titleRow.setLayoutParams(titleLp);
        root.addView(titleRow);

        // Mode
        root.addView(label("Mode"));
        final Spinner modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(smallAdapter(MODE_LABELS));
        root.addView(modeSpinner);

        // ── Spoof box: cascading Vendor → Model ──────────────────────────
        final LinearLayout spoofBox = new LinearLayout(this);
        spoofBox.setOrientation(LinearLayout.VERTICAL);
        spoofBox.setLayoutParams(topMargin(dp(6)));

        spoofBox.addView(label("Vendor"));
        final Spinner vendorSpinner = new Spinner(this);
        String[] vendorLabels = new String[BhGpuCards.VENDOR_LABEL.length];
        for (int i = 0; i < vendorLabels.length; i++) {
            vendorLabels[i] = BhGpuCards.VENDOR_LABEL[i]
                    + "  (" + BhGpuCards.CARDS[i].length + " cards)";
        }
        vendorSpinner.setAdapter(smallAdapter(vendorLabels));
        spoofBox.addView(vendorSpinner);

        spoofBox.addView(label("Model"));
        final Spinner modelSpinner = new Spinner(this);
        spoofBox.addView(modelSpinner);
        root.addView(spoofBox);

        // ── Custom box: vendor / device / name ───────────────────────────
        final LinearLayout customBox = new LinearLayout(this);
        customBox.setOrientation(LinearLayout.VERTICAL);
        customBox.setLayoutParams(topMargin(dp(6)));

        final EditText vendorIn = hexField("Vendor ID (hex, e.g. 10de)", ctl.getVendor());
        final EditText deviceIn = hexField("Device ID (hex, e.g. 1c03)", ctl.getDevice());
        final EditText nameIn   = new EditText(this);
        nameIn.setHint("Adapter name (optional)");
        nameIn.setText(ctl.getName());
        nameIn.setTextSize(12);
        nameIn.setSingleLine(true);
        styleField(nameIn);
        customBox.addView(vendorIn);
        customBox.addView(deviceIn);
        customBox.addView(nameIn);
        root.addView(customBox);

        // Deep (DX12 / Vulkan) opt-in — visible whenever a spoof is on.
        final CheckBox deepCheck = new CheckBox(this);
        deepCheck.setText("Also spoof DX12 / Vulkan games (turns off frame-gen for this game)");
        deepCheck.setTextColor(Color.WHITE);
        deepCheck.setTextSize(11);
        deepCheck.setChecked(ctl.getDeep());
        deepCheck.setLayoutParams(topMargin(dp(8)));
        root.addView(deepCheck);

        // One-line tip
        TextView desc = new TextView(this);
        desc.setText("Overrides the GPU vendor/device games see (DXVK). Fixes "
                + "CryEngine \"Unsupported video card\". Saves to this game's PC config.");
        desc.setTextColor(0xFF999999);
        desc.setTextSize(10);
        desc.setLayoutParams(topMargin(dp(6)));
        root.addView(desc);

        // Close
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setLayoutParams(topMargin(dp(6)));
        // Cancel = discard (nothing persisted). Save = single atomic commit
        // of the current UI selection into the per-game store. No live writes
        // anymore, so the seed-time spinner callbacks can't persist a default.
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                persistForMode(ctl, modeSpinner.getSelectedItemPosition(),
                        vendorSpinner, modelSpinner, vendorIn, deviceIn, nameIn);
                ctl.setDeep(deepCheck.isChecked());
                finish();
            }
        });
        btnRow.addView(cancel);
        btnRow.addView(save);
        root.addView(btnRow);

        // ── Wiring ───────────────────────────────────────────────────────

        // ── Restore persisted state FIRST (no listeners attached yet, so the
        // programmatic setSelection() calls below can't trigger a callback
        // that resets the model spinner to 0 and loses the saved model). ──
        int mode = clampMode(ctl.getMode());
        int vSel = 0, mSel = 0;
        int[] loc = BhGpuCards.locate(ctl.getVendor(), ctl.getDevice());
        if (loc != null) {
            vSel = loc[0];
            mSel = loc[1] >= 0 ? loc[1] : 0;
        }
        vendorSpinner.setSelection(vSel);
        rebuildModels(modelSpinner, vSel, mSel);
        spoofBox.setVisibility(mode == BhGpuSpoofController.MODE_SPOOF
                ? View.VISIBLE : View.GONE);
        customBox.setVisibility(mode == BhGpuSpoofController.MODE_CUSTOM
                ? View.VISIBLE : View.GONE);
        deepCheck.setVisibility(mode == BhGpuSpoofController.MODE_OFF
                ? View.GONE : View.VISIBLE);
        modeSpinner.setSelection(mode);

        // ── THEN attach UI-only listeners (NO persistence — Save commits).
        // Vendor change rebuilds the Model list; Mode change toggles which
        // editor is visible. Model spinner / Deep checkbox have no UI
        // side-effect so they carry no listener.
        vendorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View vw, int vIdx, long id) {
                // Ignore the deferred callback from the restore-time
                // setSelection(vSel): the model list is already built for
                // this vendor with the saved card selected. Only a real
                // vendor change (different index) resets the model to 0.
                if (vIdx == lastVendorIdx) return;
                rebuildModels(modelSpinner, vIdx, 0);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View vw, int pos, long id) {
                spoofBox.setVisibility(pos == BhGpuSpoofController.MODE_SPOOF
                        ? View.VISIBLE : View.GONE);
                customBox.setVisibility(pos == BhGpuSpoofController.MODE_CUSTOM
                        ? View.VISIBLE : View.GONE);
                deepCheck.setVisibility(pos == BhGpuSpoofController.MODE_OFF
                        ? View.GONE : View.VISIBLE);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

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

    /** Repopulates the Model spinner for a vendor and selects {@code sel}. */
    private void rebuildModels(Spinner modelSpinner, int vendorIdx, int sel) {
        lastVendorIdx = vendorIdx;
        String[] models = BhGpuCards.modelNames(vendorIdx);
        modelSpinner.setAdapter(smallAdapter(models));
        if (sel < 0 || sel >= models.length) sel = 0;
        if (models.length > 0) modelSpinner.setSelection(sel);
    }

    /** Persists the picked card's vendor/device/name (Spoof mode). */
    private void writeCard(BhGpuSpoofController ctl, int vendorIdx, int modelIdx) {
        if (vendorIdx < 0 || vendorIdx >= BhGpuCards.CARDS.length) return;
        String[][] list = BhGpuCards.CARDS[vendorIdx];
        if (modelIdx < 0 || modelIdx >= list.length) return;
        ctl.setCustom(
                BhGpuCards.VENDOR_HEX[vendorIdx],
                list[modelIdx][0],
                list[modelIdx][1]);
    }

    private void persistForMode(BhGpuSpoofController ctl, int mode,
                                Spinner vendorSpinner, Spinner modelSpinner,
                                EditText vendorIn, EditText deviceIn, EditText nameIn) {
        ctl.setMode(clampMode(mode));
        if (mode == BhGpuSpoofController.MODE_SPOOF) {
            writeCard(ctl, vendorSpinner.getSelectedItemPosition(),
                    modelSpinner.getSelectedItemPosition());
        } else if (mode == BhGpuSpoofController.MODE_CUSTOM) {
            persistCustom(ctl, vendorIn, deviceIn, nameIn);
        }
    }

    private void persistCustom(BhGpuSpoofController ctl,
                               EditText vendor, EditText device, EditText name) {
        ctl.setCustom(
                vendor.getText().toString(),
                device.getText().toString(),
                name.getText().toString());
    }

    /**
     * Compact spinner adapter — small text + tight row padding for both the
     * collapsed control and the dropdown list, so the 313-entry Model spinner
     * (and Mode/Vendor) take far less vertical space than the stock chunky rows.
     */
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

    private EditText hexField(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(12);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        styleField(e);
        return e;
    }

    /**
     * Dark rounded background + readable colors + padding for a Custom-mode
     * input. The host theme gives EditText a light/white background, so the
     * dialog's white text was white-on-white (invisible). This makes the
     * fields legible and gives them breathing room.
     */
    private void styleField(EditText e) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF2A2A2A);
        g.setCornerRadius(dp(6));
        g.setStroke(dp(1), 0xFF4A4A4A);
        e.setBackground(g);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF8A8A8A);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        e.setLayoutParams(lp);
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
