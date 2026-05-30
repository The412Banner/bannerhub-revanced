package app.revanced.extension.gamehub.explore;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * BannerHub-owned Explore screen — opened when the user taps the (otherwise
 * unused) "Explore" bottom-nav tab, hijacked by {@code ExploreTabHijackPatch}
 * via {@link com.xj.winemu.explore.BhExploreTabClick}.
 *
 * Instead of forging xiaoji's server-driven discovery feed (which we don't
 * control), this renders content WE own from {@link BhExploreManifest}, with
 * each card routed to our own handlers by {@link BhExploreActions}. Classic
 * programmatic Views only — our ReVanced extension has no Compose compiler
 * plugin. See GOG_LIBRARY_TAB_DESIGN §42.
 *
 * This build is the "how fancy can it get" prototype: a store-style homepage
 * with a hero banner, cover-art game rails, a news rail that opens article
 * pages, badges, and network image loading (with an offline gradient
 * fallback). Layout is driven entirely by the manifest's rail {@code type}.
 */
public class BannerExploreActivity extends Activity {

    private static final int BG          = 0xFF0D0D0D;
    private static final int CARD_BG     = 0xFF1C1C1E;
    private static final int CARD_STROKE = 0xFF2E2E32;
    private static final int TEXT        = 0xFFFFFFFF;
    private static final int TEXT_DIM    = 0xFFB0B0B5;
    private static final int ACCENT      = 0xFF8B5CF6; // GOG-ish purple
    private static final int PLACEHOLDER = 0xFF26262B;

    private static final int DISCORD_BG  = 0xFF5865F2; // Discord blurple
    private static final int REDDIT_BG   = 0xFFFF4500; // Reddit orange
    private static final int DL_BG       = 0xFF2F8F4E; // downloads green

    private static final String URL_RELEASES =
        "https://github.com/The412Banner/bannerhub-revanced/releases";
    private static final String URL_DISCORD = "https://discord.gg/n8S4G2WZQ4";
    private static final String URL_REDDIT = "https://www.reddit.com/user/The412Banner";
    private static final String URL_DL_COUNT =
        "https://img.shields.io/github/downloads/The412Banner/bannerhub-revanced/total.json";

    private static final int UPDATE_BG     = 0xFF2E2710; // amber-tinted banner
    private static final int UPDATE_STROKE = 0xFF6B5524;
    private static final int UPDATE_ACCENT = 0xFFFFC857; // amber "Get" button / highlight

    private static final String PREFS = "bh_explore";
    private static final String KEY_DL_COUNT = "dl_count"; // last value seen online
    private static final String KEY_UPDATE_ALERT_DISABLED = "update_alert_disabled";

    private LinearLayout column;
    private List<BhExploreManifest.Rail> currentRails;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(BG);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroller.addView(column, new ScrollView.LayoutParams(-1, -1));

        // Instant render from the local sources (no network on this path).
        renderRails(BhExploreManifest.load(this));

        setContentView(scroller);
        hideSystemBars();

        // Then refresh from the latest stable Release asset in the background;
        // re-render only if the content actually changed. Offline / no change /
        // any error → we silently keep what's already on screen.
        new Thread(new Runnable() {
            @Override public void run() {
                final List<BhExploreManifest.Rail> fresh =
                    BhExploreManifest.refreshFromNetwork(getApplicationContext());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        // Re-render with the freshest rails if they changed, else
                        // re-render the current ones so a newly-learned "latest"
                        // version shows the update banner / version readout.
                        List<BhExploreManifest.Rail> toRender =
                            (fresh != null && !fresh.isEmpty()) ? fresh : currentRails;
                        if (toRender != null) renderRails(toRender);
                    }
                });
            }
        }, "bh-explore-refresh").start();
    }

    /** (Re)build the whole list: back button, header, hero, the social/links
     *  badge row, then the remaining rails. */
    private void renderRails(List<BhExploreManifest.Rail> rails) {
        currentRails = rails;
        column.removeAllViews();

        column.addView(buildTopBar());

        TextView title = new TextView(this);
        title.setText("Explore");
        title.setTextColor(TEXT);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(4), dp(6), 0, dp(4));
        column.addView(title);

        View versionLine = buildVersionLine();
        if (versionLine != null) column.addView(versionLine);

        if (showUpdateBanner()) column.addView(buildUpdateBanner());

        if (rails == null || rails.isEmpty()) {
            column.addView(emptyState());
            return;
        }
        for (BhExploreManifest.Rail rail : rails) {
            column.addView(buildRail(rail));
            // Downloads / Discord / Reddit badges sit right under the hero.
            if ("hero".equals(rail.type)) column.addView(buildBadgesRow());
        }
    }

    /** Small "← Back" chip at the very top — finishes the screen, returning to
     *  GameHub (the tab the user came from). */
    private View buildBackButton() {
        TextView back = new TextView(this);
        back.setText("←  Back");
        back.setTextColor(TEXT);
        back.setTextSize(14);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setPadding(dp(14), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), CARD_STROKE);
        back.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.bottomMargin = dp(6);
        back.setLayoutParams(lp);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        return back;
    }

    /** Top row: "← Back" chip on the left, a settings ⚙ cog on the right. */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.bottomMargin = dp(6);
        bar.setLayoutParams(barLp);

        bar.addView(buildBackButton());

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        bar.addView(buildSettingsCog());
        return bar;
    }

    /** Small gear chip → opens the update-alert settings dialog. */
    private View buildSettingsCog() {
        TextView cog = new TextView(this);
        cog.setText("⚙"); // ⚙
        cog.setTextColor(TEXT);
        cog.setTextSize(16);
        cog.setTypeface(Typeface.DEFAULT_BOLD);
        cog.setGravity(Gravity.CENTER);
        cog.setPadding(dp(13), dp(8), dp(13), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), CARD_STROKE);
        cog.setBackground(bg);
        cog.setContentDescription("Update settings");
        cog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openUpdateSettings(); }
        });
        return cog;
    }

    /** Settings dialog: toggle update notifications + show installed/latest. */
    private void openUpdateSettings() {
        final android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final boolean disabled = p.getBoolean(KEY_UPDATE_ALERT_DISABLED, false);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        box.setPadding(pad, dp(8), pad, 0);

        final android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText("Notify me when an update is available");
        cb.setTextColor(TEXT);
        cb.setChecked(!disabled); // checked = notifications ON
        box.addView(cb);

        TextView info = new TextView(this);
        info.setText(versionSummary());
        info.setTextColor(TEXT_DIM);
        info.setTextSize(12);
        info.setPadding(dp(2), dp(10), 0, dp(4));
        box.addView(info);

        new android.app.AlertDialog.Builder(this)
            .setTitle("Updates")
            .setView(box)
            .setPositiveButton("Done", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) {
                    p.edit().putBoolean(KEY_UPDATE_ALERT_DISABLED, !cb.isChecked()).apply();
                    if (currentRails != null) renderRails(currentRails); // reflect toggle
                }
            })
            .show();
    }

    /** "Version 1.6.0-604 · up to date" / "· latest 1.7.0-604" — null if the
     *  installed version asset isn't present (e.g. the preview harness). */
    private View buildVersionLine() {
        String installed = BhExploreManifest.installedVersion(this);
        if (installed == null) return null;

        boolean update = BhExploreManifest.updateAvailable(this);
        String latest = BhExploreManifest.latestVersion(this);

        TextView tv = new TextView(this);
        String text = "Version " + installed;
        if (update && latest != null) {
            text += "  ·  latest " + latest;
        } else if (BhExploreManifest.latestBuild(this) > 0) {
            text += "  ·  up to date";
        }
        tv.setText(text);
        tv.setTextColor(update ? UPDATE_ACCENT : TEXT_DIM);
        tv.setTextSize(12);
        tv.setPadding(dp(4), 0, 0, dp(14));
        return tv;
    }

    /** Plain-text version summary for the settings dialog. */
    private String versionSummary() {
        String installed = BhExploreManifest.installedVersion(this);
        String latest = BhExploreManifest.latestVersion(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Installed: ").append(installed != null ? installed : "unknown");
        if (latest != null) {
            sb.append("\nLatest: ").append(latest);
            sb.append(BhExploreManifest.updateAvailable(this)
                ? "  (update available)" : "  (up to date)");
        }
        return sb.toString();
    }

    private boolean showUpdateBanner() {
        boolean disabled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_UPDATE_ALERT_DISABLED, false);
        return !disabled && BhExploreManifest.updateAvailable(this);
    }

    /** Dismiss-by-cog amber banner shown when a newer release is available. */
    private View buildUpdateBanner() {
        final String latest = BhExploreManifest.latestVersion(this);

        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), dp(13), dp(13), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UPDATE_BG);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), UPDATE_STROKE);
        banner.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(18);
        banner.setLayoutParams(lp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText("Update available");
        t.setTextColor(UPDATE_ACCENT);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText(latest != null
            ? ("Version " + latest + " — tap to download")
            : "Tap to download the latest version");
        s.setTextColor(0xFFE7D9B0);
        s.setTextSize(12);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
        sLp.topMargin = dp(2);
        textCol.addView(s, sLp);

        banner.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView btn = new TextView(this);
        btn.setText("Get");
        btn.setTextColor(0xFF1A1408);
        btn.setTextSize(13);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(UPDATE_ACCENT);
        btnBg.setCornerRadius(dp(18));
        btn.setBackground(btnBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
        btnLp.leftMargin = dp(10);
        banner.addView(btn, btnLp);

        View.OnClickListener go = new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl(URL_RELEASES); }
        };
        banner.setOnClickListener(go);
        btn.setOnClickListener(go);
        return banner;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ── Rail dispatch ────────────────────────────────────────────────────────

    private View buildRail(BhExploreManifest.Rail rail) {
        String type = rail.type == null ? "shortcuts" : rail.type;
        switch (type) {
            case "hero":  return buildHero(rail);
            case "news":  return buildCarousel(rail, CardStyle.NEWS);
            case "games": return buildCarousel(rail, CardStyle.GAME);
            default:      return buildCarousel(rail, CardStyle.SHORTCUT);
        }
    }

    // ── Hero banner ──────────────────────────────────────────────────────────

    private View buildHero(BhExploreManifest.Rail rail) {
        final BhExploreManifest.Card card = rail.cards.get(0);

        // A bundled drawable (e.g. our BannerHub logo) → render it as a crisp,
        // un-cropped emblem beside the text on a gradient. A network image →
        // the photo-background-with-scrim style.
        int logoId = resolveDrawable(card.icon);
        return logoId != 0 ? buildLogoHero(card, logoId) : buildPhotoHero(card);
    }

    /** Wide BannerHub wordmark across the top, text below — on a dark band that
     *  blends the logo's black background. */
    private View buildLogoHero(final BhExploreManifest.Card card, int logoId) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.bottomMargin = dp(24);
        hero.setLayoutParams(heroLp);

        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{ 0xFF1C1530, 0xFF0A0A0C });   // dark purple → near-black
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0xFF2E2650);
        hero.setBackground(bg);
        hero.setClipToOutline(true);

        if (notEmpty(card.badge)) hero.addView(badge(card.badge));

        ImageView logo = new ImageView(this);
        logo.setImageResource(logoId);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(-1, dp(64));
        logoLp.topMargin = dp(10);
        logoLp.gravity = Gravity.START;
        hero.addView(logo, logoLp);

        TextView t = new TextView(this);
        t.setText(card.label);
        t.setTextColor(TEXT);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
        tLp.topMargin = dp(14);
        hero.addView(t, tLp);

        if (notEmpty(card.subtitle)) {
            TextView s = new TextView(this);
            s.setText(card.subtitle);
            s.setTextColor(0xFFD9D2EE);
            s.setTextSize(13);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
            sLp.topMargin = dp(4);
            hero.addView(s, sLp);
        }

        hero.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatch(card); }
        });
        return hero;
    }

    /** Full-bleed network photo + bottom scrim + overlaid text. */
    private View buildPhotoHero(final BhExploreManifest.Card card) {
        FrameLayout hero = new FrameLayout(this);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, dp(190));
        heroLp.bottomMargin = dp(24);
        hero.setLayoutParams(heroLp);
        hero.setClipToOutline(true);

        GradientDrawable frame = new GradientDrawable();
        frame.setColor(PLACEHOLDER);
        frame.setCornerRadius(dp(18));
        hero.setBackground(frame);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(img, new FrameLayout.LayoutParams(-1, -1));
        roundClip(img, dp(18));
        BhImageLoader.load(img, card.image);

        View scrim = new View(this);
        GradientDrawable scrimBg = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{ Color.TRANSPARENT, 0x00000000, 0xCC000000 });
        scrimBg.setCornerRadius(dp(18));
        scrim.setBackground(scrimBg);
        hero.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(18), dp(18), dp(18), dp(18));
        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(-1, -2);
        overlayLp.gravity = Gravity.BOTTOM;
        hero.addView(overlay, overlayLp);

        if (notEmpty(card.badge)) overlay.addView(badge(card.badge));

        TextView t = new TextView(this);
        t.setText(card.label);
        t.setTextColor(TEXT);
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
        tLp.topMargin = dp(8);
        overlay.addView(t, tLp);

        if (notEmpty(card.subtitle)) {
            TextView s = new TextView(this);
            s.setText(card.subtitle);
            s.setTextColor(0xFFE0E0E5);
            s.setTextSize(13);
            overlay.addView(s);
        }

        hero.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatch(card); }
        });
        return hero;
    }

    /** Row of small link badges under the hero: live Downloads · Discord · Reddit. */
    private View buildBadgesRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.bottomMargin = dp(22);
        row.setLayoutParams(rowLp);
        row.setPadding(dp(2), 0, 0, 0);

        // Downloads — seed from the last count seen online so it survives going
        // offline; the placeholder only shows before the very first online fetch.
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DL_COUNT, null);
        // Guard against a cache poisoned by a shields.io error string (older
        // builds saved whatever `message` held); show the placeholder instead.
        if (last != null && !looksLikeDownloadCount(last)) last = null;
        TextView dl = chip(last != null ? "↓ " + last : "↓ …", DL_BG, URL_RELEASES);
        row.addView(dl);
        fetchDownloads(dl);

        row.addView(chip("Discord", DISCORD_BG, URL_DISCORD));
        row.addView(chip("Reddit", REDDIT_BG, URL_REDDIT));
        return row;
    }

    /** A small rounded, brand-coloured, tappable badge. */
    private TextView chip(String label, int bgColor, final String url) {
        TextView c = new TextView(this);
        c.setText(label);
        c.setTextColor(0xFFFFFFFF);
        c.setTextSize(12);
        c.setTypeface(Typeface.DEFAULT_BOLD);
        c.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        c.setLayoutParams(lp);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl(url); }
        });
        return c;
    }

    private void openUrl(String url) {
        try {
            android.content.Intent i = new android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            android.util.Log.w("BhExplore", "openUrl failed: " + url, t);
        }
    }

    /** Fetch the live total-downloads count (shields.io JSON, same source as the
     *  README badge) off the main thread and update the chip. Offline → the chip
     *  just keeps its placeholder. */
    private void fetchDownloads(final TextView chip) {
        new Thread(new Runnable() {
            @Override public void run() {
                java.net.HttpURLConnection conn = null;
                try {
                    conn = (java.net.HttpURLConnection)
                        new java.net.URL(URL_DL_COUNT).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setInstanceFollowRedirects(true);
                    conn.connect();
                    if (conn.getResponseCode() != 200) return;
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    java.io.InputStream in = conn.getInputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    String msg = new org.json.JSONObject(bos.toString("UTF-8"))
                        .optString("message", "").trim();
                    // shields.io returns its OWN internal errors (rate-limit,
                    // "Unable to select next GitHub token from pool", "invalid",
                    // "inaccessible", etc.) in this same `message` field with an
                    // HTTP 200. Only accept values that actually look like a
                    // humanized download count; otherwise keep the cached /
                    // placeholder text instead of rendering shields' error.
                    if (!looksLikeDownloadCount(msg)) {
                        android.util.Log.d("BhExplore",
                            "downloads message not a count, ignoring: " + msg);
                        return;
                    }
                    // Remember it so a later offline open still shows this number.
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_DL_COUNT, msg).apply();
                    final String text = "↓ " + msg;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (!isFinishing()) chip.setText(text);
                        }
                    });
                } catch (Throwable t) {
                    android.util.Log.d("BhExplore", "downloads fetch skipped: " + t.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "bh-downloads").start();
    }

    /** True iff a shields.io {@code message} looks like a humanized download
     *  count — digits with optional {@code . ,} grouping and a single
     *  k/M/G/B/T magnitude suffix (e.g. {@code "12"}, {@code "1,234"},
     *  {@code "1.2k"}, {@code "3M"}). Rejects error strings shields.io serves
     *  in the same field (e.g. "Unable to select next GitHub token from pool"),
     *  which always contain spaces / non-suffix letters. */
    private static boolean looksLikeDownloadCount(String s) {
        return s != null && s.matches("\\d[\\d.,]*\\s?[kKmMgGbBtT]?");
    }

    /** Resolve a drawable resource NAME against the host package; 0 if absent. */
    private int resolveDrawable(String name) {
        if (!notEmpty(name)) return 0;
        try {
            return getResources().getIdentifier(name, "drawable", getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ── Carousels (news / games / shortcuts) ──────────────────────────────────

    private enum CardStyle { NEWS, GAME, SHORTCUT }

    private View buildCarousel(BhExploreManifest.Rail rail, CardStyle style) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = dp(22);
        section.setLayoutParams(sectionLp);

        if (notEmpty(rail.title)) {
            TextView header = new TextView(this);
            header.setText(rail.title);
            header.setTextColor(TEXT);
            header.setTextSize(19);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setPadding(dp(4), 0, 0, dp(12));
            section.addView(header);
        }

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));

        for (BhExploreManifest.Card card : rail.cards) {
            switch (style) {
                case NEWS: row.addView(buildNewsCard(card)); break;
                case GAME: row.addView(buildGameCard(card)); break;
                default:   row.addView(buildShortcutCard(card)); break;
            }
        }
        section.addView(hsv);
        return section;
    }

    /** Wide 16:9 image + headline + date. */
    private View buildNewsCard(final BhExploreManifest.Card card) {
        LinearLayout cardView = new LinearLayout(this);
        cardView.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(264), -2);
        lp.rightMargin = dp(14);
        cardView.setLayoutParams(lp);
        applyCardBg(cardView);

        FrameLayout imgWrap = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(PLACEHOLDER);
        imgWrap.addView(img, new FrameLayout.LayoutParams(-1, dp(148)));
        roundClip(img, dp(14));
        BhImageLoader.load(img, card.image);
        if (notEmpty(card.badge)) {
            FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(-2, -2);
            bLp.setMargins(dp(10), dp(10), 0, 0);
            View b = badge(card.badge);
            imgWrap.addView(b, bLp);
        }
        cardView.addView(imgWrap, new LinearLayout.LayoutParams(-1, dp(148)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(12), dp(12), dp(12), dp(14));

        TextView headline = new TextView(this);
        headline.setText(card.label);
        headline.setTextColor(TEXT);
        headline.setTextSize(15);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setMaxLines(2);
        textCol.addView(headline);

        if (notEmpty(card.date)) {
            TextView d = new TextView(this);
            d.setText(card.date);
            d.setTextColor(TEXT_DIM);
            d.setTextSize(12);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, -2);
            dLp.topMargin = dp(6);
            textCol.addView(d, dLp);
        }
        cardView.addView(textCol);

        cardView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatch(card); }
        });
        return cardView;
    }

    /** Portrait cover-art card. */
    private View buildGameCard(final BhExploreManifest.Card card) {
        LinearLayout cardView = new LinearLayout(this);
        cardView.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(124), -2);
        lp.rightMargin = dp(12);
        cardView.setLayoutParams(lp);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable ph = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[]{ 0xFF2A2A30, 0xFF18181C });
        ph.setCornerRadius(dp(14));
        img.setBackground(ph);
        img.setClipToOutline(true);
        roundClip(img, dp(14));
        cardView.addView(img, new LinearLayout.LayoutParams(dp(124), dp(176)));
        BhImageLoader.load(img, card.image);

        TextView label = new TextView(this);
        label.setText(card.label);
        label.setTextColor(TEXT);
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setMaxLines(1);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
        labelLp.topMargin = dp(8);
        labelLp.leftMargin = dp(2);
        cardView.addView(label, labelLp);

        cardView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatch(card); }
        });
        return cardView;
    }

    /** The original compact card: drawable icon (or accent chip) + label + sub. */
    private View buildShortcutCard(final BhExploreManifest.Card card) {
        LinearLayout cardView = new LinearLayout(this);
        cardView.setOrientation(LinearLayout.VERTICAL);
        cardView.setPadding(dp(14), dp(14), dp(14), dp(14));
        applyCardBg(cardView);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(168), -2);
        lp.rightMargin = dp(12);
        cardView.setLayoutParams(lp);

        View iconView = null;
        if (notEmpty(card.icon)) {
            try {
                int id = getResources().getIdentifier(card.icon, "drawable", getPackageName());
                if (id != 0) {
                    ImageView iv = new ImageView(this);
                    iv.setImageResource(id);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iconView = iv;
                }
            } catch (Throwable ignored) { }
        }
        if (iconView == null) {
            View chip = new View(this);
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setColor(ACCENT);
            chipBg.setCornerRadius(dp(8));
            chip.setBackground(chipBg);
            iconView = chip;
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        iconLp.bottomMargin = dp(12);
        cardView.addView(iconView, iconLp);

        TextView label = new TextView(this);
        label.setText(card.label);
        label.setTextColor(TEXT);
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        cardView.addView(label);

        if (notEmpty(card.subtitle)) {
            TextView sub = new TextView(this);
            sub.setText(card.subtitle);
            sub.setTextColor(TEXT_DIM);
            sub.setTextSize(12);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = dp(4);
            cardView.addView(sub, subLp);
        }

        cardView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dispatch(card); }
        });
        return cardView;
    }

    // ── Shared bits ───────────────────────────────────────────────────────────

    private void dispatch(BhExploreManifest.Card card) {
        BhExploreActions.dispatch(this, card);
    }

    private TextView badge(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(10);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(true);
        b.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        b.setLayoutParams(lp);
        return b;
    }

    private void applyCardBg(View v) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), CARD_STROKE);
        v.setBackground(bg);
        v.setClipToOutline(true);
    }

    /** Round-clip a view to a radius (API 21+, ignored below). */
    private void roundClip(final View v, final int radius) {
        if (Build.VERSION.SDK_INT >= 21) {
            v.setClipToOutline(true);
            v.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
        }
    }

    private View emptyState() {
        TextView tv = new TextView(this);
        tv.setText("Nothing here yet.");
        tv.setTextColor(TEXT_DIM);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(48), 0, 0);
        return tv;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * Match GameHub's fullscreen look — hide both the top status/notification bar
     * and the bottom navigation bar (immersive sticky). WindowInsetsController on
     * API 30+, legacy SYSTEM_UI_FLAG_* below. Must run AFTER setContentView so the
     * decor view exists (pre26 crash: NPE on a null DecorView's controller).
     */
    private void hideSystemBars() {
        try {
            Window window = getWindow();
            View decor = window.getDecorView();
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController c = decor.getWindowInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable t) {
            android.util.Log.w("BhExplore", "hideSystemBars failed", t);
        }
    }

    private int dp(int v) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (v * density);
    }
}
