package app.revanced.extension.gamehub;

import android.content.Context;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * BhWineLaunchHelper — helpers for the Launch tab in BhTaskManagerFragment.
 * Finds the running Wine binary, reads the Wine prefix and environment from
 * /proc, lists directories, and launches executables inside the Wine session.
 */
public class BhWineLaunchHelper {

    /**
     * Find the wine loader binary.  Strategy:
     *  1. Read WINELOADER from the running wine process environ — Wine always
     *     sets this to point to its own binary (most reliable).
     *  2. Fall back to scanning /proc for wineserver or wine64-preloader,
     *     resolving /proc/<pid>/exe, and checking several binary names in
     *     the same directory (wine64, wine, wineloader, wine64-preloader).
     */
    public static String findWineBinary() {
        // 1. Try WINELOADER env var first
        String wineLoader = readWineEnvVar("WINELOADER");
        if (wineLoader != null && !wineLoader.isEmpty()) {
            File f = new File(wineLoader);
            if (f.exists()) return wineLoader;
        }

        // 2. Scan /proc for wine processes and resolve their exe path
        try {
            File proc = new File("/proc");
            File[] entries = proc.listFiles();
            if (entries == null) return null;
            for (File entry : entries) {
                try { Integer.parseInt(entry.getName()); } catch (NumberFormatException e) { continue; }
                String comm = readFirstLine("/proc/" + entry.getName() + "/comm");
                if (comm == null) continue;
                String lower = comm.trim().toLowerCase();
                if (!lower.equals("wineserver") && !lower.startsWith("wine64-preload")
                        && !lower.equals("wineloader")) continue;
                String exePath = new File("/proc/" + entry.getName() + "/exe").getCanonicalPath();
                if (exePath == null) continue;
                int slash = exePath.lastIndexOf('/');
                if (slash < 0) continue;
                String dir = exePath.substring(0, slash);
                // Try all common wine launcher names
                for (String name : new String[]{"wine64", "wine", "wineloader", "wine64-preloader"}) {
                    File candidate = new File(dir, name);
                    if (candidate.exists()) return candidate.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Read WINEPREFIX from the environment of a running Wine process.
     * Returns null if no Wine process is found or WINEPREFIX is not set.
     */
    public static String getWinePrefix() {
        return readWineEnvVar("WINEPREFIX");
    }

    /**
     * Read the full environment block from the first running .exe process.
     * Returns a "KEY=VALUE" String array, or null if not found.
     */
    public static String[] getWineEnviron() {
        try {
            File proc = new File("/proc");
            File[] entries = proc.listFiles();
            if (entries == null) return null;
            for (File entry : entries) {
                try { Integer.parseInt(entry.getName()); } catch (NumberFormatException e) { continue; }
                String comm = readFirstLine("/proc/" + entry.getName() + "/comm");
                if (comm == null) continue;
                if (!comm.trim().toLowerCase().endsWith(".exe")) continue;
                byte[] buf = new byte[65536];
                FileInputStream fis = new FileInputStream("/proc/" + entry.getName() + "/environ");
                int read = fis.read(buf);
                fis.close();
                if (read <= 0) continue;
                String content = new String(buf, 0, read);
                return content.split("\u0000");
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * List a directory's contents.  Directories are returned with a trailing
     * "/" suffix.  Results are sorted: directories first, then files,
     * alphabetically within each group.  Returns an empty array on error.
     */
    public static String[] listDir(String path) {
        try {
            File dir = new File(path);
            File[] files = dir.listFiles();
            if (files == null) return new String[0];
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            String[] result = new String[files.length];
            for (int i = 0; i < files.length; i++) {
                result[i] = files[i].isDirectory()
                        ? files[i].getName() + "/"
                        : files[i].getName();
            }
            return result;
        } catch (Exception ignored) {
            return new String[0];
        }
    }

    /**
     * Returns true if the filename has a launchable Windows extension.
     */
    public static boolean isLaunchable(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".exe") || lower.endsWith(".msi")
                || lower.endsWith(".bat") || lower.endsWith(".cmd");
    }

    /**
     * Launch a Windows executable using the running Wine binary and
     * environment.  Runs on a background thread.  Shows an error Toast
     * (on the main thread) if the wine binary cannot be located.
     */
    public static void launchExe(final Context ctx, final String exePath) {
        new Thread(() -> {
            try {
                String wineBin = findWineBinary();
                if (wineBin == null) {
                    showToast(ctx, "Launch failed: wine binary not found");
                    return;
                }
                String[] env = getWineEnviron();
                Runtime.getRuntime().exec(new String[]{wineBin, exePath}, env, null);
            } catch (Exception e) {
                showToast(ctx, "Launch error: " + e.getMessage());
            }
        }).start();
    }

    /** Post a Toast to the main thread from any thread. */
    private static void showToast(final Context ctx, final String msg) {
        if (ctx == null) return;
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }

    // ── private helpers ───────────────────────────────────────────────

    private static String readFirstLine(String path) {
        try {
            RandomAccessFile raf = new RandomAccessFile(path, "r");
            String line = raf.readLine();
            raf.close();
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readWineEnvVar(String key) {
        try {
            File proc = new File("/proc");
            File[] entries = proc.listFiles();
            if (entries == null) return null;
            for (File entry : entries) {
                try { Integer.parseInt(entry.getName()); } catch (NumberFormatException e) { continue; }
                String comm = readFirstLine("/proc/" + entry.getName() + "/comm");
                if (comm == null) continue;
                String lower = comm.trim().toLowerCase();
                if (!lower.endsWith(".exe") && !lower.startsWith("wine")) continue;
                byte[] buf = new byte[65536];
                FileInputStream fis = new FileInputStream("/proc/" + entry.getName() + "/environ");
                int read = fis.read(buf);
                fis.close();
                if (read <= 0) continue;
                String content = new String(buf, 0, read);
                String prefix = key + "=";
                int idx = content.indexOf(prefix);
                if (idx < 0) continue;
                int start = idx + prefix.length();
                int end = content.indexOf('\u0000', start);
                if (end < 0) return content.substring(start);
                return content.substring(start, end);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
