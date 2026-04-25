package com.xj.winemu.sidebar;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;

import app.revanced.extension.gamehub.BhWineLaunchHelper;

/**
 * Wine Task Manager sidebar tab (Feature 51).
 * Three-tab UI: Applications (wine infra) / Processes (.exe) / Launch (WINEPREFIX browser).
 * Auto-refreshes every 3 seconds while visible.
 */
public class BhTaskManagerFragment extends Fragment {

    public LinearLayout appsLayout;
    public LinearLayout procsLayout;
    public LinearLayout launchLayout;
    public TextView currentPathText;
    public LinearLayout fileListLayout;
    public String wineRootPath;
    public String currentBrowsePath;
    public Context bhContext;
    public Handler handler;
    public Runnable autoRefresher;

    // ── Parse kB value from a /proc/meminfo line ──────────────────────────────
    private static long parseMemKb(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            return Long.parseLong(parts[1].trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    // ── Read VRAM limit from SharedPreferences (reflection — WineActivity.u.a = gameId) ──
    private static String getContainerVramInfo(Context ctx) {
        try {
            Object dataObj = ctx.getClass().getField("u").get(ctx);
            if (dataObj == null) return "Unlimited";
            String gameId = (String) dataObj.getClass().getField("a").get(dataObj);
            if (gameId == null) return "Unlimited";
            android.content.SharedPreferences sp =
                ctx.getSharedPreferences("pc_g_setting" + gameId, 0);
            int mb = sp.getInt("pc_ls_max_memory", 0);
            return mb == 0 ? "Unlimited" : mb + " MB";
        } catch (Exception e) {
            return "N/A";
        }
    }

    // ── Device RAM from /proc/meminfo ─────────────────────────────────────────
    private static String getContainerRamInfo() {
        long totalKb = 0, availKb = 0;
        try (RandomAccessFile raf = new RandomAccessFile("/proc/meminfo", "r")) {
            String line;
            while ((line = raf.readLine()) != null) {
                if (line.startsWith("MemTotal:"))     totalKb = parseMemKb(line);
                else if (line.startsWith("MemAvailable:")) { availKb = parseMemKb(line); break; }
            }
        } catch (Exception ignored) {}
        long usedMb  = (totalKb - availKb) / 1024;
        long totalMb = totalKb / 1024;
        return usedMb + " MB used / " + totalMb + " MB total";
    }

    // ── Read env var from a wine child process's /proc/<pid>/environ ──────────
    private static String readWineEnv(String key) {
        try {
            java.io.File[] entries = new java.io.File("/proc").listFiles();
            if (entries == null) return null;
            for (java.io.File entry : entries) {
                try { Integer.parseInt(entry.getName()); } catch (NumberFormatException e) { continue; }
                String comm = readFirstLine("/proc/" + entry.getName() + "/comm");
                if (comm == null) continue;
                if (!comm.trim().toLowerCase().endsWith(".exe")) continue;
                String envPath = "/proc/" + entry.getName() + "/environ";
                try {
                    byte[] buf = new byte[0x4000];
                    int n;
                    try (FileInputStream fis = new FileInputStream(envPath)) {
                        n = fis.read(buf);
                    }
                    if (n <= 0) continue;
                    String content = new String(buf, 0, n);
                    String prefix = key + "=";
                    int pos = content.indexOf(prefix);
                    if (pos < 0) continue;
                    int start = pos + prefix.length();
                    int end = content.indexOf((char)0, start);
                    return end < 0 ? content.substring(start) : content.substring(start, end);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String readFirstLine(String path) {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            return raf.readLine();
        } catch (Exception e) { return null; }
    }

    // ── CPU info string ───────────────────────────────────────────────────────
    private static String getContainerCpuInfo() {
        int assigned = getActiveCores();
        int total    = getActiveCores();
        try {
            String affinity = readWineEnv("WINEMU_CPU_AFFINITY");
            if (affinity != null && !affinity.isEmpty()) {
                int mask = Integer.parseInt(affinity);
                if (mask != 0) assigned = Integer.bitCount(mask);
            }
        } catch (Exception ignored) {}
        return "CPU Cores:  " + assigned + " / " + total + " total";
    }

    private static int getActiveCores() {
        int count = 0;
        try (RandomAccessFile raf = new RandomAccessFile("/proc/stat", "r")) {
            String line;
            while ((line = raf.readLine()) != null) {
                if (line.length() >= 4 && line.startsWith("cpu") && Character.isDigit(line.charAt(3)))
                    count++;
            }
        } catch (Exception ignored) {}
        return count;
    }

    // ── TextView helpers ──────────────────────────────────────────────────────
    private static TextView makeInfoText(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13f);
        tv.setPadding(6, 6, 6, 6);
        return tv;
    }

    private static TextView makeHeaderText(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFFFCC00);
        tv.setTextSize(14f);
        tv.setPadding(4, 12, 4, 4);
        return tv;
    }

    // ── showTab ───────────────────────────────────────────────────────────────
    public void showTab(int idx) {
        if (appsLayout  != null) appsLayout.setVisibility(View.GONE);
        if (procsLayout != null) procsLayout.setVisibility(View.GONE);
        if (launchLayout != null) launchLayout.setVisibility(View.GONE);

        switch (idx) {
            case 1: if (procsLayout  != null) procsLayout.setVisibility(View.VISIBLE); break;
            case 2:
                if (launchLayout != null) {
                    launchLayout.setVisibility(View.VISIBLE);
                    if (currentBrowsePath == null) {
                        new Thread(new BhInitLaunchRunnable(this)).start();
                    }
                }
                break;
            default: if (appsLayout != null) appsLayout.setVisibility(View.VISIBLE); break;
        }
    }

    // ── onCreateView ──────────────────────────────────────────────────────────
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        androidx.fragment.app.FragmentActivity act = getActivity();
        if (act == null) return null;
        bhContext = act;

        ScrollView scroll = new ScrollView(bhContext);
        LinearLayout root = new LinearLayout(bhContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);
        scroll.addView(root, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        // Container Info section
        root.addView(makeHeaderText(bhContext, "Container Info"));
        root.addView(makeInfoText(bhContext, getContainerCpuInfo()));
        root.addView(makeInfoText(bhContext, "Sys RAM:     " + getContainerRamInfo()));
        root.addView(makeInfoText(bhContext, "VRam Limit:  " + getContainerVramInfo(bhContext)));
        root.addView(makeInfoText(bhContext, "Device:      " + Build.MODEL));
        root.addView(makeInfoText(bhContext, "Android:     " + Build.VERSION.RELEASE +
            " (API " + Build.VERSION.SDK_INT + ")"));

        // Tab bar
        LinearLayout tabBar = new LinearLayout(bhContext);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        addTabButton(tabBar, "Applications", 0);
        addTabButton(tabBar, "Processes",    1);
        addTabButton(tabBar, "Launch",       2);
        Button refresh = new Button(bhContext);
        refresh.setText("↺");
        refresh.setTextColor(0xFFFFFFFF);
        refresh.setOnClickListener(v -> startScan());
        tabBar.addView(refresh);
        root.addView(tabBar);

        // Applications layout (initially visible)
        appsLayout = new LinearLayout(bhContext);
        appsLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(appsLayout);

        // Processes layout (initially gone)
        procsLayout = new LinearLayout(bhContext);
        procsLayout.setOrientation(LinearLayout.VERTICAL);
        procsLayout.setVisibility(View.GONE);
        root.addView(procsLayout);

        // Launch layout (initially gone)
        launchLayout = new LinearLayout(bhContext);
        launchLayout.setOrientation(LinearLayout.VERTICAL);
        launchLayout.setVisibility(View.GONE);
        currentPathText = new TextView(bhContext);
        currentPathText.setText("/");
        currentPathText.setTextColor(0xFFFFCC00);
        currentPathText.setTextSize(10f);
        currentPathText.setPadding(4, 8, 4, 4);
        launchLayout.addView(currentPathText);
        fileListLayout = new LinearLayout(bhContext);
        fileListLayout.setOrientation(LinearLayout.VERTICAL);
        launchLayout.addView(fileListLayout);
        root.addView(launchLayout);

        return scroll;
    }

    private void addTabButton(LinearLayout bar, String label, int idx) {
        Button btn = new Button(bhContext);
        btn.setText(label);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(10f);
        btn.setAllCaps(false);
        btn.setOnClickListener(v -> showTab(idx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.weight = 1f;
        bar.addView(btn, lp);
    }

    // ── browseTo ──────────────────────────────────────────────────────────────
    public void browseTo(String path) {
        currentBrowsePath = path;
        if (currentPathText != null) currentPathText.setText(path);
        if (fileListLayout == null || bhContext == null) return;
        fileListLayout.removeAllViews();

        // Up button (unless at wineRootPath)
        if (wineRootPath != null && !path.equals(wineRootPath)) {
            int slash = path.lastIndexOf('/');
            if (slash > 0) {
                String parent = path.substring(0, slash);
                TextView upRow = makeRow(bhContext, "↑  ..", 0xFFFFCC00, 13f);
                upRow.setOnClickListener(new BhFolderListener(this, parent));
                fileListLayout.addView(upRow);
            }
        }

        String[] items = BhWineLaunchHelper.listDir(path);
        if (items == null) return;
        for (String item : items) {
            if (item.endsWith("/")) {
                // Directory
                String dirName = item.substring(0, item.length() - 1);
                String absPath = path + "/" + dirName;
                TextView row = makeRow(bhContext, "▶ " + dirName, 0xFFFFCC00, 13f);
                row.setOnClickListener(new BhFolderListener(this, absPath));
                fileListLayout.addView(row);
            } else if (BhWineLaunchHelper.isLaunchable(item)) {
                String absPath = path + "/" + item;
                TextView row = makeRow(bhContext, item, 0xFFFFFFFF, 13f);
                row.setOnClickListener(new BhExeLaunchListener(this, absPath));
                fileListLayout.addView(row);
            }
        }
    }

    private static TextView makeRow(Context ctx, String text, int color, float sp) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        tv.setPadding(8, 8, 8, 8);
        return tv;
    }

    // ── Process scanning ──────────────────────────────────────────────────────
    public void startScan() {
        new Thread(new ScanRunnable(this)).start();
    }

    @Override
    public void onResume() {
        super.onResume();
        Handler h = new Handler(Looper.getMainLooper());
        handler = h;
        AutoRefreshRunnable r = new AutoRefreshRunnable(this, h);
        autoRefresher = r;
        h.post(r);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }

    public void onScanComplete(ArrayList<String> names, ArrayList<Integer> pids) {
        if (appsLayout == null || procsLayout == null || bhContext == null) return;
        appsLayout.removeAllViews();
        procsLayout.removeAllViews();

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int    pid  = pids.get(i);

            LinearLayout row = new LinearLayout(bhContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(0x10); // CENTER_VERTICAL
            row.setPadding(4, 4, 4, 4);

            TextView tv = new TextView(bhContext);
            tv.setText(name + "  (PID " + pid + ")");
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(12f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.weight = 1f;
            row.addView(tv, lp);

            Button kill = new Button(bhContext);
            kill.setText("Kill");
            kill.setTextColor(0xFFFFFFFF);
            kill.setTextSize(12f);
            kill.setOnClickListener(new KillListener(pid, this));
            row.addView(kill);

            if (name.toLowerCase().endsWith(".exe")) {
                procsLayout.addView(row);
            } else {
                appsLayout.addView(row);
            }
        }

        if (appsLayout.getChildCount() == 0) {
            TextView ph = makeInfoText(bhContext, "No Wine processes running");
            ph.setTextColor(0xFFAAAAAA);
            appsLayout.addView(ph);
        }
        if (procsLayout.getChildCount() == 0) {
            TextView ph = makeInfoText(bhContext, "No Windows processes running");
            ph.setTextColor(0xFFAAAAAA);
            procsLayout.addView(ph);
        }
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    static class AutoRefreshRunnable implements Runnable {
        final BhTaskManagerFragment fragment;
        final Handler handler;
        AutoRefreshRunnable(BhTaskManagerFragment f, Handler h) { fragment = f; handler = h; }
        @Override public void run() {
            fragment.startScan();
            handler.postDelayed(this, 3000);
        }
    }

    static class KillListener implements android.view.View.OnClickListener {
        final int pid;
        final BhTaskManagerFragment fragment;
        KillListener(int pid, BhTaskManagerFragment f) { this.pid = pid; fragment = f; }
        @Override public void onClick(View v) {
            Process.sendSignal(pid, 9);
            fragment.startScan();
        }
    }

    static class RefreshListener implements android.view.View.OnClickListener {
        final BhTaskManagerFragment fragment;
        RefreshListener(BhTaskManagerFragment f) { fragment = f; }
        @Override public void onClick(View v) { fragment.startScan(); }
    }

    static class ScanRunnable implements Runnable {
        final BhTaskManagerFragment fragment;
        ScanRunnable(BhTaskManagerFragment f) { fragment = f; }

        private static String readFirstLine(String path) {
            try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
                return raf.readLine();
            } catch (Exception e) { return null; }
        }

        @Override public void run() {
            ArrayList<String>  names = new ArrayList<>();
            ArrayList<Integer> pids  = new ArrayList<>();
            java.io.File[] entries = new java.io.File("/proc").listFiles();
            if (entries != null) {
                for (java.io.File entry : entries) {
                    int pid;
                    try { pid = Integer.parseInt(entry.getName()); }
                    catch (NumberFormatException e) { continue; }
                    String comm = readFirstLine("/proc/" + entry.getName() + "/comm");
                    if (comm == null) continue;
                    String lower = comm.trim().toLowerCase();
                    if (!lower.contains("wine") && !lower.endsWith(".exe")) continue;
                    names.add(comm.trim());
                    pids.add(pid);
                }
            }
            new Handler(Looper.getMainLooper()).post(new UpdateRunnable(fragment, names, pids));
        }
    }

    static class UpdateRunnable implements Runnable {
        final BhTaskManagerFragment fragment;
        final ArrayList<String>  names;
        final ArrayList<Integer> pids;
        UpdateRunnable(BhTaskManagerFragment f, ArrayList<String> n, ArrayList<Integer> p) {
            fragment = f; names = n; pids = p;
        }
        @Override public void run() { fragment.onScanComplete(names, pids); }
    }
}
