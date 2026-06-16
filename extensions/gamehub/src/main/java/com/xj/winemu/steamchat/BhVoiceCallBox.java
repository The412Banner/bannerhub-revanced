package com.xj.winemu.steamchat;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Standalone, draggable voice-call window for the in-game Steam chat overlay.
 *
 * <p>This is its own {@link WindowManager} overlay, independent of the chat
 * panel, so a call (and an incoming-call prompt) surfaces over the game even
 * when the chat pill is collapsed. It is a pure view: it renders one of a few
 * call states and reports button taps back through {@link Actions}; the overlay
 * {@code Controller} owns all the call logic (ringing, the WebRTC controller,
 * teardown). The box can be dragged anywhere by its header.
 *
 * <p>States:
 * <ul>
 *   <li><b>outgoing&nbsp;idle</b> — placed by tapping 🎙: Close + Call</li>
 *   <li><b>outgoing&nbsp;ringing</b> — after Call: "Calling…" + Cancel</li>
 *   <li><b>incoming</b> — a ring arrived: Answer + Ignore</li>
 *   <li><b>connecting</b> — answered, audio linking up: Hang up</li>
 *   <li><b>connected</b> — both users listed + running timer: Mute + Hang up</li>
 * </ul>
 */
public final class BhVoiceCallBox {

    private static final String TAG = "BhSteamChat";

    private static final int COL_BG      = 0xF21A1D24;
    private static final int COL_PILL    = 0xF22A2E38;
    private static final int COL_ACCENT  = 0xFF66C0F4; // Steam blue
    private static final int COL_GREEN   = 0xFF90BA3C; // Steam green
    private static final int COL_TEXT    = 0xFFEFEFEF;
    private static final int COL_SUBTEXT = 0xFF9AA0AC;
    private static final int COL_RED     = 0xFFE05B5B;

    /** Button taps the box reports back to the overlay controller. */
    public interface Actions {
        void onPlaceCall();   // Call (outgoing idle)
        void onAnswer();      // Answer (incoming)
        void onDecline();     // Ignore (incoming)
        void onToggleMute();  // Mute / Unmute (connected)
        void onAddUser();     // ＋ Add (connected) — open the friend picker
        void onEnd();         // Close / Cancel / Hang up — tear the call down
    }

    /** Friend-picker callbacks (the "＋ Add" flow during a connected call). */
    public interface AddPicker {
        void onPick(int index);  // index into the names list passed to showAddPicker
        void onCancel();
    }

    private final Activity act;
    private final Actions actions;
    private final float density;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private boolean attached;

    private LinearLayout root;
    private TextView header;     // drag handle + title
    private TextView body;       // status / "X is calling" line
    private LinearLayout users;  // connected-state participant list
    private Chronometer timer;   // connected-state call duration
    private LinearLayout buttons;
    private boolean connectedShown;  // timer started; subsequent roster updates must not reset it

    public BhVoiceCallBox(Activity act, Actions actions) {
        this.act = act;
        this.actions = actions;
        this.density = act.getResources().getDisplayMetrics().density;
    }

    // ── public state transitions (UI thread) ────────────────────────────────

    public void showOutgoingIdle(String peerName) {
        ensureAttached();
        header.setText("🎙  Voice call");
        body.setVisibility(View.VISIBLE);
        body.setText("Call " + safe(peerName, "friend") + "?");
        stopTimer();
        users.setVisibility(View.GONE);
        buttons.removeAllViews();
        buttons.addView(button("Close", COL_PILL, new View.OnClickListener() {
            public void onClick(View v) { actions.onEnd(); }
        }));
        buttons.addView(button("Call", COL_ACCENT, new View.OnClickListener() {
            public void onClick(View v) { actions.onPlaceCall(); }
        }));
    }

    public void showOutgoingRinging(String peerName) {
        ensureAttached();
        header.setText("🎙  Voice call");
        body.setVisibility(View.VISIBLE);
        body.setText("Calling " + safe(peerName, "friend") + "…");
        stopTimer();
        users.setVisibility(View.GONE);
        buttons.removeAllViews();
        buttons.addView(button("Cancel", COL_RED, new View.OnClickListener() {
            public void onClick(View v) { actions.onEnd(); }
        }));
    }

    public void showIncoming(String peerName) {
        ensureAttached();
        header.setText("📞  Incoming call");
        body.setVisibility(View.VISIBLE);
        body.setText(safe(peerName, "Someone") + " is calling…");
        stopTimer();
        users.setVisibility(View.GONE);
        buttons.removeAllViews();
        buttons.addView(button("Ignore", COL_PILL, new View.OnClickListener() {
            public void onClick(View v) { actions.onDecline(); }
        }));
        buttons.addView(button("Answer", COL_GREEN, new View.OnClickListener() {
            public void onClick(View v) { actions.onAnswer(); }
        }));
    }

    public void showConnecting() {
        ensureAttached();
        header.setText("🎙  Voice call");
        body.setVisibility(View.VISIBLE);
        body.setText("Connecting…");
        stopTimer();
        users.setVisibility(View.GONE);
        buttons.removeAllViews();
        buttons.addView(button("Hang up", COL_RED, new View.OnClickListener() {
            public void onClick(View v) { actions.onEnd(); }
        }));
    }

    /** Connected: show the live participant list and (the first time) start the
     *  call timer. Safe to call repeatedly as people join/leave — the timer is
     *  only started once so it keeps counting across roster updates. */
    public void showConnected(List<String> participants) {
        ensureAttached();
        header.setText("🟢  In call");
        body.setVisibility(View.GONE);

        users.removeAllViews();
        users.setVisibility(View.VISIBLE);
        if (participants != null) for (String p : participants) users.addView(participant(safe(p, "Friend")));

        timer.setVisibility(View.VISIBLE);
        if (!connectedShown) {
            connectedShown = true;
            timer.setBase(SystemClock.elapsedRealtime());
            timer.start();
        }

        buttons.removeAllViews();
        buttons.addView(button("Mute", COL_PILL, new View.OnClickListener() {
            public void onClick(View v) { actions.onToggleMute(); }
        }));
        buttons.addView(button("＋ Add", COL_ACCENT, new View.OnClickListener() {
            public void onClick(View v) { actions.onAddUser(); }
        }));
        buttons.addView(button("Hang up", COL_RED, new View.OnClickListener() {
            public void onClick(View v) { actions.onEnd(); }
        }));
    }

    /** Swap the connected view for a scrollable friend picker (the "＋ Add"
     *  flow). The timer keeps running underneath; picking or cancelling is up to
     *  the controller, which then re-renders the connected view. */
    public void showAddPicker(final List<String> friendNames, final AddPicker cb) {
        ensureAttached();
        header.setText("Add to call");
        body.setVisibility(View.GONE);
        timer.setVisibility(View.GONE);  // hidden while picking; not stopped

        users.removeAllViews();
        users.setVisibility(View.VISIBLE);
        ScrollView sc = new ScrollView(act);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(220), pickerMaxH())));
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        if (friendNames == null || friendNames.isEmpty()) {
            TextView none = participant("No friends available to add");
            none.setTextColor(COL_SUBTEXT);
            list.addView(none);
        } else {
            for (int i = 0; i < friendNames.size(); i++) {
                final int idx = i;
                TextView row = participant(safe(friendNames.get(i), "Friend"));
                row.setPadding(dp(2), dp(8), dp(2), dp(8));
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { cb.onPick(idx); }
                });
                list.addView(row);
            }
        }
        sc.addView(list);
        users.addView(sc);

        buttons.removeAllViews();
        buttons.addView(button("Cancel", COL_PILL, new View.OnClickListener() {
            public void onClick(View v) { cb.onCancel(); }
        }));
    }

    private int pickerMaxH() {
        try { return (int) (act.getResources().getDisplayMetrics().heightPixels * 0.5f); }
        catch (Throwable t) { return dp(220); }
    }

    /** Relabel the connected-state mute button. */
    public void setMuted(boolean muted) {
        if (buttons == null || buttons.getChildCount() == 0) return;
        View first = buttons.getChildAt(0);
        if (first instanceof TextView) ((TextView) first).setText(muted ? "Unmute" : "Mute");
    }

    public boolean isShowing() { return attached; }

    /** Remove the box from the window. */
    public void close() {
        stopTimer();
        connectedShown = false;
        if (!attached || wm == null || root == null) { attached = false; return; }
        try { wm.removeView(root); } catch (Throwable ignored) {}
        attached = false;
    }

    // ── view construction ───────────────────────────────────────────────────

    private void ensureAttached() {
        if (attached) return;
        if (root == null) build();
        try {
            wm = act.getWindowManager();
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;   // drag offsets accumulate from centre
            lp.x = 0;
            lp.y = 0;
            wm.addView(root, lp);
            attached = true;
        } catch (Throwable t) {
            Log.w(TAG, "voice call box attach failed", t);
        }
    }

    private void build() {
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setMinimumWidth(dp(210));
        root.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_BG);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x40FFFFFF);
        root.setBackground(bg);
        // Drag the whole box anywhere on screen by touching any non-button area
        // (the action buttons are clickable and consume their own touches first).
        root.setOnTouchListener(new DragTouch());

        header = new TextView(act);
        header.setText("🎙  Voice call");
        header.setTextColor(COL_TEXT);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        header.setPadding(0, dp(2), 0, dp(8));
        root.addView(header);

        body = new TextView(act);
        body.setTextColor(COL_SUBTEXT);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setPadding(0, 0, 0, dp(10));
        root.addView(body);

        users = new LinearLayout(act);
        users.setOrientation(LinearLayout.VERTICAL);
        users.setVisibility(View.GONE);
        root.addView(users);

        timer = new Chronometer(act);
        timer.setTextColor(COL_GREEN);
        timer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        timer.setTypeface(Typeface.MONOSPACE);
        timer.setPadding(0, dp(2), 0, dp(10));
        timer.setVisibility(View.GONE);
        root.addView(timer);

        buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        root.addView(buttons);
    }

    /** One "● name" row in the connected participant list. */
    private TextView participant(String name) {
        TextView t = new TextView(act);
        t.setText("● " + name);
        t.setTextColor(COL_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, dp(1), 0, dp(1));
        return t;
    }

    private TextView button(String label, int color, View.OnClickListener onClick) {
        TextView b = new TextView(act);
        b.setText(label);
        b.setTextColor(COL_TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(16), dp(7), dp(16), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private void stopTimer() {
        if (timer != null) { timer.stop(); timer.setVisibility(View.GONE); }
    }

    private int dp(int v) { return (int) (v * density + 0.5f); }

    private static String safe(String s, String fallback) {
        return (s == null || s.trim().isEmpty() || s.equals("null")) ? fallback : s;
    }

    // ── drag the box anywhere on screen (any non-button area) ────────────────
    private final class DragTouch implements View.OnTouchListener {
        private float startRawX, startRawY;
        private int startX, startY;

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = e.getRawX();
                    startRawY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (lp == null || wm == null || !attached) return true;
                    lp.x = startX + (int) (e.getRawX() - startRawX);
                    lp.y = startY + (int) (e.getRawY() - startRawY);
                    try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
                    return true;
                default:
                    return false;
            }
        }
    }
}
