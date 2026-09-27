package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The GOG <b>Store</b> tab — the hub's front door, Bannerlator's Steam-style Store tab re-cut
 * over GOG's public catalog ({@link GogStoreCatalog}).
 *
 * Portrait: search, featured hero, then Trending / Free games / New releases / Deals rails.
 * Landscape: two columns — hero + the free-games list on the left (the titles you can actually
 * act on), the browse rails on the right. Typing replaces either layout with a result list.
 *
 * Owned titles open the owned detail page ({@link GogGameDetailActivity}); everything else opens
 * the store-only detail page ({@link GogCatalogDetailActivity}). "Get for free" / "View on
 * GOG.com" open the product page in the browser — GOG exposes no add-to-library API to third
 * parties, so a claim is a web checkout, exactly as Heroic does it.
 */
final class GogStoreTabView {

    interface Host {
        Set<String> ownedIds();
        Set<String> installedIds();
        void openOwned(String gameId);
        void openCatalog(GogCatalogItem item);
        void openWeb(String url, String title);
        void message(String text);
    }

    private static final long SEARCH_DEBOUNCE_MS = 350L;

    final LinearLayout view;
    private final Activity ctx;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final EditText search;
    private final FrameLayout content;

    private GogStoreCatalog.Featured featured;
    private boolean loading;
    private boolean everFailed;
    private String query = "";
    private List<GogCatalogItem> results = new ArrayList<>();
    private boolean searching;
    private int searchSeq = 0;
    private final Runnable searchRunnable = this::runSearch;

    GogStoreTabView(Activity ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        view = BhStoreUi.column(ctx);
        view.setBackgroundColor(BhStoreUi.BG);

        search = BhStoreUi.searchField(ctx, "Search the GOG catalog…");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString();
                ui.removeCallbacks(searchRunnable);
                if (query.trim().length() < 2) { results = new ArrayList<>(); searching = false; render(); return; }
                searching = true;
                render();
                ui.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        search.setOnEditorActionListener((v, actionId, ev) -> { hideKeyboard(); return false; });
        view.addView(search, BhStoreUi.searchLp(ctx));

        content = new FrameLayout(ctx);
        view.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        featured = GogStoreCatalog.cachedFeatured(ctx);
        loading = featured == null;
        render();
        refresh(false);
    }

    /** Re-paint the rails' action buttons (ownership / install state may have changed). */
    void onResume() { render(); }

    void refresh(final boolean force) {
        if (featured == null) { loading = true; render(); }
        new Thread(() -> {
            GogStoreCatalog.Featured fresh = null;
            try { fresh = GogStoreCatalog.featured(ctx, force); } catch (Throwable ignored) {}
            final GogStoreCatalog.Featured f = fresh;
            ui.post(() -> {
                if (f != null) featured = f;
                loading = false;
                if (featured == null && !everFailed) { everFailed = true; host.message("GOG's catalog didn't answer"); }
                render();
            });
        }, "gog-store-rails").start();
    }

    private void runSearch() {
        final String term = query.trim();
        final int seq = ++searchSeq;
        new Thread(() -> {
            List<GogCatalogItem> r = new ArrayList<>();
            try { r = GogStoreCatalog.search(term); } catch (Throwable ignored) {}
            final List<GogCatalogItem> res = r;
            ui.post(() -> {
                if (seq != searchSeq) return;
                results = res;
                searching = false;
                render();
            });
        }, "gog-store-search").start();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        } catch (Exception ignored) {}
        search.clearFocus();
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private GogCatalogItem.Action actionFor(GogCatalogItem item) {
        if (host.ownedIds().contains(item.id)) {
            return host.installedIds().contains(item.id)
                    ? GogCatalogItem.Action.OPEN_INSTALLED : GogCatalogItem.Action.OPEN_OWNED;
        }
        return item.isFree ? GogCatalogItem.Action.CLAIM_FREE : GogCatalogItem.Action.VIEW_ON_STORE;
    }

    private void open(GogCatalogItem item) {
        if (host.ownedIds().contains(item.id)) host.openOwned(item.id);
        else host.openCatalog(item);
    }

    private void act(GogCatalogItem item) {
        switch (actionFor(item)) {
            case OPEN_INSTALLED:
            case OPEN_OWNED:
                host.openOwned(item.id);
                break;
            default:
                if (item.storeUrl.isEmpty()) host.message("No store page for " + item.title);
                else host.openWeb(item.storeUrl, item.title);
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void render() {
        content.removeAllViews();
        if (query.trim().length() >= 2) { content.addView(buildResults(), new FrameLayout.LayoutParams(-1, -1)); return; }
        if (loading && featured == null) {
            ProgressBar pb = new ProgressBar(ctx);
            FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(-2, -2);
            l.gravity = Gravity.CENTER;
            content.addView(pb, l);
            return;
        }
        if (featured == null || featured.isEmpty()) {
            content.addView(BhStoreUi.notice(ctx, "Store browsing is unavailable",
                    "GOG's catalog didn't answer. Search still works, and everything you already own is in the Library tab.",
                    "Retry", () -> refresh(true)), new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        content.addView(BhStoreUi.isWide(ctx) ? buildLandscape(featured) : buildPortrait(featured),
                new FrameLayout.LayoutParams(-1, -1));
    }

    private View heroFor(GogStoreCatalog.Featured data) {
        if (data.hero == null) return null;
        final GogCatalogItem hero = data.hero;
        View card = BhStoreUi.heroCard(ctx, hero, actionFor(hero), "TRENDING ON GOG",
                () -> open(hero), () -> act(hero));
        LinearLayout.LayoutParams l = BhStoreUi.lp(-1, -2);
        l.setMargins(BhStoreUi.dp(ctx, 14), BhStoreUi.dp(ctx, 12), BhStoreUi.dp(ctx, 14), BhStoreUi.dp(ctx, 4));
        card.setLayoutParams(l);
        return card;
    }

    private View buildPortrait(GogStoreCatalog.Featured data) {
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = BhStoreUi.column(ctx);
        col.setPadding(0, 0, 0, BhStoreUi.dp(ctx, 24));
        View hero = heroFor(data);
        if (hero != null) col.addView(hero);
        BhStoreUi.addRail(ctx, col, "Trending", "Popular right now", data.trending, this::actionFor, this::open, this::act);
        BhStoreUi.addRail(ctx, col, "Free Games", "Claim on GOG.com", data.free, this::actionFor, this::open, this::act);
        BhStoreUi.addRail(ctx, col, "New Releases", "Just landed", data.newReleases, this::actionFor, this::open, this::act);
        BhStoreUi.addRail(ctx, col, "Deals", "Biggest discounts", data.deals, this::actionFor, this::open, this::act);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private View buildLandscape(GogStoreCatalog.Featured data) {
        LinearLayout rowL = new LinearLayout(ctx);
        rowL.setOrientation(LinearLayout.HORIZONTAL);

        ScrollView left = new ScrollView(ctx);
        left.setVerticalScrollBarEnabled(false);
        LinearLayout lcol = BhStoreUi.column(ctx);
        lcol.setPadding(0, 0, 0, BhStoreUi.dp(ctx, 24));
        View hero = heroFor(data);
        if (hero != null) lcol.addView(hero);
        if (!data.free.isEmpty()) {
            lcol.addView(BhStoreUi.sectionHeader(ctx, "Free Games", "Claim on GOG.com"), BhStoreUi.lp(-1, -2));
            Set<String> seen = new HashSet<>();
            for (final GogCatalogItem g : data.free) {
                if (!seen.add(g.id)) continue;
                View r = BhStoreUi.resultRow(ctx, g, actionFor(g), () -> open(g), () -> act(g));
                LinearLayout.LayoutParams l = BhStoreUi.lp(-1, -2);
                l.setMargins(BhStoreUi.dp(ctx, 14), BhStoreUi.dp(ctx, 4), BhStoreUi.dp(ctx, 14), BhStoreUi.dp(ctx, 4));
                lcol.addView(r, l);
            }
        }
        left.addView(lcol, new FrameLayout.LayoutParams(-1, -2));
        rowL.addView(left, new LinearLayout.LayoutParams(0, -1, 0.42f));

        ScrollView right = new ScrollView(ctx);
        right.setVerticalScrollBarEnabled(false);
        LinearLayout rcol = BhStoreUi.column(ctx);
        rcol.setPadding(0, 0, 0, BhStoreUi.dp(ctx, 24));
        BhStoreUi.addRail(ctx, rcol, "Trending", "Popular right now", data.trending, this::actionFor, this::open, this::act);
        BhStoreUi.addRail(ctx, rcol, "New Releases", "Just landed", data.newReleases, this::actionFor, this::open, this::act);
        BhStoreUi.addRail(ctx, rcol, "Deals", "Biggest discounts", data.deals, this::actionFor, this::open, this::act);
        if (data.trending.isEmpty() && data.newReleases.isEmpty() && data.deals.isEmpty()) {
            rcol.addView(BhStoreUi.notice(ctx, "Nothing to browse right now", "GOG's catalog feeds came back empty.", null, null));
        }
        right.addView(rcol, new FrameLayout.LayoutParams(-1, -2));
        rowL.addView(right, new LinearLayout.LayoutParams(0, -1, 0.58f));
        return rowL;
    }

    /** The vertical result list that replaces the rails while a query is active. */
    private View buildResults() {
        LinearLayout col = BhStoreUi.column(ctx);
        List<GogCatalogItem> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GogCatalogItem r : results) if (seen.add(r.id)) rows.add(r);
        String head = (searching && rows.isEmpty()) ? "Searching…"
                : rows.size() + (rows.size() == 1 ? " result" : " results");
        col.addView(BhStoreUi.sectionHeader(ctx, head, "“" + query.trim() + "”"), BhStoreUi.lp(-1, -2));
        if (rows.isEmpty() && searching) {
            ProgressBar pb = new ProgressBar(ctx);
            LinearLayout.LayoutParams l = BhStoreUi.lp(-2, -2);
            l.gravity = Gravity.CENTER_HORIZONTAL; l.topMargin = BhStoreUi.dp(ctx, 32);
            col.addView(pb, l);
            return col;
        }
        if (rows.isEmpty()) {
            col.addView(BhStoreUi.notice(ctx, "No matches", "Nothing on GOG matched that. Try a shorter or different title.", null, null));
            return col;
        }
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = BhStoreUi.column(ctx);
        int hp = BhStoreUi.dp(ctx, 14);
        list.setPadding(hp, BhStoreUi.dp(ctx, 4), hp, BhStoreUi.dp(ctx, 24));
        boolean wide = BhStoreUi.isWide(ctx);
        int per = wide ? 2 : 1;
        for (int i = 0; i < rows.size(); i += per) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < per; k++) {
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
                if (k > 0) l.leftMargin = BhStoreUi.dp(ctx, 9);
                if (i + k < rows.size()) {
                    final GogCatalogItem g = rows.get(i + k);
                    line.addView(BhStoreUi.resultRow(ctx, g, actionFor(g), () -> open(g), () -> act(g)), l);
                } else {
                    line.addView(new View(ctx), l);
                }
            }
            LinearLayout.LayoutParams ll = BhStoreUi.lp(-1, -2);
            ll.bottomMargin = BhStoreUi.dp(ctx, 9);
            list.addView(line, ll);
        }
        sv.addView(list, new FrameLayout.LayoutParams(-1, -2));
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        return col;
    }
}
