package app.revanced.extension.gamehub.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.gamehub.prefs.GameHubPrefs;
import app.revanced.extension.gamehub.util.DeviceMetrics;
import app.revanced.extension.gamehub.util.GHLog;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Adds CPU, GPU, and RAM usage metrics to the Performance tab of the
 * game overlay sidebar. Views are programmatically appended to the
 * performanceFl LinearLayout and refreshed every second via a Handler loop.
 */
@SuppressLint("SetTextI18n")
@SuppressWarnings("unused")
public final class PerformanceMetricsHelper {

    private static final long REFRESH_INTERVAL_MS = 1000;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static WeakReference<TextView> cpuTextRef;
    private static WeakReference<TextView> gpuTextRef;
    private static WeakReference<TextView> ramTextRef;
    private static boolean refreshLoopRunning = false;

    /**
     * Called from the injected smali in SidebarPerformanceFragment.m0().
     *
     * @param performanceFl the FocusableLinearLayout containing Performance tab views
     */
    public static void initMetrics(ViewGroup performanceFl) {
        try {
            if (performanceFl == null) return;
            if (!GameHubPrefs.isPerfMetricsEnabled()) return;

            Context ctx = performanceFl.getContext();

            // Add section header.
            TextView header = new TextView(ctx);
            header.setText("Performance Metrics");
            header.setTextColor(0xFFFFFFFF);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            header.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            headerParams.topMargin = dpToPx(ctx, 16);
            headerParams.bottomMargin = dpToPx(ctx, 8);
            headerParams.leftMargin = dpToPx(ctx, 16);
            header.setLayoutParams(headerParams);
            performanceFl.addView(header);

            // Create metric TextViews.
            TextView cpuTv = createMetricTextView(ctx);
            TextView gpuTv = createMetricTextView(ctx);
            TextView ramTv = createMetricTextView(ctx);

            cpuTv.setText("CPU: --");
            gpuTv.setText("GPU: --");
            ramTv.setText("RAM: --");

            performanceFl.addView(cpuTv);
            performanceFl.addView(gpuTv);
            performanceFl.addView(ramTv);

            cpuTextRef = new WeakReference<>(cpuTv);
            gpuTextRef = new WeakReference<>(gpuTv);
            ramTextRef = new WeakReference<>(ramTv);

            if (!refreshLoopRunning) {
                refreshLoopRunning = true;
                scheduleRefresh();
            }

            GHLog.PERF.d("Performance metrics initialized");
        } catch (Exception e) {
            GHLog.PERF.w("initMetrics failed", e);
        }
    }

    private static TextView createMetricTextView(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTextColor(0xB3FFFFFF); // 70% white
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dpToPx(ctx, 16);
        params.topMargin = dpToPx(ctx, 2);
        tv.setLayoutParams(params);
        return tv;
    }

    private static int dpToPx(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    // --- Refresh loop ---

    private static final Runnable refreshRunnable = () -> {
        refreshMetrics();
        scheduleRefresh();
    };

    private static void scheduleRefresh() {
        if (refreshLoopRunning) {
            handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
        }
    }

    private static void refreshMetrics() {
        try {
            TextView cpuTv = deref(cpuTextRef);
            TextView gpuTv = deref(gpuTextRef);
            TextView ramTv = deref(ramTextRef);

            if (cpuTv == null && gpuTv == null && ramTv == null) {
                // All views GC'd — stop the loop.
                refreshLoopRunning = false;
                handler.removeCallbacks(refreshRunnable);
                return;
            }

            if (!GameHubPrefs.isPerfMetricsEnabled()) return;

            if (cpuTv != null) {
                int cpu = DeviceMetrics.readCpuUsage();
                cpuTv.setText(cpu >= 0 ? String.format(Locale.US, "CPU: %d%%", cpu) : "CPU: --");
            }

            if (gpuTv != null) {
                int gpu = DeviceMetrics.readGpuUsage();
                gpuTv.setText(gpu >= 0 ? String.format(Locale.US, "GPU: %d%%", gpu) : "GPU: N/A");
            }

            if (ramTv != null) {
                ramTv.setText(DeviceMetrics.readRamUsage(ramTv.getContext()));
            }
        } catch (Exception e) {
            GHLog.PERF.w("refreshMetrics failed", e);
        }
    }

    private static TextView deref(WeakReference<TextView> ref) {
        return ref != null ? ref.get() : null;
    }
}
