package app.revanced.extension.gamehub.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * Shared device metrics readers for CPU, GPU, and RAM usage.
 *
 * <p>CPU usage is computed via {@link android.os.Process#getElapsedCpuTime()} deltas
 * (app-level, not system-wide) and cached for 500ms so multiple callers running
 * concurrent refresh loops don't corrupt each other's baselines.
 *
 * <p>GPU usage is read from sysfs (Qualcomm Adreno, ARM Mali, Samsung Exynos,
 * or Amlogic) and returns -1 when the kernel doesn't expose a utilisation node.
 *
 * <p>RAM usage uses {@link ActivityManager.MemoryInfo} for system-wide totals.
 */
public final class DeviceMetrics {

    private DeviceMetrics() {}

    // --- CPU (delta-based with 500ms cache) ---

    private static long prevCpuTime = -1;
    private static long prevWallTime = -1;
    private static int cachedCpuPercent = -1;
    private static long cachedCpuTimestamp = 0;
    private static final long CPU_CACHE_MS = 500;

    /**
     * Returns app CPU usage as a percentage (0-100), or -1 on the first call
     * (no baseline yet). Results are cached for 500ms so concurrent callers
     * share the same reading.
     */
    public static int readCpuUsage() {
        long now = SystemClock.elapsedRealtime();
        if (cachedCpuPercent >= 0 && (now - cachedCpuTimestamp) < CPU_CACHE_MS) {
            return cachedCpuPercent;
        }

        long cpuTime = android.os.Process.getElapsedCpuTime();
        if (cpuTime == 0) return -1; // clock_gettime failed

        if (prevCpuTime < 0) {
            prevCpuTime = cpuTime;
            prevWallTime = now;
            return -1;
        }

        long cpuDelta = cpuTime - prevCpuTime;
        long wallDelta = now - prevWallTime;

        prevCpuTime = cpuTime;
        prevWallTime = now;

        if (wallDelta <= 0) {
            cachedCpuPercent = 0;
        } else {
            int cores = Runtime.getRuntime().availableProcessors();
            cachedCpuPercent = Math.min((int) (cpuDelta * 100 / (wallDelta * cores)), 100);
        }
        cachedCpuTimestamp = now;
        return cachedCpuPercent;
    }

    // --- GPU (sysfs-based, path resolved once) ---

    private static final int GPU_FORMAT_PERCENTAGE = 0;
    private static final int GPU_FORMAT_BUSY_TOTAL = 1;

    private static volatile String gpuSysfsPath;
    private static volatile int gpuFormat;
    private static volatile boolean gpuPathResolved = false;

    /**
     * Returns GPU usage as a percentage (0-100), or -1 if the kernel doesn't
     * expose a GPU utilisation node.
     */
    public static int readGpuUsage() {
        try {
            String path = resolveGpuSysfsPath();
            if (path == null) return -1;

            String line;
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                line = reader.readLine();
            }

            if (line == null) return -1;

            if (gpuFormat == GPU_FORMAT_BUSY_TOTAL) {
                // Qualcomm gpubusy: "busy total" format (e.g., "1234567 9876543").
                // Values are pre-computed deltas by the kernel (~1s window), not cumulative.
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    long busy = Long.parseLong(parts[0]);
                    long total = Long.parseLong(parts[1]);
                    if (total > 0) {
                        return (int) (busy * 100 / total);
                    }
                }
                return -1;
            }

            // Single percentage value (integer, float like "45.00", or "45 %" with suffix).
            line = line.trim().replace("%", "").trim();
            if (line.contains(".")) {
                return (int) Float.parseFloat(line);
            }
            return Integer.parseInt(line);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String resolveGpuSysfsPath() {
        if (gpuPathResolved) return gpuSysfsPath;

        // Qualcomm Adreno: pre-calculated percentage (newer kernels 4.14+).
        String qualcommPct = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage";
        if (new File(qualcommPct).canRead()) {
            gpuFormat = GPU_FORMAT_PERCENTAGE;
            gpuSysfsPath = qualcommPct;
            gpuPathResolved = true;
            GHLog.PERF.d("GPU sysfs: Qualcomm gpu_busy_percentage");
            return gpuSysfsPath;
        }

        // Qualcomm Adreno: "busy total" pair (widely available).
        String qualcommBusy = "/sys/class/kgsl/kgsl-3d0/gpubusy";
        if (new File(qualcommBusy).canRead()) {
            gpuFormat = GPU_FORMAT_BUSY_TOTAL;
            gpuSysfsPath = qualcommBusy;
            gpuPathResolved = true;
            GHLog.PERF.d("GPU sysfs: Qualcomm gpubusy");
            return gpuSysfsPath;
        }

        // Qualcomm Adreno (newer SoCs like Snapdragon 8 Elite): unified kernel GPU path.
        // Outputs "X %" format.
        String qualcommKernelGpu = "/sys/kernel/gpu/gpu_busy";
        if (new File(qualcommKernelGpu).canRead()) {
            gpuFormat = GPU_FORMAT_PERCENTAGE;
            gpuSysfsPath = qualcommKernelGpu;
            gpuPathResolved = true;
            GHLog.PERF.d("GPU sysfs: Qualcomm kernel gpu_busy");
            return gpuSysfsPath;
        }

        // Samsung Exynos (Mali or Xclipse): standardized kernel path.
        String samsung = "/sys/kernel/gpu/gpu_load";
        if (new File(samsung).canRead()) {
            gpuFormat = GPU_FORMAT_PERCENTAGE;
            gpuSysfsPath = samsung;
            gpuPathResolved = true;
            GHLog.PERF.d("GPU sysfs: Samsung gpu_load");
            return gpuSysfsPath;
        }

        // Mali: search platform devices (check both British and American spelling).
        try {
            File platform = new File("/sys/devices/platform");
            File[] children = platform.listFiles();
            if (children != null) {
                for (File child : children) {
                    for (String name : new String[] {"utilisation", "utilization"}) {
                        File util = new File(child, "gpu/" + name);
                        if (util.canRead()) {
                            gpuFormat = GPU_FORMAT_PERCENTAGE;
                            gpuSysfsPath = util.getAbsolutePath();
                            gpuPathResolved = true;
                            GHLog.PERF.d("GPU sysfs: Mali (" + gpuSysfsPath + ")");
                            return gpuSysfsPath;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Amlogic SoCs with Mali GPU.
        String amlogic = "/sys/class/mpgpu/utilization";
        if (new File(amlogic).canRead()) {
            gpuFormat = GPU_FORMAT_PERCENTAGE;
            gpuSysfsPath = amlogic;
            gpuPathResolved = true;
            GHLog.PERF.d("GPU sysfs: Amlogic mpgpu");
            return gpuSysfsPath;
        }

        gpuPathResolved = true;
        GHLog.PERF.d("GPU sysfs: not available");
        return null;
    }

    // --- RAM ---

    /**
     * Returns a formatted RAM usage string like "RAM: 3.2 / 5.8 GB (55%)",
     * or "RAM: N/A" on failure.
     */
    public static String readRamUsage(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return "RAM: N/A";

            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);

            double totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0);
            double availGb = mi.availMem / (1024.0 * 1024.0 * 1024.0);
            double usedGb = totalGb - availGb;
            int percent = (int) (usedGb * 100 / totalGb);

            return String.format(Locale.US, "RAM: %.1f / %.1f GB (%d%%)", usedGb, totalGb, percent);
        } catch (Exception e) {
            return "RAM: N/A";
        }
    }
}
