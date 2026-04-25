package app.revanced.extension.gamehub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings("unused")
public class ComponentManagerActivity extends Activity {

    private static final String TAG = "BannerHub";
    private static final int REQUEST_PICK_FILE = 0x3e9;

    static final int TYPE_GPU_DRIVER = 10;
    static final int TYPE_DXVK      = 12;
    static final int TYPE_VKD3D     = 13;
    static final int TYPE_BOX64     = 94;
    static final int TYPE_FEXCORE   = 95;

    private File   componentsDir;
    private File[] allComponents      = new File[0];
    private File[] filteredComponents = new File[0];

    private ListView             listView;
    private ComponentCardAdapter lvAdapter;
    private TextView             emptyState;
    private TextView             countBadge;
    private TextView             removeAllBtn;
    private EditText             searchBar;
    private String               currentQuery = "";

    private int selectedIndex; // into filteredComponents[]
    private int selectedType;
    private int pendingMode;   // 1 = injectRaw into existing, 3 = new inject

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        componentsDir = new File(getFilesDir(), "usr/home/components");
        buildUI();
        showComponents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showComponents();
    }

    // ── Build persistent UI (called once) ─────────────────────────────────────

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0D0D);
        root.setFitsSystemWindows(true);

        root.addView(buildHeader());

        searchBar = new EditText(this);
        searchBar.setHint("Search components...");
        searchBar.setHintTextColor(0xFF555555);
        searchBar.setTextColor(0xFFCCCCCC);
        searchBar.setTextSize(13f);
        searchBar.setBackgroundColor(0xFF1A1A1A);
        searchBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchBar.setSingleLine(true);
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                currentQuery = s.toString().trim();
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(searchBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        root.addView(buildContent(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildBottomBar());

        setContentView(root);
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF111111);
        header.setPadding(dp(6), dp(10), dp(10), dp(10));

        TextView back = new TextView(this);
        back.setText("\u2190");
        back.setTextSize(18f);
        back.setTextColor(0xFFFFFFFF);
        back.setPadding(dp(8), dp(4), dp(12), dp(4));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("Banners Component Manager");
        title.setTextSize(15f);
        title.setTextColor(0xFFFF9800);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        countBadge = new TextView(this);
        countBadge.setText("0");
        countBadge.setTextSize(11f);
        countBadge.setTextColor(0xFF888888);
        countBadge.setPadding(dp(7), dp(2), dp(7), dp(2));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0xFF2A2A2A);
        badgeBg.setCornerRadius(dp(8));
        countBadge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.rightMargin = dp(8);
        header.addView(countBadge, badgeLp);

        removeAllBtn = new TextView(this);
        removeAllBtn.setText("\u2715 All");
        removeAllBtn.setTextSize(12f);
        removeAllBtn.setTextColor(0xFFE53935);
        removeAllBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        removeAllBtn.setOnClickListener(v -> confirmRemoveAll());
        removeAllBtn.setVisibility(View.GONE);
        header.addView(removeAllBtn);

        return header;
    }

    private FrameLayout buildContent() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF0D0D0D);

        lvAdapter = new ComponentCardAdapter(new File[0]);
        listView = new ListView(this);
        listView.setBackgroundColor(0xFF0D0D0D);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setAdapter(lvAdapter);
        listView.setOnItemClickListener((parent, view, pos, id) -> onItemClick(pos));
        frame.addView(listView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        emptyState = new TextView(this);
        emptyState.setText("No components installed.\nTap \"+\" to add one.");
        emptyState.setTextColor(0xFF555555);
        emptyState.setTextSize(14f);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setVisibility(View.GONE);
        frame.addView(emptyState, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        return frame;
    }

    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF111111);

        GradientDrawable addBg = new GradientDrawable();
        addBg.setColor(0xFF222222);
        addBg.setCornerRadius(dp(6));
        TextView addBtn = new TextView(this);
        addBtn.setText("+ Add New");
        addBtn.setTextSize(13f);
        addBtn.setTextColor(0xFFFFFFFF);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setBackground(addBg);
        addBtn.setOnClickListener(v -> showTypeDialog());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        addLp.leftMargin  = dp(8);
        addLp.topMargin   = dp(6);
        addLp.rightMargin = dp(4);
        addLp.bottomMargin = dp(6);
        bar.addView(addBtn, addLp);

        GradientDrawable dlBg = new GradientDrawable();
        dlBg.setColor(0xFF1A1200);
        dlBg.setCornerRadius(dp(6));
        TextView dlBtn = new TextView(this);
        dlBtn.setText("\u2193 Download");
        dlBtn.setTextSize(13f);
        dlBtn.setTextColor(0xFFFF9800);
        dlBtn.setGravity(Gravity.CENTER);
        dlBtn.setBackground(dlBg);
        dlBtn.setOnClickListener(v -> startActivity(new Intent(this, ComponentDownloadActivity.class)));
        LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        dlLp.leftMargin   = dp(4);
        dlLp.topMargin    = dp(6);
        dlLp.rightMargin  = dp(8);
        dlLp.bottomMargin = dp(6);
        bar.addView(dlBtn, dlLp);

        return bar;
    }

    // ── Data / filter ─────────────────────────────────────────────────────────

    private void showComponents() {
        componentsDir.mkdirs();
        File[] dirs = componentsDir.listFiles(File::isDirectory);
        if (dirs != null) { Arrays.sort(dirs); allComponents = dirs; }
        else allComponents = new File[0];
        applyFilter();
    }

    private void applyFilter() {
        if (currentQuery.isEmpty()) {
            filteredComponents = allComponents;
        } else {
            String q = currentQuery.toLowerCase();
            List<File> list = new ArrayList<>();
            for (File f : allComponents) {
                if (f.getName().toLowerCase().contains(q)) list.add(f);
            }
            filteredComponents = list.toArray(new File[0]);
        }
        refreshList();
    }

    private void refreshList() {
        countBadge.setText(String.valueOf(allComponents.length));

        boolean hasBh = false;
        for (File f : allComponents) {
            if (new File(f, ".bh_injected").exists()) { hasBh = true; break; }
        }
        removeAllBtn.setVisibility(hasBh ? View.VISIBLE : View.GONE);

        lvAdapter.setData(filteredComponents);

        boolean empty = filteredComponents.length == 0;
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    // ── Click handler ─────────────────────────────────────────────────────────

    private void onItemClick(int pos) {
        if (pos < 0 || pos >= filteredComponents.length) return;
        selectedIndex = pos;
        showOptionsDialog(pos);
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private void showOptionsDialog(int index) {
        String name = filteredComponents[index].getName();
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"Inject / Replace file...", "Backup to Downloads", "Remove"},
                        (d, which) -> {
                            switch (which) {
                                case 0: pendingMode = 1; pickFile(); break;
                                case 1: backupComponent(filteredComponents[index]); break;
                                case 2: confirmRemove(index); break;
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTypeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Select Component Type")
                .setItems(new String[]{"DXVK", "VKD3D-Proton", "Box64", "FEXCore", "GPU Driver / Turnip"},
                        (d, which) -> {
                            switch (which) {
                                case 0: selectedType = TYPE_DXVK;       break;
                                case 1: selectedType = TYPE_VKD3D;      break;
                                case 2: selectedType = TYPE_BOX64;      break;
                                case 3: selectedType = TYPE_FEXCORE;    break;
                                case 4: selectedType = TYPE_GPU_DRIVER; break;
                                default: return;
                            }
                            pendingMode = 3;
                            pickFile();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── File picker ───────────────────────────────────────────────────────────

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQUEST_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQUEST_PICK_FILE || res != RESULT_OK || data == null || data.getData() == null) {
            showComponents();
            return;
        }
        Uri uri = data.getData();
        if (pendingMode == 3) {
            int type = selectedType;
            new Thread(() -> {
                ComponentInjectorHelper.injectComponent(this, uri, type);
                runOnUiThread(this::showComponents);
            }).start();
        } else if (pendingMode == 1) {
            injectRaw(uri, filteredComponents[selectedIndex]);
        } else {
            showComponents();
        }
    }

    // ── Raw copy (Inject/Replace into existing component) ─────────────────────

    private void injectRaw(Uri uri, File destDir) {
        String filename = ComponentInjectorHelper.getDisplayName(this, uri);
        if (filename == null || filename.isEmpty()) filename = "injected_file";
        File destFile = new File(destDir, filename);
        final String name = filename;

        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(destFile)) {
                byte[] buf = new byte[8192];
                int n;
                if (in != null) while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Injected: " + name, Toast.LENGTH_SHORT).show();
                    showComponents();
                });
            } catch (Exception e) {
                Log.e(TAG, "injectRaw failed", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Inject failed: " + msg, Toast.LENGTH_LONG).show();
                    showComponents();
                });
            }
        }).start();
    }

    // ── Remove component ──────────────────────────────────────────────────────

    private void confirmRemove(int index) {
        File dir = filteredComponents[index];
        String name = dir.getName();
        new AlertDialog.Builder(this)
                .setTitle("Remove Component")
                .setMessage("Delete \"" + name + "\"? This cannot be undone.")
                .setPositiveButton("Remove", (d, w) -> {
                    ComponentInjectorHelper.unregisterComponent(name);
                    cleanSP(name);
                    deleteDir(dir);
                    Toast.makeText(this, "Removed: " + name, Toast.LENGTH_SHORT).show();
                    showComponents();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Remove All ────────────────────────────────────────────────────────────

    private void confirmRemoveAll() {
        List<File> bhDirs = new ArrayList<>();
        for (File dir : allComponents) {
            if (new File(dir, ".bh_injected").exists()) bhDirs.add(dir);
        }
        if (bhDirs.isEmpty()) {
            Toast.makeText(this, "No BannerHub-added components to remove", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = bhDirs.size();
        new AlertDialog.Builder(this)
                .setTitle("Remove All BannerHub Components")
                .setMessage("Remove " + count + " BannerHub-added component(s)?\n"
                        + "Components installed by GameHub will not be affected.")
                .setPositiveButton("Remove", (d, w) -> {
                    for (File dir : bhDirs) {
                        String name = dir.getName();
                        ComponentInjectorHelper.unregisterComponent(name);
                        cleanSP(name);
                        deleteDir(dir);
                    }
                    Toast.makeText(this, "Removed " + count + " component(s)", Toast.LENGTH_SHORT).show();
                    showComponents();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Backup ────────────────────────────────────────────────────────────────

    private void backupComponent(File src) {
        String name = src.getName();
        File dst = new File(
                android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS),
                "BannerHub/" + name);
        dst.mkdirs();

        new Thread(() -> {
            try {
                copyDir(src, dst);
                runOnUiThread(() -> Toast.makeText(this,
                        "Backed up to Downloads/BannerHub/" + name, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Backup failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── SP cleanup (4 keys per component) ────────────────────────────────────

    private void cleanSP(String name) {
        SharedPreferences sp = getSharedPreferences("banners_sources", 0);
        String url = sp.getString("url_for:" + name, null);
        SharedPreferences.Editor ed = sp.edit();
        ed.remove(name);
        ed.remove(name + ":type");
        ed.remove("url_for:" + name);
        if (url != null) ed.remove("dl:" + url);
        ed.apply();
    }

    // ── Card adapter ──────────────────────────────────────────────────────────

    private class ComponentCardAdapter extends BaseAdapter {
        private File[] data;

        ComponentCardAdapter(File[] data) { this.data = data; }

        void setData(File[] d) { this.data = d; notifyDataSetChanged(); }

        @Override public int getCount() { return data.length; }
        @Override public Object getItem(int pos) { return data[pos]; }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            File dir = data[pos];
            String name = dir.getName();

            SharedPreferences sp = getSharedPreferences("banners_sources", 0);
            String source     = sp.getString(name, null);
            String typeNameSp = sp.getString(name + ":type", null);
            String typeName   = typeNameSp != null ? typeNameSp : getTypeName(name);
            int    typeColor  = getTypeColor(typeName);

            LinearLayout card = new LinearLayout(ComponentManagerActivity.this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(0, dp(6), dp(10), dp(6));
            card.setMinimumHeight(dp(56));

            // Accent strip
            View strip = new View(ComponentManagerActivity.this);
            strip.setBackgroundColor(typeColor);
            LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(dp(3), dp(42));
            stripLp.rightMargin = dp(12);
            card.addView(strip, stripLp);

            // Name + source column
            LinearLayout nameCol = new LinearLayout(ComponentManagerActivity.this);
            nameCol.setOrientation(LinearLayout.VERTICAL);
            nameCol.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameView = new TextView(ComponentManagerActivity.this);
            nameView.setText(name);
            nameView.setTextSize(12f);
            nameView.setTextColor(0xFFFFFFFF);
            nameView.setMaxLines(2);
            nameCol.addView(nameView);

            if (source != null && !source.isEmpty()) {
                TextView sourceView = new TextView(ComponentManagerActivity.this);
                sourceView.setText(source);
                sourceView.setTextSize(10f);
                sourceView.setTextColor(0xFF888888);
                nameCol.addView(sourceView);
            }

            card.addView(nameCol, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // Type badge
            if (typeName != null) {
                TextView badge = new TextView(ComponentManagerActivity.this);
                badge.setText(typeName);
                badge.setTextSize(9f);
                badge.setTextColor(typeColor);
                badge.setPadding(dp(6), dp(2), dp(6), dp(2));
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setColor((typeColor & 0x00FFFFFF) | 0x33000000);
                badgeBg.setCornerRadius(dp(6));
                badge.setBackground(badgeBg);
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                badgeLp.rightMargin = dp(8);
                card.addView(badge, badgeLp);
            }

            // Arrow
            TextView arrow = new TextView(ComponentManagerActivity.this);
            arrow.setText("\u203a");
            arrow.setTextSize(18f);
            arrow.setTextColor(0xFF555555);
            card.addView(arrow);

            return card;
        }
    }

    // ── Type helpers ──────────────────────────────────────────────────────────

    private static String getTypeName(String name) {
        String lower = name.toLowerCase();
        if (lower.contains("dxvk"))                                    return "DXVK";
        if (lower.contains("vkd3d"))                                   return "VKD3D";
        if (lower.contains("box64"))                                   return "Box64";
        if (lower.contains("fexcore") || lower.contains("fex"))       return "FEX";
        if (lower.contains("turnip") || lower.contains("adreno")
                || lower.contains("gpu"))                              return "GPU";
        if (lower.endsWith(".wcp"))                                    return "WCP";
        return null;
    }

    private static int getTypeColor(String typeName) {
        if (typeName == null) return 0xFF888888;
        switch (typeName) {
            case "DXVK":  return 0xFF4D8FFF;
            case "VKD3D": return 0xFF9B59B6;
            case "Box64": return 0xFF47B24F;
            case "FEX":   return 0xFFE67E22;
            case "GPU":   return 0xFFF0C140;
            default:      return 0xFF888E99;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()));
    }

    static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }

    private static void copyDir(File src, File dst) throws Exception {
        dst.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        byte[] buf = new byte[8192];
        for (File f : files) {
            if (f.isDirectory()) {
                copyDir(f, new File(dst, f.getName()));
            } else {
                try (InputStream in  = new FileInputStream(f);
                     OutputStream out = new FileOutputStream(new File(dst, f.getName()))) {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
    }
}
