package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Steam game-detail LAYOUT of Bannerlator ({@code StoreDetailScaffold}) as one reusable
 * view tree, shared by the owned detail page ({@link GogGameDetailActivity}) and the store-only
 * catalog page ({@link GogCatalogDetailActivity}):
 *
 * <pre>
 *   header (back · store badge · downloads button)
 *   hero band with the fade into the page background
 *   game name (+ optional subtitle lines)
 *   ONE primary action button (accent, or a read-only progress fill while downloading) + ⚙ gear
 *   optional info line under the button
 *   pill tab strip (Details · DLC · Cloud saves · Media)
 *   the selected tab's body
 * </pre>
 *
 * The gear opens a plain list dialog of {@link GearItem}s — the extension's dialogs are all
 * {@link AlertDialog}s, so the menu reads like the rest of BannerHub.
 */
final class GogDetailScaffold {

    /** One entry in the gear menu. */
    static final class GearItem {
        final String label;
        final boolean enabled;
        final boolean danger;
        final Runnable onClick;
        GearItem(String label, boolean enabled, boolean danger, Runnable onClick) {
            this.label = label; this.enabled = enabled; this.danger = danger; this.onClick = onClick;
        }
        static GearItem of(String label, Runnable onClick) { return new GearItem(label, true, false, onClick); }
        static GearItem danger(String label, Runnable onClick) { return new GearItem(label, true, true, onClick); }
    }

    final LinearLayout root;
    final ScrollView scroll;
    final LinearLayout column;
    final ImageView hero;
    final TextView titleTV;
    final LinearLayout subtitle;
    final BhStoreUi.ProgressButton primary;
    final Button gear;
    final TextView infoLine;
    final FrameLayout tabHost;
    final FrameLayout body;
    private BhStoreUi.TabStrip tabs;
    private final Activity ctx;
    private List<GearItem> gearItems = new ArrayList<>();

    GogDetailScaffold(final Activity ctx, String title, Runnable onBack, Runnable onDownloads) {
        this.ctx = ctx;
        root = BhStoreUi.column(ctx);
        root.setBackgroundColor(BhStoreUi.BG);

        // Header: back · spacer · badge · downloads
        LinearLayout header = BhStoreUi.row(ctx);
        header.setPadding(BhStoreUi.dp(ctx, 8), BhStoreUi.dp(ctx, 4), BhStoreUi.dp(ctx, 8), BhStoreUi.dp(ctx, 4));
        Button back = BhStoreUi.headerButton(ctx, "←");
        back.setOnClickListener(v -> onBack.run());
        header.addView(back, BhStoreUi.lp(-2, BhStoreUi.dp(ctx, 36)));
        header.addView(new View(ctx), BhStoreUi.lpWeight(0, 1f));
        TextView badge = BhStoreUi.text(ctx, "GOG.com", 11f, 0xFFB39DFF, true);
        badge.setPadding(BhStoreUi.dp(ctx, 8), BhStoreUi.dp(ctx, 3), BhStoreUi.dp(ctx, 8), BhStoreUi.dp(ctx, 3));
        badge.setBackground(BhStoreUi.roundBg(ctx, 0x297033FF, 6));
        LinearLayout.LayoutParams bl = BhStoreUi.lp(-2, -2); bl.rightMargin = BhStoreUi.dp(ctx, 8);
        header.addView(badge, bl);
        if (onDownloads != null) {
            Button dl = BhStoreUi.headerButton(ctx, "⬇");
            dl.setOnClickListener(v -> onDownloads.run());
            header.addView(dl, BhStoreUi.lp(-2, BhStoreUi.dp(ctx, 36)));
        }
        root.addView(header, BhStoreUi.lp(-1, -2));

        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        column = BhStoreUi.column(ctx);

        // Hero band + bottom fade
        FrameLayout heroBox = new FrameLayout(ctx);
        heroBox.setBackgroundColor(BhStoreUi.SURFACE_VAR);
        hero = new ImageView(ctx);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroBox.addView(hero, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(ctx);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, BhStoreUi.BG}));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-1, BhStoreUi.dp(ctx, 56));
        fl.gravity = Gravity.BOTTOM;
        heroBox.addView(fade, fl);
        column.addView(heroBox, BhStoreUi.lp(-1, BhStoreUi.dp(ctx, BhStoreUi.isWide(ctx) ? 220 : 180)));

        // Name + subtitle
        LinearLayout nameCol = BhStoreUi.column(ctx);
        nameCol.setPadding(BhStoreUi.dp(ctx, 16), BhStoreUi.dp(ctx, 8), BhStoreUi.dp(ctx, 16), BhStoreUi.dp(ctx, 4));
        titleTV = BhStoreUi.oneLine(BhStoreUi.text(ctx, title == null ? "" : title, 22f, BhStoreUi.TEXT, true), 2);
        nameCol.addView(titleTV);
        subtitle = BhStoreUi.column(ctx);
        nameCol.addView(subtitle, BhStoreUi.lp(-1, -2));
        column.addView(nameCol, BhStoreUi.lp(-1, -2));

        // Primary + gear
        LinearLayout actionRow = BhStoreUi.row(ctx);
        actionRow.setPadding(BhStoreUi.dp(ctx, 16), BhStoreUi.dp(ctx, 8), BhStoreUi.dp(ctx, 16), BhStoreUi.dp(ctx, 8));
        primary = new BhStoreUi.ProgressButton(ctx);
        actionRow.addView(primary, new LinearLayout.LayoutParams(0, BhStoreUi.dp(ctx, 46), 1f));
        gear = BhStoreUi.outlinedButton(ctx, "⚙");
        gear.setTextSize(18f);
        gear.setTextColor(0xFFB39DFF);
        gear.setPadding(0, 0, 0, 0);
        gear.setOnClickListener(v -> showGear());
        LinearLayout.LayoutParams gl = BhStoreUi.lp(BhStoreUi.dp(ctx, 46), BhStoreUi.dp(ctx, 46));
        gl.leftMargin = BhStoreUi.dp(ctx, 8);
        actionRow.addView(gear, gl);
        gear.setVisibility(View.GONE);
        column.addView(actionRow, BhStoreUi.lp(-1, -2));

        infoLine = BhStoreUi.text(ctx, "", 12f, BhStoreUi.MUTED, false);
        infoLine.setPadding(BhStoreUi.dp(ctx, 16), 0, BhStoreUi.dp(ctx, 16), BhStoreUi.dp(ctx, 8));
        infoLine.setVisibility(View.GONE);
        column.addView(infoLine, BhStoreUi.lp(-1, -2));

        tabHost = new FrameLayout(ctx);
        column.addView(tabHost, BhStoreUi.lp(-1, -2));
        body = new FrameLayout(ctx);
        column.addView(body, BhStoreUi.lp(-1, -2));
        column.addView(BhStoreUi.spacer(ctx, 16));

        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    void setTitle(String s) { titleTV.setText(s == null ? "" : s); }

    void setInfoLine(String s) {
        infoLine.setText(s == null ? "" : s);
        infoLine.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Replace the subtitle lines under the name (install status, exe name, price row). */
    void setSubtitle(List<View> lines) {
        subtitle.removeAllViews();
        if (lines == null) return;
        for (View v : lines) {
            LinearLayout.LayoutParams l = BhStoreUi.lp(-1, -2);
            l.topMargin = BhStoreUi.dp(ctx, 4);
            subtitle.addView(v, l);
        }
    }

    void setGear(List<GearItem> items) {
        gearItems = items == null ? new ArrayList<>() : items;
        gear.setVisibility(gearItems.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showGear() {
        if (gearItems.isEmpty()) return;
        final List<GearItem> items = new ArrayList<>(gearItems);
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) {
            GearItem it = items.get(i);
            android.text.SpannableString s = new android.text.SpannableString(it.label);
            int color = it.danger ? 0xFFFF6B6B : (it.enabled ? BhStoreUi.TEXT : BhStoreUi.MUTED);
            s.setSpan(new android.text.style.ForegroundColorSpan(color), 0, s.length(), 0);
            labels[i] = s;
        }
        new AlertDialog.Builder(ctx)
                .setTitle("Actions")
                .setItems(labels, (d, which) -> {
                    GearItem it = items.get(which);
                    if (it.enabled && it.onClick != null) it.onClick.run();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** (Re)build the pill tab strip. A single tab hides the strip entirely. */
    void setTabs(String[] labels, int selected, BhStoreUi.IntHandler onSelect) {
        tabHost.removeAllViews();
        tabs = null;
        if (labels == null || labels.length <= 1) return;
        tabs = new BhStoreUi.TabStrip(ctx, labels, selected, onSelect);
        tabHost.addView(tabs.view, new FrameLayout.LayoutParams(-1, -2));
    }

    void setTabBadge(int index, String badge) { if (tabs != null) tabs.setBadge(index, badge); }

    void setBody(View v) {
        body.removeAllViews();
        if (v != null) body.addView(v, new FrameLayout.LayoutParams(-1, -2));
    }

    /** Load the hero from an ordered candidate chain (wide art first). */
    void loadHero(List<String> candidates) { GogImageLoader.load(hero, candidates); }
}
