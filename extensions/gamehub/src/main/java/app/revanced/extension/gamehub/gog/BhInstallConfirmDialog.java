package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Centralized install-confirmation dialog used by GOG / Epic / Amazon list activities
 * and detail activities. Shows install size, available space, and a per-download
 * thread-count picker. Default opens at {@link BhDownloadConfig#DEFAULT_THREADS}.
 *
 * GOG callers can also pass a {@link CdnListFetcher} to enable a CDN picker row
 * (lazy-loaded — fetches secure_link + HEAD-probes on first tap so the dialog
 * opens instantly). Selected CDN is delivered alongside thread count via the
 * {@link Callback#onConfirmWithCdn} default method; non-GOG callers ignore it.
 *
 * Chosen values are not persisted anywhere — every install starts fresh at the
 * defaults (DEFAULT_THREADS + AUTO CDN).
 */
public final class BhInstallConfirmDialog {

    /** Sentinel used in onConfirmWithCdn to mean "use the multi-CDN rank+cycle". */
    public static final String CDN_PREF_AUTO = "AUTO";

    public interface Callback {
        /** Legacy — non-GOG callers (Epic/Amazon) just need the thread count. */
        void onConfirm(int threadCount);
        /**
         * GOG callers receive the CDN preference too: either {@link #CDN_PREF_AUTO}
         * or a specific CDN base URL the user picked from the picker. Default
         * delegates to {@link #onConfirm} so legacy callers don't have to change.
         */
        default void onConfirmWithCdn(int threadCount, String cdnPref) {
            onConfirm(threadCount);
        }
    }

    /** Runtime hook for fetching the install size async (e.g. via a manifest fetch). */
    public interface SizeFetcher {
        /** Implementations should call back on the UI thread. */
        void fetch(SizeCallback cb);
    }

    public interface SizeCallback {
        /** Pass the final size in bytes (or 0 if unknown). */
        void onSize(long bytes);
    }

    /**
     * Runtime hook for fetching the CDN list async. GOG callers wire this to
     * {@code GogDownloadManager.fetchCdnUrls(ctx, gameId)} on a background
     * thread. Called only once per dialog (first CDN-row tap).
     */
    public interface CdnListFetcher {
        /** Implementations call back on the UI thread with the unranked URL list. */
        void fetch(CdnListCallback cb);
    }

    public interface CdnListCallback {
        /** Pass the list of CDN base URLs (empty list on any failure). */
        void onCdnList(List<String> urls);
    }

    private BhInstallConfirmDialog() {}

    /**
     * Show the dialog with a known install size.
     *
     * @param activity  hosting activity
     * @param title     game title (e.g. "Cyberpunk 2077")
     * @param sizeBytes install size in bytes; 0 = unknown
     * @param storeDir  store dir name passed to BhStoragePath (e.g. "gog_games", "epic_games", "Amazon")
     * @param callback  invoked with the chosen thread count on Install
     */
    public static void show(Activity activity, String title, long sizeBytes,
                            String storeDir, Callback callback) {
        showAsync(activity, title, storeDir, callback, sizeBytes, null);
    }

    /**
     * Show the dialog when install size needs to be fetched async (e.g. Amazon manifest
     * fetch). The dialog opens immediately with "Fetching…" in the size row, then
     * updates when {@code fetcher} delivers the result.
     */
    public static void showAsync(Activity activity, String title, String storeDir,
                                 Callback callback, long initialSizeBytes,
                                 SizeFetcher fetcher) {
        showAsync(activity, title, storeDir, callback, initialSizeBytes, fetcher, null);
    }

    /**
     * Overload that adds a CDN picker row (GOG callers only). The row appears
     * below "Download speed". Initial label "CDN: Auto ▾". On first tap, the
     * {@code cdnFetcher} is invoked async; while waiting the row shows
     * "CDN: Fetching… ▾" and is disabled. Once results arrive, the picker
     * lists Auto + each CDN with its HEAD-probe latency badge.
     */
    public static void showAsync(Activity activity, String title, String storeDir,
                                 Callback callback, long initialSizeBytes,
                                 SizeFetcher fetcher, CdnListFetcher cdnFetcher) {
        final Context ctx = activity;
        final long freeBytes = computeFreeBytes(activity, storeDir);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4));

        final TextView sizeTV = makeRow(ctx,
                "Install size:  " + (initialSizeBytes > 0 ? formatBytes(initialSizeBytes)
                        : (fetcher != null ? "Fetching…" : "Unknown")),
                0xFFCCCCCC);
        content.addView(sizeTV);

        TextView freeTV = makeRow(ctx,
                "Available space:  " + (freeBytes >= 0 ? formatBytes(freeBytes) : "Unknown"),
                0xFF88CC88);
        content.addView(freeTV);

        // Download speed row — tappable, opens preset picker
        final AtomicInteger threads = new AtomicInteger(BhDownloadConfig.DEFAULT_THREADS);
        final TextView speedTV = makeRow(ctx,
                "Download speed:  " + BhDownloadConfig.labelFor(threads.get()) + "  ▾",
                0xFFAACCFF);
        speedTV.setPadding(0, dp(ctx, 12), 0, dp(ctx, 4));
        speedTV.setOnClickListener(v -> {
            String[] labels = BhDownloadConfig.presetLabels();
            int[] values = BhDownloadConfig.presets();
            int currentIdx = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == threads.get()) { currentIdx = i; break; }
            }
            new AlertDialog.Builder(activity)
                    .setTitle("Download speed")
                    .setSingleChoiceItems(labels, currentIdx, (d, which) -> {
                        threads.set(BhDownloadConfig.clamp(values[which]));
                        speedTV.setText("Download speed:  "
                                + BhDownloadConfig.labelFor(threads.get()) + "  ▾");
                        d.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        content.addView(speedTV);

        TextView hint = new TextView(ctx);
        hint.setText("Higher = faster but uses more CPU and battery. Default is Low.");
        hint.setTextColor(0xFF888888);
        hint.setTextSize(11f);
        hint.setPadding(0, dp(ctx, 4), 0, 0);
        content.addView(hint);

        // CDN row (GOG only) — lazy-loaded on first tap. State held in
        // these refs so the click handler stays simple and the eventual
        // probe results can update the row label.
        final AtomicReference<String> cdnPref = new AtomicReference<>(CDN_PREF_AUTO);
        final AtomicReference<List<BhCdnHelper.ProbeResult>> probedRef = new AtomicReference<>(null);
        final AtomicReference<Boolean> probing = new AtomicReference<>(Boolean.FALSE);
        final TextView cdnTV;
        if (cdnFetcher != null) {
            cdnTV = makeRow(ctx, "CDN:  Auto  ▾", 0xFFAACCFF);
            cdnTV.setPadding(0, dp(ctx, 12), 0, dp(ctx, 4));
            cdnTV.setOnClickListener(v -> openCdnPicker(activity, cdnFetcher, cdnTV, cdnPref, probedRef, probing));
            content.addView(cdnTV);

            TextView cdnHint = new TextView(ctx);
            cdnHint.setText("Auto picks the fastest CDN and retries on others if a download fails.");
            cdnHint.setTextColor(0xFF888888);
            cdnHint.setTextSize(11f);
            cdnHint.setPadding(0, dp(ctx, 4), 0, 0);
            content.addView(cdnHint);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Install " + title + "?")
                .setView(content)
                .setPositiveButton("Install", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.dismiss();
            // Always send via the CDN-aware method; legacy callbacks default-
            // delegate to onConfirm() so they don't have to opt in.
            callback.onConfirmWithCdn(threads.get(), cdnPref.get());
        });

        if (fetcher != null && initialSizeBytes <= 0) {
            fetcher.fetch(bytes -> {
                if (dialog.isShowing()) {
                    sizeTV.setText("Install size:  "
                            + (bytes > 0 ? formatBytes(bytes) : "Unknown"));
                }
            });
        }
    }

    /**
     * Opens the CDN picker dialog. Triggers async fetch on first call (shows
     * "Fetching…" state); reuses cached probedRef on subsequent calls. The
     * latency-bearing list is built by HEAD-probing on a background thread.
     */
    private static void openCdnPicker(Activity activity, CdnListFetcher cdnFetcher,
                                       TextView cdnTV, AtomicReference<String> cdnPref,
                                       AtomicReference<List<BhCdnHelper.ProbeResult>> probedRef,
                                       AtomicReference<Boolean> probing) {
        List<BhCdnHelper.ProbeResult> probed = probedRef.get();
        if (probed != null) {
            renderCdnPicker(activity, probed, cdnTV, cdnPref, probedRef, probing);
            return;
        }
        if (Boolean.TRUE.equals(probing.get())) return;          // already in-flight
        probing.set(Boolean.TRUE);
        cdnTV.setText("CDN:  Fetching…  ▾");
        cdnTV.setEnabled(false);
        cdnFetcher.fetch(urls -> {
            new Thread(() -> {
                List<BhCdnHelper.ProbeResult> probe = BhCdnHelper.probeAndRank(urls, 1500);
                activity.runOnUiThread(() -> {
                    probedRef.set(probe);
                    probing.set(Boolean.FALSE);
                    cdnTV.setEnabled(true);
                    cdnTV.setText("CDN:  " + cdnLabelFor(cdnPref.get(), probe) + "  ▾");
                    renderCdnPicker(activity, probe, cdnTV, cdnPref, probedRef, probing);
                });
            }, "bh-cdn-probe").start();
        });
    }

    private static void renderCdnPicker(Activity activity,
                                         List<BhCdnHelper.ProbeResult> probed,
                                         TextView cdnTV,
                                         AtomicReference<String> cdnPref,
                                         AtomicReference<List<BhCdnHelper.ProbeResult>> probedRef,
                                         AtomicReference<Boolean> probing) {
        int count = probed.size();
        // Picker has [Auto] + N entries
        String[] labels = new String[count + 1];
        labels[0] = "Auto (recommended — uses fastest)";
        for (int i = 0; i < count; i++) {
            BhCdnHelper.ProbeResult p = probed.get(i);
            String host = hostOf(p.url);
            String latency = p.reachable ? (p.latencyMs + " ms") : "unreachable";
            labels[i + 1] = host + "    " + latency;
        }

        // Current selection idx
        int currentIdx = 0;
        if (!CDN_PREF_AUTO.equals(cdnPref.get())) {
            String currentHost = hostOf(cdnPref.get());
            for (int i = 0; i < count; i++) {
                if (currentHost != null && currentHost.equalsIgnoreCase(hostOf(probed.get(i).url))) {
                    currentIdx = i + 1;
                    break;
                }
            }
        }

        new AlertDialog.Builder(activity)
                .setTitle("CDN selection")
                .setSingleChoiceItems(labels, currentIdx, (d, which) -> {
                    if (which == 0) {
                        cdnPref.set(CDN_PREF_AUTO);
                    } else {
                        cdnPref.set(probed.get(which - 1).url);
                    }
                    cdnTV.setText("CDN:  " + cdnLabelFor(cdnPref.get(), probed) + "  ▾");
                    d.dismiss();
                })
                // ↻ Refresh re-probes the SAME URL list (no secure_link re-fetch
                // needed; the URL list is stable for the dialog session). Closes
                // the current picker, briefly shows "Refreshing…" on the inline
                // row, then re-opens with fresh latency numbers.
                .setNeutralButton("↻ Refresh", (d, w) -> {
                    refreshAndReopenCdnPicker(activity, probed, cdnTV, cdnPref, probedRef, probing);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Re-runs HEAD probes against the existing CDN URL list, updates the cached
     * probedRef, and re-opens the picker with the fresh latency numbers.
     * No-op if a probe is already in flight (race protection).
     */
    private static void refreshAndReopenCdnPicker(Activity activity,
                                                   List<BhCdnHelper.ProbeResult> prevProbed,
                                                   TextView cdnTV,
                                                   AtomicReference<String> cdnPref,
                                                   AtomicReference<List<BhCdnHelper.ProbeResult>> probedRef,
                                                   AtomicReference<Boolean> probing) {
        if (Boolean.TRUE.equals(probing.get())) return;
        probing.set(Boolean.TRUE);
        cdnTV.setEnabled(false);
        cdnTV.setText("CDN:  Refreshing…  ▾");

        // Extract URLs from the previous probe (re-probe same hosts).
        final java.util.List<String> urls = new java.util.ArrayList<>(prevProbed.size());
        for (BhCdnHelper.ProbeResult p : prevProbed) urls.add(p.url);

        new Thread(() -> {
            List<BhCdnHelper.ProbeResult> fresh = BhCdnHelper.probeAndRank(urls, 1500);
            activity.runOnUiThread(() -> {
                probedRef.set(fresh);
                probing.set(Boolean.FALSE);
                cdnTV.setEnabled(true);
                cdnTV.setText("CDN:  " + cdnLabelFor(cdnPref.get(), fresh) + "  ▾");
                renderCdnPicker(activity, fresh, cdnTV, cdnPref, probedRef, probing);
            });
        }, "bh-cdn-probe-refresh").start();
    }

    /** Short label for the inline row — host name or "Auto". */
    private static String cdnLabelFor(String cdnPref, List<BhCdnHelper.ProbeResult> probed) {
        if (cdnPref == null || CDN_PREF_AUTO.equals(cdnPref)) return "Auto";
        String host = hostOf(cdnPref);
        return host != null ? host : "Auto";
    }

    private static String hostOf(String url) {
        if (url == null) return null;
        int schemeEnd = url.indexOf("://");
        int start = schemeEnd >= 0 ? schemeEnd + 3 : 0;
        int pathStart = url.indexOf('/', start);
        int queryStart = url.indexOf('?', start);
        int end = url.length();
        if (pathStart > 0 && pathStart < end) end = pathStart;
        if (queryStart > 0 && queryStart < end) end = queryStart;
        return url.substring(start, end);
    }

    private static TextView makeRow(Context ctx, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(14f);
        tv.setGravity(Gravity.START);
        return tv;
    }

    private static long computeFreeBytes(Context ctx, String storeDir) {
        try {
            File base = BhStoragePath.getInstallDir(ctx, storeDir, "_check");
            File parent = base.getParentFile();
            if (parent != null) parent.mkdirs();
            android.os.StatFs sf = new android.os.StatFs(
                    parent != null ? parent.getAbsolutePath()
                            : ctx.getCacheDir().getAbsolutePath());
            return sf.getAvailableBlocksLong() * sf.getBlockSizeLong();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes >= 1_073_741_824L) return String.format("%.2f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)     return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1024L)          return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
