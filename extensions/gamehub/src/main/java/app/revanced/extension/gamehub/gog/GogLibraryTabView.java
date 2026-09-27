package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The GOG <b>Library</b> tab: filter chips, a search box and the adaptive 2:3 tile grid, over
 * {@link GogLibraryRepo}'s cache. Syncs on first open (empty cache) or on demand; re-reads
 * install state on every resume so an install done on the detail page shows when the user
 * comes back.
 *
 * Tiles carry the install state ({@link GogInstallState.Badge}: Installed / Update available /
 * Partial) — the update marker comes from a one-shot background check of every installed game's
 * build id against GOG's builds feed, run once per hub open.
 *
 * The full games screen ({@link GogGamesActivity}) still exists one tap away for its list view,
 * in-list installs and DLC sync — the footer links to it.
 */
final class GogLibraryTabView {

    interface Host {
        void openOwned(GogGame game);
        void openFullLibrary();
        void message(String text);
        /** The library or install set changed — the hub re-derives owned / installed ids. */
        void onLibraryChanged(List<GogGame> games, Set<String> installedIds);
    }

    final LinearLayout view;
    private final Activity ctx;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final EditText search;
    private final LinearLayout chipRow;
    private final TextView statusTV;
    private final FrameLayout content;

    List<GogGame> games = new ArrayList<>();
    Set<String> installedIds = new HashSet<>();
    private boolean syncing;
    private String status = "";
    private String query = "";
    private boolean installedOnly;
    private final AtomicBoolean updateCheckDone = new AtomicBoolean(false);

    GogLibraryTabView(Activity ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        view = BhStoreUi.column(ctx);
        view.setBackgroundColor(BhStoreUi.BG);

        search = BhStoreUi.searchField(ctx, "Search your GOG library…");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString(); render(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        view.addView(search, BhStoreUi.searchLp(ctx));

        chipRow = BhStoreUi.row(ctx);
        chipRow.setPadding(BhStoreUi.dp(ctx, 14), BhStoreUi.dp(ctx, 2), BhStoreUi.dp(ctx, 14), BhStoreUi.dp(ctx, 2));
        statusTV = BhStoreUi.oneLine(BhStoreUi.text(ctx, "", 11f, BhStoreUi.MUTED, false), 1);
        view.addView(chipRow, BhStoreUi.lp(-1, -2));

        content = new FrameLayout(ctx);
        view.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        load();
        if (GogLibraryRepo.isLoggedIn(ctx)) sync(false);
    }

    /** Re-read the cache + install state and re-paint. Cheap; call on every resume. */
    void load() {
        games = GogLibraryRepo.cached(ctx);
        installedIds = GogInstallState.installedIds(ctx);
        if (!syncing) {
            status = games.isEmpty() ? "" : games.size() + (games.size() == 1 ? " game" : " games")
                    + " · " + installedIds.size() + " installed";
        }
        host.onLibraryChanged(games, installedIds);
        render();
        maybeCheckUpdates();
    }

    void sync(final boolean force) {
        if (syncing) return;
        syncing = true;
        status = "Syncing library…";
        render();
        new Thread(() -> {
            final GogLibraryRepo.SyncResult r = GogLibraryRepo.sync(ctx, force, s -> ui.post(() -> { status = s; renderStatus(); }));
            ui.post(() -> {
                syncing = false;
                switch (r.status) {
                    case OK:
                        load();
                        if (r.fetched > 0) host.message("Library updated — " + r.fetched + (r.fetched == 1 ? " game" : " games") + " synced");
                        break;
                    case FAILED: load(); host.message(r.message); break;
                    case NOT_LOGGED_IN: load(); host.message("Session expired — please sign in again"); break;
                    default: load();
                }
            });
        }, "gog-library-sync").start();
    }

    /**
     * One-shot per hub open: compare every installed game's recorded build id with GOG's builds
     * feed and set the "Update available" markers. Installed counts are small (a handful of
     * requests), so this is cheap; a miss simply leaves the marker as it was.
     */
    private void maybeCheckUpdates() {
        if (installedIds.isEmpty() || !updateCheckDone.compareAndSet(false, true)) return;
        final List<String> ids = new ArrayList<>(installedIds);
        new Thread(() -> {
            String token = GogLibraryRepo.validToken(ctx);
            if (token == null) return;
            boolean changed = false;
            for (String id : ids) {
                boolean before = GogInstallState.isUpdateAvailable(ctx, id);
                GogInstallState.checkForUpdate(ctx, id, token);
                if (before != GogInstallState.isUpdateAvailable(ctx, id)) changed = true;
            }
            if (changed) ui.post(this::render);
        }, "gog-library-updates").start();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void renderStatus() { statusTV.setText(status); }

    private void render() {
        chipRow.removeAllViews();
        TextView all = BhStoreUi.chip(ctx, "All", !installedOnly);
        all.setOnClickListener(v -> { installedOnly = false; render(); });
        TextView inst = BhStoreUi.chip(ctx, "Installed", installedOnly);
        inst.setOnClickListener(v -> { installedOnly = true; render(); });
        LinearLayout.LayoutParams cl = BhStoreUi.lp(-2, -2); cl.rightMargin = BhStoreUi.dp(ctx, 8);
        chipRow.addView(all, cl);
        chipRow.addView(inst, BhStoreUi.lp(-2, -2));
        chipRow.addView(new View(ctx), BhStoreUi.lpWeight(0, 1f));
        chipRow.addView(statusTV, BhStoreUi.lp(-2, -2));
        renderStatus();

        content.removeAllViews();
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<GogGame> shown = new ArrayList<>();
        for (GogGame g : games) {
            if (installedOnly && !installedIds.contains(g.gameId)) continue;
            if (!q.isEmpty() && !g.title.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(g);
        }
        if (games.isEmpty() && syncing) {
            content.addView(BhStoreUi.notice(ctx, "Syncing your library",
                    status.isEmpty() ? "Fetching your GOG games…" : status, null, null));
            return;
        }
        if (games.isEmpty()) {
            content.addView(BhStoreUi.notice(ctx, "Your GOG library is empty here",
                    "Sync to pull your games from GOG, or open the full library screen.",
                    "Sync now", () -> sync(true)));
            return;
        }
        if (shown.isEmpty()) {
            content.addView(BhStoreUi.notice(ctx, "No matches", "Nothing in your library matches that filter.", null, null));
            return;
        }
        content.addView(buildGrid(shown), new FrameLayout.LayoutParams(-1, -1));
    }

    private View buildGrid(List<GogGame> shown) {
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = BhStoreUi.column(ctx);
        int hp = BhStoreUi.dp(ctx, 14);
        col.setPadding(hp, BhStoreUi.dp(ctx, 8), hp, BhStoreUi.dp(ctx, 24));

        int tileDp = BhStoreUi.isWide(ctx) ? 132 : 118;
        float widthDp = ctx.getResources().getConfiguration().screenWidthDp - 28;
        int cols = Math.max(2, (int) (widthDp / tileDp));
        int gap = BhStoreUi.dp(ctx, 10);

        for (int i = 0; i < shown.size(); i += cols) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < cols; k++) {
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
                if (k > 0) l.leftMargin = gap;
                if (i + k < shown.size()) {
                    final GogGame g = shown.get(i + k);
                    View tile = BhStoreUi.libraryTile(ctx, GogLibraryRepo.toCatalogItem(g),
                            g.generation > 0 ? "Gen " + g.generation : null,
                            GogInstallState.badge(ctx, g.gameId),
                            () -> host.openOwned(g));
                    line.addView(tile, l);
                } else {
                    line.addView(new View(ctx), l);
                }
            }
            LinearLayout.LayoutParams ll = BhStoreUi.lp(-1, -2);
            ll.bottomMargin = gap;
            col.addView(line, ll);
        }

        View footer = BhStoreUi.notice(ctx, "Full library",
                "Need the list view, batch installs or DLC sync? Open the full library screen.",
                "Open full library", host::openFullLibrary);
        col.addView(footer, BhStoreUi.lp(-1, -2));
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    /** Keeps the status line honest when the hub sync button is used while the tab is hidden. */
    boolean isSyncing() { return syncing; }
}
