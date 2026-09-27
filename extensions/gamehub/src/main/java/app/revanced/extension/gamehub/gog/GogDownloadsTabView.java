package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hub's <b>Downloads</b> tab: a live view of {@link BhDownloadService}'s active jobs (name,
 * last engine message, percent, cancel) plus the games it has installed, and a button into the
 * full download manager ({@link BhDownloadsActivity}). Fed by the service's static registry and
 * its {@link BhDownloadService.GlobalListener}; attach / detach with the hub's resume / pause.
 */
final class GogDownloadsTabView {

    interface Host {
        void openOwned(String gameId);
        void openDownloadManager();
    }

    final ScrollView view;
    private final Activity ctx;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private final Map<String, View[]> activeRows = new HashMap<>();

    private final BhDownloadService.GlobalListener listener = new BhDownloadService.GlobalListener() {
        @Override public void onAnyProgress(String gameId, String gameName, String msg, int pct) {
            ui.post(() -> {
                View[] row = activeRows.get(gameId);
                if (row == null) { render(); return; }
                ((TextView) row[0]).setText(msg == null ? "" : msg);
                ((ProgressBar) row[1]).setProgress(pct);
                ((TextView) row[2]).setText(pct + "%");
            });
        }
        @Override public void onAnyComplete(String gameId, String gameName) { ui.post(GogDownloadsTabView.this::render); }
        @Override public void onAnyError(String gameId, String msg) { ui.post(GogDownloadsTabView.this::render); }
        @Override public void onAnyCancelled(String gameId) { ui.post(GogDownloadsTabView.this::render); }
    };

    GogDownloadsTabView(Activity ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        view = new ScrollView(ctx);
        view.setVerticalScrollBarEnabled(false);
        view.setBackgroundColor(BhStoreUi.BG);
        root = BhStoreUi.column(ctx);
        root.setPadding(0, 0, 0, BhStoreUi.dp(ctx, 24));
        view.addView(root, new FrameLayout.LayoutParams(-1, -2));
        render();
    }

    void onResume() { BhDownloadService.addGlobalListener(listener); render(); }
    void onPause() { BhDownloadService.removeGlobalListener(listener); }

    void render() {
        root.removeAllViews();
        activeRows.clear();

        Set<String> jobs = BhDownloadService.getActiveJobs();
        root.addView(BhStoreUi.sectionHeader(ctx, "Active downloads",
                jobs.isEmpty() ? "Nothing in flight" : jobs.size() + " running"), BhStoreUi.lp(-1, -2));
        if (jobs.isEmpty()) {
            root.addView(BhStoreUi.notice(ctx, "No active downloads",
                    "Install a game from your Library and its progress shows here and in the shade.", null, null));
        } else {
            for (final String key : jobs) root.addView(activeRow(key), BhStoreUi.cardLp(ctx));
        }

        List<BhDownloadService.LibraryEntry> lib = BhDownloadService.getLibrary(ctx);
        List<BhDownloadService.LibraryEntry> gog = new ArrayList<>();
        for (BhDownloadService.LibraryEntry e : lib) if ("GOG".equals(e.store) || e.dlKey.startsWith("gog_")) gog.add(e);
        root.addView(BhStoreUi.sectionHeader(ctx, "Installed", gog.isEmpty() ? "No downloads finished yet"
                : gog.size() + (gog.size() == 1 ? " game" : " games")), BhStoreUi.lp(-1, -2));
        for (final BhDownloadService.LibraryEntry e : gog) {
            LinearLayout card = BhStoreUi.card(ctx);
            android.graphics.drawable.GradientDrawable bg = BhStoreUi.roundBg(ctx, BhStoreUi.CARD, 10, BhStoreUi.OUTLINE);
            card.setBackground(bg);
            BhStoreUi.focusRing(card, bg, BhStoreUi.CARD, BhStoreUi.CARD_HI);
            card.addView(BhStoreUi.oneLine(BhStoreUi.text(ctx, e.name.isEmpty() ? e.dlKey : e.name, 14f, BhStoreUi.TEXT, true), 1));
            card.addView(BhStoreUi.oneLine(BhStoreUi.text(ctx, e.installPath, 11f, BhStoreUi.MUTED, false), 1));
            card.setOnClickListener(v -> {
                if (e.dlKey.startsWith("gog_")) host.openOwned(e.dlKey.substring(4));
            });
            root.addView(card, BhStoreUi.cardLp(ctx));
        }

        Button open = BhStoreUi.button(ctx, "Open download manager", BhStoreUi.ACCENT);
        open.setOnClickListener(v -> host.openDownloadManager());
        LinearLayout.LayoutParams ol = BhStoreUi.buttonLp(ctx);
        ol.leftMargin = BhStoreUi.dp(ctx, 14); ol.rightMargin = BhStoreUi.dp(ctx, 14); ol.topMargin = BhStoreUi.dp(ctx, 8);
        root.addView(open, ol);
    }

    private View activeRow(final String key) {
        LinearLayout card = BhStoreUi.card(ctx);
        card.addView(BhStoreUi.oneLine(BhStoreUi.text(ctx, BhDownloadService.getGameName(key), 14f, BhStoreUi.TEXT, true), 1));
        TextView msg = BhStoreUi.oneLine(BhStoreUi.text(ctx, BhDownloadService.getLastMsg(key), 11f, BhStoreUi.TEXT2, false), 1);
        LinearLayout.LayoutParams ml = BhStoreUi.lp(-1, -2); ml.topMargin = BhStoreUi.dp(ctx, 4);
        card.addView(msg, ml);
        LinearLayout pr = BhStoreUi.row(ctx);
        ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(BhDownloadService.getLastPct(key));
        pb.getProgressDrawable().setColorFilter(BhStoreUi.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        pr.addView(pb, new LinearLayout.LayoutParams(0, BhStoreUi.dp(ctx, 6), 1f));
        TextView pct = BhStoreUi.text(ctx, BhDownloadService.getLastPct(key) + "%", 12f, BhStoreUi.ORANGE, true);
        LinearLayout.LayoutParams pl = BhStoreUi.lp(-2, -2); pl.leftMargin = BhStoreUi.dp(ctx, 8);
        pr.addView(pct, pl);
        LinearLayout.LayoutParams prl = BhStoreUi.lp(-1, -2); prl.topMargin = BhStoreUi.dp(ctx, 8);
        card.addView(pr, prl);
        Button cancel = BhStoreUi.button(ctx, "Cancel", BhStoreUi.RED);
        cancel.setOnClickListener(v -> BhDownloadService.cancel(ctx, key));
        LinearLayout.LayoutParams cl = BhStoreUi.lp(-2, BhStoreUi.dp(ctx, 34)); cl.topMargin = BhStoreUi.dp(ctx, 8);
        card.addView(cancel, cl);
        activeRows.put(key, new View[]{msg, pb, pct});
        return card;
    }
}
