package com.xj.winemu.steamchat;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Banner Tools → "Steam Chat" dialog: master on/off for whether the in-game
 * Steam friends/chat overlay pill attaches during games. Built programmatically
 * (no XML injected into the foreign GameHub package), runs on the UI thread.
 * Persists {@link BhSteamChatController#KEY_ENABLED}.
 */
public final class BhSteamChatMenus {

    private static final int COL_TEXT    = 0xFFEFEFEF;
    private static final int COL_SUBTEXT = 0xFF9AA0AC;
    private static final int COL_ACCENT  = 0xFF66C0F4;

    private BhSteamChatMenus() {}

    public static void showToggleDialog(final Activity host) {
        if (host == null) return;
        try {
            final BhSteamChatController ctl = BhSteamChatController.get();

            LinearLayout box = new LinearLayout(host);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(host, 22);
            box.setPadding(pad, dp(host, 16), pad, dp(host, 8));

            TextView title = new TextView(host);
            title.setText("Steam Chat overlay");
            title.setTextColor(COL_ACCENT);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            box.addView(title);

            TextView desc = new TextView(host);
            desc.setText("A draggable 💬 pill appears over your game. Tap it to see "
                    + "your Steam friends list and presence, and open a friend to read "
                    + "recent messages.\n\n"
                    + "Read-only prototype — you can browse, not send (yet). Requires "
                    + "being signed into Steam in GameHub. Takes effect next time you "
                    + "open a game.");
            desc.setTextColor(COL_SUBTEXT);
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            desc.setPadding(0, dp(host, 10), 0, dp(host, 14));
            box.addView(desc);

            final CheckBox cb = new CheckBox(host);
            cb.setText("Show in-game Steam chat overlay");
            cb.setTextColor(COL_TEXT);
            cb.setChecked(ctl.isEnabled(host));
            box.addView(cb);

            new AlertDialog.Builder(host)
                    .setView(box)
                    .setPositiveButton("Save", (d, w) -> {
                        ctl.setEnabled(host, cb.isChecked());
                        Toast.makeText(host,
                                cb.isChecked() ? "Steam chat overlay ON" : "Steam chat overlay OFF",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
            android.util.Log.w("BhSteamChat", "showToggleDialog failed", t);
        }
    }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }
}
