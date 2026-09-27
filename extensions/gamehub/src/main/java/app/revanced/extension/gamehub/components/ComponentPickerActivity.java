package app.revanced.extension.gamehub.components;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.TypedValue;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-app browser over device storage for the Component Manager: shows
 * folders and component archives only — {@code .wcp} (every component but
 * GPU drivers comes this way: a tar compressed with zstd or xz),
 * {@code .zip} (adrenotools GPU driver packages, GitHub release archives)
 * and {@code .tzst} (GameHub's own format). Tap an archive to pick it; use
 * "Use this folder" to pick an already-extracted component folder. No SAF —
 * the host holds {@code MANAGE_EXTERNAL_STORAGE}; when all-files access is
 * not granted we show a notice that opens the system toggle.
 *
 * <p>Structural clone of the GOG {@code FolderPickerActivity} (roots
 * spinner + path label + rows), with files added and the roots switched to
 * Internal Storage / any readable {@code /storage/<sdcard>} mount.
 *
 * Result extras: {@value #EXTRA_PATH} (absolute path), {@value #EXTRA_IS_DIR}.
 */
public class ComponentPickerActivity extends Activity {

    private static final String TAG = "BhComponentPicker";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_IS_DIR = "isDir";

    private File currentDir;
    private String[] rootLabels;
    private File[] rootDirs;

    private TextView pathTV;
    private LinearLayout listContainer;
    private LinearLayout permissionBanner;
    private Button useFolderBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        List<String> labels = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        File internal = Environment.getExternalStorageDirectory();
        labels.add("Internal Storage");
        dirs.add(internal);
        for (File sd : sdCardRoots()) {
            labels.add("SD Card (" + sd.getName() + ")");
            dirs.add(sd);
        }
        rootLabels = labels.toArray(new String[0]);
        rootDirs = dirs.toArray(new File[0]);

        // Start in Download when it exists — that is where a browser put the file.
        File dl = new File(internal, Environment.DIRECTORY_DOWNLOADS);
        currentDir = dl.isDirectory() ? dl : internal;
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionBanner();
        refreshList();
    }

    /** Readable removable mounts under /storage (skip the emulated + self views). */
    private static List<File> sdCardRoots() {
        List<File> out = new ArrayList<>();
        File[] kids = new File("/storage").listFiles();
        if (kids == null) return out;
        for (File k : kids) {
            String n = k.getName();
            if ("emulated".equals(n) || "self".equals(n)) continue;
            if (k.isDirectory() && k.canRead()) out.add(k);
        }
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    // ── Permission ────────────────────────────────────────────────────────

    private static boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return true;   // let the browse attempt speak
            }
        }
        return true;           // API 29: legacy external storage, nothing to grant here
    }

    private void openAllFilesSettings() {
        try {
            Intent i = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
            } catch (Throwable t2) {
                Log.w(TAG, "no all-files settings screen", t2);
                Toast.makeText(this, "Grant \"All files access\" to GameHub in system settings",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refreshPermissionBanner() {
        boolean ok = hasAllFilesAccess();
        permissionBanner.setVisibility(ok ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void buildUi() {
        LinearLayout root = BhComponentUi.column(this);
        root.setBackgroundColor(BhComponentUi.BG);

        LinearLayout header = BhComponentUi.column(this);
        header.setBackgroundColor(BhComponentUi.HEADER);
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        header.addView(BhComponentUi.text(this, "Pick a component (.wcp / .zip / .tzst or folder)", 16f,
                BhComponentUi.TEXT, true));

        TextView locationLabel = BhComponentUi.text(this, "Location:", 11f, 0xFF8888AA, false);
        LinearLayout.LayoutParams llLp = BhComponentUi.lp(-2, -2);
        llLp.topMargin = dp(8);
        llLp.bottomMargin = dp(2);
        header.addView(locationLabel, llLp);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, rootLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean firstCall = true;
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int pos, long id) {
                if (firstCall) { firstCall = false; return; }
                currentDir = rootDirs[pos];
                refreshList();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        header.addView(spinner, BhComponentUi.lp(-1, -2));

        pathTV = BhComponentUi.text(this, "", 11f, 0xFF666688, false);
        pathTV.setPadding(0, dp(4), 0, 0);
        header.addView(pathTV);
        root.addView(header, BhComponentUi.lp(-1, -2));

        // All-files-access notice (hidden when granted).
        permissionBanner = BhComponentUi.row(this);
        permissionBanner.setPadding(dp(12), dp(8), dp(12), dp(8));
        permissionBanner.setBackgroundColor(0xFF3A2A10);
        TextView permText = BhComponentUi.text(this,
                "All files access is off — storage may look empty.", 12f, BhComponentUi.AMBER, false);
        permissionBanner.addView(permText, new LinearLayout.LayoutParams(0, -2, 1f));
        Button grant = BhComponentUi.button(this, "Grant", BhComponentUi.AMBER);
        grant.setTextColor(0xFF000000);
        grant.setOnClickListener(v -> openAllFilesSettings());
        permissionBanner.addView(grant, BhComponentUi.lp(dp(80), dp(36)));
        root.addView(permissionBanner, BhComponentUi.lp(-1, -2));

        // Action row: use the current folder as the component.
        LinearLayout btnRow = BhComponentUi.row(this);
        LinearLayout.LayoutParams btnRowLp = BhComponentUi.lp(-1, -2);
        btnRowLp.setMargins(dp(12), dp(8), dp(12), dp(4));
        useFolderBtn = BhComponentUi.button(this, "✓  Use this folder as the component", BhComponentUi.GREEN);
        useFolderBtn.setOnClickListener(v -> confirmFolder(currentDir));
        btnRow.addView(useFolderBtn, new LinearLayout.LayoutParams(-1, dp(44)));
        root.addView(btnRow, btnRowLp);

        ScrollView scroll = new ScrollView(this);
        listContainer = BhComponentUi.column(this);
        listContainer.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
    }

    private void refreshList() {
        listContainer.removeAllViews();
        updatePathLabel();
        // A storage root is never a component; only enable the folder pick below it.
        boolean atRoot = isRoot(currentDir);
        useFolderBtn.setEnabled(!atRoot);
        useFolderBtn.setAlpha(atRoot ? 0.4f : 1f);

        File parent = currentDir.getParentFile();
        if (parent != null && !atRoot) {
            listContainer.addView(makeRow("↑  Up", parent, true, false));
        }

        File[] files = currentDir.listFiles();
        if (files == null) {
            addEmptyLabel(hasAllFilesAccess()
                    ? "(empty or no read permission)"
                    : "(no read permission — grant All files access above)");
            return;
        }
        List<File> dirs = new ArrayList<>();
        List<File> archives = new ArrayList<>();
        for (File f : files) {
            if (f.isDirectory()) {
                if (f.getName().startsWith(".")) continue;
                dirs.add(f);
            } else if (BhComponentType.isArchiveName(f.getName())) {
                archives.add(f);
            }
        }
        Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        Collections.sort(archives, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        if (dirs.isEmpty() && archives.isEmpty()) {
            addEmptyLabel("(no folders or .wcp / .zip / .tzst files here)");
            return;
        }
        for (File d : dirs) listContainer.addView(makeRow("📁  " + d.getName(), d, false, false));
        for (File a : archives) {
            listContainer.addView(makeRow("📦  " + a.getName() + "   "
                    + BhComponentUi.humanSize(a.length()), a, false, true));
        }
    }

    private boolean isRoot(File dir) {
        for (File r : rootDirs) if (r != null && r.equals(dir)) return true;
        return false;
    }

    private void updatePathLabel() {
        String abs = currentDir.getAbsolutePath();
        String[] parts = abs.split("/");
        if (parts.length <= 3) pathTV.setText(abs);
        else pathTV.setText("…/" + parts[parts.length - 2] + "/" + parts[parts.length - 1]);
    }

    private void addEmptyLabel(String msg) {
        TextView tv = BhComponentUi.text(this, msg, 13f, BhComponentUi.DIM, false);
        tv.setPadding(dp(4), dp(8), dp(4), dp(8));
        listContainer.addView(tv);
    }

    private LinearLayout makeRow(String label, File target, boolean isUp, boolean isFile) {
        LinearLayout row = BhComponentUi.row(this);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(BhComponentUi.roundBg(this,
                isUp ? 0xFF1E1A2E : (isFile ? BhComponentUi.CARD_HI : 0xFF1A1E2E), 6, BhComponentUi.OUTLINE));

        TextView tv = BhComponentUi.text(this, label, 13f,
                isUp ? 0xFFAAAAAA : (isFile ? BhComponentUi.TEXT : 0xFFDDDDFF), false);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        if (!isUp && !isFile) {
            TextView arrow = BhComponentUi.text(this, "›", 18f, BhComponentUi.DIM, false);
            row.addView(arrow, BhComponentUi.lp(-2, -2));
        }
        row.setOnClickListener(v -> {
            if (isFile) {
                confirmFile(target);
            } else {
                currentDir = target;
                refreshList();
            }
        });
        LinearLayout.LayoutParams lp = BhComponentUi.lp(-1, -2);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void confirmFile(File f) {
        finishWith(f, false);
    }

    private void confirmFolder(File dir) {
        if (dir == null || isRoot(dir)) return;
        new AlertDialog.Builder(this)
                .setTitle("Use folder")
                .setMessage("Inject \"" + dir.getName() + "\" as an already-extracted component?\n\n"
                        + "It should hold the component's files at its top level "
                        + "(e.g. libvulkan_freedreno.so + meta.json for a driver, "
                        + "profile.json + system32/ + syswow64/ for DXVK).")
                .setPositiveButton("Yes", (d, w) -> finishWith(dir, true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void finishWith(File f, boolean isDir) {
        Intent result = new Intent();
        result.putExtra(EXTRA_PATH, f.getAbsolutePath());
        result.putExtra(EXTRA_IS_DIR, isDir);
        setResult(RESULT_OK, result);
        finish();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
