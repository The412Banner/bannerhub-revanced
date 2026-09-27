package app.revanced.extension.gamehub.components;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Programmatic-view helpers for the Component Manager screens. Same palette
 * and shapes as the GOG screens' {@code BhStoreUi} (which is package-private
 * to {@code gog}), trimmed to what these two screens use.
 */
final class BhComponentUi {

    static final int BG          = 0xFF0D0D0D;
    static final int HEADER      = 0xFF1A1A2E;
    static final int CARD        = 0xFF161622;
    static final int CARD_HI     = 0xFF1D1D3A;
    static final int SURFACE_VAR = 0xFF222233;
    static final int OUTLINE     = 0xFF2A2A3A;
    static final int ACCENT      = 0xFF7033FF;
    static final int TEXT        = 0xFFFFFFFF;
    static final int TEXT2       = 0xFFCCCCCC;
    static final int MUTED       = 0xFF888888;
    static final int DIM         = 0xFF555577;
    static final int GREEN       = 0xFF2E7D32;
    static final int RED         = 0xFFCC3333;
    static final int AMBER       = 0xFFFFB300;
    static final int NEUTRAL_BTN = 0xFF444466;

    private BhComponentUi() {}

    static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    static GradientDrawable roundBg(Context ctx, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(ctx, radiusDp));
        return g;
    }

    static GradientDrawable roundBg(Context ctx, int color, int radiusDp, int strokeColor) {
        GradientDrawable g = roundBg(ctx, color, radiusDp);
        g.setStroke(dp(ctx, 1), strokeColor);
        return g;
    }

    static TextView text(Context ctx, CharSequence s, float sp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    static LinearLayout column(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static View spacer(Context ctx, int hDp) {
        View v = new View(ctx);
        v.setLayoutParams(lp(-1, dp(ctx, hDp)));
        return v;
    }

    /** Filled 44dp button, the folder picker's style. */
    static Button button(Context ctx, String label, int color) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setTextColor(TEXT);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setAllCaps(false);
        btn.setBackground(roundBg(ctx, color, 8));
        return btn;
    }

    /** Titled section container (rounded card on the dark background). */
    static LinearLayout card(Context ctx) {
        LinearLayout c = column(ctx);
        int p = dp(ctx, 12);
        c.setPadding(p, p, p, p);
        c.setBackground(roundBg(ctx, CARD, 10, OUTLINE));
        return c;
    }

    static LinearLayout.LayoutParams cardLp(Context ctx) {
        LinearLayout.LayoutParams lp = lp(-1, -2);
        lp.bottomMargin = dp(ctx, 8);
        return lp;
    }

    /** Small rounded chip — the type badge / state pill. */
    static TextView chip(Context ctx, String label, int bg, int ink) {
        TextView tv = text(ctx, label, 10f, ink, true);
        tv.setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2));
        tv.setBackground(roundBg(ctx, bg, 6));
        return tv;
    }

    static LinearLayout.LayoutParams chipLp(Context ctx) {
        LinearLayout.LayoutParams lp = lp(-2, -2);
        lp.rightMargin = dp(ctx, 6);
        return lp;
    }

    /** "TITLE" + muted count, the group header. */
    static View sectionHeader(Context ctx, String title, String sub) {
        LinearLayout r = row(ctx);
        r.setPadding(0, dp(ctx, 12), 0, dp(ctx, 6));
        r.addView(text(ctx, title.toUpperCase(Locale.ROOT), 12f, TEXT2, true), lp(0, -2));
        ((LinearLayout.LayoutParams) r.getChildAt(0).getLayoutParams()).weight = 1f;
        if (sub != null) r.addView(text(ctx, sub, 11f, MUTED, false), lp(-2, -2));
        return r;
    }

    /** Centered notice card: title, body, optional action. Empty / error states. */
    static View notice(Context ctx, String title, String body, String actionLabel, Runnable action) {
        LinearLayout c = card(ctx);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = text(ctx, title, 15f, TEXT, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        if (body != null) {
            TextView b = text(ctx, body, 12f, MUTED, false);
            b.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams blp = lp(-1, -2);
            blp.topMargin = dp(ctx, 6);
            c.addView(b, blp);
        }
        if (actionLabel != null && action != null) {
            Button btn = button(ctx, actionLabel, ACCENT);
            btn.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams alp = lp(-2, dp(ctx, 40));
            alp.topMargin = dp(ctx, 12);
            c.addView(btn, alp);
        }
        return c;
    }

    static String humanSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    static String humanDate(long millis) {
        if (millis <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(millis));
    }
}
