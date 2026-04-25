package app.revanced.extension.gamehub;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

@SuppressWarnings("unused")
public class ComponentDownloadActivity extends Activity {

    private static final String TAG = "BannerHub";
    private static final String SP_SOURCES = "banners_sources";

    // ── Repo definitions ──────────────────────────────────────────────────────

    private static final String[] REPO_NAMES = {
        "Arihany WCPHub",
        "Kimchi GPU Drivers",
        "StevenMXZ GPU Drivers",
        "MTR GPU Drivers",
        "Whitebelyash GPU Drivers",
        "The412Banner Nightlies",
    };

    private static final String[] REPO_URLS = {
        "https://raw.githubusercontent.com/Arihany/WinlatorWCPHub/refs/heads/main/pack.json",
        "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/kimchi_drivers.json",
        "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/stevenmxz_drivers.json",
        "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/mtr_drivers.json",
        "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/white_drivers.json",
        "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/nightlies_components.json",
    };

    private static final String[] CATEGORIES = {
        "DXVK", "VKD3D-Proton", "Box64", "FEXCore", "GPU Driver / Turnip",
    };

    // ── State ─────────────────────────────────────────────────────────────────

    private int mode; // 0=repos, 1=categories, 2=assets
    private ListView mListView;
    private TextView mStatusText;
    private ProgressBar mProgressBar;
    private String mCurrentRepo;
    private int mSelectedType;
    private final ArrayList<String> mAllNames = new ArrayList<>();
    private final ArrayList<String> mAllUrls = new ArrayList<>();
    private final ArrayList<String> mCurrentNames = new ArrayList<>();
    private final ArrayList<String> mCurrentUrls = new ArrayList<>();
    private String mDownloadUrl;
    private String mDownloadFilename;
    private Handler uiHandler;
    private File componentsDir;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uiHandler = new Handler(Looper.getMainLooper());
        componentsDir = new File(getFilesDir(), "usr/home/components");

        // Root
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(0xFF0D0D0D);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(0xFF161616);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int p16 = dp(16);
        header.setPadding(p16, p16, p16, p16);

        TextView backBtn = new TextView(this);
        backBtn.setText("\u2190");
        backBtn.setTextSize(20f);
        backBtn.setTextColor(0xFFFF9800);
        int p12 = dp(12);
        backBtn.setPadding(p12, p12, p12, p12);
        backBtn.setOnClickListener(v -> onBackPressed());
        header.addView(backBtn);

        TextView titleTv = new TextView(this);
        titleTv.setText("Download Components");
        titleTv.setTextSize(20f);
        titleTv.setTextColor(0xFFFF9800);
        int p8 = dp(8);
        titleTv.setPadding(p8, 0, 0, 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleTv, titleLp);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Status text
        mStatusText = new TextView(this);
        mStatusText.setText("Select a source");
        mStatusText.setTextSize(14f);
        mStatusText.setTextColor(0xFF888888);
        mStatusText.setPadding(p16, p8, p16, p8);
        root.addView(mStatusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ProgressBar
        mProgressBar = new ProgressBar(this);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.GONE);
        root.addView(mProgressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ListView
        mListView = new ListView(this);
        mListView.setOnItemClickListener((parent, view, pos, id) -> onItemClick(pos));
        mListView.setClipToPadding(false);
        mListView.setBackgroundColor(0xFF0D0D0D);
        mListView.setSelector(new ColorDrawable(0x40FF9800));
        root.addView(mListView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showRepos();
    }

    @Override
    public void onBackPressed() {
        switch (mode) {
            case 2: showCategories(); break;
            case 1: showRepos(); break;
            default: super.onBackPressed(); break;
        }
    }

    // ── Mode 0: repo list ─────────────────────────────────────────────────────

    private void showRepos() {
        mode = 0;
        mProgressBar.setVisibility(View.GONE);
        mStatusText.setText("Select a source");
        String[] items = new String[REPO_NAMES.length + 1];
        System.arraycopy(REPO_NAMES, 0, items, 0, REPO_NAMES.length);
        items[REPO_NAMES.length] = "\u2190 Back";
        mListView.setAdapter(new DarkAdapter(items));
    }

    // ── Mode 1: category list ─────────────────────────────────────────────────

    private void showCategories() {
        mode = 1;
        mProgressBar.setVisibility(View.GONE);
        mStatusText.setText("Select a component type");
        String[] items = new String[CATEGORIES.length + 1];
        System.arraycopy(CATEGORIES, 0, items, 0, CATEGORIES.length);
        items[CATEGORIES.length] = "\u2190 Back";
        mListView.setAdapter(new DarkAdapter(items));
    }

    // ── Mode 2: asset list ────────────────────────────────────────────────────

    private void showAssets(int type) {
        mProgressBar.setVisibility(View.GONE);
        mCurrentNames.clear();
        mCurrentUrls.clear();

        for (int i = 0; i < mAllNames.size(); i++) {
            String name = mAllNames.get(i);
            if (detectType(name) == type) {
                mCurrentNames.add(name);
                mCurrentUrls.add(mAllUrls.get(i));
            }
        }

        if (mCurrentNames.isEmpty()) {
            Toast.makeText(this, "No components of this type in latest nightly",
                    Toast.LENGTH_SHORT).show();
            return; // stay in mode=1
        }

        mode = 2;
        mStatusText.setText("Tap a component to download and inject");

        SharedPreferences sp = getSharedPreferences(SP_SOURCES, 0);
        String[] items = new String[mCurrentNames.size() + 1];
        for (int i = 0; i < mCurrentNames.size(); i++) {
            boolean downloaded = sp.contains("dl:" + mCurrentUrls.get(i));
            items[i] = (downloaded ? "\u2713 " : "") + mCurrentNames.get(i);
        }
        items[mCurrentNames.size()] = "\u2190 Back";
        mListView.setAdapter(new DarkAdapter(items));
    }

    // ── Click dispatch ────────────────────────────────────────────────────────

    private void onItemClick(int pos) {
        if (mode == 0) {
            if (pos == REPO_NAMES.length) { finish(); return; } // ← Back
            mCurrentRepo = REPO_NAMES[pos];
            final String url = REPO_URLS[pos];
            mProgressBar.setVisibility(View.VISIBLE);
            mStatusText.setText("Loading...");
            new Thread(() -> {
                try {
                    fetchIntoAllLists(url);
                    uiHandler.post(this::showCategories);
                } catch (Exception e) {
                    Log.e(TAG, "fetch failed", e);
                    uiHandler.post(() -> {
                        Toast.makeText(this, "Load failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        showRepos();
                    });
                }
            }).start();

        } else if (mode == 1) {
            if (pos == CATEGORIES.length) { showRepos(); return; } // ← Back
            mSelectedType = categoryToType(CATEGORIES[pos]);
            showAssets(mSelectedType);

        } else if (mode == 2) {
            if (pos == mCurrentNames.size()) { showCategories(); return; } // ← Back
            mDownloadFilename = mCurrentNames.get(pos);
            mDownloadUrl = mCurrentUrls.get(pos);
            // Append real extension from URL if missing (avoids double-extension bug)
            String lastSeg = android.net.Uri.parse(mDownloadUrl).getLastPathSegment();
            if (lastSeg != null) {
                int dot = lastSeg.lastIndexOf('.');
                if (dot >= 0) {
                    String ext = lastSeg.substring(dot);
                    if (!mDownloadFilename.endsWith(ext)) mDownloadFilename += ext;
                }
            }
            startDownload();
        }
    }

    // ── Fetch pack.json into mAllNames/mAllUrls ───────────────────────────────

    private void fetchIntoAllLists(String url) throws Exception {
        mAllNames.clear();
        mAllUrls.clear();
        String json = httpGet(url);
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String type = obj.optString("type", "");
            if (type.equalsIgnoreCase("wine") || type.equalsIgnoreCase("proton")) continue;
            String remoteUrl = obj.optString("remoteUrl", "");
            if (remoteUrl.isEmpty()) continue;
            String filename = remoteUrl.substring(remoteUrl.lastIndexOf('/') + 1);
            if (filename.isEmpty()) filename = type + "-" + obj.optString("verName", "") + ".wcp";
            mAllNames.add(filename);
            mAllUrls.add(remoteUrl);
        }
    }

    // ── Download + inject ─────────────────────────────────────────────────────

    private void startDownload() {
        mProgressBar.setVisibility(View.VISIBLE);
        mStatusText.setText("Downloading: " + mDownloadFilename);

        final String url = mDownloadUrl;
        final String filename = mDownloadFilename;
        final String repo = mCurrentRepo;
        final int type = mSelectedType;
        String folderName = filename;
        int dot = folderName.lastIndexOf('.');
        if (dot > 0) folderName = folderName.substring(0, dot);
        final String folder = folderName;

        new Thread(() -> {
            File cacheFile = null;
            try {
                cacheFile = new File(getCacheDir(), filename);
                long preInjectTs = System.currentTimeMillis();
                downloadFile(url, cacheFile);
                ComponentInjectorHelper.injectFromCachedFile(this, cacheFile, folder, type);
                String dirName = findNewDir(preInjectTs, folder);
                writeSourceSP(dirName, repo, url, type);
                uiHandler.post(() -> {
                    Toast.makeText(this,
                            "Installed: " + filename + " \u2192 " + dirName,
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception e) {
                Log.e(TAG, "download+inject failed", e);
                uiHandler.post(() -> {
                    Toast.makeText(this, "Install failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    mProgressBar.setVisibility(View.GONE);
                    showCategories();
                });
            } finally {
                if (cacheFile != null) cacheFile.delete();
            }
        }).start();
    }

    // ── Find newly created component directory ────────────────────────────────

    private String findNewDir(long preInjectTs, String fallback) {
        File[] files = componentsDir.listFiles();
        String found = null;
        long latest = preInjectTs;
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && f.lastModified() > latest) {
                    latest = f.lastModified();
                    found = f.getName();
                }
            }
        }
        return found != null ? found : fallback;
    }

    // ── Write banners_sources SP ──────────────────────────────────────────────

    private void writeSourceSP(String dirName, String repo, String url, int type) {
        SharedPreferences.Editor ed = getSharedPreferences(SP_SOURCES, 0).edit();
        if (repo == null) repo = "BannerHub Lite";
        ed.putString(dirName, repo);
        ed.putString("dl:" + url, "1");
        String typeName = typeIntToName(type);
        if (typeName != null) ed.putString(dirName + ":type", typeName);
        ed.putString("url_for:" + dirName, url);
        ed.apply();
    }

    // ── Type helpers ──────────────────────────────────────────────────────────

    private static int detectType(String name) {
        String lower = name.toLowerCase();
        if (lower.contains("dxvk")) return ComponentInjectorHelper.TYPE_DXVK;
        if (lower.contains("vkd3d")) return ComponentInjectorHelper.TYPE_VKD3D;
        if (lower.contains("box64")) return ComponentInjectorHelper.TYPE_BOX64;
        if (lower.contains("fex")) return ComponentInjectorHelper.TYPE_FEXCORE;
        if (lower.contains("turnip") || lower.contains("adreno") || lower.contains("vulkan")
                || lower.contains("driver") || lower.contains("qualcomm")
                || lower.contains("freedreno") || lower.contains("gpu"))
            return ComponentInjectorHelper.TYPE_GPU_DRIVER;
        return ComponentInjectorHelper.TYPE_DXVK;
    }

    private static int categoryToType(String cat) {
        switch (cat) {
            case "DXVK":                return ComponentInjectorHelper.TYPE_DXVK;
            case "VKD3D-Proton":        return ComponentInjectorHelper.TYPE_VKD3D;
            case "Box64":               return ComponentInjectorHelper.TYPE_BOX64;
            case "FEXCore":             return ComponentInjectorHelper.TYPE_FEXCORE;
            case "GPU Driver / Turnip": return ComponentInjectorHelper.TYPE_GPU_DRIVER;
            default:                    return ComponentInjectorHelper.TYPE_GPU_DRIVER;
        }
    }

    private static String typeIntToName(int type) {
        switch (type) {
            case ComponentInjectorHelper.TYPE_FEXCORE:    return "FEXCore";
            case ComponentInjectorHelper.TYPE_BOX64:      return "Box64";
            case ComponentInjectorHelper.TYPE_VKD3D:      return "VKD3D";
            case ComponentInjectorHelper.TYPE_GPU_DRIVER: return "GPU";
            case ComponentInjectorHelper.TYPE_DXVK:       return "DXVK";
            default:                                       return null;
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "BannerHub-Lite/1.0");
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code + " for " + urlStr);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private void downloadFile(String urlStr, File dest) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "BannerHub-Lite/1.0");
        if (conn.getResponseCode() != 200)
            throw new Exception("Download HTTP " + conn.getResponseCode());
        byte[] buf = new byte[8192];
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(dest)) {
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
    }

    // ── dp helper ─────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // ── DarkAdapter ───────────────────────────────────────────────────────────

    private class DarkAdapter extends BaseAdapter {
        private final String[] items;

        DarkAdapter(String[] items) {
            this.items = items;
        }

        @Override public int getCount() { return items.length; }
        @Override public Object getItem(int pos) { return items[pos]; }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(ComponentDownloadActivity.this);
                tv.setTextSize(15f);
                tv.setTextColor(0xFFFFFFFF);
                tv.setBackgroundColor(0xFF0D0D0D);
                tv.setMinimumHeight(dp(48));
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setPadding(dp(16), 0, dp(16), 0);
            }
            tv.setText(items[position]);
            return tv;
        }
    }
}
