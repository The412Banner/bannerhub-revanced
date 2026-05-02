package app.revanced.extension.gamehub.components;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * BannerHub Component Manager — main activity.
 *
 * Three-mode ListView ported from BannerHub 3.5.0 smali. Mode 0 lists components
 * with "+ Add New Component" header; mode 1 shows options for a selected component
 * (Inject/Replace, Backup, Remove, Back); mode 2 picks a type for a new injection
 * (Download from Online Repos, DXVK, VKD3D-Proton, Box64, FEXCore, GPU Driver, Back).
 *
 * <p>Type ints reported to {@link ComponentInjectorHelper#injectComponent} use
 * GameHub 6.0's {@code EnvLayerEntity.type} mapping, not 5.x's:
 * GPU=2, DXVK=3, VKD3D=4, Box64/FEXCore→6 (runtime dep) since 6.0 has no native type for those.</p>
 */
public final class ComponentManagerActivity extends Activity
        implements AdapterView.OnItemClickListener {

    private static final int REQ_PICK_FILE = 0x3e9;

    // 6.0 EnvLayerEntity.type values (per master map):
    // 2=GPU driver, 3=DXVK, 4=VKD3D, 5=settings pack, 6=runtime dep.
    // Host EnvLayerEntity.type values — confirmed against installed registry
    // (Fex_*/Box64-* share type=1 translator bucket; FEX vs Box64 disambiguated
    // by name prefix downstream).
    private static final int TYPE_GPU = 2;
    private static final int TYPE_DXVK = 3;
    private static final int TYPE_VKD3D = 4;
    private static final int TYPE_BOX64 = 1;
    private static final int TYPE_FEXCORE = 1;

    // Category tags — full discriminator preserved for the name-prefix step.
    private static final int CAT_DXVK = 0xc;
    private static final int CAT_VKD3D = 0xd;
    private static final int CAT_GPU = 0xa;
    private static final int CAT_BOX64 = 0x5e;
    private static final int CAT_FEXCORE = 0x5f;

    private ListView listView;
    private int mode;
    private File[] components = new File[0];
    private int selectedIndex;
    private int selectedType;
    private int selectedCategoryTag;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Self-heal insurance: re-write every sidecar entry into the host registry
        // on every Component Manager open. Cheap, idempotent, defends against any
        // hypothetical future API refresh that prunes unknown keys.
        HostRegistry.rehydrateFromSidecar(this);

        listView = new ListView(this);
        listView.setOnItemClickListener(this);
        listView.setClipToPadding(false);

        TextView title = new TextView(this);
        title.setText("Banners Component Manager");
        title.setTextSize(18f);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(48, 24, 48, 24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout.LayoutParams titleLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(title, titleLp);

        LinearLayout.LayoutParams listLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(listView, listLp);

        setContentView(root);
        showComponents();
    }

    public void showComponents() {
        mode = 0;

        File baseDir = new File(getFilesDir(), "usr/home/components");
        File[] all = baseDir.listFiles();

        List<File> dirs = new ArrayList<>();
        if (all != null) {
            for (File f : all) {
                if (f.isDirectory()) dirs.add(f);
            }
        }

        components = dirs.toArray(new File[0]);
        String[] names = new String[components.length + 1];
        names[0] = "+ Add New Component";
        for (int i = 0; i < components.length; i++) {
            names[i + 1] = components[i].getName();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);
    }

    public void showOptions() {
        mode = 1;
        String[] items = { "Inject/Replace file...", "Backup", "Remove", "← Back" };
        listView.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, items));
    }

    public void showTypeSelection() {
        mode = 2;
        String[] items = {
                "↓ Download from Online Repos",
                "DXVK", "VKD3D-Proton", "Box64", "FEXCore", "GPU Driver / Turnip",
                "← Back"
        };
        listView.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, items));
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        switch (mode) {
            case 0:
                if (position == 0) {
                    showTypeSelection();
                } else {
                    selectedIndex = position - 1;
                    showOptions();
                }
                return;
            case 1:
                switch (position) {
                    case 0: pickFile(); return;
                    case 1: backupComponent(); return;
                    case 2: removeComponent(); return;
                    default: showComponents(); return;
                }
            case 2:
                if (position == 0) {
                    Intent intent = new Intent(this, ComponentDownloadActivity.class);
                    startActivity(intent);
                    return;
                }
                switch (position) {
                    case 1: selectedType = TYPE_DXVK;    selectedCategoryTag = CAT_DXVK;    break;
                    case 2: selectedType = TYPE_VKD3D;   selectedCategoryTag = CAT_VKD3D;   break;
                    case 3: selectedType = TYPE_BOX64;   selectedCategoryTag = CAT_BOX64;   break;
                    case 4: selectedType = TYPE_FEXCORE; selectedCategoryTag = CAT_FEXCORE; break;
                    case 5: selectedType = TYPE_GPU;     selectedCategoryTag = CAT_GPU;     break;
                    default:
                        showComponents();
                        return;
                }
                mode = 3;
                pickFile();
                return;
            default:
                showComponents();
        }
    }

    public void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            showComponents();
            return;
        }

        Uri uri = data.getData();
        if (mode == 3) {
            ComponentInjectorHelper.injectComponent(this, uri, selectedType, selectedCategoryTag);
            showComponents();
        } else {
            injectFile(uri);
        }
    }

    public void injectFile(Uri uri) {
        File targetDir = components[selectedIndex];
        String filename = "injected_file";
        try (Cursor c = getContentResolver().query(
                uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null && !s.isEmpty()) filename = s;
            }
        } catch (Exception ignored) {
        }

        File outFile = new File(targetDir, filename);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[0x2000];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);

            Toast.makeText(this, "Injected: " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Inject failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        showComponents();
    }

    public void backupComponent() {
        File componentDir = components[selectedIndex];
        String name = componentDir.getName();

        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File destDir = new File(new File(downloads, "BannerHub"), name);
        destDir.mkdirs();

        try {
            copyDir(componentDir, destDir);
            Toast.makeText(this, "Backed up to Downloads/BannerHub/" + name,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Backup failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        showComponents();
    }

    public void copyDir(File src, File dst) throws Exception {
        dst.mkdirs();
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File target = new File(dst, k.getName());
            if (k.isDirectory()) {
                copyDir(k, target);
            } else {
                try (FileInputStream in = new FileInputStream(k);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[0x2000];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
    }

    public void removeComponent() {
        File componentDir = components[selectedIndex];
        String name = componentDir.getName();

        // Sidecar variant: drop the registration so ComponentInjector stops merging it.
        SidecarRegistry.remove(this, name);

        deleteDir(componentDir);
        Toast.makeText(this, "Removed: " + name, Toast.LENGTH_LONG).show();
        showComponents();
    }

    public static void deleteDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) deleteDir(k);
                else k.delete();
            }
        }
        dir.delete();
    }

    @Override
    public void onBackPressed() {
        if (mode != 0) {
            showComponents();
        } else {
            super.onBackPressed();
        }
    }
}
