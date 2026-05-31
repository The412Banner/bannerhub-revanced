package com.xj.winemu.perf;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.WeakHashMap;

/**
 * BhPerfOverlay — Banner-owned in-game overlay for the two root performance
 * toggles. Classic Android Views (no Compose).
 *
 * UX: an edge pill ("⚡") parked on the right edge over the Wine game surface.
 * Tap it to slide out a compact panel with two switch rows (Sustained
 * Performance Mode, Max Adreno Clocks) plus a root-status line. Tap the pill
 * again, or anywhere outside, to collapse. The pill is draggable vertically and
 * remembers its Y in prefs.
 *
 * WINDOW STRATEGY — WHY NOT A DECOR-VIEW CHILD: WineActivity OVERRIDES
 * dispatchTouchEvent and routes touches into the game's input pipeline, so a
 * View added as a child of its decor view renders but never receives taps/drags
 * (confirmed by decompile: WineActivity.dispatchTouchEvent is a real override).
 * Instead we add the overlay as its OWN sub-window via WindowManager, attached
 * to the activity's window token. A separate window gets its own input channel
 * straight from the system, independent of WineActivity's dispatch override, so
 * the pill/panel receive touches. FLAG_NOT_TOUCH_MODAL + WRAP_CONTENT sizing
 * means only the pill/panel area is touchable; the rest of the screen still
 * reaches the game. No SYSTEM_ALERT_WINDOW permission needed — it's an
 * application sub-window bound to the host's window token.
 *
 * Root-gated: until root is granted the two rows are greyed (50% alpha,
 * non-interactive) and a "Grant root" affordance is shown.
 *
 * AUTO-REVERT: the patch hooks WineActivity.onDestroy to call
 * {@link #revertAndDetach(Activity)}, which restores both hardware defaults and
 * removes the window.
 *
 * Master toggle: {@link BhPerfController#isOverlayEnabled} (Banner Tools →
 * In-game Performance Overlay) gates whether attach adds the window at all.
 *
 * Entry points (called from smali patches):
 *   - attach(Activity)            : WineActivity.onResume tail
 *   - revertAndDetach(Activity)   : WineActivity.onDestroy
 */
public final class BhPerfOverlay {

    private static final String TAG = "BhPerf";

    // colors
    private static final int COL_PANEL_BG   = 0xF21A1D24; // ~95% dark
    private static final int COL_PILL_BG    = 0xF22A2E38;
    private static final int COL_ACCENT     = 0xFFFFC107; // amber
    private static final int COL_TEXT       = 0xFFEFEFEF;
    private static final int COL_SUBTEXT    = 0xFF9AA0AC;
    private static final int COL_TRACK_OFF  = 0xFF3A3F4B;
    private static final int COL_KNOB       = 0xFFEFEFEF;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // One overlay window per running WineActivity. WeakHashMap so a destroyed
    // activity we somehow miss can't leak.
    private static final WeakHashMap<Activity, Controller> sOverlays =
            new WeakHashMap<>();

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
            boolean enabled = BhPerfController.get().isOverlayEnabled(activity);
            Controller existing = sOverlays.get(activity);

            // Master toggle OFF → ensure no window is present and bail. Toggling
            // back ON re-attaches on the next onResume (the "live" behaviour).
            if (!enabled) {
                if (existing != null) {
                    existing.detach();
                    sOverlays.remove(activity);
                }
                return;
            }
            // Already attached (onResume can fire repeatedly) → nothing to do.
            if (existing != null && existing.isAttached()) return;

            final Controller c = new Controller(activity);
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            if (decor == null) {
                android.util.Log.w(TAG, "attach: no decor view");
                return;
            }
            // CRITICAL: a WindowManager sub-window needs the decor's window
            // token, but on an activity's FIRST onResume the decor isn't added
            // to the WindowManager yet (ActivityThread adds it AFTER onResume),
            // so getWindowToken() is null and the add silently fails. Defer to
            // after the current traversal, by which point the token is valid.
            final Runnable doAttach = new Runnable() {
                @Override public void run() {
                    Controller cur = sOverlays.get(activity);
                    if (cur != null && cur.isAttached()) return; // raced
                    if (c.attachToWindow()) sOverlays.put(activity, c);
                }
            };
            if (decor.getWindowToken() != null) {
                doAttach.run();
            } else {
                android.util.Log.i(TAG, "attach: token not ready, deferring");
                decor.post(doAttach);
            }
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
                    Controller c = sOverlays.remove(activity);
                    if (c != null) c.detach();
                } catch (Throwable ignored) {}
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) detach.run();
        else activity.runOnUiThread(detach);
    }

    // ── overlay controller ──────────────────────────────────────────────────

    private static final class Controller {
        private final Activity act;
        private WindowManager wm;
        private WindowManager.LayoutParams params;
        private LinearLayout container;  // horizontal: [panel][pill]
        private LinearLayout panel;      // the slide-out panel (GONE when collapsed)
        private TextView pill;           // the edge tab
        private boolean expanded = false;
        private boolean attached = false;

        // toggle rows
        private ToggleRow rowSustained;
        private ToggleRow rowMaxAdreno;
        private TextView rootLine;

        Controller(Activity a) { this.act = a; }

        boolean isAttached() { return attached; }

        boolean attachToWindow() {
            try {
                wm = act.getWindowManager();
                if (wm == null) return false;
                IBinder token = act.getWindow() != null
                        && act.getWindow().getDecorView() != null
                        ? act.getWindow().getDecorView().getWindowToken() : null;
                if (token == null) {
                    android.util.Log.w(TAG, "attachToWindow: window token still null");
                    return false; // decor not attached yet
                }

                container = new LinearLayout(act);
                container.setOrientation(LinearLayout.HORIZONTAL);
                container.setGravity(Gravity.CENTER_VERTICAL);

                buildPanel();   // added first → sits LEFT of the pill
                buildPill();    // added second → right edge

                // Collapse when a touch lands outside our window (the game area).
                container.setOnTouchListener(new View.OnTouchListener() {
                    @Override public boolean onTouch(View v, MotionEvent e) {
                        if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE && expanded) {
                            setExpanded(false);
                        }
                        return false; // never consume; children handle their own
                    }
                });

                params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.END;
                params.token = token;
                params.y = BhPerfController.get().getPillY(act, dp(120));

                wm.addView(container, params);
                attached = true;
                android.util.Log.i(TAG, "overlay window attached (y=" + params.y + ")");
                return true;
            } catch (Throwable t) {
                android.util.Log.w(TAG, "attachToWindow failed", t);
                return false;
            }
        }

        void detach() {
            if (!attached) return;
            attached = false;
            try {
                if (wm != null && container != null) wm.removeView(container);
            } catch (Throwable ignored) {}
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
            pill.setLayoutParams(new LinearLayout.LayoutParams(w, h));
            pill.setAlpha(opacityFraction());
            pill.setOnTouchListener(new PillTouch());
            container.addView(pill);
        }

        /** Stored pill opacity as an alpha fraction (0.05..1.0). */
        private float opacityFraction() {
            return BhPerfController.get().getPillOpacity(act) / 100f;
        }

        // panel -------------------------------------------------------------
        private void buildPanel() {
            panel = new LinearLayout(act);
            panel.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COL_PANEL_BG);
            bg.setCornerRadius(dp(14));
            panel.setBackground(bg);
            panel.setPadding(dp(16), dp(14), dp(16), dp(14));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(248), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            panel.setLayoutParams(lp);

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

            panel.addView(buildOpacityRow());

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

        // pill-opacity slider ----------------------------------------------
        private View buildOpacityRow() {
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            int padV = dp(6);
            col.setPadding(0, padV, 0, padV);

            final TextView label = new TextView(act);
            int pct = BhPerfController.get().getPillOpacity(act);
            label.setText("Pill opacity — " + pct + "%");
            label.setTextColor(COL_TEXT);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            col.addView(label);

            TextView hint = new TextView(act);
            hint.setText("Fade the pill so it doesn't block your view");
            hint.setTextColor(COL_SUBTEXT);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            col.addView(hint);

            final android.widget.SeekBar bar = new android.widget.SeekBar(act);
            // Map slider 0..(100-MIN) onto opacity MIN..100 so the pill can be
            // faded to nearly invisible but never fully disappears.
            final int min = BhPerfController.PILL_OPACITY_MIN;
            bar.setMax(100 - min);
            bar.setProgress(pct - min);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(4);
            bar.setLayoutParams(blp);
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(
                        android.widget.SeekBar sb, int progress, boolean fromUser) {
                    int p = progress + min;
                    label.setText("Pill opacity — " + p + "%");
                    if (pill != null) pill.setAlpha(p / 100f);
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                    BhPerfController.get().setPillOpacity(act, sb.getProgress() + min);
                }
            });
            col.addView(bar);
            return col;
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
            // Full-opacity pill while open (easy to tap closed); restore the
            // user's faded opacity when collapsed.
            if (pill != null) pill.setAlpha(exp ? 1f : opacityFraction());
            if (exp) refreshRootUi();
            // WRAP_CONTENT window must re-measure to grow/shrink with the panel.
            try {
                if (wm != null && container != null && attached) {
                    wm.updateViewLayout(container, params);
                }
            } catch (Throwable ignored) {}
        }

        // pill drag (move the window) + tap (expand) -------------------------
        private final class PillTouch implements View.OnTouchListener {
            private float downRawY;
            private int downY;
            private boolean dragged;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = e.getRawY();
                        downY = params.y;
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dy = (int) (e.getRawY() - downRawY);
                        if (Math.abs(dy) > dp(6)) dragged = true;
                        int ny = downY + dy;
                        if (ny < 0) ny = 0;
                        int max = act.getResources().getDisplayMetrics().heightPixels
                                - container.getHeight();
                        if (max > 0 && ny > max) ny = max;
                        params.y = ny;
                        try {
                            if (wm != null && attached) wm.updateViewLayout(container, params);
                        } catch (Throwable ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragged) {
                            BhPerfController.get().setPillY(act, params.y);
                        } else {
                            setExpanded(!expanded);
                        }
                        return true;
                    default:
                        return false;
                }
            }
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
