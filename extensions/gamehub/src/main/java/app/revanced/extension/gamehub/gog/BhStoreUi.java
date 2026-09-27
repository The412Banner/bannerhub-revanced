package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The GOG storefront's programmatic widget kit — the Steam-style store pieces of Bannerlator
 * ({@code StoreCatalogUi} / {@code StoreDetailScaffold} / {@code StoreDetailComponents}) re-cut over
 * plain {@code android.widget} views and this extension's dark palette (the same colours the
 * existing GOG screens hard-code: {@code 0xFF0D0D0D} page, {@code 0xFF1A1A2E} header,
 * {@code 0xFF161622} card, {@code 0xFF7033FF} accent, gold focus ring).
 *
 * Everything here is stateless factory code: build a view, hand it back. Gamepad focus is
 * honoured the way the games screen does it — cards are focusable and paint a gold stroke.
 */
final class BhStoreUi {

    // ── Palette ───────────────────────────────────────────────────────────────

    static final int BG            = 0xFF0D0D0D;
    static final int HEADER        = 0xFF1A1A2E;
    static final int CARD          = 0xFF161622;
    static final int CARD_HI       = 0xFF1D1D3A;
    static final int SURFACE_VAR   = 0xFF222233;
    static final int OUTLINE       = 0xFF2A2A3A;
    static final int ACCENT        = 0xFF7033FF;
    static final int ACCENT_DIM    = 0xFF5533CC;
    static final int TEXT          = 0xFFFFFFFF;
    static final int TEXT2         = 0xFFCCCCCC;
    static final int MUTED         = 0xFF888888;
    static final int DIM           = 0xFF555577;
    static final int GREEN         = 0xFF4CAF50;
    static final int FREE_GREEN    = 0xFF66BB6A;
    static final int RED           = 0xFFCC3333;
    static final int AMBER         = 0xFFFFB300;
    static final int GOLD          = 0xFFFFD700;
    static final int ORANGE        = 0xFFFF9800;
    static final int DISCOUNT_BG   = 0xFF4C6B22;
    static final int DISCOUNT_INK  = 0xFFBEEE11;
    static final int INFO_BLUE     = 0xFF0277BD;

    /** Rail card width and the wide-art aspect (GOG's coverHorizontal is 92:43). */
    static final int RAIL_CARD_W_DP = 176;
    static final float WIDE_RATIO = 92f / 43f;
    static final float TALL_RATIO = 2f / 3f;

    private BhStoreUi() {}

    // ── Primitives ────────────────────────────────────────────────────────────

    static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /** Landscape = the two-column store layout; portrait = stacked rails. */
    static boolean isWide(Context ctx) {
        return ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    static GradientDrawable roundBg(Context ctx, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    static GradientDrawable roundBg(Context ctx, int color, int radiusDp, int strokeColor) {
        GradientDrawable d = roundBg(ctx, color, radiusDp);
        d.setStroke(dp(ctx, 1), strokeColor);
        return d;
    }

    /**
     * Gamepad focus: a gold stroke + the raised fill while focused, exactly as the games screen
     * paints its cards. The view keeps its own click handling.
     */
    static void focusRing(final View v, final GradientDrawable bg, final int base, final int hi) {
        v.setFocusable(true);
        v.setOnFocusChangeListener((view, hasFocus) -> {
            bg.setColor(hasFocus ? hi : base);
            bg.setStroke(dp(view.getContext(), hasFocus ? 2 : 1), hasFocus ? GOLD : OUTLINE);
        });
    }

    static TextView text(Context ctx, CharSequence s, float sp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    static TextView oneLine(TextView tv, int maxLines) {
        tv.setMaxLines(maxLines);
        tv.setEllipsize(TextUtils.TruncateAt.END);
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

    static LinearLayout.LayoutParams lpWeight(int h, float weight) {
        return new LinearLayout.LayoutParams(0, h, weight);
    }

    static View spacer(Context ctx, int hDp) {
        View v = new View(ctx);
        v.setLayoutParams(lp(-1, dp(ctx, hDp)));
        return v;
    }

    static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(OUTLINE);
        v.setLayoutParams(lp(-1, dp(ctx, 1)));
        return v;
    }

    /** Full-width accent button, 42dp, rounded 8 — the detail page's button. */
    static Button button(Context ctx, String label, int color) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(13f);
        b.setAllCaps(false);
        GradientDrawable bg = roundBg(ctx, color, 8);
        b.setBackground(bg);
        b.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setOnFocusChangeListener((v, f) -> bg.setStroke(f ? dp(ctx, 2) : 0, f ? GOLD : 0));
        return b;
    }

    /** Outlined secondary button. */
    static Button outlinedButton(Context ctx, String label) {
        Button b = button(ctx, label, CARD);
        GradientDrawable bg = roundBg(ctx, CARD, 8, OUTLINE);
        b.setBackground(bg);
        b.setTextColor(TEXT2);
        b.setOnFocusChangeListener((v, f) -> {
            bg.setStroke(dp(ctx, f ? 2 : 1), f ? GOLD : OUTLINE);
        });
        return b;
    }

    static LinearLayout.LayoutParams buttonLp(Context ctx) {
        LinearLayout.LayoutParams l = lp(-1, dp(ctx, 42));
        l.bottomMargin = dp(ctx, 8);
        return l;
    }

    /** A 40dp header icon button (glyph text), the games screen's header style. */
    static Button headerButton(Context ctx, String glyph) {
        Button b = new Button(ctx);
        b.setText(glyph);
        b.setTextColor(TEXT);
        b.setTextSize(16f);
        b.setAllCaps(false);
        GradientDrawable bg = roundBg(ctx, 0xFF333333, 4);
        b.setBackground(bg);
        b.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xFF555555 : 0xFF333333);
            bg.setStroke(f ? dp(ctx, 2) : 0, f ? GOLD : 0);
        });
        return b;
    }

    /** Filter chip ("All" / "Installed"): filled accent when selected, outlined otherwise. */
    static TextView chip(Context ctx, String label, boolean selected) {
        TextView tv = text(ctx, label, 12f, selected ? TEXT : TEXT2, true);
        tv.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        GradientDrawable bg = roundBg(ctx, selected ? ACCENT_DIM : CARD, 16, selected ? ACCENT : OUTLINE);
        tv.setBackground(bg);
        tv.setFocusable(true);
        tv.setOnFocusChangeListener((v, f) -> bg.setStroke(dp(ctx, f ? 2 : 1), f ? GOLD : (selected ? ACCENT : OUTLINE)));
        return tv;
    }

    /** Small rounded metadata chip (size / developer / genre). */
    static TextView infoChip(Context ctx, String label) {
        TextView tv = text(ctx, label, 11f, TEXT2, false);
        tv.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        tv.setBackground(roundBg(ctx, SURFACE_VAR, 6));
        return tv;
    }

    static LinearLayout.LayoutParams chipLp(Context ctx) {
        LinearLayout.LayoutParams l = lp(-2, -2);
        l.rightMargin = dp(ctx, 8);
        l.bottomMargin = dp(ctx, 6);
        return l;
    }

    /** "TITLE" + muted subtitle, the rail / section header. */
    static View sectionHeader(Context ctx, String title, String sub) {
        LinearLayout col = column(ctx);
        col.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 6));
        col.addView(oneLine(text(ctx, title, 16f, TEXT, true), 1));
        if (sub != null && !sub.isEmpty()) col.addView(oneLine(text(ctx, sub, 11f, MUTED, false), 1));
        return col;
    }

    /** The detail page's muted uppercase caption ("UPDATES"). */
    static TextView caption(Context ctx, String s) {
        TextView tv = text(ctx, s, 11f, 0xFF8888AA, true);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(ctx, 2), dp(ctx, 16), 0, dp(ctx, 6));
        return tv;
    }

    /** Titled section container (the detail page's Updates / DLC / Cloud saves card). */
    static LinearLayout card(Context ctx) {
        LinearLayout c = column(ctx);
        c.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        c.setBackground(roundBg(ctx, CARD, 10, OUTLINE));
        return c;
    }

    static LinearLayout.LayoutParams cardLp(Context ctx) {
        LinearLayout.LayoutParams l = lp(-1, -2);
        l.leftMargin = dp(ctx, 14); l.rightMargin = dp(ctx, 14); l.bottomMargin = dp(ctx, 12);
        return l;
    }

    /** Centered notice card: title, body, optional action. Empty / error states. */
    static View notice(Context ctx, String title, String body, String actionLabel, Runnable action) {
        LinearLayout c = card(ctx);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = text(ctx, title, 15f, TEXT, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        if (body != null && !body.isEmpty()) {
            TextView b = text(ctx, body, 12f, MUTED, false);
            b.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams l = lp(-2, -2); l.topMargin = dp(ctx, 6);
            c.addView(b, l);
        }
        if (actionLabel != null && action != null) {
            Button btn = button(ctx, actionLabel, ACCENT);
            btn.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams l = lp(-2, dp(ctx, 38)); l.topMargin = dp(ctx, 12);
            c.addView(btn, l);
        }
        FrameLayout wrap = new FrameLayout(ctx);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-2, -2);
        fl.gravity = Gravity.CENTER;
        fl.setMargins(dp(ctx, 24), dp(ctx, 24), dp(ctx, 24), dp(ctx, 24));
        wrap.addView(c, fl);
        return wrap;
    }

    static EditText searchField(Context ctx, String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setHintTextColor(0xFF666666);
        e.setTextColor(TEXT);
        e.setTextSize(14f);
        e.setSingleLine(true);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        e.setBackground(roundBg(ctx, SURFACE_VAR, 8, OUTLINE));
        e.setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9));
        return e;
    }

    static LinearLayout.LayoutParams searchLp(Context ctx) {
        LinearLayout.LayoutParams l = lp(-1, -2);
        l.setMargins(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 4));
        return l;
    }

    // ── Art ───────────────────────────────────────────────────────────────────

    /** An ImageView that keeps a fixed width:height ratio from its measured width. */
    static final class AspectImageView extends ImageView {
        private final float ratio;
        AspectImageView(Context ctx, float ratio) {
            super(ctx);
            this.ratio = ratio;
            setScaleType(ScaleType.CENTER_CROP);
        }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            int height = (int) (width / ratio);
            setMeasuredDimension(width, height);
        }
    }

    /** Cover art with a candidate chain over a placeholder (icon-less: the title in muted text). */
    static FrameLayout art(Context ctx, List<String> candidates, String title, float ratio, int radiusDp) {
        FrameLayout box = new FrameLayout(ctx);
        box.setBackground(roundBg(ctx, SURFACE_VAR, radiusDp));
        box.setClipToOutline(true);
        TextView ph = text(ctx, title == null ? "" : title, 11f, MUTED, false);
        ph.setGravity(Gravity.CENTER);
        ph.setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0);
        oneLine(ph, 2);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -1);
        box.addView(ph, pl);
        AspectImageView iv = new AspectImageView(ctx, ratio);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -2));
        GogImageLoader.load(iv, candidates);
        return box;
    }

    // ── Price / action ────────────────────────────────────────────────────────

    /** "-50%  ~~$24.99~~  $12.49", or "Free", or an empty row when the store gave no price. */
    static View priceRow(Context ctx, GogCatalogItem item) {
        LinearLayout r = row(ctx);
        if (item.isFree) {
            r.addView(text(ctx, "Free", 13f, FREE_GREEN, true));
            return r;
        }
        if (!item.hasPrice) return r;
        if (item.isDiscounted()) {
            TextView disc = text(ctx, "-" + item.discountPercent + "%", 11f, DISCOUNT_INK, true);
            disc.setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2));
            disc.setBackground(roundBg(ctx, DISCOUNT_BG, 4));
            LinearLayout.LayoutParams dl = lp(-2, -2); dl.rightMargin = dp(ctx, 6);
            r.addView(disc, dl);
            TextView orig = text(ctx, item.originalPrice, 11f, MUTED, false);
            orig.setPaintFlags(orig.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            LinearLayout.LayoutParams ol = lp(-2, -2); ol.rightMargin = dp(ctx, 6);
            r.addView(orig, ol);
        }
        r.addView(oneLine(text(ctx, item.finalPrice, 13f, TEXT, true), 1));
        return r;
    }

    /**
     * The one action button every catalog card draws: filled accent for a free claim, the raised
     * tier for "already yours" (Play / Download), outlined-but-enabled for "View on GOG.com".
     */
    static Button actionButton(Context ctx, GogCatalogItem.Action action, boolean compact, View.OnClickListener onClick) {
        String label;
        int color;
        int textColor = TEXT;
        boolean outlined = false;
        switch (action) {
            case CLAIM_FREE:     label = "Get for free"; color = ACCENT; break;
            case OPEN_INSTALLED: label = "▶ Play";   color = 0xFF2E7D32; break;
            case OPEN_OWNED:     label = "⬇ Download"; color = 0xFF333355; break;
            default:             label = "View on GOG.com"; color = CARD; textColor = TEXT2; outlined = true; break;
        }
        Button b = button(ctx, label, color);
        b.setTextColor(textColor);
        b.setTextSize(compact ? 11f : 13f);
        if (outlined) {
            GradientDrawable bg = roundBg(ctx, CARD, 7, OUTLINE);
            b.setBackground(bg);
            b.setOnFocusChangeListener((v, f) -> bg.setStroke(dp(ctx, f ? 2 : 1), f ? GOLD : OUTLINE));
        }
        b.setPadding(dp(ctx, compact ? 8 : 12), 0, dp(ctx, compact ? 8 : 12), 0);
        b.setOnClickListener(onClick);
        return b;
    }

    // ── Cards ─────────────────────────────────────────────────────────────────

    /** One rail card: wide art over title, price row, compact action. Fixed width. */
    static View railCard(Context ctx, GogCatalogItem item, GogCatalogItem.Action action,
                         Runnable onOpen, Runnable onAction) {
        LinearLayout card = column(ctx);
        GradientDrawable bg = roundBg(ctx, CARD, 10, OUTLINE);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        focusRing(card, bg, CARD, CARD_HI);
        card.setOnClickListener(v -> onOpen.run());

        card.addView(art(ctx, item.wideArt(), item.title, WIDE_RATIO, 0), lp(-1, -2));
        LinearLayout body = column(ctx);
        body.setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9));
        TextView title = oneLine(text(ctx, item.title, 13f, TEXT, true), 2);
        title.setMinLines(2);
        body.addView(title);
        View price = priceRow(ctx, item);
        LinearLayout.LayoutParams pl = lp(-1, -2); pl.topMargin = dp(ctx, 6);
        body.addView(price, pl);
        Button act = actionButton(ctx, action, true, v -> onAction.run());
        LinearLayout.LayoutParams al = lp(-1, dp(ctx, 32)); al.topMargin = dp(ctx, 7);
        body.addView(act, al);
        card.addView(body, lp(-1, -2));
        return card;
    }

    /** One search-result row: thumb + title + tags + price on the left, action on the right. */
    static View resultRow(Context ctx, GogCatalogItem item, GogCatalogItem.Action action,
                          Runnable onOpen, Runnable onAction) {
        LinearLayout r = row(ctx);
        GradientDrawable bg = roundBg(ctx, CARD, 10, OUTLINE);
        r.setBackground(bg);
        r.setPadding(dp(ctx, 9), dp(ctx, 9), dp(ctx, 9), dp(ctx, 9));
        r.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        focusRing(r, bg, CARD, CARD_HI);
        r.setOnClickListener(v -> onOpen.run());

        FrameLayout thumb = art(ctx, item.wideArt(), item.title, WIDE_RATIO, 6);
        LinearLayout.LayoutParams tl = lp(dp(ctx, 104), -2); tl.rightMargin = dp(ctx, 11);
        r.addView(thumb, tl);

        LinearLayout col = column(ctx);
        col.addView(oneLine(text(ctx, item.title, 13f, TEXT, true), 2));
        if (!item.tags.isEmpty()) {
            TextView tags = oneLine(text(ctx, item.tags, 11f, MUTED, false), 1);
            LinearLayout.LayoutParams gl = lp(-1, -2); gl.topMargin = dp(ctx, 3);
            col.addView(tags, gl);
        }
        LinearLayout.LayoutParams pl = lp(-1, -2); pl.topMargin = dp(ctx, 4);
        col.addView(priceRow(ctx, item), pl);
        r.addView(col, lpWeight(-2, 1f));

        Button act = actionButton(ctx, action, true, v -> onAction.run());
        LinearLayout.LayoutParams al = lp(dp(ctx, 118), dp(ctx, 32)); al.leftMargin = dp(ctx, 11);
        r.addView(act, al);
        return r;
    }

    /** The featured hero: full-bleed wide art with a bottom scrim carrying eyebrow / title / price. */
    static View heroCard(Context ctx, GogCatalogItem item, GogCatalogItem.Action action, String eyebrow,
                         Runnable onOpen, Runnable onAction) {
        FrameLayout box = new FrameLayout(ctx);
        GradientDrawable bg = roundBg(ctx, CARD, 12, OUTLINE);
        box.setBackground(bg);
        box.setClipToOutline(true);
        box.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        focusRing(box, bg, CARD, CARD_HI);
        box.setOnClickListener(v -> onOpen.run());

        box.addView(art(ctx, item.wideArt(), item.title, WIDE_RATIO, 0), new FrameLayout.LayoutParams(-1, -2));

        LinearLayout scrim = column(ctx);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xEB000000}));
        scrim.setPadding(dp(ctx, 13), dp(ctx, 28), dp(ctx, 13), dp(ctx, 11));
        scrim.addView(text(ctx, eyebrow, 10f, ACCENT_TEXT(), true));
        TextView title = oneLine(text(ctx, item.title, 20f, TEXT, true), 1);
        LinearLayout.LayoutParams tl = lp(-1, -2); tl.topMargin = dp(ctx, 4);
        scrim.addView(title, tl);
        LinearLayout bottom = row(ctx);
        bottom.addView(priceRow(ctx, item), lpWeight(-2, 1f));
        Button act = actionButton(ctx, action, true, v -> onAction.run());
        bottom.addView(act, lp(-2, dp(ctx, 32)));
        LinearLayout.LayoutParams bl = lp(-1, -2); bl.topMargin = dp(ctx, 6);
        scrim.addView(bottom, bl);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -2);
        sl.gravity = Gravity.BOTTOM;
        box.addView(scrim, sl);
        return box;
    }

    /** A lighter accent for text on the hero scrim (the accent itself is too dark on black). */
    private static int ACCENT_TEXT() { return 0xFFB39DFF; }

    /**
     * A 2:3 library tile: tall box art over the title, a small pill over the art's top-left
     * ("Gen 2") and an install-state ribbon over the top-right (Installed / Update / Partial).
     */
    static View libraryTile(Context ctx, GogCatalogItem item, String badgeText,
                            GogInstallState.Badge install, Runnable onOpen) {
        LinearLayout card = column(ctx);
        GradientDrawable bg = roundBg(ctx, CARD, 10, OUTLINE);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        focusRing(card, bg, CARD, CARD_HI);
        card.setOnClickListener(v -> onOpen.run());

        FrameLayout artBox = new FrameLayout(ctx);
        artBox.addView(art(ctx, item.tallArt(), item.title, TALL_RATIO, 0), new FrameLayout.LayoutParams(-1, -2));
        if (badgeText != null && !badgeText.isEmpty()) {
            TextView pill = text(ctx, badgeText, 9f, TEXT, true);
            pill.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
            pill.setBackground(roundBg(ctx, 0xE6333355, 5));
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2);
            pl.gravity = Gravity.TOP | Gravity.START;
            pl.setMargins(dp(ctx, 6), dp(ctx, 6), 0, 0);
            artBox.addView(pill, pl);
        }
        String ribbon = null; int ribbonColor = GREEN;
        switch (install) {
            case INSTALLED: ribbon = "✓ Installed"; ribbonColor = 0xE62E7D32; break;
            case UPDATE:    ribbon = "↑ Update";     ribbonColor = 0xE6B26A00; break;
            case PARTIAL:   ribbon = "Partial";           ribbonColor = 0xE68B0000; break;
            default: break;
        }
        if (ribbon != null) {
            TextView r = text(ctx, ribbon, 9f, TEXT, true);
            r.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
            r.setBackground(roundBg(ctx, ribbonColor, 5));
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2);
            rl.gravity = Gravity.TOP | Gravity.END;
            rl.setMargins(0, dp(ctx, 6), dp(ctx, 6), 0);
            artBox.addView(r, rl);
        }
        card.addView(artBox, lp(-1, -2));

        LinearLayout body = column(ctx);
        body.setPadding(dp(ctx, 9), dp(ctx, 8), dp(ctx, 9), dp(ctx, 8));
        TextView title = oneLine(text(ctx, item.title, 12f, TEXT, false), 2);
        title.setMinLines(2);
        body.addView(title);
        GogCatalogItem.Action a = install == GogInstallState.Badge.INSTALLED || install == GogInstallState.Badge.UPDATE
                ? GogCatalogItem.Action.OPEN_INSTALLED : GogCatalogItem.Action.OPEN_OWNED;
        Button act = actionButton(ctx, a, true, v -> onOpen.run());
        act.setFocusable(false);
        LinearLayout.LayoutParams al = lp(-1, dp(ctx, 30)); al.topMargin = dp(ctx, 7);
        body.addView(act, al);
        card.addView(body, lp(-1, -2));
        return card;
    }

    // ── Rails ─────────────────────────────────────────────────────────────────

    interface ActionResolver { GogCatalogItem.Action resolve(GogCatalogItem item); }
    interface ItemHandler { void handle(GogCatalogItem item); }

    /** One horizontal rail (header + scrolling row of cards). Empty list = nothing added. */
    static void addRail(Context ctx, LinearLayout into, String title, String sub, List<GogCatalogItem> items,
                        ActionResolver actionFor, ItemHandler onOpen, ItemHandler onAction) {
        if (items == null || items.isEmpty()) return;
        into.addView(sectionHeader(ctx, title, sub), lp(-1, -2));
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout rowL = row(ctx);
        rowL.setGravity(Gravity.TOP);
        rowL.setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (final GogCatalogItem g : items) {
            if (!seen.add(g.id)) continue;
            View card = railCard(ctx, g, actionFor.resolve(g), () -> onOpen.handle(g), () -> onAction.handle(g));
            LinearLayout.LayoutParams cl = lp(dp(ctx, RAIL_CARD_W_DP), -2);
            cl.rightMargin = dp(ctx, 11);
            rowL.addView(card, cl);
        }
        hs.addView(rowL, new FrameLayout.LayoutParams(-2, -2));
        into.addView(hs, lp(-1, -2));
    }

    // ── Tab strip ─────────────────────────────────────────────────────────────

    interface IntHandler { void handle(int index); }

    /** The pill tab strip with optional count badges; {@link #select} re-paints. */
    static final class TabStrip {
        final HorizontalScrollView view;
        private final LinearLayout rowL;
        private final Context ctx;
        private final String[] labels;
        private final TextView[] labelViews;
        private final TextView[] badgeViews;
        private final GradientDrawable[] bgs;
        private final LinearLayout[] pills;
        int selected;

        TabStrip(Context ctx, String[] labels, int selected, IntHandler onSelect) {
            this.ctx = ctx;
            this.labels = labels;
            this.selected = selected;
            view = new HorizontalScrollView(ctx);
            view.setHorizontalScrollBarEnabled(false);
            rowL = row(ctx);
            rowL.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 6));
            labelViews = new TextView[labels.length];
            badgeViews = new TextView[labels.length];
            bgs = new GradientDrawable[labels.length];
            pills = new LinearLayout[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                LinearLayout pill = row(ctx);
                pill.setPadding(dp(ctx, 15), dp(ctx, 9), dp(ctx, 15), dp(ctx, 9));
                GradientDrawable bg = roundBg(ctx, CARD, 9, OUTLINE);
                pill.setBackground(bg);
                pill.setFocusable(true);
                pill.setOnFocusChangeListener((v, f) -> paint(idx, f));
                pill.setOnClickListener(v -> { if (idx != this.selected) { select(idx); onSelect.handle(idx); } });
                TextView tv = text(ctx, labels[i], 13f, MUTED, true);
                pill.addView(tv, lp(-2, -2));
                TextView badge = text(ctx, "", 10f, MUTED, true);
                badge.setPadding(dp(ctx, 7), dp(ctx, 1), dp(ctx, 7), dp(ctx, 1));
                badge.setBackground(roundBg(ctx, BG, 20));
                badge.setVisibility(View.GONE);
                LinearLayout.LayoutParams bl = lp(-2, -2); bl.leftMargin = dp(ctx, 7);
                pill.addView(badge, bl);
                LinearLayout.LayoutParams pl = lp(-2, -2); pl.rightMargin = dp(ctx, 6);
                rowL.addView(pill, pl);
                labelViews[i] = tv; badgeViews[i] = badge; bgs[i] = bg; pills[i] = pill;
            }
            view.addView(rowL, new FrameLayout.LayoutParams(-2, -2));
            select(selected);
        }

        private void paint(int i, boolean focused) {
            boolean sel = i == selected;
            bgs[i].setColor(sel ? 0xFF2A1F55 : CARD);
            bgs[i].setStroke(dp(ctx, focused ? 2 : 1), focused ? GOLD : (sel ? 0xFF7A5CFF : OUTLINE));
            labelViews[i].setTextColor(sel ? 0xFFB39DFF : MUTED);
            badgeViews[i].setTextColor(sel ? 0xFFB39DFF : MUTED);
        }

        void select(int i) {
            selected = i;
            for (int k = 0; k < labels.length; k++) paint(k, pills[k].hasFocus());
        }

        void setBadge(int i, String s) {
            if (i < 0 || i >= badgeViews.length) return;
            badgeViews[i].setText(s == null ? "" : s);
            badgeViews[i].setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ── Primary progress button ───────────────────────────────────────────────

    /**
     * The detail page's ONE primary button: an accent block when actionable, or a read-only
     * progress fill (solid front fill = fraction) while downloading. 46dp, rounded 10.
     */
    static final class ProgressButton extends FrameLayout {
        private final View fill;
        private final TextView label;
        private final GradientDrawable bg;
        private final LinearLayout track;
        private float fraction = 0f;
        private boolean progressMode = false;

        ProgressButton(Context ctx) {
            super(ctx);
            bg = roundBg(ctx, ACCENT, 10);
            setBackground(bg);
            setClipToOutline(true);
            track = new LinearLayout(ctx);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setWeightSum(1000f);
            fill = new View(ctx);
            fill.setBackgroundColor(ACCENT);
            track.addView(fill, new LinearLayout.LayoutParams(0, -1, 0f));
            View rest = new View(ctx);
            track.addView(rest, new LinearLayout.LayoutParams(0, -1, 1000f));
            addView(track, new LayoutParams(-1, -1));
            label = text(ctx, "", 15f, TEXT, true);
            label.setGravity(Gravity.CENTER);
            oneLine(label, 1);
            addView(label, new LayoutParams(-1, -1));
            setFocusable(true);
            setOnFocusChangeListener((v, f) -> bg.setStroke(f ? dp(ctx, 2) : 0, f ? GOLD : 0));
        }

        /** Actionable: solid accent (or dimmed when disabled). */
        void setAction(String text, boolean enabled, OnClickListener onClick) {
            progressMode = false;
            label.setText(text);
            track.setVisibility(GONE);
            bg.setColor(enabled ? ACCENT : 0x665533CC);
            bg.setStroke(0, 0);
            setEnabled(enabled);
            setClickable(enabled);
            setOnClickListener(enabled ? onClick : null);
        }

        /** Read-only download fill. Tapping does nothing; cancel lives in the gear. */
        void setProgress(String text, float frac) {
            progressMode = true;
            fraction = Math.max(0f, Math.min(1f, frac));
            label.setText(text);
            track.setVisibility(VISIBLE);
            bg.setColor(SURFACE_VAR);
            bg.setStroke(dp(getContext(), 1), OUTLINE);
            ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = fraction * 1000f;
            ((LinearLayout.LayoutParams) track.getChildAt(1).getLayoutParams()).weight = (1f - fraction) * 1000f;
            track.requestLayout();
            setEnabled(false);
            setClickable(false);
            setOnClickListener(null);
        }

        boolean isProgressMode() { return progressMode; }
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    /** Round avatar with a candidate chain and an initial-letter placeholder. */
    static View avatar(Context ctx, List<String> candidates, String name, int sizeDp) {
        FrameLayout box = new FrameLayout(ctx);
        int px = dp(ctx, sizeDp);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(SURFACE_VAR);
        bg.setStroke(dp(ctx, 1), OUTLINE);
        box.setBackground(bg);
        box.setClipToOutline(true);
        TextView initial = text(ctx, name == null || name.isEmpty() ? "G" : name.substring(0, 1).toUpperCase(), sizeDp >= 64 ? 26f : 15f, TEXT2, true);
        initial.setGravity(Gravity.CENTER);
        box.addView(initial, new FrameLayout.LayoutParams(px, px));
        final ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setVisibility(View.GONE);
        box.addView(iv, new FrameLayout.LayoutParams(px, px));
        // The OVAL background + clipToOutline clips the bitmap to the circle; no custom drawable.
        GogImageLoader.load(iv, candidates, bmp -> iv.setVisibility(View.VISIBLE));
        return box;
    }

    /** A stat tile: big number over a muted label. */
    static View statTile(Context ctx, String label, int value) {
        LinearLayout c = column(ctx);
        c.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        c.setBackground(roundBg(ctx, CARD, 10, OUTLINE));
        c.addView(oneLine(text(ctx, String.format(java.util.Locale.getDefault(), "%,d", value), 20f, 0xFFB39DFF, true), 1));
        c.addView(oneLine(text(ctx, label, 11f, MUTED, false), 1));
        return c;
    }

    static void hideSystemBars(Activity a) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            a.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L) return String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0);
        return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
    }

    /** "2024-03-01" / "2024.03.01" → "Mar 1, 2024"; anything else passes through. */
    static String formatDate(String iso) {
        if (iso == null || iso.length() < 10) return iso != null ? iso : "";
        String[] parts = iso.substring(0, 10).split("[.\\-]");
        if (parts.length != 3) return iso.substring(0, 10);
        try {
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            String[] months = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
            if (month < 1 || month > 12) return iso.substring(0, 10);
            return months[month - 1] + " " + day + ", " + year;
        } catch (Exception e) {
            return iso.substring(0, 10);
        }
    }

    /** Helper for a bitmap-backed hero when the loader already handed us the bitmap. */
    static void setBitmap(ImageView iv, Bitmap bmp) {
        if (iv != null && bmp != null) iv.setImageBitmap(bmp);
    }
}
