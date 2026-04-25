package app.revanced.extension.gamehub.ui;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import app.revanced.extension.gamehub.prefs.GameHubPrefs;
import app.revanced.extension.gamehub.util.DeviceMetrics;
import app.revanced.extension.gamehub.util.GHLog;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Uses {@link android.os.Process#getElapsedCpuTime()} to compute app CPU usage
 * percentage and updates a TextView injected as a sibling of the battery icon
 * ImageView by the resource patch.
 *
 * <p>{@code /proc/stat} is blocked by SELinux on Android 8+, so we use the SDK
 * API instead. This gives the app's own CPU usage (across all cores), which is
 * more relevant for a game streaming app than system-wide usage.
 *
 * <p>BatteryUtil.a() fires infrequently (on battery status changes), so we
 * start a self-refreshing Handler loop on the first call to update every second.
 */
@SuppressLint("DiscouragedApi")
@SuppressWarnings("unused")
public final class CpuUsageHelper {

    private static final long REFRESH_INTERVAL_MS = 1000;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static WeakReference<TextView> cpuTextViewRef;
    private static boolean refreshLoopRunning = false;

    /**
     * Called from BatteryUtil.a(Context, ImageView) after battery level injection.
     * Locates the tv_cpu_percent TextView and starts a 1-second refresh loop.
     *
     * @param batteryImageView the battery icon ImageView
     */
    public static void updateCpuText(ImageView batteryImageView) {
        try {
            if (batteryImageView == null) return;

            ViewGroup parent = (ViewGroup) batteryImageView.getParent();
            if (parent == null) return;

            int tvId = batteryImageView
                    .getResources()
                    .getIdentifier(
                            "tv_cpu_percent",
                            "id",
                            batteryImageView.getContext().getPackageName());
            if (tvId == 0) return;

            View tv = parent.findViewById(tvId);
            if (!(tv instanceof TextView)) return;

            cpuTextViewRef = new WeakReference<>((TextView) tv);
            refreshCpuText();

            if (!refreshLoopRunning) {
                refreshLoopRunning = true;
                scheduleRefresh();
            }
        } catch (Exception e) {
            GHLog.CPU.w("updateCpuText failed", e);
        }
    }

    private static final Runnable refreshRunnable = () -> {
        refreshCpuText();
        scheduleRefresh();
    };

    private static void scheduleRefresh() {
        if (refreshLoopRunning) {
            handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
        }
    }

    private static void refreshCpuText() {
        try {
            WeakReference<TextView> ref = cpuTextViewRef;
            if (ref == null) return;
            TextView cpuTextView = ref.get();
            if (cpuTextView == null) {
                // View was GC'd (activity destroyed) — stop the loop.
                refreshLoopRunning = false;
                handler.removeCallbacks(refreshRunnable);
                return;
            }

            if (!GameHubPrefs.isCpuUsageEnabled()) {
                cpuTextView.setVisibility(View.GONE);
                return;
            }

            int cpuPercent = DeviceMetrics.readCpuUsage();
            if (cpuPercent < 0) {
                cpuTextView.setVisibility(View.GONE);
                return;
            }

            cpuTextView.setText(String.format(Locale.US, "CPU: %3d%%", cpuPercent));
            cpuTextView.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            GHLog.CPU.w("refreshCpuText failed", e);
        }
    }
}
