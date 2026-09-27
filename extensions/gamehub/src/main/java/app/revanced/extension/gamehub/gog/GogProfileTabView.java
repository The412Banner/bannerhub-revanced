package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The GOG <b>Profile</b> tab, fed by one {@link GogUserData} fetch: avatar + name + country /
 * Galaxy id, stat tiles (games / in library here / installed / friends / wishlisted / movies) and
 * a small friends peek. GOG friends are a Galaxy-only roster with no presence and, for nearly
 * everyone, empty — so there is no Friends tab; the count and an avatar row live here instead.
 */
final class GogProfileTabView {

    interface Host {
        void openWeb(String url, String title);
        int libraryCount();
        int installedCount();
        String username();
    }

    final ScrollView view;
    private final Activity ctx;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private GogUserData.Profile profile;
    private boolean loading;

    GogProfileTabView(Activity ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        view = new ScrollView(ctx);
        view.setVerticalScrollBarEnabled(false);
        view.setBackgroundColor(BhStoreUi.BG);
        root = BhStoreUi.column(ctx);
        view.addView(root, new FrameLayout.LayoutParams(-1, -2));
        profile = GogUserData.cached(ctx);
        render();
        refresh();
    }

    void onResume() { render(); }

    void refresh() {
        if (loading) return;
        loading = true;
        render();
        new Thread(() -> {
            GogUserData.Profile fresh = null;
            try { fresh = GogUserData.fetch(ctx); } catch (Throwable ignored) {}
            final GogUserData.Profile f = fresh;
            ui.post(() -> { if (f != null) profile = f; loading = false; render(); });
        }, "gog-profile").start();
    }

    private void render() {
        root.removeAllViews();
        boolean wide = BhStoreUi.isWide(ctx);
        int pad = BhStoreUi.dp(ctx, 14);
        if (wide) {
            LinearLayout rowL = new LinearLayout(ctx);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout left = BhStoreUi.column(ctx);
            left.setPadding(pad, pad, pad, pad);
            left.addView(identity(), BhStoreUi.lp(-1, -2));
            rowL.addView(left, new LinearLayout.LayoutParams(0, -2, 0.42f));
            LinearLayout right = BhStoreUi.column(ctx);
            right.setPadding(0, pad, pad, pad);
            right.addView(stats(3), BhStoreUi.lp(-1, -2));
            right.addView(BhStoreUi.spacer(ctx, 8));
            friendsPeek(right, 8);
            rowL.addView(right, new LinearLayout.LayoutParams(0, -2, 0.58f));
            root.addView(rowL, BhStoreUi.lp(-1, -2));
        } else {
            LinearLayout col = BhStoreUi.column(ctx);
            col.setPadding(pad, pad, pad, pad);
            col.addView(identity(), BhStoreUi.lp(-1, -2));
            col.addView(BhStoreUi.spacer(ctx, 12));
            col.addView(stats(2), BhStoreUi.lp(-1, -2));
            root.addView(col, BhStoreUi.lp(-1, -2));
            friendsPeek(root, 5);
            root.addView(BhStoreUi.spacer(ctx, 24));
        }
    }

    private String displayName() {
        if (profile != null && profile.username != null && !profile.username.isEmpty()) return profile.username;
        String u = host.username();
        return (u == null || u.isEmpty()) ? "GOG user" : u;
    }

    private View identity() {
        LinearLayout c = BhStoreUi.card(ctx);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(BhStoreUi.dp(ctx, 16), BhStoreUi.dp(ctx, 16), BhStoreUi.dp(ctx, 16), BhStoreUi.dp(ctx, 16));
        String name = displayName();
        c.addView(BhStoreUi.avatar(ctx, GogUserData.avatarCandidates(profile != null ? profile.avatar : null), name, 84),
                BhStoreUi.lp(-2, -2));
        TextView nameTV = BhStoreUi.oneLine(BhStoreUi.text(ctx, name, 20f, BhStoreUi.TEXT, true), 1);
        nameTV.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nl = BhStoreUi.lp(-2, -2); nl.topMargin = BhStoreUi.dp(ctx, 10);
        c.addView(nameTV, nl);
        List<String> subs = new ArrayList<>();
        if (profile != null) {
            if (!profile.country.isEmpty()) subs.add(profile.country.toUpperCase(Locale.ROOT));
            if (!profile.galaxyUserId.isEmpty()) subs.add("Galaxy ID " + profile.galaxyUserId);
        }
        if (!subs.isEmpty()) {
            TextView sub = BhStoreUi.oneLine(BhStoreUi.text(ctx, String.join(" · ", subs), 11f, BhStoreUi.MUTED, false), 1);
            sub.setGravity(Gravity.CENTER);
            c.addView(sub, BhStoreUi.lp(-2, -2));
        }
        LinearLayout chips = BhStoreUi.row(ctx);
        TextView account = BhStoreUi.chip(ctx, "Account on GOG.com", false);
        account.setOnClickListener(v -> host.openWeb("https://www.gog.com/account", "GOG account"));
        LinearLayout.LayoutParams al = BhStoreUi.lp(-2, -2); al.rightMargin = BhStoreUi.dp(ctx, 8);
        chips.addView(account, al);
        TextView refresh = BhStoreUi.chip(ctx, loading ? "Refreshing…" : "Refresh", false);
        refresh.setOnClickListener(v -> refresh());
        chips.addView(refresh, BhStoreUi.lp(-2, -2));
        LinearLayout.LayoutParams cl = BhStoreUi.lp(-2, -2); cl.topMargin = BhStoreUi.dp(ctx, 12);
        c.addView(chips, cl);
        return c;
    }

    private View stats(int cols) {
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        int lib = host.libraryCount();
        labels.add("Games"); values.add(profile != null && profile.ownedGames > 0 ? profile.ownedGames : lib);
        labels.add("In library here"); values.add(lib);
        labels.add("Installed"); values.add(host.installedCount());
        if (profile != null) { labels.add("Friends"); values.add(profile.friends.size()); }
        if (profile != null && profile.wishlisted > 0) { labels.add("Wishlisted"); values.add(profile.wishlisted); }
        if (profile != null && profile.ownedMovies > 0) { labels.add("Movies"); values.add(profile.ownedMovies); }

        LinearLayout col = BhStoreUi.column(ctx);
        int gap = BhStoreUi.dp(ctx, 8);
        for (int i = 0; i < labels.size(); i += cols) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < cols; k++) {
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
                if (k > 0) l.leftMargin = gap;
                if (i + k < labels.size()) line.addView(BhStoreUi.statTile(ctx, labels.get(i + k), values.get(i + k)), l);
                else line.addView(new View(ctx), l);
            }
            LinearLayout.LayoutParams ll = BhStoreUi.lp(-1, -2); ll.bottomMargin = gap;
            col.addView(line, ll);
        }
        return col;
    }

    private void friendsPeek(LinearLayout into, int max) {
        if (profile == null || profile.friends.isEmpty()) return;
        into.addView(BhStoreUi.sectionHeader(ctx, "GOG friends", profile.friends.size() + " · no presence feed"), BhStoreUi.lp(-1, -2));
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout rowL = BhStoreUi.row(ctx);
        rowL.setGravity(Gravity.TOP);
        rowL.setPadding(BhStoreUi.dp(ctx, 14), 0, BhStoreUi.dp(ctx, 14), 0);
        int n = 0;
        for (GogUserData.Friend f : profile.friends) {
            if (n++ >= max) break;
            LinearLayout c = BhStoreUi.column(ctx);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.addView(BhStoreUi.avatar(ctx, GogUserData.avatarCandidates(f.avatar), f.username, 48), BhStoreUi.lp(-2, -2));
            TextView name = BhStoreUi.oneLine(BhStoreUi.text(ctx, f.username, 10f, BhStoreUi.MUTED, false), 1);
            name.setGravity(Gravity.CENTER);
            c.addView(name, BhStoreUi.lp(-1, -2));
            LinearLayout.LayoutParams l = BhStoreUi.lp(BhStoreUi.dp(ctx, 60), -2);
            l.rightMargin = BhStoreUi.dp(ctx, 10);
            rowL.addView(c, l);
        }
        hs.addView(rowL, new FrameLayout.LayoutParams(-2, -2));
        into.addView(hs, BhStoreUi.lp(-1, -2));
    }
}
