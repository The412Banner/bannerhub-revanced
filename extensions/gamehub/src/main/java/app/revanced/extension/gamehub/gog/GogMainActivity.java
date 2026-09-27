package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The GOG section's host — flipped from a login card + "View Game Library" button to a
 * <b>store-first</b> tabbed shell: <b>Store / Library / Downloads / Profile</b>, the shape of
 * Bannerlator's GOG hub over this extension's programmatic widget kit ({@link BhStoreUi}).
 *
 * The login gate is unchanged ({@code bh_gog_prefs.access_token}), as is the pending-exe bounce
 * the launcher relies on. The full games screen ({@link GogGamesActivity}) still exists one tap
 * away (header ☰) for its list view, in-list installs and DLC sync; owned titles open
 * {@link GogGameDetailActivity}, catalog titles open {@link GogCatalogDetailActivity}, and claims
 * / purchases go to the browser (GOG has no third-party add-to-library API).
 *
 * Launched from the side menu / Banner Tools / Explore card (all by class name).
 */
public class GogMainActivity extends Activity {

    public static final String EXTRA_TAB = "gog_tab";

    private static final int REQ_DETAIL = 4101;
    private static final int REQ_FULL_LIBRARY = 4102;
    private static final int REQ_DOWNLOADS = 4103;

    private static final String[] TABS = {"Store", "Library", "Downloads", "Profile"};
    private static final int TAB_STORE = 0, TAB_LIBRARY = 1, TAB_DOWNLOADS = 2, TAB_PROFILE = 3;

    private FrameLayout root;
    private LinearLayout loginCard;
    private LinearLayout hub;
    private TextView usernameView;
    private TextView dlBadge;
    private FrameLayout tabContent;
    private BhStoreUi.TabStrip tabStrip;
    private int currentTab = TAB_STORE;

    private GogStoreTabView storeTab;
    private GogLibraryTabView libraryTab;
    private GogDownloadsTabView downloadsTab;
    private GogProfileTabView profileTab;

    private List<GogGame> games = new java.util.ArrayList<>();
    private Set<String> ownedIds = new HashSet<>();
    private Set<String> installedIds = new HashSet<>();
    private boolean hubBuilt;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentTab = Math.max(0, Math.min(TABS.length - 1, getIntent().getIntExtra(EXTRA_TAB, TAB_STORE)));

        root = new FrameLayout(this);
        root.setBackgroundColor(BhStoreUi.BG);
        loginCard = buildLoginCard();
        root.addView(loginCard, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        BhStoreUi.hideSystemBars(this);
        refreshView();

        BhStorageMigration.maybeShowDialog(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The pending_gog_exe -> finish() hand-off to the launcher: the Phase-1 GogLaunchHelper
        // never writes it, so this only refreshes the login / hub state.
        SharedPreferences prefs = getSharedPreferences("bh_gog_prefs", 0);
        if (prefs.getString("pending_gog_exe", null) != null) { finish(); return; }
        refreshView();
        if (hubBuilt) {
            libraryTab.load();
            storeTab.onResume();
            downloadsTab.onResume();
            profileTab.onResume();
            BhDownloadService.setCountObserver(n -> runOnUiThread(() -> updateDlBadge(n)));
            updateDlBadge(BhDownloadService.getActiveCount());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (hubBuilt) {
            downloadsTab.onPause();
            BhDownloadService.clearCountObserver();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) BhStoreUi.hideSystemBars(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Any of these can change install state (install / uninstall / exe set); the resume path
        // re-reads it, so nothing extra is needed here beyond a repaint of the visible tab.
        if (hubBuilt && (requestCode == REQ_DETAIL || requestCode == REQ_FULL_LIBRARY || requestCode == REQ_DOWNLOADS)) {
            libraryTab.load();
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private boolean isLoggedIn() {
        return getSharedPreferences("bh_gog_prefs", 0).getString("access_token", null) != null;
    }

    private String username() {
        String u = getSharedPreferences("bh_gog_prefs", 0).getString("username", null);
        return u == null ? "" : u;
    }

    private void refreshView() {
        boolean loggedIn = isLoggedIn();
        if (loggedIn && !hubBuilt) {
            hub = buildHub();
            root.addView(hub, new FrameLayout.LayoutParams(-1, -1));
            hubBuilt = true;
        }
        loginCard.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        if (hub != null) hub.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        if (loggedIn && usernameView != null) usernameView.setText(username());
    }

    private void signOut() {
        getSharedPreferences("bh_gog_prefs", 0).edit().clear().apply();
        if (hub != null) { root.removeView(hub); hub = null; }
        hubBuilt = false;
        storeTab = null; libraryTab = null; downloadsTab = null; profileTab = null;
        games = new java.util.ArrayList<>(); ownedIds = new HashSet<>(); installedIds = new HashSet<>();
        refreshView();
    }

    private void message(String text) {
        if (text == null || text.isEmpty()) return;
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void updateDlBadge(int n) {
        if (dlBadge == null) return;
        dlBadge.setText(String.valueOf(n));
        dlBadge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void openOwned(String gameId) {
        GogGame g = null;
        for (GogGame x : games) if (x.gameId.equals(gameId)) { g = x; break; }
        if (g == null) g = GogLibraryRepo.find(this, gameId);
        Intent i = new Intent(this, GogGameDetailActivity.class);
        i.putExtra("game_id", gameId);
        i.putExtra("title", g != null ? g.title : "");
        i.putExtra("image_url", g != null ? g.imageUrl : "");
        i.putExtra("description", g != null ? g.description : "");
        i.putExtra("developer", g != null ? g.developer : "");
        i.putExtra("category", g != null ? g.category : "");
        i.putExtra("generation", g != null ? g.generation : 0);
        if (g != null && g.verticalCover != null) i.putExtra("vertical_cover", g.verticalCover);
        startActivityForResult(i, REQ_DETAIL);
    }

    private void openWeb(String url, String title) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            message("No browser available");
        }
    }

    private void selectTab(int idx) {
        currentTab = idx;
        tabContent.removeAllViews();
        View v;
        switch (idx) {
            case TAB_LIBRARY:   v = libraryTab.view; break;
            case TAB_DOWNLOADS: v = downloadsTab.view; downloadsTab.render(); break;
            case TAB_PROFILE:   v = profileTab.view; profileTab.onResume(); break;
            default:            v = storeTab.view; storeTab.onResume(); break;
        }
        tabContent.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    // ── Hub ───────────────────────────────────────────────────────────────────

    private LinearLayout buildHub() {
        final Activity act = this;
        LinearLayout col = BhStoreUi.column(this);
        col.setBackgroundColor(BhStoreUi.BG);

        // Header: ← · GOG / username · ↺ · ☰ · ⬇(badge) · sign out
        LinearLayout header = BhStoreUi.row(this);
        header.setBackgroundColor(BhStoreUi.HEADER);
        header.setPadding(BhStoreUi.dp(this, 8), BhStoreUi.dp(this, 8), BhStoreUi.dp(this, 8), BhStoreUi.dp(this, 8));

        Button back = BhStoreUi.headerButton(this, "←");
        back.setOnClickListener(v -> finish());
        header.addView(back, BhStoreUi.lp(-2, BhStoreUi.dp(this, 40)));

        LinearLayout titleCol = BhStoreUi.column(this);
        titleCol.setPadding(BhStoreUi.dp(this, 12), 0, 0, 0);
        TextView title = BhStoreUi.text(this, "GOG", 18f, BhStoreUi.ORANGE, true);
        title.setTypeface(null, Typeface.BOLD);
        titleCol.addView(title);
        usernameView = BhStoreUi.oneLine(BhStoreUi.text(this, username(), 11f, BhStoreUi.MUTED, false), 1);
        titleCol.addView(usernameView);
        header.addView(titleCol, BhStoreUi.lpWeight(-2, 1f));

        Button sync = BhStoreUi.headerButton(this, "↺");
        sync.setOnClickListener(v -> { if (libraryTab != null) libraryTab.sync(true); });
        header.addView(sync, headerBtnLp());

        Button full = BhStoreUi.headerButton(this, "☰");
        full.setOnClickListener(v -> startActivityForResult(new Intent(act, GogGamesActivity.class), REQ_FULL_LIBRARY));
        header.addView(full, headerBtnLp());

        FrameLayout dlWrap = new FrameLayout(this);
        Button dl = BhStoreUi.headerButton(this, "⬇");
        dl.setOnClickListener(v -> startActivityForResult(new Intent(act, BhDownloadsActivity.class), REQ_DOWNLOADS));
        dlWrap.addView(dl, new FrameLayout.LayoutParams(-2, BhStoreUi.dp(this, 40)));
        dlBadge = BhStoreUi.text(this, "0", 9f, BhStoreUi.TEXT, true);
        dlBadge.setPadding(BhStoreUi.dp(this, 5), BhStoreUi.dp(this, 1), BhStoreUi.dp(this, 5), BhStoreUi.dp(this, 1));
        dlBadge.setBackground(BhStoreUi.roundBg(this, BhStoreUi.ACCENT, 10));
        dlBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2);
        bl.gravity = Gravity.TOP | Gravity.END;
        dlWrap.addView(dlBadge, bl);
        header.addView(dlWrap, headerBtnLp());

        Button out = BhStoreUi.headerButton(this, "⏏");
        out.setOnClickListener(v -> signOut());
        header.addView(out, headerBtnLp());

        col.addView(header, BhStoreUi.lp(-1, -2));

        // Tabs
        tabStrip = new BhStoreUi.TabStrip(this, TABS, currentTab, this::selectTab);
        col.addView(tabStrip.view, BhStoreUi.lp(-1, -2));

        tabContent = new FrameLayout(this);
        col.addView(tabContent, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Tab views. The library tab is built first: it owns the owned / installed sets the
        // store tab's action buttons read.
        libraryTab = new GogLibraryTabView(this, new GogLibraryTabView.Host() {
            @Override public void openOwned(GogGame game) { GogMainActivity.this.openOwned(game.gameId); }
            @Override public void openFullLibrary() { startActivityForResult(new Intent(act, GogGamesActivity.class), REQ_FULL_LIBRARY); }
            @Override public void message(String text) { GogMainActivity.this.message(text); }
            @Override public void onLibraryChanged(List<GogGame> g, Set<String> installed) {
                games = g;
                ownedIds = GogLibraryRepo.ownedIds(g);
                installedIds = installed;
                if (tabStrip != null) tabStrip.setBadge(TAB_LIBRARY, g.isEmpty() ? null : String.valueOf(g.size()));
                if (storeTab != null && currentTab == TAB_STORE) storeTab.onResume();
            }
        });
        storeTab = new GogStoreTabView(this, new GogStoreTabView.Host() {
            @Override public Set<String> ownedIds() { return ownedIds; }
            @Override public Set<String> installedIds() { return installedIds; }
            @Override public void openOwned(String gameId) { GogMainActivity.this.openOwned(gameId); }
            @Override public void openCatalog(GogCatalogItem item) { startActivity(GogCatalogDetailActivity.intent(act, item)); }
            @Override public void openWeb(String url, String title) { GogMainActivity.this.openWeb(url, title); }
            @Override public void message(String text) { GogMainActivity.this.message(text); }
        });
        downloadsTab = new GogDownloadsTabView(this, new GogDownloadsTabView.Host() {
            @Override public void openOwned(String gameId) { GogMainActivity.this.openOwned(gameId); }
            @Override public void openDownloadManager() { startActivityForResult(new Intent(act, BhDownloadsActivity.class), REQ_DOWNLOADS); }
        });
        profileTab = new GogProfileTabView(this, new GogProfileTabView.Host() {
            @Override public void openWeb(String url, String title) { GogMainActivity.this.openWeb(url, title); }
            @Override public int libraryCount() { return games.size(); }
            @Override public int installedCount() { return installedIds.size(); }
            @Override public String username() { return GogMainActivity.this.username(); }
        });
        tabStrip.setBadge(TAB_LIBRARY, games.isEmpty() ? null : String.valueOf(games.size()));

        selectTab(currentTab);
        return col;
    }

    private LinearLayout.LayoutParams headerBtnLp() {
        LinearLayout.LayoutParams l = BhStoreUi.lp(-2, BhStoreUi.dp(this, 40));
        l.leftMargin = BhStoreUi.dp(this, 4);
        return l;
    }

    // ── Login card ────────────────────────────────────────────────────────────

    private LinearLayout buildLoginCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(BhStoreUi.HEADER);
        card.setGravity(Gravity.CENTER);
        int pad = BhStoreUi.dp(this, 40);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("GOG.com");
        title.setTextSize(32f);
        title.setTextColor(BhStoreUi.TEXT);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Sign in to browse the GOG store, claim free games and download your library.");
        sub.setTextSize(14f);
        sub.setTextColor(0xFFAAAAAA);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = BhStoreUi.dp(this, 16);
        card.addView(sub, subLp);

        Button loginBtn = BhStoreUi.button(this, "Login with GOG", BhStoreUi.ACCENT);
        loginBtn.setTextSize(15f);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, BhStoreUi.dp(this, 44));
        btnLp.topMargin = BhStoreUi.dp(this, 24);
        loginBtn.setOnClickListener(v -> startActivity(new Intent(this, GogLoginActivity.class)));
        card.addView(loginBtn, btnLp);

        return card;
    }
}
