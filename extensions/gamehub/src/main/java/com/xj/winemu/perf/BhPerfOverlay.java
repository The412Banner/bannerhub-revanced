package com.xj.winemu.perf;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * BhPerfOverlay — Banner-owned in-game overlay for the two root performance
 * toggles. Classic Android Views (no Compose), attached to the running
 * WineActivity's decor view so it floats over the Wine game surface.
 *
 * UX: an edge pill ("⚡") parked on the right edge. Tap it to slide out a
 * compact panel with two switch rows (Sustained Performance Mode, Max Adreno
 * Clocks) plus a root-status line. Tap the pill again or outside the panel to
 * collapse. The pill is draggable vertically and remembers its Y in prefs.
 *
 * Root-gated: until root is granted the two rows are greyed (50% alpha,
 * non-interactive) and a "Grant root" affordance is shown; granting runs
 * {@code su -c id} once and caches the result.
 *
 * AUTO-REVERT: this class does not itself watch the lifecycle — the patch hooks
 * WineActivity.onDestroy to call {@link #revertAndDetach(Activity)}, which tells
 * {@link BhPerfController} to restore both hardware defaults.
 *
 * Entry points (called from smali patches):
 *   - attach(Activity)            : WineActivity.onCreate / onResume tail
 *   - revertAndDetach(Activity)   : WineActivity.onDestroy
 */
public final class BhPerfOverlay {

    private static final String TAG = "BhPerf";
    private static final int TAG_KEY = 0x7e9f0001; // marker tag to avoid double-attach

    // colors
    private static final int COL_PANEL_BG   = 0xF21A1D24; // ~95% dark
    private static final int COL_PILL_BG    = 0xF22A2E38;
    private static final int COL_ACCENT     = 0xFFFFC107; // amber
    private static final int COL_TEXT       = 0xFFEFEFEF;
    private static final int COL_SUBTEXT    = 0xFF9AA0AC;
    private static final int COL_TRACK_OFF  = 0xFF3A3F4B;
    private static final int COL_KNOB       = 0xFFEFEFEF;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BhPerfOverlay() {}

    // ── public smali entry points ───────────────────────────────────────────

    public static void attach(final Activity activity) {
        if (activity == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(new Runnable() {
                @Override public void run() { attach(activity); }
            });
            return;
        }
        try {
            ViewGroup root = rootView(activity);
            if (root == null) return;
            // Avoid double-attach if onCreate AND onResume both fire.
            if (root.findViewWithTag(TAG_KEY_OBJ) != null) return;
            View overlay = new Controller(activity).build();
            overlay.setTag(TAG_KEY_OBJ);
            root.addView(overlay);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "attach failed", t);
        }
    }

    public static void revertAndDetach(final Activity activity) {
        // Revert hardware regardless of UI thread (controller hops to worker).
        try {
            BhPerfController.get().revertAll(null, null);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "revert failed", t);
        }
        if (activity == null) return;
        Runnable detach = new Runnable() {
            @Override public void run() {
                try {
                    ViewGroup root = rootView(activity);
                    if (root == null) return;
                    View v = root.findViewWithTag(TAG_KEY_OBJ);
                    if (v != null) root.removeView(v);
                } catch (Throwable ignored) {}
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) detach.run();
        else activity.runOnUiThread(detach);
    }

    // A stable Object tag (findViewWithTag matches by equals()).
    private static final Object TAG_KEY_OBJ = "bh_perf_overlay_root";

    private static ViewGroup rootView(Activity a) {
        View dv = a.getWindow() != null ? a.getWindow().getDecorView() : null;
        if (dv instanceof ViewGroup) return (ViewGroup) dv;
        return null;
    }

    // ── overlay controller ──────────────────────────────────────────────────

    private static final class Controller {
        private final Activity act;
        private FrameLayout container;   // full-screen transparent host
        private LinearLayout panel;      // the slide-out panel
        private TextView pill;           // the edge tab
        private boolean expanded = false;

        // toggle rows
        private ToggleRow rowSustained;
        private ToggleRow rowMaxAdreno;
        private TextView rootLine;

        Controller(Activity a) { this.act = a; }

        View build() {
            container = new FrameLayout(act);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            // Let touches outside our widgets pass through to the game: the
            // container itself is not clickable; only pill/panel consume taps.
            container.setClickable(false);

            buildPanel();
            buildPill();

            // Tap-outside-to-collapse: a transparent catcher behind the panel,
            // only present while expanded.
            return container;
        }

        // pill --------------------------------------------------------------
        private void buildPill() {
            pill = new TextView(act);
            pill.setText("⚡");
            pill.setTextColor(COL_ACCENT);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            pill.setGravity(Gravity.CENTER);
            int w = dp(34), h = dp(54);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COL_PILL_BG);
            bg.setCornerRadii(new float[]{dp(10), dp(10), 0, 0, 0, 0, dp(10), dp(10)});
            pill.setBackground(bg);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
            lp.gravity = Gravity.END | Gravity.TOP;
            lp.topMargin = BhPerfController.get().getPillY(act, dp(120));
            pill.setLayoutParams(lp);

            pill.setOnTouchListener(new PillTouch(lp));
            container.addView(pill);
        }

        // panel -------------------------------------------------------------
        private void buildPanel() {
            panel = new LinearLayout(act);
            panel.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COL_PANEL_BG);
            bg.setCornerRadii(new float[]{dp(14), dp(14), 0, 0, 0, 0, dp(14), dp(14)});
            panel.setBackground(bg);
            panel.setPadding(dp(16), dp(14), dp(16), dp(14));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    dp(248), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.END | Gravity.TOP;
            lp.topMargin = dp(96);
            lp.rightMargin = dp(34); // sit left of the pill
            panel.setLayoutParams(lp);

            // header
            TextView header = new TextView(act);
            header.setText("BANNER PERFORMANCE");
            header.setTextColor(COL_ACCENT);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            header.setLetterSpacing(0.08f);
            header.setPadding(0, 0, 0, dp(10));
            panel.addView(header);

            rowSustained = new ToggleRow(
                    act,
                    "Sustained Performance",
                    "Lock CPU cores to max (no downclock)",
                    BhPerfController.get().isSustainedApplied(),
                    new ToggleRow.OnToggle() {
                        @Override public void onToggle(boolean want) { onSustained(want); }
                    });
            panel.addView(rowSustained.view);

            panel.addView(divider());

            rowMaxAdreno = new ToggleRow(
                    act,
                    "Max Adreno Clocks",
                    "Pin GPU clock to its ceiling",
                    BhPerfController.get().isMaxAdrenoApplied(),
                    new ToggleRow.OnToggle() {
                        @Override public void onToggle(boolean want) { onMaxAdreno(want); }
                    });
            panel.addView(rowMaxAdreno.view);

            panel.addView(divider());

            rootLine = new TextView(act);
            rootLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            rootLine.setPadding(0, dp(8), 0, 0);
            rootLine.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onRootLineTap(); }
            });
            panel.addView(rootLine);

            refreshRootUi();

            panel.setVisibility(View.GONE);
            container.addView(panel);
        }

        private View divider() {
            View d = new View(act);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lp.topMargin = dp(8);
            lp.bottomMargin = dp(2);
            d.setLayoutParams(lp);
            d.setBackgroundColor(0x14FFFFFF);
            return d;
        }

        // root gating -------------------------------------------------------
        private boolean rootGranted() {
            return BhPerfController.get().isRootGranted(act);
        }

        private void refreshRootUi() {
            boolean ok = rootGranted();
            float alpha = ok ? 1f : 0.5f;
            rowSustained.setEnabled(ok, alpha);
            rowMaxAdreno.setEnabled(ok, alpha);
            if (ok) {
                rootLine.setText("Root granted ✓");
                rootLine.setTextColor(COL_SUBTEXT);
            } else {
                rootLine.setText("⚠ Root required — tap to grant");
                rootLine.setTextColor(COL_ACCENT);
            }
        }

        private void onRootLineTap() {
            if (rootGranted()) return;
            rootLine.setText("Requesting root…");
            rootLine.setTextColor(COL_SUBTEXT);
            BhPerfController.get().requestRootGrant(act, MAIN,
                    new BhPerfController.ResultCallback() {
                        @Override public void onResult(boolean ok) {
                            refreshRootUi();
                            if (!ok) toast("Root denied or unavailable");
                        }
                    });
        }

        // toggle handlers ---------------------------------------------------
        private void onSustained(final boolean want) {
            if (!rootGranted()) { rowSustained.setChecked(false); onRootLineTap(); return; }
            BhPerfController.get().setSustained(want, MAIN,
                    new BhPerfController.ResultCallback() {
                        @Override public void onResult(boolean ok) {
                            if (!ok) { rowSustained.setChecked(!want); toast("Sustained Perf failed"); }
                        }
                    });
        }

        private void onMaxAdreno(final boolean want) {
            if (!rootGranted()) { rowMaxAdreno.setChecked(false); onRootLineTap(); return; }
            BhPerfController.get().setMaxAdreno(want, MAIN,
                    new BhPerfController.ResultCallback() {
                        @Override public void onResult(boolean ok) {
                            if (!ok) { rowMaxAdreno.setChecked(!want); toast("Max Adreno failed"); }
                        }
                    });
        }

        private void toast(String m) {
            try { Toast.makeText(act, m, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
        }

        // expand/collapse ---------------------------------------------------
        private void setExpanded(boolean exp) {
            expanded = exp;
            panel.setVisibility(exp ? View.VISIBLE : View.GONE);
            if (exp) refreshRootUi();
        }

        // pill drag + tap ---------------------------------------------------
        private final class PillTouch implements View.OnTouchListener {
            private final FrameLayout.LayoutParams lp;
            private float downRawY;
            private int downMargin;
            private boolean dragged;
            PillTouch(FrameLayout.LayoutParams lp) { this.lp = lp; }

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = e.getRawY();
                        downMargin = lp.topMargin;
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dy = (int) (e.getRawY() - downRawY);
                        if (Math.abs(dy) > dp(6)) dragged = true;
                        int ny = downMargin + dy;
                        if (ny < 0) ny = 0;
                        int max = container.getHeight() - v.getHeight();
                        if (max > 0 && ny > max) ny = max;
                        lp.topMargin = ny;
                        v.setLayoutParams(lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragged) {
                            BhPerfController.get().setPillY(act, lp.topMargin);
                            // keep panel aligned roughly with pill next open
                            alignPanelTo(lp.topMargin);
                        } else {
                            setExpanded(!expanded);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        }

        private void alignPanelTo(int pillTop) {
            try {
                FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) panel.getLayoutParams();
                int want = pillTop - dp(40);
                plp.topMargin = Math.max(dp(8), want);
                panel.setLayoutParams(plp);
            } catch (Throwable ignored) {}
        }

        private int dp(int v) { return BhPerfOverlay.dp(act, v); }
    }

    // ── reusable switch row ─────────────────────────────────────────────────

    private static final class ToggleRow {
        interface OnToggle { void onToggle(boolean want); }

        final LinearLayout view;
        private final SwitchView sw;
        private boolean enabled = true;

        ToggleRow(android.content.Context ctx, String title, String subtitle,
                  boolean checked, final OnToggle cb) {
            view = new LinearLayout(ctx);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            int padV = dp(ctx, 8);
            view.setPadding(0, padV, 0, padV);

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            texts.setLayoutParams(tlp);

            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextColor(COL_TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            texts.addView(t);

            TextView s = new TextView(ctx);
            s.setText(subtitle);
            s.setTextColor(COL_SUBTEXT);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            texts.addView(s);

            view.addView(texts);

            sw = new SwitchView(ctx, checked);
            view.addView(sw.view);

            view.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!enabled) return;
                    boolean want = !sw.isChecked();
                    sw.setChecked(want);
                    cb.onToggle(want);
                }
            });
        }

        void setChecked(boolean c) { sw.setChecked(c); }
        boolean isChecked() { return sw.isChecked(); }

        void setEnabled(boolean en, float alpha) {
            this.enabled = en;
            view.setAlpha(alpha);
            sw.view.setAlpha(alpha);
        }
    }

    // ── tiny custom switch (no AppCompat dependency) ────────────────────────

    private static final class SwitchView {
        final FrameLayout view;
        private final View knob;
        private boolean checked;

        SwitchView(android.content.Context ctx, boolean checked) {
            this.checked = checked;
            int w = dp(ctx, 44), h = dp(ctx, 24), pad = dp(ctx, 3);
            view = new FrameLayout(ctx);
            view.setLayoutParams(new LinearLayout.LayoutParams(w, h));
            knob = new View(ctx);
            int k = h - pad * 2;
            FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(k, k);
            klp.topMargin = pad;
            klp.leftMargin = pad;
            knob.setLayoutParams(klp);
            GradientDrawable kbg = new GradientDrawable();
            kbg.setColor(COL_KNOB);
            kbg.setShape(GradientDrawable.OVAL);
            knob.setBackground(kbg);
            view.addView(knob);
            render();
        }

        boolean isChecked() { return checked; }

        void setChecked(boolean c) {
            if (c == checked) return;
            checked = c;
            render();
        }

        private void render() {
            GradientDrawable track = new GradientDrawable();
            track.setColor(checked ? COL_ACCENT : COL_TRACK_OFF);
            track.setCornerRadius(view.getLayoutParams().height / 2f);
            view.setBackground(track);
            FrameLayout.LayoutParams klp = (FrameLayout.LayoutParams) knob.getLayoutParams();
            int w = view.getLayoutParams().width;
            int k = knob.getLayoutParams().width;
            int pad = klp.topMargin;
            klp.leftMargin = checked ? (w - k - pad) : pad;
            knob.setLayoutParams(klp);
        }
    }

    // ── dp helper ───────────────────────────────────────────────────────────

    private static int dp(android.content.Context ctx, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }
}
