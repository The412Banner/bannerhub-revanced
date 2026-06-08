package com.xj.winemu.perf;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * BhPerfMenus — the two Banner Tools dialogs that front the in-game
 * performance overlay:
 *
 *   - {@link #showOverlayToggleDialog} — master on/off for whether the ⚡
 *     overlay pill attaches during games (persists
 *     {@link BhPerfController#KEY_OVERLAY_ENABLED}).
 *   - {@link #showRootDialog} — 3.7.5-style root explainer with a Grant /
 *     Revoke button driving {@link BhPerfController}'s shared root-grant
 *     state (the same flag the overlay's toggles gate on).
 *
 * Both are built programmatically (no XML resource injected into the foreign
 * GameHub package) and run on the UI thread.
 */
public final class BhPerfMenus {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final int COL_TEXT    = 0xFFEFEFEF;
    private static final int COL_SUBTEXT = 0xFF9AA0AC;
    private static final int COL_WARN    = 0xFFFF6E6E;
    private static final int COL_ACCENT  = 0xFF66C0F4;

    private BhPerfMenus() {}

    // ── Overlay master toggle ────────────────────────────────────────────────

    public static void showOverlayToggleDialog(final Activity host) {
        if (host == null) return;
        try {
            final BhPerfController perf = BhPerfController.get();
            final com.xj.winemu.steamchat.BhSteamChatController chat =
                com.xj.winemu.steamchat.BhSteamChatController.get();

            LinearLayout box = column(host);

            // ── Steam Chat overlay (no root needed) ──────────────────────────
            box.addView(sectionHeader(host, "💬  Steam Chat overlay"));
            box.addView(body(host,
                "A draggable 💬 pill over your game — browse your Steam friends, "
              + "see presence, and read/reply to chats inline. Requires being "
              + "signed into Steam in GameHub. Takes effect next time you open a "
              + "game."));

            final CheckBox chatCb = new CheckBox(host);
            chatCb.setText("Show in-game Steam chat overlay");
            chatCb.setTextColor(COL_TEXT);
            chatCb.setChecked(chat.isEnabled(host));
            chatCb.setLayoutParams(rowLp(host));
            chatCb.setOnCheckedChangeListener((b, checked) -> {
                chat.setEnabled(host, checked);
                Toast.makeText(host,
                    checked ? "Steam chat overlay ON" : "Steam chat overlay OFF",
                    Toast.LENGTH_SHORT).show();
            });
            box.addView(chatCb);

            // ── Performance overlay (root-gated in place) ────────────────────
            box.addView(sectionHeader(host, "⚡  Performance overlay"));
            box.addView(body(host,
                "A draggable ⚡ pill with quick toggles — Sustained Performance "
              + "Mode and Max Adreno Clocks. These do privileged sysfs writes, "
              + "so they need root.\n\n"
              + "Turn it off to hide the pill. Takes effect next time you open a "
              + "game."));

            final boolean rooted = perf.isRootGranted(host);
            final CheckBox perfCb = new CheckBox(host);
            perfCb.setText("Show in-game performance overlay");
            perfCb.setTextColor(rooted ? COL_TEXT : COL_SUBTEXT);
            perfCb.setChecked(perf.isOverlayEnabled(host));
            perfCb.setEnabled(rooted);
            perfCb.setAlpha(rooted ? 1f : 0.5f);
            perfCb.setLayoutParams(rowLp(host));
            perfCb.setOnCheckedChangeListener((b, checked) -> {
                perf.setOverlayEnabled(host, checked);
                Toast.makeText(host,
                    checked ? "Overlay enabled" : "Overlay disabled",
                    Toast.LENGTH_SHORT).show();
            });
            box.addView(perfCb);

            if (!rooted) {
                // Move the gate the Overlay TILE used to do onto this toggle, so
                // the root-granting path stays discoverable.
                TextView grant = body(host, "⚠ Requires root — tap to grant");
                grant.setTextColor(COL_ACCENT);
                grant.setOnClickListener(v -> showRootDialog(host));
                box.addView(grant);
            }

            new AlertDialog.Builder(host)
                .setTitle("In-game Overlays")
                .setView(scroll(host, box))
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } catch (Throwable t) {
            Toast.makeText(host, "Couldn't open overlay settings",
                Toast.LENGTH_SHORT).show();
        }
    }

    private static TextView sectionHeader(Activity host, String text) {
        TextView tv = new TextView(host);
        tv.setText(text);
        tv.setTextColor(COL_ACCENT);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(host, 16);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static LinearLayout.LayoutParams rowLp(Activity host) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(host, 10);
        return lp;
    }

    // ── Root grant / revoke ──────────────────────────────────────────────────

    public static void showRootDialog(final Activity host) {
        if (host == null) return;
        try {
            final BhPerfController ctl = BhPerfController.get();
            final boolean granted = ctl.isRootGranted(host);

            LinearLayout box = column(host);

            box.addView(body(host,
                "Some BannerHub features need root (superuser) access:\n\n"
              + "• Sustained Performance Mode — sets every CPU core's frequency "
              + "governor to \"performance\" so the CPU never downclocks while "
              + "the toggle is on.\n\n"
              + "• Max Adreno Clocks — locks the Qualcomm Adreno GPU's minimum "
              + "clock to its maximum via the KGSL sysfs interface, so the GPU "
              + "can't drop below its ceiling under load.\n\n"
              + "These are direct privileged sysfs writes — that's why root is "
              + "required. Without root, both toggles stay greyed out. Root is "
              + "checked once here; there's no superuser prompt every time the "
              + "overlay opens."));

            TextView warn = body(host,
                "⚠ These toggles override your device's thermal management and "
              + "make it run hotter. Don't leave them on unattended; disable "
              + "them if your device gets uncomfortably warm. Use at your own "
              + "risk.");
            warn.setTextColor(COL_WARN);
            box.addView(warn);

            TextView status = body(host,
                granted ? "Status: root access granted ✓"
                        : "Status: root access not granted");
            status.setTextColor(COL_SUBTEXT);
            box.addView(status);

            AlertDialog.Builder b = new AlertDialog.Builder(host)
                .setTitle("Root Access")
                .setView(scroll(host, box))
                .setNegativeButton(android.R.string.cancel, null);

            if (granted) {
                b.setPositiveButton("Revoke", (d, w) ->
                    ctl.revokeRoot(host, MAIN, ok ->
                        Toast.makeText(host, "Root access revoked",
                            Toast.LENGTH_SHORT).show()));
            } else {
                b.setPositiveButton("Grant", (d, w) ->
                    ctl.requestRootGrant(host, MAIN, ok ->
                        Toast.makeText(host,
                            ok ? "Root access granted"
                               : "Root denied or unavailable",
                            Toast.LENGTH_SHORT).show()));
            }
            b.show();
        } catch (Throwable t) {
            Toast.makeText(host, "Couldn't open root settings",
                Toast.LENGTH_SHORT).show();
        }
    }

    // ── tiny view helpers ────────────────────────────────────────────────────

    private static LinearLayout column(Activity host) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(host, 20);
        box.setPadding(pad, dp(host, 8), pad, 0);
        return box;
    }

    private static ScrollView scroll(Activity host, View child) {
        ScrollView sv = new ScrollView(host);
        sv.addView(child);
        return sv;
    }

    private static TextView body(Activity host, String text) {
        TextView tv = new TextView(host);
        tv.setText(text);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(host, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static int dp(Activity host, int v) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v,
            host.getResources().getDisplayMetrics()));
    }
}
