package app.revanced.extension.gamehub.steamclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Environment;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import app.revanced.extension.gamehub.gog.FolderPickerActivity;
import app.revanced.extension.gamehub.gog.GogLaunchHelper;

/**
 * "Steam" card on the Explore screen's Stores rail (action "steam" in
 * {@link app.revanced.extension.gamehub.explore.BhExploreActions}).
 *
 * Flow (user spec 2026-06-12):
 *  - Not installed → dialog: "Download and install the full Steam client?"
 *    with Close / Download. Destination defaults to the public Downloads
 *    folder; a Browse row (reuses the GOG {@link FolderPickerActivity})
 *    lets the user pick anywhere first. If the picked folder already holds
 *    a steam.exe, the Download button flips to "Add to Library" and no
 *    download happens (register-existing-install path).
 *  - On Download the dialog swaps to TWO progress bars: download, then
 *    zip extraction. Archive is fetched from DOWNLOAD_URL (a GitHub
 *    release asset), extracted in place, the zip deleted, and the found
 *    steam.exe path persisted to prefs.
 *  - Installed (prefs path valid, or auto-detected in Downloads) → tapping
 *    the card registers Steam into GameHub's library exactly like a GOG
 *    game: {@link GogLaunchHelper#addToLibrary} with
 *    start_type=1409 (GogGameByPcEmulator) → GameHub launches steam.exe in
 *    its own container from the library tile.
 *
 * Browse plumbing: FolderPickerActivity returns via onActivityResult on the
 * HOST activity (BannerExploreActivity forwards to
 * {@link #handleActivityResult}); the open dialog session is kept in a
 * static so the destination row updates in place.
 *
 * Fail-safe by construction: every entry point catches Throwable, logs and
 * toasts — never crashes GameHub.
 */
public final class SteamClientCard {

    private static final String TAG = "BannerHub";

    /**
     * GitHub release asset holding the packaged Steam client as a .zip.
     * NOTE: placeholder until the archive is uploaded — keep tag/file in
     * sync with the actual release (one-line change).
     */
    private static final String DOWNLOAD_URL =
        "https://github.com/The412Banner/Nightlies/releases/download/steam-client/steam-client.zip";

    /** Library card art (remote URL — Coil loads it like the GOG card's). */
    private static final String CARD_ART =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/8/83/Steam_icon_logo.svg/960px-Steam_icon_logo.svg.png";

    /** Library row identity → GogLaunchHelper row id "gog_steamclient". */
    private static final String STEAM_ID   = "steamclient";
    private static final String STEAM_NAME = "Steam";

    private static final String PREFS       = "bh_steam_client";
    private static final String KEY_EXE     = "exe_path";
    private static final String KEY_DIR     = "install_dir";

    /** Picker request code owned by BannerExploreActivity's forward. */
    public static final int REQUEST_FOLDER_PICKER = 7401;

    private static final String ZIP_NAME = "steam-client.zip";

    private SteamClientCard() {}

    // ── card tap entry ───────────────────────────────────────────────────────

    public static void onCardTap(Activity host) {
        if (host == null) return;
        try {
            String exe = findInstalledExe(host);
            if (exe != null) {
                showInstalledDialog(host, exe);
            } else {
                showInstallDialog(host);
            }
        } catch (Throwable t) {
            Log.e(TAG, "SteamClientCard.onCardTap failed (non-fatal)", t);
            toast(host, "Couldn't open Steam card");
        }
    }

    /** prefs path if still valid, else auto-detect under the default
     *  Downloads locations (covers a manual install/unzip too). */
    private static String findInstalledExe(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            String saved = p.getString(KEY_EXE, null);
            if (saved != null && new File(saved).isFile()) return saved;

            File dl = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            String found = scanForSteamExe(dl, 2);
            if (found != null) {
                p.edit().putString(KEY_EXE, found)
                        .putString(KEY_DIR, new File(found).getParent()).apply();
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Bounded search for steam.exe: dir itself, then subdirs to depth. */
    private static String scanForSteamExe(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth < 0) return null;
        File direct = new File(dir, "steam.exe");
        if (direct.isFile()) return direct.getAbsolutePath();
        File alt = new File(dir, "Steam.exe");
        if (alt.isFile()) return alt.getAbsolutePath();
        if (depth == 0) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (!k.isDirectory()) continue;
            // Only descend into plausibly-Steam dirs at the top level to keep
            // the Downloads scan cheap; deeper levels (explicit installs)
            // search everything.
            String n = k.getName().toLowerCase();
            if (depth == 2 && !n.contains("steam")) continue;
            String r = scanForSteamExe(k, depth - 1);
            if (r != null) return r;
        }
        return null;
    }

    // ── installed → add to library (like a GOG game) ─────────────────────────

    private static void showInstalledDialog(Activity host, String exePath) {
        new AlertDialog.Builder(host)
            .setTitle(STEAM_NAME)
            .setMessage("Steam client is installed:\n" + exePath
                    + "\n\nAdd it to your game library? It will launch in its "
                    + "own container, like any other PC game.")
            .setPositiveButton("Add to Library", (d, w) ->
                GogLaunchHelper.addToLibrary(host, exePath, STEAM_ID, STEAM_NAME, CARD_ART))
            .setNeutralButton("Reinstall…", (d, w) -> {
                host.getSharedPreferences(PREFS, 0).edit().remove(KEY_EXE).apply();
                showInstallDialog(host);
            })
            .setNegativeButton("Close", null)
            .show();
    }

    // ── install dialog (download + extract) ──────────────────────────────────

    /** Live dialog session — lets the FolderPicker result update the open
     *  dialog, and the worker thread publish progress. Single instance;
     *  cleared on dismiss. */
    private static final class Session {
        Activity host;
        AlertDialog dialog;
        TextView destTV;
        TextView statusTV;
        ProgressBar dlBar;   TextView dlLabel;
        ProgressBar exBar;   TextView exLabel;
        File destDir;
        String existingExe;          // non-null → register-only mode
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile boolean downloading = false;
    }

    private static volatile Session sSession;

    private static void showInstallDialog(Activity host) {
        File defaultDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);

        Session s = new Session();
        s.host = host;
        s.destDir = defaultDir;

        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(host, 20), dp(host, 12), dp(host, 20), dp(host, 4));

        TextView blurb = new TextView(host);
        blurb.setText("Download and install the full Steam client? "
                + "It will be downloaded and extracted to the folder below, "
                + "then added to your game library like a GOG game.");
        blurb.setTextColor(0xFFCCCCCC);
        blurb.setTextSize(14f);
        content.addView(blurb);

        s.destTV = new TextView(host);
        s.destTV.setTextColor(0xFFAACCFF);
        s.destTV.setTextSize(13f);
        s.destTV.setTypeface(null, Typeface.BOLD);
        s.destTV.setPadding(0, dp(host, 12), 0, dp(host, 2));
        s.destTV.setText("Install to:  " + s.destDir.getAbsolutePath() + "  ▾");
        s.destTV.setOnClickListener(v -> {
            if (s.downloading) return;
            try {
                Intent i = new Intent(host, FolderPickerActivity.class);
                host.startActivityForResult(i, REQUEST_FOLDER_PICKER);
            } catch (Throwable t) {
                Log.e(TAG, "SteamClientCard: folder picker failed", t);
                toast(host, "Couldn't open folder picker");
            }
        });
        content.addView(s.destTV);

        TextView hint = new TextView(host);
        hint.setText("Tap to change. If the folder you pick already contains "
                + "steam.exe, it will be used as-is (no download).");
        hint.setTextColor(0xFF888888);
        hint.setTextSize(11f);
        content.addView(hint);

        // Progress section — hidden until Download is pressed.
        s.dlLabel = progressLabel(host, "Download");
        s.dlBar   = progressBar(host);
        s.exLabel = progressLabel(host, "Extraction");
        s.exBar   = progressBar(host);
        s.statusTV = new TextView(host);
        s.statusTV.setTextSize(12f);
        s.statusTV.setPadding(0, dp(host, 8), 0, 0);
        for (android.view.View v : new android.view.View[]{
                s.dlLabel, s.dlBar, s.exLabel, s.exBar, s.statusTV}) {
            v.setVisibility(android.view.View.GONE);
            content.addView(v);
        }

        AlertDialog dialog = new AlertDialog.Builder(host)
            .setTitle("Install Steam Client")
            .setView(content)
            .setPositiveButton("Download", null) // listener set below (no auto-dismiss)
            .setNegativeButton("Close", null)
            .create();
        dialog.setOnDismissListener(d -> {
            s.cancelled.set(true);
            if (sSession == s) sSession = null;
        });
        s.dialog = dialog;
        sSession = s;
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (s.existingExe != null) {
                // Register-existing-install path (no download).
                persistInstall(host, s.existingExe);
                dialog.dismiss();
                GogLaunchHelper.addToLibrary(host, s.existingExe,
                        STEAM_ID, STEAM_NAME, CARD_ART);
                return;
            }
            if (s.downloading) return;
            startDownload(s);
        });
    }

    /** Folder picker result, forwarded by BannerExploreActivity. */
    public static void handleActivityResult(Activity host, int requestCode,
                                            int resultCode, Intent data) {
        if (requestCode != REQUEST_FOLDER_PICKER) return;
        try {
            Session s = sSession;
            if (s == null || s.dialog == null || !s.dialog.isShowing()) return;
            if (resultCode != Activity.RESULT_OK || data == null) return;
            String path = data.getStringExtra("path");
            if (path == null || path.isEmpty()) return;

            s.destDir = new File(path);
            String existing = scanForSteamExe(s.destDir, 2);
            s.existingExe = existing;
            android.widget.Button pos = s.dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (existing != null) {
                s.destTV.setText("Install to:  " + path + "  ▾");
                s.statusTV.setVisibility(android.view.View.VISIBLE);
                s.statusTV.setTextColor(0xFF88CC88);
                s.statusTV.setText("Existing Steam install detected:\n" + existing
                        + "\nNo download needed.");
                if (pos != null) pos.setText("Add to Library");
            } else {
                s.destTV.setText("Install to:  " + path + "  ▾");
                s.statusTV.setVisibility(android.view.View.GONE);
                if (pos != null) pos.setText("Download");
            }
        } catch (Throwable t) {
            Log.e(TAG, "SteamClientCard.handleActivityResult failed (non-fatal)", t);
        }
    }

    // ── worker: download then extract, with per-phase progress ───────────────

    private static void startDownload(Session s) {
        s.downloading = true;
        s.dlLabel.setVisibility(android.view.View.VISIBLE);
        s.dlBar.setVisibility(android.view.View.VISIBLE);
        s.exLabel.setVisibility(android.view.View.VISIBLE);
        s.exBar.setVisibility(android.view.View.VISIBLE);
        s.statusTV.setVisibility(android.view.View.VISIBLE);
        s.statusTV.setTextColor(0xFFCCCCCC);
        s.statusTV.setText("Starting download…");
        android.widget.Button pos = s.dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos != null) pos.setEnabled(false);
        android.widget.Button neg = s.dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (neg != null) neg.setText("Cancel");

        new Thread(() -> {
            File zip  = new File(s.destDir, ZIP_NAME);
            File part = new File(s.destDir, ZIP_NAME + ".part");
            try {
                if (!s.destDir.isDirectory() && !s.destDir.mkdirs()) {
                    throw new java.io.IOException(
                            "Can't create " + s.destDir + " — check storage permission");
                }
                downloadTo(s, part);
                if (s.cancelled.get()) { part.delete(); return; }
                if (zip.exists()) zip.delete();
                if (!part.renameTo(zip)) throw new java.io.IOException("rename failed");

                extractZip(s, zip, s.destDir);
                zip.delete();
                if (s.cancelled.get()) return;

                String exe = scanForSteamExe(s.destDir, 4);
                if (exe == null) {
                    throw new java.io.IOException(
                            "Extracted, but steam.exe wasn't found in the archive");
                }
                persistInstall(s.host, exe);
                final String exeF = exe;
                ui(s, () -> {
                    s.statusTV.setTextColor(0xFF88CC88);
                    s.statusTV.setText("Installed ✓\n" + exeF);
                    android.widget.Button p = s.dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (p != null) {
                        p.setText("Add to Library");
                        p.setEnabled(true);
                        p.setOnClickListener(v -> {
                            s.dialog.dismiss();
                            GogLaunchHelper.addToLibrary(s.host, exeF,
                                    STEAM_ID, STEAM_NAME, CARD_ART);
                        });
                    }
                    android.widget.Button n = s.dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                    if (n != null) n.setText("Close");
                });
            } catch (Throwable t) {
                Log.e(TAG, "SteamClientCard download/extract failed", t);
                part.delete();
                if (!s.cancelled.get()) {
                    ui(s, () -> {
                        s.statusTV.setTextColor(0xFFE08888);
                        s.statusTV.setText("Failed: " + t.getMessage());
                        android.widget.Button p = s.dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                        if (p != null) { p.setText("Retry"); p.setEnabled(true); }
                        s.downloading = false;
                    });
                }
            }
        }, "bh-steam-install").start();
    }

    private static void downloadTo(Session s, File part) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(DOWNLOAD_URL).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "BannerHub");
        try {
            int code = c.getResponseCode();
            // HttpURLConnection won't hop a redirect chain that crosses hosts
            // more than once in some stacks — follow manually to be safe
            // (GitHub release assets 302 to objects.githubusercontent.com).
            int hops = 0;
            while (code / 100 == 3 && hops++ < 5) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new java.io.IOException("redirect without Location");
                c = (HttpURLConnection) new URL(loc).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "BannerHub");
                code = c.getResponseCode();
            }
            if (code != 200) {
                throw new java.io.IOException("HTTP " + code
                        + (code == 404 ? " — release asset not uploaded yet?" : ""));
            }
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream();
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                long done = 0, lastPost = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (s.cancelled.get()) return;
                    out.write(buf, 0, n);
                    done += n;
                    if (done - lastPost > 1_000_000 || done == total) {
                        lastPost = done;
                        final long d = done, t = total;
                        ui(s, () -> {
                            if (t > 0) {
                                s.dlBar.setIndeterminate(false);
                                s.dlBar.setProgress((int) (d * 100 / t));
                                s.dlLabel.setText("Download  —  "
                                        + fmt(d) + " / " + fmt(t));
                            } else {
                                s.dlBar.setIndeterminate(true);
                                s.dlLabel.setText("Download  —  " + fmt(d));
                            }
                            s.statusTV.setText("Downloading…");
                        });
                    }
                }
            }
            ui(s, () -> { s.dlBar.setIndeterminate(false); s.dlBar.setProgress(100); });
        } finally {
            c.disconnect();
        }
    }

    private static void extractZip(Session s, File zip, File destDir) throws Exception {
        ui(s, () -> s.statusTV.setText("Extracting…"));
        String destCanon = destDir.getCanonicalPath();
        try (ZipFile zf = new ZipFile(zip)) {
            long total = 0;
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements();) {
                ZipEntry ze = e.nextElement();
                if (!ze.isDirectory() && ze.getSize() > 0) total += ze.getSize();
            }
            long done = 0, lastPost = 0;
            byte[] buf = new byte[65536];
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements();) {
                if (s.cancelled.get()) return;
                ZipEntry ze = e.nextElement();
                File out = new File(destDir, ze.getName());
                // zip-slip guard
                if (!out.getCanonicalPath().startsWith(destCanon + File.separator)
                        && !out.getCanonicalPath().equals(destCanon)) {
                    throw new java.io.IOException("Blocked unsafe zip entry: " + ze.getName());
                }
                if (ze.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = zf.getInputStream(ze);
                     OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (s.cancelled.get()) return;
                        os.write(buf, 0, n);
                        done += n;
                        if (done - lastPost > 2_000_000 || done == total) {
                            lastPost = done;
                            final long d = done, t = total;
                            ui(s, () -> {
                                if (t > 0) s.exBar.setProgress((int) (d * 100 / t));
                                s.exLabel.setText("Extraction  —  "
                                        + fmt(d) + " / " + fmt(t));
                            });
                        }
                    }
                }
            }
            ui(s, () -> s.exBar.setProgress(100));
        }
    }

    private static void persistInstall(Context ctx, String exePath) {
        try {
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putString(KEY_EXE, exePath)
                .putString(KEY_DIR, new File(exePath).getParent())
                .apply();
        } catch (Throwable ignored) {}
    }

    // ── small UI helpers ─────────────────────────────────────────────────────

    private static void ui(Session s, Runnable r) {
        try {
            if (s.host != null && !s.cancelled.get()
                    && s.dialog != null && s.dialog.isShowing()) {
                s.host.runOnUiThread(r);
            }
        } catch (Throwable ignored) {}
    }

    private static TextView progressLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(12f);
        tv.setPadding(0, dp(ctx, 10), 0, dp(ctx, 2));
        return tv;
    }

    private static ProgressBar progressBar(Context ctx) {
        ProgressBar pb = new ProgressBar(ctx, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        return pb;
    }

    private static String fmt(long bytes) {
        if (bytes >= 1_073_741_824L) return String.format("%.2f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)     return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1024L)          return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private static void toast(Activity a, String msg) {
        try { Toast.makeText(a, msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
