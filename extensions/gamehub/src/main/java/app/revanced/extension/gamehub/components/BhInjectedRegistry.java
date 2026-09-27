package app.revanced.extension.gamehub.components;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE one place that knows how an injected component is registered for the
 * PC-engine plugin (6.3.1, plugin 107-7). Everything the plugin reads lives
 * here — the JSON record shape, the prefs file it goes into, and the two
 * on-disk locations the plugin expects — so a field tweak from the plugin
 * side is a one-file change. The list screen's own bookkeeping (a small
 * index) is kept in a SEPARATE prefs file so it never leaks into what the
 * plugin decodes.
 *
 * <h3>Plugin record (prefs {@value #PLUGIN_PREFS}, key = component name)</h3>
 * The value is the JSON the plugin's Gson model decodes — the same
 * {@code ComponentRepo{EnvLayerEntity}} shape the unified registry
 * ({@value #UNIFIED_PREFS}, keys {@code COMPONENT:<name>}) uses. Keep EVERY
 * key, blank/null where noted; Gson tolerates extras but the plugin's null
 * checks do not tolerate absences:
 * <pre>
 * {
 *   "category": "COMPONENT",
 *   "depInfo":  null,
 *   "entry": {
 *     "base":          null,
 *     "blurb":         "<optional description>",
 *     "displayName":   "<display name shown in the pickers>",
 *     "downloadUrl":   "",                       // nothing to fetch — local
 *     "fileMd5":       "<md5 of the .tzst>" | "",  // "" for folder installs
 *     "fileName":      "<md5>.tzst" | "",
 *     "fileSize":      <bytes> | 0,
 *     "fileType":      4,
 *     "framework":     "",
 *     "frameworkType": "",
 *     "id":            <negative unique int>,    // never collides with catalog ids (positive)
 *     "isSteam":       0,
 *     "logo":          "",
 *     "name":          "<name>",
 *     "state":         "Downloaded" | "Extracted",
 *     "status":        1,
 *     "subData":       null,
 *     "type":          1 | 2 | 3 | 4 | 6,        // see BhComponentType
 *     "upgradeMsg":    "",
 *     "version":       "<version>",
 *     "versionCode":   1
 *   },
 *   "isBase":  false,
 *   "isDep":   false,
 *   "name":    "<name>",
 *   "state":   "Downloaded" | "Extracted",
 *   "version": "<version>"
 * }
 * </pre>
 *
 * <h3>States and where the bytes go</h3>
 * <ul>
 *   <li>{@code Downloaded} — the archive is parked where a catalog download
 *       lands: {@code <filesDir>/xj_winemu/xj_downloads/component/<name>/<version>/<md5>.tzst}.
 *       The plugin's own extractor + md5 check install it on first use.</li>
 *   <li>{@code Extracted} — the files are already in the plugin's component
 *       layout: {@code <filesDir>/usr/home/components/<name>/}.</li>
 * </ul>
 * A {@code .bh_injected} marker (3.8.1 convention) is dropped next to the
 * bytes: in the component folder for Extracted, in the archive's version
 * folder for Downloaded (we do not pre-create the component folder for an
 * archive — the plugin's extractor owns it).
 *
 * <p>Writes use {@code commit()} (not {@code apply()}) on purpose: the
 * plugin runs in the {@code :pcengine} process and re-reads the XML at its
 * next start, which {@link #reloadPcEngine} forces right after a write.
 */
public final class BhInjectedRegistry {

    private static final String TAG = "BhInjectedRegistry";

    /** Prefs the plugin merges at start (one loader call in the plugin patch). */
    public static final String PLUGIN_PREFS = "sp_bh_injected_components";
    /** The plugin's own unified registry — read-only for us (name collision check). */
    public static final String UNIFIED_PREFS = "sp_winemu_unified_resources";
    /** Our list-screen index (name → {type, source, size, date, ...}). */
    public static final String INDEX_PREFS = "bh_component_manager";

    public static final String STATE_DOWNLOADED = "Downloaded";
    public static final String STATE_EXTRACTED = "Extracted";

    /** Sub-paths under filesDir, verbatim from the device layout. */
    static final String DOWNLOADS_SUBDIR = "xj_winemu/xj_downloads/component";
    static final String COMPONENTS_SUBDIR = "usr/home/components";
    static final String MARKER_FILE = ".bh_injected";

    private BhInjectedRegistry() {}

    // ── Paths ─────────────────────────────────────────────────────────────

    /** {@code <filesDir>/xj_winemu/xj_downloads/component/<name>} */
    public static File downloadDir(Context ctx, String name) {
        return new File(new File(ctx.getFilesDir(), DOWNLOADS_SUBDIR), name);
    }

    /** {@code <filesDir>/xj_winemu/xj_downloads/component/<name>/<version>} */
    public static File downloadVersionDir(Context ctx, String name, String version) {
        return new File(downloadDir(ctx, name), version);
    }

    /** {@code <filesDir>/usr/home/components/<name>} */
    public static File componentDir(Context ctx, String name) {
        return new File(new File(ctx.getFilesDir(), COMPONENTS_SUBDIR), name);
    }

    // ── Name rules ────────────────────────────────────────────────────────

    /** Collapse anything outside {@code [A-Za-z0-9._-]} to '_'; trim dots/underscores. */
    public static String sanitizeName(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        s = s.replaceAll("^[._-]+", "").replaceAll("[._-]+$", "");
        return s;
    }

    /** Versions become a path segment too; the catalog itself uses spaces ("Vulkan 1.4.359"). */
    public static String sanitizeVersion(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("[^A-Za-z0-9 ._-]+", "_").replaceAll("\\s+", " ");
        s = s.replaceAll("^[._-]+", "").replaceAll("[._-]+$", "");
        return s;
    }

    /**
     * Null when the name is free; otherwise a short reason. A name is taken
     * if the plugin's unified registry has it, our injected prefs have it, or
     * either on-disk location already exists (a stale folder would confuse
     * the plugin's extractor).
     */
    public static String nameConflict(Context ctx, String name) {
        if (name == null || name.isEmpty()) return "Name is empty";
        try {
            SharedPreferences unified = ctx.getSharedPreferences(UNIFIED_PREFS, Context.MODE_PRIVATE);
            if (unified.contains("COMPONENT:" + name)) {
                return "A catalog component is already called \"" + name + "\"";
            }
        } catch (Throwable t) {
            Log.w(TAG, "unified registry check failed", t);
        }
        if (pluginPrefs(ctx).contains(name)) {
            return "An injected component is already called \"" + name + "\"";
        }
        if (componentDir(ctx, name).exists()) {
            return "components/" + name + " already exists on disk";
        }
        if (downloadDir(ctx, name).exists()) {
            return "A download folder for \"" + name + "\" already exists";
        }
        return null;
    }

    // ── The plugin record ─────────────────────────────────────────────────

    /** Negative, stable per name, never 0 — catalog ids are positive. */
    static int syntheticId(String name) {
        int h = name.hashCode();
        if (h == Integer.MIN_VALUE) h = 7;
        int abs = Math.abs(h);
        return -(abs == 0 ? 1 : abs);
    }

    /**
     * Builds the record documented in the class javadoc.
     *
     * @param fileMd5  md5 of the archive, or "" for a folder install
     * @param fileSize archive bytes, or 0 for a folder install
     */
    static JSONObject buildRecord(String name, String displayName, String version, int type,
                                  String state, String blurb, String fileMd5, long fileSize)
            throws JSONException {
        boolean archive = fileMd5 != null && !fileMd5.isEmpty();

        JSONObject entry = new JSONObject();
        entry.put("base", JSONObject.NULL);
        entry.put("blurb", blurb == null ? "" : blurb);
        entry.put("displayName", displayName == null || displayName.isEmpty() ? name : displayName);
        entry.put("downloadUrl", "");
        entry.put("fileMd5", archive ? fileMd5 : "");
        entry.put("fileName", archive ? fileMd5 + ".tzst" : "");
        entry.put("fileSize", archive ? fileSize : 0L);
        entry.put("fileType", 4);
        entry.put("framework", "");
        entry.put("frameworkType", "");
        entry.put("id", syntheticId(name));
        entry.put("isSteam", 0);
        entry.put("logo", "");
        entry.put("name", name);
        entry.put("state", state);
        entry.put("status", 1);
        entry.put("subData", JSONObject.NULL);
        entry.put("type", type);
        entry.put("upgradeMsg", "");
        entry.put("version", version);
        entry.put("versionCode", 1);

        JSONObject repo = new JSONObject();
        repo.put("category", "COMPONENT");
        repo.put("depInfo", JSONObject.NULL);
        repo.put("entry", entry);
        repo.put("isBase", false);
        repo.put("isDep", false);
        repo.put("name", name);
        repo.put("state", state);
        repo.put("version", version);
        return repo;
    }

    /**
     * Writes the plugin record + our index entry, and drops the marker file.
     * Synchronous ({@code commit}) so the XML is on disk before the caller
     * bounces {@code :pcengine}. Returns false if anything failed (nothing
     * partial is left registered).
     */
    public static boolean register(Context ctx, Entry e) {
        try {
            JSONObject record = buildRecord(e.name, e.displayName, e.version, e.type, e.state,
                    e.blurb, e.fileMd5, e.fileSize);
            boolean ok = pluginPrefs(ctx).edit().putString(e.name, record.toString()).commit();
            if (!ok) {
                Log.w(TAG, "plugin prefs commit failed for " + e.name);
                return false;
            }
            JSONObject idx = new JSONObject();
            idx.put("name", e.name);
            idx.put("displayName", e.displayName);
            idx.put("version", e.version);
            idx.put("type", e.type);
            idx.put("state", e.state);
            idx.put("source", e.source == null ? "" : e.source);
            idx.put("size", e.fileSize);
            idx.put("date", System.currentTimeMillis());
            idx.put("md5", e.fileMd5 == null ? "" : e.fileMd5);
            idx.put("format", e.format == null ? "" : e.format);
            ok = indexPrefs(ctx).edit().putString(e.name, idx.toString()).commit();
            if (!ok) {
                pluginPrefs(ctx).edit().remove(e.name).commit();
                Log.w(TAG, "index commit failed for " + e.name);
                return false;
            }
            File markerDir = STATE_EXTRACTED.equals(e.state)
                    ? componentDir(ctx, e.name)
                    : downloadVersionDir(ctx, e.name, e.version);
            try {
                if (markerDir.isDirectory()) new File(markerDir, MARKER_FILE).createNewFile();
            } catch (Throwable t) {
                Log.w(TAG, "marker write failed (non-fatal)", t);
            }
            Log.i(TAG, "registered " + e.name + " type=" + e.type + " state=" + e.state
                    + " version=" + e.version);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "register failed for " + (e == null ? "null" : e.name), t);
            return false;
        }
    }

    /**
     * Removes the plugin record, the index entry, and BOTH on-disk locations
     * (archive cache + extracted folder — the plugin may have extracted a
     * Downloaded one by now). Returns true if the prefs were cleaned; file
     * deletion problems are logged but do not fail the removal.
     */
    public static boolean remove(Context ctx, String name) {
        if (name == null || name.isEmpty()) return false;
        boolean ok = pluginPrefs(ctx).edit().remove(name).commit();
        ok &= indexPrefs(ctx).edit().remove(name).commit();
        deleteTree(componentDir(ctx, name));
        deleteTree(downloadDir(ctx, name));
        Log.i(TAG, "removed " + name + " prefsOk=" + ok);
        return ok;
    }

    // ── Index (list screen) ───────────────────────────────────────────────

    /** All injected components from the index, in name order. */
    public static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        Map<String, ?> all = indexPrefs(ctx).getAll();
        List<String> names = new ArrayList<>(all.keySet());
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        for (String n : names) {
            Object v = all.get(n);
            if (!(v instanceof String)) continue;
            try {
                JSONObject o = new JSONObject((String) v);
                Entry e = new Entry();
                e.name = o.optString("name", n);
                e.displayName = o.optString("displayName", e.name);
                e.version = o.optString("version", "");
                e.type = o.optInt("type", BhComponentType.TYPE_LIBRARY);
                e.state = o.optString("state", STATE_DOWNLOADED);
                e.source = o.optString("source", "");
                e.fileSize = o.optLong("size", 0L);
                e.date = o.optLong("date", 0L);
                e.fileMd5 = o.optString("md5", "");
                e.format = o.optString("format", "");
                if (e.format.isEmpty()) {   // records from the first cut: .tzst had an md5, folders did not
                    e.format = e.fileMd5.isEmpty() ? "folder" : "tzst";
                }
                out.add(e);
            } catch (JSONException ex) {
                Log.w(TAG, "bad index entry " + n, ex);
            }
        }
        return out;
    }

    // ── :pcengine reload ──────────────────────────────────────────────────

    /**
     * Kills the plugin process so it re-reads the registries on next use. The
     * process is ours (same uid) so {@code Process.killProcess} is allowed
     * without any permission. Returns the number of processes killed.
     */
    public static int reloadPcEngine(Context ctx) {
        int killed = 0;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs =
                    am == null ? null : am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.processName != null && p.processName.endsWith(":pcengine")) {
                        Log.i(TAG, "killing " + p.processName + " pid=" + p.pid);
                        android.os.Process.killProcess(p.pid);
                        killed++;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "reloadPcEngine failed", t);
        }
        return killed;
    }

    /** {@link #reloadPcEngine} + the user-facing toast. Call on the UI thread. */
    public static void reloadPcEngineWithToast(Context ctx) {
        reloadPcEngine(ctx);
        Toast.makeText(ctx, "PC engine reloaded — reopen the game settings",
                Toast.LENGTH_LONG).show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    static SharedPreferences pluginPrefs(Context ctx) {
        return ctx.getSharedPreferences(PLUGIN_PREFS, Context.MODE_PRIVATE);
    }

    static SharedPreferences indexPrefs(Context ctx) {
        return ctx.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE);
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        try {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) deleteTree(k);
            }
            if (!f.delete()) Log.w(TAG, "could not delete " + f);
        } catch (Throwable t) {
            Log.w(TAG, "delete failed " + f, t);
        }
    }

    /** One injected component, as the registry and the index see it. */
    public static final class Entry {
        public String name;
        public String displayName;
        public String version;
        public int type;
        public String state;
        public String blurb;
        /** Original path the user picked (index only, for the list screen). */
        public String source;
        /** Archive md5 or "" for folders. */
        public String fileMd5 = "";
        /** Archive bytes, or the folder's total bytes for Extracted (index/display only; the record gets 0). */
        public long fileSize;
        public long date;
        /** Source format tag for the list badge: tzst, wcp, zip, tar, folder (index only). */
        public String format = "";
    }
}
