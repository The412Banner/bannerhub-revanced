package app.revanced.extension.gamehub.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.revanced.extension.gamehub.util.GHLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class CreditsHelper {

    // GameHub color palette.
    private static final int COLOR_BG = 0xFF191A1C;
    private static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFF787A80;
    private static final int COLOR_DIVIDER = 0x33FFFFFF;
    private static final int COLOR_BTN = 0xFF2E7D32;
    private static final int COLOR_LINK = 0xFF8D57FC;

    private static final class CreditEntry {
        final String feature;
        final Map<String, String> authors = new LinkedHashMap<>();

        CreditEntry(String feature) {
            this.feature = feature;
        }
    }

    // Insertion-ordered list for display; keyed lookup via creditsByFeature.
    private static final List<CreditEntry> credits = new ArrayList<>();
    private static final Map<String, CreditEntry> creditsByFeature = new LinkedHashMap<>();

    /**
     * Registers a credit entry. Multiple calls with the same feature name
     * merge their authors into a single entry (preserving insertion order).
     * Called from injected smali in SettingItemViewModel.l().
     *
     * @param feature the feature/patch name
     * @param author  the author name
     * @param url     the author URL (empty string if none)
     */
    public static void addCredit(String feature, String author, String url) {
        GHLog.CREDITS.d("addCredit: feature=" + feature + " author=" + author + " url=" + url);
        CreditEntry entry = creditsByFeature.get(feature);
        if (entry == null) {
            entry = new CreditEntry(feature);
            creditsByFeature.put(feature, entry);
            credits.add(entry);
        }
        boolean added = entry.authors.putIfAbsent(author, url) == null;
        GHLog.CREDITS.d(
                "  → " + (added ? "added" : "DUPLICATE, skipped") + " (total authors=" + entry.authors.size() + ")");
    }

    /**
     * Shows a GameHub-styled dialog listing all registered credits.
     * Called when the user taps the Credits button in settings.
     *
     * @param context the Activity context from the tapped view
     */
    public static void showCreditsDialog(Context context) {
        try {
            if (credits.isEmpty()) return;

            Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout root = buildDialogContent(context, dialog);
            dialog.setContentView(root);

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(dpToPx(context, 460), ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.CENTER);
            }

            dialog.show();
        } catch (Exception e) {
            GHLog.CREDITS.w("showCreditsDialog failed", e);
        }
    }

    private static LinearLayout buildDialogContent(Context ctx, Dialog dialog) {
        // Root container with dark background and rounded corners.
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int hPad = dpToPx(ctx, 24);
        root.setPadding(hPad, dpToPx(ctx, 28), hPad, hPad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_BG);
        bg.setCornerRadius(dpToPx(ctx, 12));
        root.setBackground(bg);

        // Title.
        TextView title = new TextView(ctx);
        title.setText("Credits");
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        // Divider below title.
        root.addView(createDivider(ctx, dpToPx(ctx, 16), dpToPx(ctx, 12)));

        // Scrollable credits list.
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < credits.size(); i++) {
            list.addView(createCreditEntry(ctx, credits.get(i)));
            if (i < credits.size() - 1) {
                list.addView(createDivider(ctx, dpToPx(ctx, 4), dpToPx(ctx, 4)));
            }
        }

        scroll.addView(list, matchWrap());

        // Give the scroll area weight so it can shrink if the dialog is too tall.
        LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        // OK button.
        root.addView(createButton(ctx, dialog));

        return root;
    }

    private static LinearLayout createCreditEntry(Context ctx, CreditEntry entry) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dpToPx(ctx, 8), 0, dpToPx(ctx, 8));

        // Feature name.
        TextView featureTv = new TextView(ctx);
        featureTv.setText(entry.feature);
        featureTv.setTextColor(COLOR_TEXT_PRIMARY);
        featureTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        featureTv.setTypeface(null, Typeface.BOLD);
        row.addView(featureTv, matchWrap());

        // Author line: "by Author1, Author2, Author3"
        // Each author name is independently linkable if they have a URL.
        TextView authorTv = new TextView(ctx);
        authorTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        authorTv.setTextColor(COLOR_TEXT_SECONDARY);
        authorTv.setLinkTextColor(COLOR_LINK);

        SpannableStringBuilder sb = new SpannableStringBuilder("by ");
        boolean first = true;
        for (Map.Entry<String, String> author : entry.authors.entrySet()) {
            if (!first) sb.append(", ");
            first = false;

            String name = author.getKey();
            String url = author.getValue();
            int start = sb.length();
            sb.append(name);

            if (url != null && !url.isEmpty()) {
                sb.setSpan(new URLSpan(url), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                authorTv.setMovementMethod(LinkMovementMethod.getInstance());
            }
        }
        authorTv.setText(sb);

        LinearLayout.LayoutParams authorParams = matchWrap();
        authorParams.topMargin = dpToPx(ctx, 2);
        row.addView(authorTv, authorParams);

        return row;
    }

    private static View createDivider(Context ctx, int topMargin, int bottomMargin) {
        View divider = new View(ctx);
        divider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(ctx, 1));
        params.topMargin = topMargin;
        params.bottomMargin = bottomMargin;
        divider.setLayoutParams(params);
        return divider;
    }

    private static TextView createButton(Context ctx, Dialog dialog) {
        TextView btn = new TextView(ctx);
        btn.setText("OK");
        btn.setTextColor(COLOR_TEXT_PRIMARY);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, dpToPx(ctx, 12), 0, dpToPx(ctx, 12));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(COLOR_BTN);
        btnBg.setCornerRadius(dpToPx(ctx, 8));
        btn.setBackground(btnBg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dpToPx(ctx, 16);
        btn.setLayoutParams(params);

        btn.setOnClickListener(v -> dialog.dismiss());
        btn.requestFocus();

        return btn;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dpToPx(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }
}
