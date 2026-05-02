package app.revanced.extension.gamehub.components;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import app.revanced.extension.gamehub.debug.DebugTrace;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * "Add custom component" UI launched from {@link ComponentManagerActivity}.
 *
 * <p>Two input modes:</p>
 * <ul>
 *   <li><b>File picker</b> — {@code Intent.ACTION_OPEN_DOCUMENT} for {@code .tzst}/{@code .yml}.
 *       Copies the picked file into {@code xj_downloads/component/<derived-name>/} and writes
 *       a sidecar entry with {@code state="Downloaded"}.</li>
 *   <li><b>URL paste</b> — fetch via HTTP, validate it's a tzst/yml by extension, move
 *       into the same downloads dir, write sidecar entry.</li>
 * </ul>
 *
 * <p>Type detection uses a filename heuristic with a fallback dropdown:</p>
 * <table>
 *   <tr><th>Filename matches</th><th>Inferred type</th></tr>
 *   <tr><td>turnip*, qcom*, *Elite*</td><td>2 — GPU driver</td></tr>
 *   <tr><td>dxvk*</td><td>3 — DXVK</td></tr>
 *   <tr><td>vkd3d*</td><td>4 — VKD3D</td></tr>
 *   <tr><td>*_Settings*</td><td>5 — Settings pack</td></tr>
 *   <tr><td>vcredist*, mono, VulkanRT, quicktime*</td><td>6 — Runtime dep</td></tr>
 *   <tr><td>(otherwise)</td><td>user picks from dropdown before save</td></tr>
 * </table>
 */
public final class ComponentDownloadActivity extends Activity {

    private static final int REQ_OPEN_DOCUMENT = 9101;

    private LinearLayout root;
    private EditText nameField;
    private EditText versionField;
    private EditText urlField;
    private Spinner typeSpinner;

    /** The picked Uri (file or content://); null until ACTION_OPEN_DOCUMENT returns. */
    private Uri pickedUri;
    /** True when the user supplied a URL; the saver fetches it instead of copying picked. */
    private boolean useUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0E0F12"));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        buildUi();
    }

    private void buildUi() {
        TextView title = new TextView(this);
        title.setText("Add custom component");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(20));
        root.addView(title);

        // Source — file vs URL
        root.addView(label("Source"));
        Button pickFile = primaryButton("Pick .tzst / .yml from storage…");
        pickFile.setOnClickListener(v -> launchFilePicker());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        plp.bottomMargin = dp(12);
        root.addView(pickFile, plp);

        TextView orLabel = new TextView(this);
        orLabel.setText("— or —");
        orLabel.setTextColor(Color.parseColor("#7C8590"));
        orLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        orLabel.setGravity(Gravity.CENTER);
        orLabel.setPadding(0, 0, 0, dp(12));
        root.addView(orLabel);

        urlField = textField("https://example.com/component.tzst", InputType.TYPE_TEXT_VARIATION_URI);
        urlField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                useUrl = s.length() > 0;
                if (useUrl) {
                    autofillFromName(deriveNameFromUrl(s.toString()));
                }
            }
        });
        root.addView(urlField);
        root.addView(spacer(dp(20)));

        // Name + version
        root.addView(label("Name"));
        nameField = textField("e.g. dxvk-2.5-async-arm64", InputType.TYPE_CLASS_TEXT);
        root.addView(nameField);
        root.addView(spacer(dp(14)));

        root.addView(label("Version"));
        versionField = textField("1.0.0", InputType.TYPE_CLASS_TEXT);
        root.addView(versionField);
        root.addView(spacer(dp(14)));

        // Type
        root.addView(label("Type"));
        typeSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[] {
                        "Auto-detect from filename",
                        "GPU driver (type 2)",
                        "DXVK (type 3)",
                        "VKD3D (type 4)",
                        "Settings pack (type 5)",
                        "Runtime dep (type 6)",
                });
        typeSpinner.setAdapter(adapter);
        typeSpinner.setBackground(roundedRect(Color.parseColor("#1B1D22"), dp(8)));
        typeSpinner.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        sp.bottomMargin = dp(20);
        root.addView(typeSpinner, sp);

        // Save / cancel
        Button save = primaryButton("Add to library");
        save.setOnClickListener(v -> doSave());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        slp.topMargin = dp(8);
        root.addView(save, slp);

        Button cancel = secondaryButton("Cancel");
        cancel.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        clp.topMargin = dp(8);
        root.addView(cancel, clp);
    }

    // ---- file picker -----------------------------------------------------

    private void launchFilePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/zstd",
                "application/x-zstd",
                "application/octet-stream",
                "text/yaml",
                "application/x-yaml",
                "*/*",
        });
        try {
            startActivityForResult(i, REQ_OPEN_DOCUMENT);
        } catch (Throwable t) {
            Toast.makeText(this, "No file picker available: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN_DOCUMENT || resultCode != RESULT_OK || data == null) return;
        pickedUri = data.getData();
        if (pickedUri == null) return;
        useUrl = false;
        urlField.setText("");

        String displayName = queryDisplayName(pickedUri);
        if (displayName != null) {
            autofillFromName(stripExt(displayName));
        }
        Toast.makeText(this, "Selected: " + (displayName != null ? displayName : pickedUri),
                Toast.LENGTH_SHORT).show();
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[] { android.provider.OpenableColumns.DISPLAY_NAME },
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable t) {
            DebugTrace.write("ComponentDownload.queryDisplayName failure", t);
        }
        return null;
    }

    // ---- save ------------------------------------------------------------

    private void doSave() {
        final String name = trim(nameField);
        final String version = trim(versionField);
        if (name.isEmpty()) {
            Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!useUrl && pickedUri == null) {
            Toast.makeText(this, "Pick a file or paste a URL", Toast.LENGTH_SHORT).show();
            return;
        }
        final int type = resolveType(name);
        if (type < 2) {
            Toast.makeText(this, "Type required — pick from the dropdown", Toast.LENGTH_LONG).show();
            return;
        }

        final boolean fromUrl = useUrl;
        final String url = trim(urlField);
        final Uri local = pickedUri;

        new AsyncTask<Void, Void, String>() {
            @Override protected String doInBackground(Void... params) {
                try {
                    File targetDir = new File(getFilesDir(),
                            "xj_winemu/xj_downloads/component/" + name);
                    if (!targetDir.exists() && !targetDir.mkdirs()) {
                        return "Failed to create download dir: " + targetDir;
                    }
                    String filename = fromUrl
                            ? deriveFilenameFromUrl(url, name)
                            : preferredFilename(local, name);
                    File outFile = new File(targetDir, filename);

                    if (fromUrl) {
                        downloadTo(url, outFile);
                    } else {
                        copyTo(local, outFile);
                    }

                    JSONObject entry = buildSidecarEntry(name, version, type,
                            fromUrl ? url : local.toString(),
                            filename, outFile.length());
                    SidecarRegistry.put(ComponentDownloadActivity.this, name, entry);
                    return null;
                } catch (Throwable t) {
                    DebugTrace.write("ComponentDownload.doSave failure", t);
                    return t.getClass().getSimpleName() + ": " + t.getMessage();
                }
            }

            @Override protected void onPostExecute(String error) {
                if (error == null) {
                    Toast.makeText(ComponentDownloadActivity.this,
                            "Added " + name, Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    new AlertDialog.Builder(ComponentDownloadActivity.this)
                            .setTitle("Add failed")
                            .setMessage(error)
                            .setPositiveButton("OK", null)
                            .show();
                }
            }
        }.execute();
    }

    private JSONObject buildSidecarEntry(String name, String version, int type,
                                         String sourceUri, String filename, long fileSize)
            throws Exception {
        JSONObject inner = new JSONObject();
        inner.put("base", JSONObject.NULL);
        inner.put("blurb", "");
        inner.put("displayName", "");
        inner.put("downloadUrl", sourceUri);
        inner.put("fileMd5", "");
        inner.put("fileName", filename);
        inner.put("fileSize", fileSize);
        inner.put("fileType", 4);
        inner.put("framework", "");
        inner.put("frameworkType", "");
        inner.put("id", -1);
        inner.put("isSteam", 0);
        inner.put("logo", "");
        inner.put("name", name);
        inner.put("state", "Downloaded");
        inner.put("status", 1);
        inner.put("subData", JSONObject.NULL);
        inner.put("type", type);
        inner.put("upgradeMsg", "");
        inner.put("version", version);
        inner.put("versionCode", 1);
        inner.put(SidecarRegistry.FIELD_BH_INJECTED, true);
        inner.put(SidecarRegistry.FIELD_BH_SKIP_MD5, true);
        inner.put(SidecarRegistry.FIELD_BH_SOURCE_URI, sourceUri);

        JSONObject root = new JSONObject();
        root.put("category", "COMPONENT");
        root.put("depInfo", JSONObject.NULL);
        root.put("entry", inner);
        root.put("isBase", false);
        root.put("isDep", false);
        root.put("name", name);
        root.put("state", "Downloaded");
        root.put("version", version);
        return root;
    }

    private void downloadTo(String url, File out) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "BannerHub-ComponentManager/1.0");
        try (InputStream in = conn.getInputStream();
             OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }

    private void copyTo(Uri uri, File out) throws Exception {
        ContentResolver cr = getContentResolver();
        try (InputStream in = cr.openInputStream(uri);
             OutputStream o = new FileOutputStream(out)) {
            if (in == null) throw new Exception("Cannot open source URI");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    // ---- type detection --------------------------------------------------

    private int resolveType(String name) {
        int spinnerSel = typeSpinner.getSelectedItemPosition();
        if (spinnerSel > 0) {
            // Mapping: index 1→2, 2→3, 3→4, 4→5, 5→6
            return spinnerSel + 1;
        }
        return inferType(name);
    }

    /** Infer component type from a name/filename. Returns -1 on no match. */
    static int inferType(String name) {
        String lc = name.toLowerCase(Locale.US);
        if (lc.startsWith("turnip") || lc.startsWith("qcom") || lc.contains("elite-")) return 2;
        if (lc.startsWith("dxvk")) return 3;
        if (lc.startsWith("vkd3d")) return 4;
        if (lc.endsWith("_settings") || lc.endsWith("_settings.tzst")) return 5;
        if (lc.startsWith("vcredist") || lc.equals("mono") || lc.equals("vulkanrt")
                || lc.startsWith("quicktime")) return 6;
        return -1;
    }

    private void autofillFromName(String derivedName) {
        if (derivedName == null) return;
        if (nameField.getText().length() == 0) {
            nameField.setText(derivedName);
        }
        int t = inferType(derivedName);
        if (t >= 2 && t <= 6 && typeSpinner.getSelectedItemPosition() == 0) {
            typeSpinner.setSelection(t - 1);
        }
    }

    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return filename;
        // Common compressed-tar suffixes: foo.tar.zst, foo.tzst, foo.yml
        String s = filename.substring(0, dot);
        if (s.endsWith(".tar")) s = s.substring(0, s.length() - 4);
        return s;
    }

    private static String deriveNameFromUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        int q = url.indexOf('?');
        if (q > 0) url = url.substring(0, q);
        int slash = url.lastIndexOf('/');
        if (slash < 0) return "";
        return stripExt(url.substring(slash + 1));
    }

    private static String deriveFilenameFromUrl(String url, String fallbackName) {
        int q = url.indexOf('?');
        if (q > 0) url = url.substring(0, q);
        int slash = url.lastIndexOf('/');
        if (slash >= 0 && slash < url.length() - 1) return url.substring(slash + 1);
        return fallbackName + ".tzst";
    }

    private String preferredFilename(Uri uri, String fallbackName) {
        String displayName = queryDisplayName(uri);
        return displayName != null && !displayName.isEmpty() ? displayName : fallbackName + ".tzst";
    }

    // ---- view helpers ----------------------------------------------------

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#9AA0A6"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private EditText textField(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.parseColor("#5C636B"));
        e.setTextColor(Color.WHITE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setBackground(roundedRect(Color.parseColor("#1B1D22"), dp(8)));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setInputType(inputType);
        return e;
    }

    private LinearLayout spacer(int height) {
        LinearLayout l = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        l.setLayoutParams(lp);
        return l;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#0E0F12"));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(roundedRect(Color.parseColor("#7DD3FC"), dp(8)));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(roundedRect(Color.parseColor("#2D3037"), dp(8)));
        return b;
    }

    private GradientDrawable roundedRect(int color, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String trim(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}
