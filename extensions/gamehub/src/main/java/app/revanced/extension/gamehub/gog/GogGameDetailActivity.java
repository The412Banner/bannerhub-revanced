package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen detail page for an OWNED GOG library entry, on the Steam-style scaffold
 * ({@link GogDetailScaffold}): hero → name → ONE primary button (Install / Resume / Update /
 * Add to Library, or the read-only download fill) + ⚙ gear → pill tabs
 * (Details · DLC · Cloud saves · Media). Every former action is still reachable: the gear
 * carries cancel / set-exe / copy / check-updates / uninstall. The HANDLERS are the existing
 * BannerHub flows — {@link BhInstallConfirmDialog} → {@link BhDownloadService} (progress via its
 * listener), {@link GogLaunchHelper} (add to GameHub's library), {@link GogCloudSaveManager},
 * {@link GogDownloadManager#copyToDownloads} / {@link GogDownloadManager#collectExeCandidates},
 * {@link FolderPickerActivity} — only the layout moved.
 *
 * Launched via startActivityForResult() from the hub / games screen / downloads screen.
 * Extras (all Strings / int): game_id, title, image_url, description, developer, category,
 * generation(int), vertical_cover (optional).
 *
 * Result codes: RESULT_CANCELED — nothing changed; RESULT_REFRESH — install state changed.
 */
public class GogGameDetailActivity extends Activity {

    public static final int RESULT_REFRESH = 100;

    private static final String TAG = "BH_GOG_DETAIL";
    private static final int REQUEST_FOLDER_PICKER = 200;
    private static final int REQUEST_COPY_STORAGE = 201;

    private static final int TAB_DETAILS = 0, TAB_DLC = 1, TAB_CLOUD = 2, TAB_MEDIA = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private String gameId, title, imageUrl, description, developer, category, verticalCover;
    private int generation;
    private String dlKey;

    private GogDetailScaffold scaffold;
    private int tab = TAB_DETAILS;

    // Download state mirrored from BhDownloadService
    private boolean downloading;
    private int progressPct;
    private String progressMsg = "";

    // Details tab live pieces
    private String sizeText = "Fetching…";
    private String updateStatusText = "";
    private boolean checkUpdateEnabled = true;

    // Media
    private GogStoreCatalog.StoreMedia media;
    private boolean mediaLoading = true;

    // Cloud saves
    private TextView cloudSaveDirTV, cloudSaveStatusTV;
    private Button cloudUploadBtn, cloudDownloadBtn;
    private String cloudStatus = "";

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("bh_gog_prefs", 0);

        Intent i = getIntent();
        gameId      = i.getStringExtra("game_id");
        if (gameId == null || gameId.isEmpty()) { finish(); return; }
        dlKey       = "gog_" + gameId;
        title       = nz(i.getStringExtra("title"));
        imageUrl    = nz(i.getStringExtra("image_url"));
        description = nz(i.getStringExtra("description"));
        developer   = nz(i.getStringExtra("developer"));
        category    = nz(i.getStringExtra("category"));
        generation  = i.getIntExtra("generation", 0);
        verticalCover = nz(i.getStringExtra("vertical_cover"));

        // A caller that only had the id (the downloads screen) gets the cached metadata.
        if (title.isEmpty()) {
            GogGame g = GogLibraryRepo.find(this, gameId);
            if (g != null) {
                title = g.title; imageUrl = g.imageUrl; description = g.description;
                developer = g.developer; category = g.category; generation = g.generation;
                if (g.verticalCover != null) verticalCover = g.verticalCover;
            }
        }
        if (verticalCover.isEmpty()) verticalCover = nz(prefs.getString("gog_vcover_" + gameId, ""));

        scaffold = new GogDetailScaffold(this, title.isEmpty() ? gameId : title, this::finish,
                () -> startActivity(new Intent(this, BhDownloadsActivity.class)));
        setContentView(scaffold.root);
        BhStoreUi.hideSystemBars(this);

        List<String> hero = new ArrayList<>();
        if (!imageUrl.isEmpty()) hero.add(imageUrl);
        if (!verticalCover.isEmpty()) hero.add(verticalCover);
        scaffold.loadHero(hero);

        String storedBuild = prefs.getString("gog_build_" + gameId, null);
        updateStatusText = storedBuild != null
                ? "Installed build: " + storedBuild.substring(0, Math.min(12, storedBuild.length())) + "…"
                : "Build ID not recorded — tap Check to verify";

        refreshState();
        loadInstallSize();
        loadMedia();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gameId == null) return;
        if (BhDownloadService.isActive(dlKey)) {
            downloading = true;
            progressPct = BhDownloadService.getLastPct(dlKey);
            progressMsg = BhDownloadService.getLastMsg(dlKey);
            attachDownloadListener();
        } else {
            downloading = false;
        }
        refreshState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameId != null) BhDownloadService.removeListener(dlKey);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) BhStoreUi.hideSystemBars(this);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ── State → scaffold ──────────────────────────────────────────────────────

    /** Re-derive the primary button, subtitle, gear and the visible tab from prefs + service. */
    private void refreshState() {
        if (scaffold == null) return;
        GogInstallPath.State state = GogInstallPath.checkState(prefs, gameId);
        boolean installed = state == GogInstallPath.State.INSTALLED;
        boolean partial = state == GogInstallPath.State.PARTIAL;
        boolean updateAvail = installed && GogInstallState.isUpdateAvailable(this, gameId);

        // Subtitle: install status line.
        List<View> sub = new ArrayList<>();
        if (installed) {
            String exe = prefs.getString("gog_exe_" + gameId, "");
            String dir = prefs.getString("gog_dir_" + gameId, "");
            sub.add(BhStoreUi.oneLine(BhStoreUi.text(this,
                    "✓ Installed · .exe: " + new File(exe).getName(), 12f, BhStoreUi.GREEN, false), 1));
            LinearLayout pathRow = BhStoreUi.row(this);
            pathRow.addView(BhStoreUi.oneLine(BhStoreUi.text(this, dir, 11f, BhStoreUi.DIM, false), 1), BhStoreUi.lpWeight(-2, 1f));
            SharedPreferences sp = getSharedPreferences(BhStorageHelper.PREFS, 0);
            String sdPath = sp.getString(BhStorageHelper.KEY_PATH, null);
            boolean isSD = sdPath != null && !sdPath.isEmpty() && dir.startsWith(sdPath);
            TextView badge = BhStoreUi.text(this, isSD ? "SD Card" : "Internal", 10f, isSD ? BhStoreUi.FREE_GREEN : BhStoreUi.MUTED, true);
            badge.setPadding(BhStoreUi.dp(this, 6), BhStoreUi.dp(this, 2), BhStoreUi.dp(this, 6), BhStoreUi.dp(this, 2));
            badge.setBackground(BhStoreUi.roundBg(this, isSD ? 0xFF1B3A1B : 0xFF2A2A2A, 10));
            LinearLayout.LayoutParams bl = BhStoreUi.lp(-2, -2); bl.leftMargin = BhStoreUi.dp(this, 6);
            pathRow.addView(badge, bl);
            sub.add(pathRow);
            if (updateAvail) sub.add(BhStoreUi.text(this, "↑ Update available on GOG", 12f, BhStoreUi.AMBER, true));
        } else if (partial) {
            sub.add(BhStoreUi.text(this, "Partial install — resume picks up where it left off", 12f, BhStoreUi.AMBER, false));
        }
        scaffold.setSubtitle(sub);

        // Primary.
        if (downloading) {
            String label = progressMsg == null || progressMsg.isEmpty() ? "Downloading… " + progressPct + "%" : progressMsg + "  " + progressPct + "%";
            scaffold.primary.setProgress(label, progressPct / 100f);
        } else if (installed && updateAvail) {
            scaffold.primary.setAction("Update now", true, v -> startInstall());
        } else if (installed) {
            scaffold.primary.setAction("Add to Library", true, v -> addToLibrary());
        } else {
            scaffold.primary.setAction(partial ? "Resume install" : "Install", true, v -> startInstall());
        }

        // Gear.
        List<GogDetailScaffold.GearItem> gear = new ArrayList<>();
        if (downloading) gear.add(GogDetailScaffold.GearItem.danger("Cancel download", () -> BhDownloadService.cancel(this, dlKey)));
        if (installed && updateAvail && !downloading) gear.add(GogDetailScaffold.GearItem.of("Add to Library", this::addToLibrary));
        if (installed && !downloading) {
            gear.add(GogDetailScaffold.GearItem.of("Set .exe…", this::pickExe));
            gear.add(GogDetailScaffold.GearItem.of("Copy to Downloads", this::startCopyToDownloads));
            gear.add(new GogDetailScaffold.GearItem("Check for updates", checkUpdateEnabled, false, this::doCheckUpdate));
        }
        if ((installed || partial) && !downloading) gear.add(GogDetailScaffold.GearItem.danger("Uninstall", this::confirmUninstall));
        scaffold.setGear(gear);

        scaffold.setInfoLine(downloading ? "Keeps running in the background — progress is also in the shade and the Downloads screen." : null);

        renderTabs();
    }

    private void renderTabs() {
        boolean mediaVisible = media != null && !media.isEmpty();
        String[] labels = mediaVisible
                ? new String[]{"Details", "DLC", "Cloud saves", "Media"}
                : new String[]{"Details", "DLC", "Cloud saves"};
        if (tab >= labels.length) tab = TAB_DETAILS;
        scaffold.setTabs(labels, tab, idx -> { tab = idx; renderBody(); });
        int dlcCount = dlcArray() == null ? 0 : dlcArray().length();
        if (dlcCount > 0) scaffold.setTabBadge(TAB_DLC, String.valueOf(dlcCount));
        if (mediaVisible) scaffold.setTabBadge(TAB_MEDIA, String.valueOf(media.count()));
        renderBody();
    }

    private void renderBody() {
        switch (tab) {
            case TAB_DLC:   scaffold.setBody(buildDlc()); break;
            case TAB_CLOUD: scaffold.setBody(buildCloudSaves()); break;
            case TAB_MEDIA: scaffold.setBody(GogMediaView.build(this, media, mediaLoading)); break;
            default:        scaffold.setBody(buildDetails()); break;
        }
    }

    // ── Details tab ───────────────────────────────────────────────────────────

    private View buildDetails() {
        LinearLayout col = BhStoreUi.column(this);
        col.setPadding(0, BhStoreUi.dp(this, 6), 0, BhStoreUi.dp(this, 8));

        LinearLayout inner = BhStoreUi.column(this);
        inner.setPadding(BhStoreUi.dp(this, 16), 0, BhStoreUi.dp(this, 16), BhStoreUi.dp(this, 8));
        LinearLayout chips = BhStoreUi.row(this);
        chips.addView(BhStoreUi.infoChip(this, sizeText), BhStoreUi.chipLp(this));
        if (!developer.isEmpty()) chips.addView(BhStoreUi.infoChip(this, developer), BhStoreUi.chipLp(this));
        if (!category.isEmpty()) chips.addView(BhStoreUi.infoChip(this, category), BhStoreUi.chipLp(this));
        if (generation > 0) chips.addView(BhStoreUi.infoChip(this, "Gen " + generation), BhStoreUi.chipLp(this));
        inner.addView(wrapChips(chips), BhStoreUi.lp(-1, -2));

        LinearLayout chips2 = BhStoreUi.row(this);
        String release = prefs.getString("gog_release_" + gameId, null);
        if (release != null && !release.isEmpty()) chips2.addView(BhStoreUi.infoChip(this, "Released " + BhStoreUi.formatDate(release)), BhStoreUi.chipLp(this));
        int rating = prefs.getInt("gog_rating_" + gameId, -1);
        if (rating > 0) chips2.addView(BhStoreUi.infoChip(this, String.format(java.util.Locale.US, "%.1f / 5 ★", rating / 100f)), BhStoreUi.chipLp(this));
        if (chips2.getChildCount() > 0) inner.addView(wrapChips(chips2), BhStoreUi.lp(-1, -2));

        if (!description.isEmpty()) {
            TextView tv = BhStoreUi.text(this, Html.fromHtml(description, Html.FROM_HTML_MODE_COMPACT).toString().trim(), 12f, BhStoreUi.TEXT2, false);
            LinearLayout.LayoutParams l = BhStoreUi.lp(-1, -2); l.topMargin = BhStoreUi.dp(this, 6);
            inner.addView(tv, l);
        }
        col.addView(inner, BhStoreUi.lp(-1, -2));

        // Updates card
        LinearLayout card = BhStoreUi.card(this);
        card.addView(BhStoreUi.text(this, "Updates", 14f, BhStoreUi.TEXT, true));
        boolean installed = GogInstallPath.checkState(prefs, gameId) == GogInstallPath.State.INSTALLED;
        if (!installed) {
            TextView tv = BhStoreUi.text(this, "Install the game first to check for updates.", 12f, BhStoreUi.DIM, false);
            LinearLayout.LayoutParams l = BhStoreUi.lp(-1, -2); l.topMargin = BhStoreUi.dp(this, 8);
            card.addView(tv, l);
        } else {
            TextView status = BhStoreUi.text(this, updateStatusText, 12f, BhStoreUi.TEXT2, false);
            LinearLayout.LayoutParams sl = BhStoreUi.lp(-1, -2); sl.topMargin = BhStoreUi.dp(this, 8); sl.bottomMargin = BhStoreUi.dp(this, 8);
            card.addView(status, sl);
            if (GogInstallState.isUpdateAvailable(this, gameId) && !downloading) {
                Button upd = BhStoreUi.button(this, "Update Now", BhStoreUi.INFO_BLUE);
                upd.setOnClickListener(v -> startInstall());
                card.addView(upd, BhStoreUi.buttonLp(this));
            }
            Button check = BhStoreUi.button(this, "Check for Updates", 0xFF333355);
            check.setEnabled(checkUpdateEnabled && !downloading);
            check.setOnClickListener(v -> doCheckUpdate());
            card.addView(check, BhStoreUi.buttonLp(this));
        }
        col.addView(card, BhStoreUi.cardLp(this));
        return col;
    }

    /** A chip row that wraps onto a second line when it does not fit (portrait). */
    private View wrapChips(LinearLayout row) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -2));
        return hs;
    }

    // ── DLC tab ───────────────────────────────────────────────────────────────

    private JSONArray dlcArray() {
        String json = prefs.getString("gog_dlcs_" + gameId, null);
        if (json == null || json.isEmpty() || "[]".equals(json)) return null;
        try {
            JSONArray a = new JSONArray(json);
            return a.length() == 0 ? null : a;
        } catch (Exception e) {
            return null;
        }
    }

    private View buildDlc() {
        LinearLayout col = BhStoreUi.column(this);
        col.setPadding(0, BhStoreUi.dp(this, 6), 0, BhStoreUi.dp(this, 8));
        LinearLayout card = BhStoreUi.card(this);
        card.addView(BhStoreUi.text(this, "DLC", 14f, BhStoreUi.TEXT, true));
        JSONArray arr = dlcArray();
        if (arr == null) {
            TextView tv = BhStoreUi.text(this, "No DLCs in your library for this game", 12f, BhStoreUi.DIM, false);
            LinearLayout.LayoutParams l = BhStoreUi.lp(-1, -2); l.topMargin = BhStoreUi.dp(this, 8);
            card.addView(tv, l);
        } else {
            TextView count = BhStoreUi.text(this, arr.length() + " DLC" + (arr.length() == 1 ? "" : "s") + " owned", 12f, BhStoreUi.MUTED, true);
            LinearLayout.LayoutParams cl = BhStoreUi.lp(-1, -2); cl.topMargin = BhStoreUi.dp(this, 8);
            card.addView(count, cl);
            TextView note = BhStoreUi.text(this, "DLC content is included in gen2 game installs.", 11f, BhStoreUi.DIM, false);
            LinearLayout.LayoutParams nl = BhStoreUi.lp(-1, -2); nl.topMargin = BhStoreUi.dp(this, 3); nl.bottomMargin = BhStoreUi.dp(this, 6);
            card.addView(note, nl);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject dlc = arr.optJSONObject(i);
                if (dlc == null) continue;
                LinearLayout row = BhStoreUi.row(this);
                row.setPadding(BhStoreUi.dp(this, 8), BhStoreUi.dp(this, 6), BhStoreUi.dp(this, 8), BhStoreUi.dp(this, 6));
                row.setBackground(BhStoreUi.roundBg(this, 0xFF1E1E2E, 6));
                row.addView(BhStoreUi.oneLine(BhStoreUi.text(this, dlc.optString("title", "Unknown DLC"), 13f, 0xFFDDDDDD, false), 2), BhStoreUi.lpWeight(-2, 1f));
                row.addView(BhStoreUi.text(this, "Owned", 11f, BhStoreUi.GREEN, true), BhStoreUi.lp(-2, -2));
                LinearLayout.LayoutParams rl = BhStoreUi.lp(-1, -2); rl.topMargin = BhStoreUi.dp(this, 4);
                card.addView(row, rl);
            }
        }
        col.addView(card, BhStoreUi.cardLp(this));
        return col;
    }

    // ── Cloud saves tab (GOG-1) ───────────────────────────────────────────────

    private View buildCloudSaves() {
        LinearLayout col = BhStoreUi.column(this);
        col.setPadding(0, BhStoreUi.dp(this, 6), 0, BhStoreUi.dp(this, 8));
        LinearLayout card = BhStoreUi.card(this);
        card.addView(BhStoreUi.text(this, "Cloud Saves", 14f, BhStoreUi.TEXT, true));

        LinearLayout folderRow = BhStoreUi.row(this);
        String savedDir = prefs.getString("gog_save_dir_" + gameId, null);
        cloudSaveDirTV = BhStoreUi.oneLine(BhStoreUi.text(this,
                savedDir != null ? shortenPath(savedDir) : "No save folder set", 12f,
                savedDir != null ? BhStoreUi.TEXT2 : BhStoreUi.DIM, false), 2);
        folderRow.addView(cloudSaveDirTV, BhStoreUi.lpWeight(-2, 1f));
        Button browse = BhStoreUi.button(this, "Browse", 0xFF333355);
        browse.setOnClickListener(v -> startActivityForResult(new Intent(this, FolderPickerActivity.class), REQUEST_FOLDER_PICKER));
        LinearLayout.LayoutParams bl = BhStoreUi.lp(-2, BhStoreUi.dp(this, 36)); bl.leftMargin = BhStoreUi.dp(this, 8);
        folderRow.addView(browse, bl);
        LinearLayout.LayoutParams fl = BhStoreUi.lp(-1, -2); fl.topMargin = BhStoreUi.dp(this, 8); fl.bottomMargin = BhStoreUi.dp(this, 10);
        card.addView(folderRow, fl);

        cloudSaveStatusTV = BhStoreUi.text(this, cloudStatus, 12f, 0xFF8888AA, false);
        cloudSaveStatusTV.setVisibility(cloudStatus.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams sl = BhStoreUi.lp(-1, -2); sl.bottomMargin = BhStoreUi.dp(this, 8);
        card.addView(cloudSaveStatusTV, sl);

        cloudUploadBtn = BhStoreUi.button(this, "Upload Saves", BhStoreUi.INFO_BLUE);
        cloudUploadBtn.setEnabled(savedDir != null);
        cloudUploadBtn.setOnClickListener(v -> cloudSync(true));
        card.addView(cloudUploadBtn, BhStoreUi.buttonLp(this));

        cloudDownloadBtn = BhStoreUi.button(this, "Download Saves", 0xFF2E7D32);
        cloudDownloadBtn.setEnabled(savedDir != null);
        cloudDownloadBtn.setOnClickListener(v -> cloudSync(false));
        card.addView(cloudDownloadBtn, BhStoreUi.buttonLp(this));

        col.addView(card, BhStoreUi.cardLp(this));
        return col;
    }

    private void cloudSync(boolean up) {
        String dir = prefs.getString("gog_save_dir_" + gameId, null);
        if (dir == null) { toast("Set a save folder first"); return; }
        enableCloudBtns(false);
        showCloudStatus(up ? "Preparing upload…" : "Preparing download…");
        GogCloudSaveManager.Callback cb = new GogCloudSaveManager.Callback() {
            @Override public void onStatus(String msg) { ui.post(() -> showCloudStatus(msg)); }
            @Override public void onDone(String msg)   { ui.post(() -> { showCloudStatus(msg); enableCloudBtns(true); }); }
            @Override public void onError(String msg)  { ui.post(() -> { showCloudStatus("Error: " + msg); enableCloudBtns(true); }); }
        };
        if (up) GogCloudSaveManager.uploadSaves(this, gameId, new File(dir), cb);
        else GogCloudSaveManager.downloadSaves(this, gameId, new File(dir), cb);
    }

    private void showCloudStatus(String msg) {
        cloudStatus = msg == null ? "" : msg;
        if (cloudSaveStatusTV == null) return;
        cloudSaveStatusTV.setText(cloudStatus);
        cloudSaveStatusTV.setVisibility(cloudStatus.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void enableCloudBtns(boolean enabled) {
        if (cloudUploadBtn != null) cloudUploadBtn.setEnabled(enabled);
        if (cloudDownloadBtn != null) cloudDownloadBtn.setEnabled(enabled);
    }

    private static String shortenPath(String path) {
        String[] parts = path.split("/");
        if (parts.length <= 3) return path;
        return "…/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    // ── Install flow (BhInstallConfirmDialog → BhDownloadService) ─────────────

    private GogGame makeGogGame() {
        return new GogGame(gameId, title, imageUrl, description, developer, category, generation, verticalCover);
    }

    private void startInstall() {
        final GogGame previewGame = makeGogGame();
        BhInstallConfirmDialog.Callback cb = new BhInstallConfirmDialog.Callback() {
            @Override public void onConfirm(int threadCount) {
                launchInstallWithThreads(threadCount, BhInstallConfirmDialog.CDN_PREF_AUTO);
            }
            @Override public void onConfirmWithCdn(int threadCount, String cdnPref) {
                launchInstallWithThreads(threadCount, cdnPref);
            }
        };
        BhInstallConfirmDialog.showAsync(this,
                title.isEmpty() ? gameId : title,
                "gog_games",
                cb,
                /* initialSizeBytes = */ 0L,
                sizeCallback -> new Thread(() -> {
                    long size = GogDownloadManager.fetchGameSize(this, previewGame);
                    runOnUiThread(() -> sizeCallback.onSize(size));
                }).start(),
                cdnListCallback -> new Thread(() -> {
                    List<String> urls = GogDownloadManager.fetchCdnUrls(this, gameId);
                    runOnUiThread(() -> cdnListCallback.onCdnList(urls));
                }).start());
    }

    private void launchInstallWithThreads(int threadCount, String cdnPref) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        downloading = true;
        progressPct = 0;
        progressMsg = "Starting…";
        attachDownloadListener();
        refreshState();

        GogGame game = makeGogGame();
        Intent svc = new Intent(this, BhDownloadService.class);
        svc.setAction(BhDownloadService.ACTION_START);
        svc.putExtra(BhDownloadService.EXTRA_STORE, "GOG");
        svc.putExtra(BhDownloadService.EXTRA_GAME_ID, dlKey);
        svc.putExtra(BhDownloadService.EXTRA_GAME_NAME, title.isEmpty() ? gameId : title);
        svc.putExtra(BhDownloadService.EXTRA_THREADS, threadCount);
        svc.putExtra(BhDownloadService.EXTRA_GOG_CDN_PREF, cdnPref);
        svc.putExtra(BhDownloadService.EXTRA_GOG_GAME_ID, game.gameId);
        svc.putExtra(BhDownloadService.EXTRA_GOG_TITLE, game.title);
        svc.putExtra(BhDownloadService.EXTRA_GOG_IMAGE_URL, game.imageUrl);
        svc.putExtra(BhDownloadService.EXTRA_GOG_DEVELOPER, game.developer);
        svc.putExtra(BhDownloadService.EXTRA_GOG_CATEGORY, game.category);
        svc.putExtra(BhDownloadService.EXTRA_GOG_GENERATION, game.generation);
        startForegroundService(svc);
    }

    private void attachDownloadListener() {
        BhDownloadService.addListener(dlKey, new BhDownloadService.DownloadListener() {
            @Override public void onProgress(String msg, int pct) {
                ui.post(() -> {
                    if (isFinishing()) return;
                    downloading = true;
                    progressPct = pct;
                    progressMsg = msg;
                    String label = msg == null || msg.isEmpty() ? "Downloading… " + pct + "%" : msg + "  " + pct + "%";
                    scaffold.primary.setProgress(label, pct / 100f);
                });
            }
            @Override public void onComplete(String installDir) {
                ui.post(() -> {
                    if (isFinishing()) return;
                    downloading = false;
                    // A fresh install rewrote gog_build_; the "update available" marker is stale.
                    GogInstallState.setUpdateAvailable(GogGameDetailActivity.this, gameId, false);
                    String b = prefs.getString("gog_build_" + gameId, null);
                    if (b != null) updateStatusText = "Installed build: " + b.substring(0, Math.min(12, b.length())) + "…";
                    setResult(RESULT_REFRESH);
                    refreshState();
                });
            }
            @Override public void onError(String msg) {
                ui.post(() -> {
                    if (isFinishing()) return;
                    downloading = false;
                    refreshState();
                    toast("Error: " + msg);
                });
            }
            @Override public void onCancelled() {
                ui.post(() -> {
                    if (isFinishing()) return;
                    downloading = false;
                    refreshState();
                });
            }
        });
    }

    // ── Add to library (GameHub DB) ───────────────────────────────────────────

    private void addToLibrary() {
        String exe = prefs.getString("gog_exe_" + gameId, null);
        if (exe != null) GogLaunchHelper.addToLibrary(this, exe, gameId, title, imageUrl);
    }

    // ── Set .exe ──────────────────────────────────────────────────────────────

    private void pickExe() {
        final String dir = prefs.getString("gog_dir_" + gameId, null);
        if (dir == null) return;
        final File installPath = new File(dir);
        new Thread(() -> {
            List<String> candidates = GogDownloadManager.collectExeCandidates(installPath);
            if (candidates.isEmpty()) {
                ui.post(() -> toast("No .exe files found"));
                return;
            }
            showExePicker(candidates, selected -> {
                if (selected != null && !selected.isEmpty()) {
                    prefs.edit().putString("gog_exe_" + gameId, selected).apply();
                    ui.post(() -> {
                        setResult(RESULT_REFRESH);
                        refreshState();
                        toast("Exe set: " + new File(selected).getName());
                    });
                }
            });
        }).start();
    }

    private void showExePicker(final List<String> candidates, final java.util.function.Consumer<String> onSelected) {
        final String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            File f = new File(candidates.get(i));
            File parent = f.getParentFile();
            labels[i] = (parent != null) ? parent.getName() + "/" + f.getName() : f.getName();
        }
        ui.post(() ->
            new AlertDialog.Builder(this)
                .setTitle("Select game executable")
                .setItems(labels, (d, which) -> new Thread(() -> onSelected.accept(candidates.get(which))).start())
                .setNegativeButton("Cancel", null)
                .show());
    }

    // ── Uninstall ─────────────────────────────────────────────────────────────

    private void confirmUninstall() {
        new AlertDialog.Builder(this)
            .setTitle("Uninstall " + title + "?")
            .setMessage("This will delete all installed game files.")
            .setPositiveButton("Uninstall", (d, w) -> doUninstall())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private AlertDialog showUninstallProgress() {
        LinearLayout ll = BhStoreUi.row(this);
        ll.setPadding(BhStoreUi.dp(this, 24), BhStoreUi.dp(this, 24), BhStoreUi.dp(this, 24), BhStoreUi.dp(this, 24));
        ll.addView(new ProgressBar(this));
        TextView tv = new TextView(this);
        tv.setText("  Uninstalling…");
        tv.setTextSize(16f);
        ll.addView(tv);
        AlertDialog d = new AlertDialog.Builder(this).setView(ll).setCancelable(false).create();
        d.show();
        return d;
    }

    private void doUninstall() {
        // The shared helper resolves both INSTALLED and PARTIAL, so a failed download cleans up
        // identically to a working install.
        final String dirName = GogInstallPath.getInstallOrPartialPath(prefs, gameId);
        if (dirName == null) return;
        final AlertDialog progress = showUninstallProgress();
        new Thread(() -> {
            deleteDir(new File(dirName));
            GogInstallState.purge(this, gameId);
            BhDownloadService.removeLibraryEntry(this, dlKey);
            ui.post(() -> {
                progress.dismiss();
                setResult(RESULT_REFRESH);
                updateStatusText = "Build ID not recorded — tap Check to verify";
                refreshState();
                toast(title + " uninstalled");
            });
        }).start();
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }

    // ── Updates (GOG-2) ───────────────────────────────────────────────────────

    private void doCheckUpdate() {
        updateStatusText = "Checking…";
        checkUpdateEnabled = false;
        refreshState();
        new Thread(() -> {
            String token = GogLibraryRepo.validToken(this);
            String stored = prefs.getString("gog_build_" + gameId, null);
            final String latest = GogInstallState.checkForUpdate(this, gameId, token);
            ui.post(() -> {
                if (isFinishing()) return;
                checkUpdateEnabled = true;
                if (latest == null) {
                    updateStatusText = "Could not reach update server.";
                } else if (stored == null) {
                    updateStatusText = "Up to date (build " + latest.substring(0, Math.min(12, latest.length())) + "…)";
                } else if (stored.equals(latest)) {
                    updateStatusText = "Up to date ✓";
                } else {
                    updateStatusText = "Update available!\nInstalled: " + stored.substring(0, Math.min(10, stored.length()))
                            + "…  →  Latest: " + latest.substring(0, Math.min(10, latest.length())) + "…";
                }
                refreshState();
            });
        }, "gog-update-check-" + gameId).start();
    }

    // ── Install size / media ──────────────────────────────────────────────────

    private void loadInstallSize() {
        long cached = prefs.getLong("gog_size_" + gameId, -1);
        if (cached > 0) { sizeText = BhStoreUi.formatBytes(cached); return; }
        new Thread(() -> {
            String token = prefs.getString("access_token", null);
            long size = GogDownloadManager.fetchInstallSizeBytes(gameId, token);
            if (size > 0) prefs.edit().putLong("gog_size_" + gameId, size).apply();
            ui.post(() -> {
                if (isFinishing()) return;
                sizeText = size > 0 ? BhStoreUi.formatBytes(size) : "Size unknown";
                if (tab == TAB_DETAILS) renderBody();
            });
        }, "gog-size-" + gameId).start();
    }

    /** Media tab source: GOG's public product page (one request per open; cached in-process). */
    private void loadMedia() {
        new Thread(() -> {
            GogStoreCatalog.ProductDetail d = null;
            try { d = GogStoreCatalog.product(gameId); } catch (Throwable ignored) {}
            final GogStoreCatalog.ProductDetail res = d;
            ui.post(() -> {
                if (isFinishing()) return;
                mediaLoading = false;
                media = res != null ? res.media : null;
                // The hero band is wide: prefer GOG's background art over the portrait SGDB cover.
                if (res != null && res.background != null) {
                    List<String> chain = new ArrayList<>();
                    chain.add(res.background);
                    if (!imageUrl.isEmpty()) chain.add(imageUrl);
                    scaffold.loadHero(chain);
                }
                if (description.isEmpty() && res != null && !res.lead.isEmpty()) description = res.lead;
                renderTabs();
            });
        }, "gog-media-" + gameId).start();
    }

    // ── Copy to Downloads ─────────────────────────────────────────────────────

    private void startCopyToDownloads() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_COPY_STORAGE);
            return;
        }
        performCopyToDownloads();
    }

    private void performCopyToDownloads() {
        toast("Copying…");
        new Thread(() -> {
            String dest = GogDownloadManager.copyToDownloads(this, gameId);
            ui.post(() -> {
                if (dest != null) Toast.makeText(this, "Copied to: " + dest, Toast.LENGTH_LONG).show();
                else toast("Copy failed — install files not found");
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_COPY_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                performCopyToDownloads();
            } else {
                toast("Storage permission denied — cannot copy to Downloads");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FOLDER_PICKER && resultCode == RESULT_OK && data != null) {
            String selectedPath = data.getStringExtra("path");
            if (selectedPath != null && !selectedPath.isEmpty()) {
                prefs.edit().putString("gog_save_dir_" + gameId, selectedPath).apply();
                if (cloudSaveDirTV != null) {
                    cloudSaveDirTV.setText(shortenPath(selectedPath));
                    cloudSaveDirTV.setTextColor(BhStoreUi.TEXT2);
                }
                enableCloudBtns(true);
                toast("Save folder set");
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
