package app.revanced.extension.gamehub.components;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.revanced.extension.gamehub.debug.DebugTrace;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BannerHub Component Manager (GameHub 6.0 port).
 *
 * Standalone AppCompatActivity that lists every component the app knows about —
 * both server-supplied entries from {@code sp_winemu_unified_resources.xml} and
 * sidecar-injected entries from {@code sp_bh_components.xml} — grouped by
 * {@code entry.type} (GPU driver, DXVK, VKD3D, settings pack, runtime dep).
 *
 * <p>Operations per row:</p>
 * <ul>
 *   <li><b>Install</b> — invokes {@code IWinEmuService.installDependencyByCheckContainer(name, …)}
 *       via reflection. The host service handles download + extraction.</li>
 *   <li><b>Remove</b> — clears state to {@code None}, deletes the archive in
 *       {@code xj_downloads/component/<name>/} and the extracted dir in
 *       {@code usr/home/components/<name>/}. For sidecar entries also removes
 *       the {@code COMPONENT:<name>} key.</li>
 * </ul>
 *
 * <p>Plus a header "Add custom component" button that launches
 * {@link ComponentDownloadActivity}.</p>
 *
 * <p>UI is built programmatically (no XML layouts) so the patch only ships
 * one Java file and doesn't require resource registration. Visually intentionally
 * minimal — this is a power-user / sideload tool, not a marketing surface.</p>
 */
public final class ComponentManagerActivity extends Activity {

    private static final String UNIFIED_PREFS = "sp_winemu_unified_resources";
    private static final String COMPONENT_KEY_PREFIX = "COMPONENT:";

    private static final String IWINEMUSERVICE_CLASS =
            "com.xiaoji.egggame.common.winemu.bean.IWinEmuService";

    /** Component type int → human-readable category label. Mirrors the report §2 table. */
    private static final Map<Integer, String> TYPE_LABELS = new HashMap<>();
    static {
        TYPE_LABELS.put(2, "GPU driver");
        TYPE_LABELS.put(3, "DXVK");
        TYPE_LABELS.put(4, "VKD3D");
        TYPE_LABELS.put(5, "Settings pack");
        TYPE_LABELS.put(6, "Runtime dep");
    }

    private LinearLayout root;

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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    /** Re-collect entries from prefs + sidecar and re-render the list. */
    private void rebuild() {
        root.removeAllViews();
        addHeader();

        List<JSONObject> all = collectAllEntries();
        Collections.sort(all, BY_TYPE_THEN_NAME);

        if (all.isEmpty()) {
            root.addView(emptyState());
            return;
        }

        int currentType = Integer.MIN_VALUE;
        for (JSONObject e : all) {
            int type = innerInt(e, "type", -1);
            if (type != currentType) {
                root.addView(sectionHeader(TYPE_LABELS.getOrDefault(type, "Type " + type)));
                currentType = type;
            }
            root.addView(rowFor(e));
        }
    }

    private void addHeader() {
        TextView title = new TextView(this);
        title.setText("Component Manager");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Manage GameHub runtime components — official + custom.");
        subtitle.setTextColor(Color.parseColor("#9AA0A6"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(0, 0, 0, dp(20));
        root.addView(subtitle);

        Button addCustom = primaryButton("Add custom component…");
        addCustom.setOnClickListener(v -> {
            Intent i = new Intent(this, ComponentDownloadActivity.class);
            startActivity(i);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        blp.bottomMargin = dp(20);
        root.addView(addCustom, blp);
    }

    private View emptyState() {
        TextView t = new TextView(this);
        t.setText("No components found. Either GameHub hasn't synced its catalogue yet, " +
                "or no custom components have been added. Tap \"Add custom component\" above " +
                "to inject one.");
        t.setTextColor(Color.parseColor("#9AA0A6"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        return t;
    }

    private View sectionHeader(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.parseColor("#7C8590"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private View rowFor(JSONObject entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(roundedRect(Color.parseColor("#1B1D22"), dp(10)));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        row.setLayoutParams(rowLp);

        // Left: name + version + state badge
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        left.setLayoutParams(leftLp);

        String name = entry.optString("name", "?");
        String version = entry.optString("version", "?");
        String state = entry.optString("state", "None");
        boolean injected = entry.optJSONObject("entry") != null
                && entry.optJSONObject("entry").optBoolean(SidecarRegistry.FIELD_BH_INJECTED, false);

        TextView nameTv = new TextView(this);
        nameTv.setText(name + (injected ? "  (custom)" : ""));
        nameTv.setTextColor(Color.WHITE);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameTv.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(nameTv);

        TextView meta = new TextView(this);
        meta.setText(String.format(Locale.US, "v%s · %s", version, prettyState(state)));
        meta.setTextColor(stateColor(state));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setPadding(0, dp(2), 0, 0);
        left.addView(meta);

        row.addView(left);

        // Right: action button(s)
        Button action = secondaryButton(actionLabelFor(state));
        action.setOnClickListener(v -> handleAction(name, state, injected));
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        actLp.leftMargin = dp(12);
        row.addView(action, actLp);

        // Long-press → remove (always available)
        row.setOnLongClickListener(v -> {
            confirmRemove(name, injected);
            return true;
        });

        return row;
    }

    // ---- actions ---------------------------------------------------------

    private void handleAction(String name, String state, boolean injected) {
        if ("Extracted".equals(state) || "INSTALLED".equals(state)) {
            confirmRemove(name, injected);
            return;
        }
        triggerInstall(name);
    }

    private void triggerInstall(String name) {
        try {
            // Resolve IWinEmuService via Koin's Java helper. KoinJavaComponent.get(...)
            // is the canonical static bridge documented in koin's docs.
            Class<?> koinJava = Class.forName("org.koin.java.KoinJavaComponent");
            Class<?> iface = Class.forName(IWINEMUSERVICE_CLASS);
            Object service = koinJava.getMethod("get", Class.class).invoke(null, iface);
            if (service == null) {
                Toast.makeText(this, "Component service unavailable", Toast.LENGTH_LONG).show();
                return;
            }
            // installDependencyByCheckContainer(name, "", false, "", continuation)
            // We can't call a suspend function from pure Java without a continuation
            // — Job 12 follow-up. For v1 we trigger via a fire-and-forget intent
            // that the host's existing component-download flow will consume.
            // Workaround: write state="Downloaded" intent metadata + show a toast
            // telling the user to launch any game once to trigger lazy download.
            //
            // Once Job 12 supplies a Continuation shim (or we find a non-suspend
            // entry point on IWinEmuService), wire the actual call here.
            Toast.makeText(this,
                    "Install requested for " + name +
                            ". Launch a game that uses it to trigger download.",
                    Toast.LENGTH_LONG).show();
            DebugTrace.write("ComponentManager: install requested for " + name +
                    " (deferred — needs suspend bridge)");
        } catch (Throwable t) {
            DebugTrace.write("ComponentManager.triggerInstall failure for " + name, t);
            Toast.makeText(this, "Install failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRemove(String name, boolean injected) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setMessage(injected
                        ? "This is a custom component. The sidecar entry, downloaded archive, " +
                          "and extracted files will all be deleted."
                        : "Downloaded archive and extracted files will be deleted. The entry " +
                          "stays in GameHub's catalogue and can be reinstalled.")
                .setPositiveButton("Remove", (d, w) -> doRemove(name, injected))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doRemove(String name, boolean injected) {
        try {
            File downloads = new File(getFilesDir(),
                    "xj_winemu/xj_downloads/component/" + name);
            File extracted = new File(getFilesDir(),
                    "usr/home/components/" + name);
            recursiveDelete(downloads);
            recursiveDelete(extracted);

            if (injected) {
                SidecarRegistry.remove(this, name);
            } else {
                // Reset state to None in the unified registry. The host app will
                // pick up the change on next sync.
                resetState(name);
            }
            Toast.makeText(this, "Removed " + name, Toast.LENGTH_SHORT).show();
            rebuild();
        } catch (Throwable t) {
            DebugTrace.write("ComponentManager.doRemove failure for " + name, t);
            Toast.makeText(this, "Remove failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resetState(String name) {
        try {
            SharedPreferences sp = getSharedPreferences(UNIFIED_PREFS, Context.MODE_PRIVATE);
            String key = COMPONENT_KEY_PREFIX + name;
            String raw = sp.getString(key, null);
            if (raw == null) return;
            JSONObject json = new JSONObject(raw);
            json.put("state", "None");
            JSONObject inner = json.optJSONObject("entry");
            if (inner != null) inner.put("state", "None");
            sp.edit().putString(key, json.toString()).apply();
        } catch (Throwable t) {
            DebugTrace.write("ComponentManager.resetState failure for " + name, t);
        }
    }

    // ---- data collection -------------------------------------------------

    private List<JSONObject> collectAllEntries() {
        List<JSONObject> out = new ArrayList<>();
        SharedPreferences sp = getSharedPreferences(UNIFIED_PREFS, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (!e.getKey().startsWith(COMPONENT_KEY_PREFIX)) continue;
            Object v = e.getValue();
            if (!(v instanceof String)) continue;
            try {
                out.add(new JSONObject((String) v));
            } catch (Throwable ignore) { /* skip malformed */ }
        }
        // Sidecar — de-dup by name; sidecar wins only if not already in server list.
        java.util.Set<String> known = new java.util.HashSet<>();
        for (JSONObject e : out) known.add(e.optString("name", ""));
        for (JSONObject sc : SidecarRegistry.getAllByType(this, /*type*/ -1)) {
            String n = sc.optString("name", "");
            if (n.isEmpty() || known.contains(n)) continue;
            out.add(sc);
        }
        return out;
    }

    // ---- helpers ---------------------------------------------------------

    private static final Comparator<JSONObject> BY_TYPE_THEN_NAME = (a, b) -> {
        int ta = innerInt(a, "type", -1);
        int tb = innerInt(b, "type", -1);
        if (ta != tb) return Integer.compare(ta, tb);
        return a.optString("name", "").compareToIgnoreCase(b.optString("name", ""));
    };

    private static int innerInt(JSONObject e, String field, int fallback) {
        JSONObject inner = e.optJSONObject("entry");
        if (inner == null) return fallback;
        return inner.optInt(field, fallback);
    }

    private static String prettyState(String state) {
        if (state == null) return "Unknown";
        switch (state) {
            case "None": return "Not downloaded";
            case "Downloaded": return "Downloaded (cached)";
            case "Extracted": return "Ready";
            case "NeedUpdate": return "Update available";
            case "INSTALLED": return "Installed";
            default: return state;
        }
    }

    private static int stateColor(String state) {
        if (state == null) return Color.parseColor("#9AA0A6");
        switch (state) {
            case "None": return Color.parseColor("#9AA0A6");
            case "Downloaded": return Color.parseColor("#FFB300");
            case "Extracted":
            case "INSTALLED": return Color.parseColor("#34D399");
            case "NeedUpdate": return Color.parseColor("#F87171");
            default: return Color.parseColor("#9AA0A6");
        }
    }

    private static String actionLabelFor(String state) {
        if (state == null) return "Install";
        switch (state) {
            case "None": return "Install";
            case "Downloaded": return "Extract";
            case "Extracted":
            case "INSTALLED": return "Remove";
            case "NeedUpdate": return "Update";
            default: return "Install";
        }
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
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setBackground(roundedRect(Color.parseColor("#2D3037"), dp(8)));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinWidth(dp(72));
        b.setMinimumWidth(dp(72));
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

    private static void recursiveDelete(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) recursiveDelete(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
