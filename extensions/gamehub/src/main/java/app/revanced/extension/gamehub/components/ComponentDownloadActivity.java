package app.revanced.extension.gamehub.components;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
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

/**
 * BannerHub Component Manager — multi-repo download browser.
 *
 * Three-mode ListView:
 *   mode 0 — pick a source repo (6 supported repos + Back)
 *   mode 1 — pick a component type (DXVK / VKD3D / Box64 / FEXCore / GPU + Back)
 *   mode 2 — pick an asset, which downloads + injects via {@link ComponentInjectorHelper}.
 *
 * <p>Internal type ints are 5.x's category tags (10/12/13/94/95) used only as opaque
 * filter keys — converted to 6.0 sidecar type ints just before calling
 * {@code ComponentInjectorHelper.injectComponent()}.</p>
 */
public final class ComponentDownloadActivity extends Activity
        implements AdapterView.OnItemClickListener {

    // Internal category-filter tags (mirror BannerHub 3.5.0 smali).
    // Not sent to GameHub — only used for matching detectType() output to category clicks.
    private static final int CAT_DXVK = 0xc;
    private static final int CAT_VKD3D = 0xd;
    private static final int CAT_BOX64 = 0x5e;
    private static final int CAT_FEXCORE = 0x5f;
    private static final int CAT_GPU = 0xa;

    // Repo URLs (verbatim from 3.5.0).
    private static final String URL_ARIHANY =
            "https://raw.githubusercontent.com/Arihany/WinlatorWCPHub/refs/heads/main/pack.json";
    private static final String URL_KIMCHI =
            "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/kimchi_drivers.json";
    private static final String URL_STEVENMXZ =
            "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/stevenmxz_drivers.json";
    private static final String URL_MTR =
            "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/mtr_drivers.json";
    private static final String URL_WHITE =
            "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/white_drivers.json";
    private static final String URL_NIGHTLIES =
            "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/nightlies_components.json";

    private int mode;
    private ListView listView;
    private TextView statusText;

    private final ArrayList<String> allNames = new ArrayList<>();
    private final ArrayList<String> allUrls = new ArrayList<>();
    private final ArrayList<String> currentNames = new ArrayList<>();
    private final ArrayList<String> currentUrls = new ArrayList<>();

    private String downloadUrl;
    private String downloadFilename;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView title = new TextView(this);
        title.setText("Download from Online Repos");
        title.setTextSize(18f);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(48, 24, 48, 24);

        statusText = new TextView(this);
        statusText.setText("Select a source");
        statusText.setTextSize(8.5f);
        statusText.setTextColor(0xFAFFFFFF);
        statusText.setPadding(48, 24, 48, 24);

        listView = new ListView(this);
        listView.setOnItemClickListener(this);
        listView.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(title, wrapLp);
        root.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showRepos();
    }

    public void showRepos() {
        mode = 0;
        statusText.setText("Select a source");
        String[] items = {
                "Arihany WCPHub",
                "Kimchi GPU Drivers",
                "StevenMXZ GPU Drivers",
                "MTR GPU Drivers",
                "Whitebelyash GPU Drivers",
                "The412Banner Nightlies",
                "← Back"
        };
        listView.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, items));
    }

    public void showCategories() {
        mode = 1;
        statusText.setText("Select a component type");
        String[] items = {
                "DXVK", "VKD3D-Proton", "Box64", "FEXCore", "GPU Driver / Turnip", "← Back"
        };
        listView.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, items));
    }

    public void showAssets(int categoryTag) {
        currentNames.clear();
        currentUrls.clear();

        for (int i = 0; i < allNames.size(); i++) {
            if (detectType(allNames.get(i)) == categoryTag) {
                currentNames.add(allNames.get(i));
                currentUrls.add(allUrls.get(i));
            }
        }

        if (currentNames.isEmpty()) {
            Toast.makeText(this, "No components of this type in latest nightly",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        mode = 2;
        statusText.setText("Tap a component to download and inject");
        listView.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1,
                currentNames.toArray(new String[0])));
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        switch (mode) {
            case 0:
                onRepoClick(position);
                return;
            case 1:
                onCategoryClick(position);
                return;
            case 2:
                onAssetClick(position);
                return;
            default:
                // ignore
        }
    }

    private void onRepoClick(int position) {
        switch (position) {
            case 0:
                clearAll();
                statusText.setText("Fetching Arihany WCPHub...");
                startFetchPackJson(URL_ARIHANY);
                return;
            case 1:
                clearAll();
                statusText.setText("Fetching Kimchi GPU Drivers...");
                startFetchGpuDrivers(URL_KIMCHI);
                return;
            case 2:
                clearAll();
                statusText.setText("Fetching StevenMXZ GPU Drivers...");
                startFetchGpuDrivers(URL_STEVENMXZ);
                return;
            case 3:
                clearAll();
                statusText.setText("Fetching MTR GPU Drivers...");
                startFetchGpuDrivers(URL_MTR);
                return;
            case 4:
                clearAll();
                statusText.setText("Fetching Whitebelyash GPU Drivers...");
                startFetchGpuDrivers(URL_WHITE);
                return;
            case 5:
                clearAll();
                statusText.setText("Fetching The412Banner Nightlies...");
                startFetchPackJson(URL_NIGHTLIES);
                return;
            default:
                finish();
        }
    }

    private void onCategoryClick(int position) {
        switch (position) {
            case 0: showAssets(CAT_DXVK); return;
            case 1: showAssets(CAT_VKD3D); return;
            case 2: showAssets(CAT_BOX64); return;
            case 3: showAssets(CAT_FEXCORE); return;
            case 4: showAssets(CAT_GPU); return;
            default: showRepos();
        }
    }

    private void onAssetClick(int position) {
        downloadFilename = currentNames.get(position);
        downloadUrl = currentUrls.get(position);

        // Append URL extension to display name so the later stripExt() in the helper
        // doesn't cut at a dot inside a version number (e.g. "v2.0.0-b" → "v2.0").
        String last = Uri.parse(downloadUrl).getLastPathSegment();
        if (last != null) {
            int dot = last.lastIndexOf('.');
            if (dot > 0) {
                downloadFilename = downloadFilename + last.substring(dot);
            }
        }

        listView.setAdapter(null);
        statusText.setText("Downloading...");
        startDownload();
    }

    private void clearAll() {
        allNames.clear();
        allUrls.clear();
    }

    public void startFetchPackJson(String url) {
        new Thread(() -> {
            try {
                String body = httpGet(url);
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String type = obj.getString("type");
                    if ("Wine".equals(type) || "Proton".equals(type)) continue;

                    String remoteUrl = obj.getString("remoteUrl");
                    int slash = remoteUrl.lastIndexOf('/');
                    String filename = remoteUrl.substring(slash + 1);

                    allNames.add(filename);
                    allUrls.add(remoteUrl);
                }
                runOnUiThread(this::showCategories);
            } catch (Exception e) {
                fetchFailed(e);
            }
        }).start();
    }

    public void startFetchGpuDrivers(String url) {
        new Thread(() -> {
            try {
                String body = httpGet(url);
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String type = obj.getString("type");
                    if ("Wine".equals(type) || "Proton".equals(type)) continue;

                    String verName = obj.getString("verName");
                    String remoteUrl = obj.getString("remoteUrl");

                    allNames.add(verName);
                    allUrls.add(remoteUrl);
                }
                runOnUiThread(this::showCategories);
            } catch (Exception e) {
                fetchFailed(e);
            }
        }).start();
    }

    public void startDownload() {
        new Thread(() -> {
            try {
                File dest = new File(getCacheDir(), downloadFilename);
                URL u = new URL(downloadUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(0x7530);
                conn.setReadTimeout(0x7530);

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[0x2000];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                Uri fileUri = Uri.fromFile(dest);
                int categoryTag = detectType(downloadFilename);
                int sidecarType = toSidecarType(categoryTag);

                runOnUiThread(() -> {
                    ComponentInjectorHelper.injectComponent(
                            ComponentDownloadActivity.this, fileUri, sidecarType);
                    finish();
                });
            } catch (Exception e) {
                fetchFailed(e);
            }
        }).start();
    }

    private void fetchFailed(Exception e) {
        String msg = e.getMessage();
        if (msg == null) msg = "Unknown error";
        String text = "Fetch failed: " + msg;
        runOnUiThread(() -> {
            statusText.setText(text);
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onBackPressed() {
        switch (mode) {
            case 2: showCategories(); return;
            case 1: showRepos(); return;
            default: super.onBackPressed();
        }
    }

    /**
     * Filename keyword detection — mirrors 3.5.0 smali. Returns one of the internal
     * category tags (CAT_*), used to filter assets by category. Defaults to DXVK.
     */
    public static int detectType(String filename) {
        String s = filename.toLowerCase();
        if (s.contains("box64")) return CAT_BOX64;
        if (s.contains("fex")) return CAT_FEXCORE;
        if (s.contains("vkd3d")) return CAT_VKD3D;
        if (s.contains("turnip")) return CAT_GPU;
        if (s.contains("adreno")) return CAT_GPU;
        if (s.contains("driver")) return CAT_GPU;
        if (s.contains("qualcomm")) return CAT_GPU;
        return CAT_DXVK;
    }

    /**
     * Convert internal category tag to GameHub 6.0 EnvLayerEntity.type for the sidecar.
     * Confirmed against installed registry: FEX + Box64 share type=1 (translator —
     * disambiguated downstream by name prefix), GPU=2, DXVK=3, VKD3D=4. type=6 is
     * the runtime-dep / isBase bucket and does NOT belong on translator/driver entries.
     */
    private static int toSidecarType(int categoryTag) {
        switch (categoryTag) {
            case CAT_GPU: return 2;
            case CAT_DXVK: return 3;
            case CAT_VKD3D: return 4;
            case CAT_BOX64:
            case CAT_FEXCORE:
                return 1;
            default: return 6;
        }
    }

    private static String httpGet(String url) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(0x3a98);
        conn.setReadTimeout(0x3a98);
        conn.setRequestProperty("User-Agent", "BannerHub/1.0");

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
