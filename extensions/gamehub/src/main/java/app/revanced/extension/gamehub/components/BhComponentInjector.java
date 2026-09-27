package app.revanced.extension.gamehub.components;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Injects a component the user picked (or a caller downloaded) into the PC
 * engine's component paths and registers it for the plugin.
 *
 * <h3>The two UI-free entry points</h3>
 * <ol>
 *   <li>{@link #inspect(File)} — worker thread. Looks at the file / folder
 *       and returns an {@link Inspection}: how it will be handled
 *       ({@link Route}), the detected category, suggested name / display
 *       name / version / blurb (from {@code meta.json} / {@code profile.json}
 *       when present), the entry list, non-blocking warnings and blocking
 *       problems. {@link Inspection#layoutFor(int)} previews the on-disk
 *       mapping for a category.</li>
 *   <li>{@link #injectFile(Context, File, ComponentSpec, ProgressListener)}
 *       — worker thread. Does the copy / extraction, validates the layout,
 *       moves it into place, writes the {@link BhInjectedRegistry} record +
 *       {@code .bh_injected} marker, and returns a {@link Result}. Nothing
 *       half-done is left on disk on failure. It does NOT bounce
 *       {@code :pcengine} — call {@link BhInjectedRegistry#reloadPcEngine}
 *       (or the toast variant) when the batch is done.</li>
 * </ol>
 * {@link #start(Activity, File, Callback)} is the picker's caller of the two:
 * inspect → confirm dialog (category / name / display name / version
 * editable) → inject with a progress dialog → reload toast.
 *
 * <h3>Routes</h3>
 * <ul>
 *   <li>{@link Route#PARK_TZST} — a {@code .tzst} that really is tar + zstd:
 *       parked verbatim as {@code xj_downloads/component/<name>/<version>/<md5>.tzst},
 *       state {@code Downloaded}; the plugin's extractor installs it on first
 *       use (same as a catalog download). Unchanged from the first cut.</li>
 *   <li>{@link Route#EXTRACT} — a {@code .wcp} (tar + zstd / xz / gzip / plain)
 *       or a {@code .zip} (adrenotools driver package, GitHub release): the
 *       entries are mapped by {@link BhComponentLayout} onto the plugin's
 *       folder shape, extracted into a temp dir under filesDir, validated,
 *       then renamed into {@code usr/home/components/<name>/}; state
 *       {@code Extracted}, {@code fileMd5 ""}, {@code fileSize 0} in the
 *       record (the loader accepts that, as for folders).</li>
 *   <li>{@link Route#FOLDER} — an already-extracted folder, copied into
 *       {@code usr/home/components/<name>/} as-is (driver library renamed per
 *       3.8.1 "Fix 2"), state {@code Extracted}.</li>
 * </ul>
 * The container is decided by magic bytes ({@link BhArchiveReader#detect}),
 * never by extension — a {@code .tzst} that is not zstd takes the EXTRACT
 * route instead of failing.
 */
public final class BhComponentInjector {

    private static final String TAG = "BhComponentInjector";
    private static final String DRIVER_LIB = BhComponentLayout.DRIVER_LIB;
    /** Extraction staging area (same filesystem as the component root → atomic rename). */
    private static final String TMP_SUBDIR = "bh_component_tmp";
    private static final long TMP_STALE_MS = 60L * 60 * 1000;
    private static final long FREE_SPACE_MARGIN = 64L * 1024 * 1024;

    interface Callback {
        /** {@code changed} = something was registered (the caller refreshes its list). */
        void onDone(boolean changed);
    }

    /** Progress for long copies; called from the worker thread, may be null. */
    public interface ProgressListener {
        void onProgress(String phase, long done, long total);
    }

    public enum Route { PARK_TZST, EXTRACT, FOLDER, UNREADABLE }

    /** What {@link #inspect} learned. Everything a confirm UI or an online-repo flow needs. */
    public static final class Inspection {
        public File source;
        public boolean isDir;
        public Route route = Route.UNREADABLE;
        /** "tar + zstd", "zip", "folder", ... */
        public String container = "";
        /** Short tag for the registry index / list badge: tzst, wcp, zip, tar, folder. */
        public String formatTag = "";
        public int detectedType = BhComponentType.TYPE_LIBRARY;
        /** True when the contents were not decisive and the type came from the name only. */
        public boolean typeFromNameOnly;
        public String suggestedName = "";
        public String displayName = "";
        public String version = "Injected";
        public String blurb = "";
        public long sourceBytes;
        public List<String> entries = Collections.emptyList();
        public List<String> warnings = new ArrayList<>();
        /** Blocking: path escapes, links, oversize, unreadable. Empty = injectable. */
        public List<String> problems = new ArrayList<>();
        BhTzstReader.Sniff sniff;

        public boolean canInject() {
            return route != Route.UNREADABLE && problems.isEmpty();
        }

        /** The on-disk mapping the EXTRACT route would apply for {@code type} (null on other routes). */
        public BhComponentLayout.Mapping layoutFor(int type) {
            if (route != Route.EXTRACT || sniff == null) return null;
            return BhComponentLayout.plan(type, BhArchiveReader.files(sniff.entries), sniff.metaJson);
        }
    }

    /** What the caller decided (defaults come from {@link #from(Inspection)}). */
    public static final class ComponentSpec {
        public String name;
        public String displayName;
        public String version;
        public int type;
        public String blurb = "";
        /** Shown in the list as "from …"; defaults to the source path. */
        public String source;

        public static ComponentSpec from(Inspection i) {
            ComponentSpec s = new ComponentSpec();
            s.name = i.suggestedName;
            s.displayName = i.displayName;
            s.version = i.version;
            s.type = i.detectedType;
            s.blurb = i.blurb;
            s.source = i.source == null ? "" : i.source.getAbsolutePath();
            return s;
        }
    }

    public static final class Result {
        public boolean ok;
        public String error;
        /** Component folder (EXTRACT / FOLDER) or the parked archive (PARK_TZST). */
        public File installed;
        public BhInjectedRegistry.Entry entry;

        static Result fail(String why) {
            Result r = new Result();
            r.error = why;
            return r;
        }
    }

    private BhComponentInjector() {}

    // ── Entry point 1: inspect ────────────────────────────────────────────

    /** Worker thread. Never throws; an unreadable source comes back with {@code route == UNREADABLE}. */
    public static Inspection inspect(File source) {
        Inspection i = new Inspection();
        i.source = source;
        if (source == null || !source.exists()) {
            i.problems.add("nothing to inject");
            return i;
        }
        i.isDir = source.isDirectory();
        try {
            BhTzstReader.Sniff s = i.isDir ? BhTzstReader.sniffFolder(source) : BhTzstReader.sniffArchive(source);
            i.sniff = s;
            i.entries = Collections.unmodifiableList(s.entries);
            i.sourceBytes = i.isDir ? s.totalBytes : source.length();
            String fileName = source.getName();
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (i.isDir) {
                i.route = Route.FOLDER;
                i.container = "folder";
                i.formatTag = "folder";
            } else if (lower.endsWith(".tzst") && s.container == BhArchiveReader.Container.ZSTD) {
                // Parked verbatim, so an unreadable sniff only costs the category hint (as before).
                i.route = Route.PARK_TZST;
                i.container = BhArchiveReader.label(s.container);
                i.formatTag = "tzst";
                if (s.unreadable) {
                    i.warnings.add("Could not look inside the archive — the category is guessed "
                            + "from the file name. Check it.");
                }
            } else if (s.container == BhArchiveReader.Container.UNKNOWN || s.unreadable) {
                i.route = Route.UNREADABLE;
                i.container = BhArchiveReader.label(s.container);
                i.problems.add(s.container == BhArchiveReader.Container.UNKNOWN
                        ? "not a zstd / xz / gzip tar or a zip"
                        : (s.container == BhArchiveReader.Container.ZSTD
                            ? "the host's zstd decoder could not be reached (repack as tar + xz)"
                            : "could not read the archive"));
            } else {
                i.route = Route.EXTRACT;
                i.container = BhArchiveReader.label(s.container);
                i.formatTag = lower.endsWith(".wcp") ? "wcp"
                        : lower.endsWith(".zip") ? "zip"
                        : s.container == BhArchiveReader.Container.ZIP ? "zip" : "tar";
                if (lower.endsWith(".tzst")) {
                    i.warnings.add("Named .tzst but " + i.container + " inside — it will be extracted, not parked.");
                }
            }
            i.problems.addAll(s.problems);

            String base = i.isDir ? fileName : BhComponentType.stripExt(fileName);
            int byProfile = BhComponentType.typeFromProfile(s.profileJson);
            int byContent = BhComponentType.detectFromEntries(s.entries);
            int byName = BhComponentType.detectFromName(base);
            i.detectedType = byProfile != 0 ? byProfile
                    : byContent != 0 ? byContent
                    : byName != 0 ? byName : BhComponentType.TYPE_LIBRARY;
            i.typeFromNameOnly = byProfile == 0 && byContent == 0;
            String profileType = BhComponentType.profileTypeName(s.profileJson);
            if (profileType != null && byProfile == 0) {
                i.warnings.add("profile.json says type \"" + profileType + "\" — that is not a "
                        + "component the PC engine can use from here (Wine packages go through "
                        + "the catalog).");
            }
            i.suggestedName = BhInjectedRegistry.sanitizeName(base);
            String dn = BhComponentType.displayNameFromDescriptor(s.metaJson, s.profileJson);
            i.displayName = dn != null ? dn : base;
            String v = BhComponentType.versionFromDescriptor(s.metaJson, s.profileJson);
            i.version = BhInjectedRegistry.sanitizeVersion(v != null ? v : "Injected");
            if (i.version.isEmpty()) i.version = "Injected";
            i.blurb = BhComponentType.blurbFromDescriptor(s.metaJson, s.profileJson);

            if (!i.isDir && i.route == Route.PARK_TZST && s.singleTopDir != null) {
                i.warnings.add("Everything sits under \"" + s.singleTopDir + "/\". The PC engine "
                        + "uses a .tzst as-is, so the component may not be found. Repack it flat "
                        + "if it does not show up.");
            }
            if (i.typeFromNameOnly && i.route != Route.UNREADABLE && !s.unreadable) {
                i.warnings.add("Contents were not decisive — the category is guessed from the name. Check it.");
            }
        } catch (Throwable t) {
            Log.w(TAG, "inspect failed", t);
            i.route = Route.UNREADABLE;
            i.problems.add("could not read: " + t.getMessage());
        }
        return i;
    }

    // ── Entry point 2: inject ─────────────────────────────────────────────

    /**
     * Worker thread. Re-inspects {@code source} (the caller's inspection may
     * be stale), checks the name is free, then runs the route. On any
     * failure the temp dir and any partial destination are removed and the
     * registry is untouched.
     */
    public static Result injectFile(Context ctx, File source, ComponentSpec spec, ProgressListener listener) {
        if (spec == null) return Result.fail("no spec");
        String name = BhInjectedRegistry.sanitizeName(spec.name);
        if (name.isEmpty()) return Result.fail("name is empty");
        String version = BhInjectedRegistry.sanitizeVersion(spec.version);
        if (version.isEmpty()) version = "Injected";
        String conflict = BhInjectedRegistry.nameConflict(ctx, name);
        if (conflict != null) return Result.fail(conflict);

        Inspection i = inspect(source);
        if (!i.canInject()) {
            return Result.fail(i.problems.isEmpty() ? "unreadable" : i.problems.get(0));
        }
        spec.name = name;
        spec.version = version;
        if (spec.displayName == null || spec.displayName.trim().isEmpty()) spec.displayName = name;
        if (spec.source == null) spec.source = source.getAbsolutePath();

        Result r;
        try {
            switch (i.route) {
                case FOLDER:    r = injectFolder(ctx, i, spec, listener); break;
                case PARK_TZST: r = injectArchive(ctx, i, spec, listener); break;
                case EXTRACT:   r = injectExtract(ctx, i, spec, listener); break;
                default:        r = Result.fail("unreadable");
            }
        } catch (Throwable t) {
            Log.w(TAG, "inject failed", t);
            r = Result.fail(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
        if (!r.ok) {
            // Leave nothing half-registered on disk.
            BhInjectedRegistry.deleteTree(BhInjectedRegistry.componentDir(ctx, name));
            BhInjectedRegistry.deleteTree(BhInjectedRegistry.downloadDir(ctx, name));
        }
        return r;
    }

    // ── Picker flow (UI) ──────────────────────────────────────────────────

    static void start(final Activity host, final File picked, final Callback cb) {
        if (picked == null || !picked.exists()) {
            Toast.makeText(host, "Nothing to inject", Toast.LENGTH_SHORT).show();
            if (cb != null) cb.onDone(false);
            return;
        }
        final ProgressDialog pd = new ProgressDialog(host);
        pd.setMessage("Inspecting " + picked.getName() + " …");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            final Inspection insp = inspect(picked);
            host.runOnUiThread(() -> {
                dismissQuietly(pd);
                if (host.isFinishing() || host.isDestroyed()) return;
                if (insp.route == Route.UNREADABLE) {
                    Toast.makeText(host, "Could not read " + picked.getName()
                            + (insp.problems.isEmpty() ? "" : ": " + insp.problems.get(0)),
                            Toast.LENGTH_LONG).show();
                    if (cb != null) cb.onDone(false);
                    return;
                }
                showConfirm(host, insp, cb);
            });
        }, "bh-component-inspect").start();
    }

    private static void showConfirm(final Activity host, final Inspection insp, final Callback cb) {
        final int pad = BhComponentUi.dp(host, 16);
        LinearLayout content = BhComponentUi.column(host);
        content.setPadding(pad, BhComponentUi.dp(host, 8), pad, 0);

        content.addView(label(host, "Source"));
        content.addView(BhComponentUi.text(host, insp.source.getAbsolutePath(), 12f, BhComponentUi.TEXT2, false));
        content.addView(BhComponentUi.text(host,
                (insp.isDir ? "Folder" : insp.container) + " · " + BhComponentUi.humanSize(insp.sourceBytes)
                        + " · " + insp.entries.size() + " entries",
                11f, BhComponentUi.MUTED, false));

        for (String p : insp.problems) content.addView(warn(host, "Blocked: " + p));
        for (String w : insp.warnings) content.addView(warn(host, w));

        content.addView(label(host, "Category"));
        final Spinner typeSpinner = new Spinner(host);
        String[] labels = new String[BhComponentType.SELECTABLE.length];
        for (int i = 0; i < labels.length; i++) labels[i] = BhComponentType.label(BhComponentType.SELECTABLE[i]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(host,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(adapter);
        typeSpinner.setSelection(BhComponentType.indexInSelectable(insp.detectedType));
        content.addView(typeSpinner, BhComponentUi.lp(-1, -2));

        content.addView(label(host, "Name (folder / registry key)"));
        final EditText nameEt = field(host, insp.suggestedName);
        content.addView(nameEt);

        content.addView(label(host, "Display name"));
        final EditText displayEt = field(host, insp.displayName);
        content.addView(displayEt);

        content.addView(label(host, "Version"));
        final EditText versionEt = field(host, insp.version);
        content.addView(versionEt);

        // Layout preview (EXTRACT only): follows the category spinner.
        final TextView layoutTv = BhComponentUi.text(host, "", 11f, BhComponentUi.MUTED, false);
        final TextView layoutWarn = BhComponentUi.text(host, "", 12f, BhComponentUi.AMBER, false);
        if (insp.route == Route.EXTRACT) {
            content.addView(label(host, "Will be written as"));
            content.addView(layoutTv);
            content.addView(layoutWarn);
            typeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                    fillLayoutPreview(insp, BhComponentType.SELECTABLE[pos], layoutTv, layoutWarn);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });
            fillLayoutPreview(insp, insp.detectedType, layoutTv, layoutWarn);
        }

        content.addView(BhComponentUi.text(host, routeBlurb(insp.route), 11f, BhComponentUi.MUTED, false));
        content.addView(BhComponentUi.spacer(host, 8));

        ScrollView scroll = new ScrollView(host);
        scroll.addView(content);

        final AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle("Inject component")
                .setView(scroll)
                .setPositiveButton("Inject", null)   // wired below so validation can keep it open
                .setNegativeButton(android.R.string.cancel, (d, w) -> { if (cb != null) cb.onDone(false); })
                .create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(insp.canInject());
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            ComponentSpec spec = ComponentSpec.from(insp);
            spec.name = BhInjectedRegistry.sanitizeName(nameEt.getText().toString());
            spec.displayName = displayEt.getText().toString().trim();
            spec.version = BhInjectedRegistry.sanitizeVersion(versionEt.getText().toString());
            spec.type = BhComponentType.SELECTABLE[typeSpinner.getSelectedItemPosition()];
            if (spec.name.isEmpty()) {
                Toast.makeText(host, "Name is empty (letters, digits, . _ - only)", Toast.LENGTH_SHORT).show();
                return;
            }
            String conflict = BhInjectedRegistry.nameConflict(host, spec.name);
            if (conflict != null) {
                Toast.makeText(host, conflict, Toast.LENGTH_LONG).show();
                return;
            }
            if (insp.route == Route.EXTRACT) {
                BhComponentLayout.Mapping m = insp.layoutFor(spec.type);
                if (m != null && m.error != null) {
                    Toast.makeText(host, "Cannot lay out as " + BhComponentType.label(spec.type)
                            + ": " + m.error, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            dialog.dismiss();
            runInject(host, insp, spec, cb);
        });
    }

    private static void fillLayoutPreview(Inspection insp, int type, TextView tv, TextView warnTv) {
        BhComponentLayout.Mapping m = insp.layoutFor(type);
        if (m == null) {
            tv.setText("");
            warnTv.setText("");
            return;
        }
        if (m.error != null) {
            tv.setText("");
            warnTv.setText("Cannot lay out as " + BhComponentType.label(type) + ": " + m.error);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : m.preview(8)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        tv.setText(sb.toString());
        StringBuilder w = new StringBuilder();
        for (String s : m.warnings) {
            if (w.length() > 0) w.append('\n');
            w.append(s);
        }
        warnTv.setText(w.toString());
    }

    private static String routeBlurb(Route r) {
        switch (r) {
            case FOLDER:
                return "Will be copied to usr/home/components/<name>/ and registered as Extracted.";
            case PARK_TZST:
                return "Will be copied to xj_downloads/component/<name>/<version>/<md5>.tzst and "
                        + "registered as Downloaded; the PC engine extracts it on first use.";
            case EXTRACT:
                return "Will be extracted to usr/home/components/<name>/ in the layout above and "
                        + "registered as Extracted.";
            default:
                return "";
        }
    }

    private static TextView label(Activity host, String s) {
        TextView tv = BhComponentUi.text(host, s, 11f, BhComponentUi.MUTED, true);
        tv.setPadding(0, BhComponentUi.dp(host, 10), 0, BhComponentUi.dp(host, 2));
        return tv;
    }

    private static EditText field(Activity host, String initial) {
        EditText et = new EditText(host);
        et.setText(initial == null ? "" : initial);
        et.setSingleLine(true);
        et.setTextSize(14f);
        return et;
    }

    private static TextView warn(Activity host, String s) {
        TextView tv = BhComponentUi.text(host, s, 12f, BhComponentUi.AMBER, false);
        tv.setPadding(0, BhComponentUi.dp(host, 6), 0, 0);
        return tv;
    }

    private static void runInject(final Activity host, final Inspection insp, final ComponentSpec spec,
                                  final Callback cb) {
        final ProgressDialog pd = new ProgressDialog(host);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMessage((insp.route == Route.EXTRACT ? "Extracting " : insp.route == Route.FOLDER ? "Copying " : "Copying + hashing ")
                + spec.name + " …");
        pd.setCancelable(false);
        pd.setMax(1000);
        pd.show();

        new Thread(() -> {
            final Result r = injectFile(host, insp.source, spec, (phase, done, total) -> {
                if (total > 0) pd.setProgress((int) Math.min(1000L, done * 1000L / total));   // posts internally
            });
            host.runOnUiThread(() -> {
                dismissQuietly(pd);
                if (host.isFinishing() || host.isDestroyed()) return;
                if (r.ok) {
                    Toast.makeText(host, "Added: " + spec.displayName, Toast.LENGTH_SHORT).show();
                    BhInjectedRegistry.reloadPcEngineWithToast(host);
                } else {
                    Toast.makeText(host, "Injection failed" + (r.error != null ? ": " + r.error : ""),
                            Toast.LENGTH_LONG).show();
                }
                if (cb != null) cb.onDone(r.ok);
            });
        }, "bh-component-inject").start();
    }

    // ── Route: PARK_TZST ──────────────────────────────────────────────────

    /** Archive: stream-copy into the download cache while hashing, rename to {@code <md5>.tzst}. */
    private static Result injectArchive(Context ctx, Inspection insp, ComponentSpec spec, ProgressListener pl)
            throws Exception {
        File verDir = BhInjectedRegistry.downloadVersionDir(ctx, spec.name, spec.version);
        if (!verDir.isDirectory() && !verDir.mkdirs()) throw new IOException("cannot create " + verDir);
        File tmp = new File(verDir, ".copying-" + System.currentTimeMillis() + ".tzst");
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        long total = insp.source.length();
        long done = 0;
        byte[] buf = new byte[1 << 18];
        try (InputStream in = new FileInputStream(insp.source);
             OutputStream out = new FileOutputStream(tmp)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md5.update(buf, 0, n);
                done += n;
                progress(pl, "Copying + hashing", done, total);
            }
            out.flush();
        }
        String hex = toHex(md5.digest());
        File dest = new File(verDir, hex + ".tzst");
        if (dest.exists() && !dest.delete()) throw new IOException("cannot replace " + dest);
        if (!tmp.renameTo(dest)) throw new IOException("cannot rename to " + dest);

        BhInjectedRegistry.Entry e = entryFor(spec, insp, BhInjectedRegistry.STATE_DOWNLOADED);
        e.fileMd5 = hex;
        e.fileSize = dest.length();
        if (!BhInjectedRegistry.register(ctx, e)) throw new IOException("registry write failed");
        Log.i(TAG, "archive parked at " + dest);
        Result r = new Result();
        r.ok = true;
        r.installed = dest;
        r.entry = e;
        return r;
    }

    // ── Route: FOLDER ─────────────────────────────────────────────────────

    /** Folder: recursive copy into the component layout, driver library rename, register Extracted. */
    private static Result injectFolder(Context ctx, Inspection insp, ComponentSpec spec, ProgressListener pl)
            throws Exception {
        File dest = BhInjectedRegistry.componentDir(ctx, spec.name);
        if (dest.exists()) throw new IOException(dest + " already exists");
        if (!dest.mkdirs()) throw new IOException("cannot create " + dest);
        long total = Math.max(1, insp.sourceBytes);
        long[] done = { 0 };
        copyTree(insp.source, dest, done, total, pl, 0);

        if (spec.type == BhComponentType.TYPE_DRIVER) {
            String lib = BhComponentType.libraryNameFromMeta(insp.sniff.metaJson);
            if (lib != null && !DRIVER_LIB.equals(lib)) {
                File from = new File(dest, lib);
                File to = new File(dest, DRIVER_LIB);
                if (from.isFile() && !to.exists() && !from.renameTo(to)) {
                    Log.w(TAG, "driver library rename failed: " + from + " -> " + to);
                }
            }
        }

        BhInjectedRegistry.Entry e = entryFor(spec, insp, BhInjectedRegistry.STATE_EXTRACTED);
        e.fileMd5 = "";
        e.fileSize = insp.sourceBytes;
        if (!BhInjectedRegistry.register(ctx, e)) throw new IOException("registry write failed");
        Log.i(TAG, "folder installed at " + dest);
        Result r = new Result();
        r.ok = true;
        r.installed = dest;
        r.entry = e;
        return r;
    }

    // ── Route: EXTRACT ────────────────────────────────────────────────────

    /**
     * .wcp / .zip: plan the layout, extract the mapped entries into a temp
     * dir (same filesystem), fix up descriptors, validate, rename into place.
     */
    private static Result injectExtract(Context ctx, Inspection insp, ComponentSpec spec, ProgressListener pl)
            throws Exception {
        final BhTzstReader.Sniff s = insp.sniff;
        final BhComponentLayout.Mapping m = BhComponentLayout.plan(spec.type,
                BhArchiveReader.files(s.entries), s.metaJson);
        if (m.error != null) return Result.fail(m.error);
        for (String w : m.warnings) Log.i(TAG, "layout: " + w);

        File tmpRoot = new File(ctx.getFilesDir(), TMP_SUBDIR);
        sweepStale(tmpRoot);
        if (!tmpRoot.isDirectory() && !tmpRoot.mkdirs()) throw new IOException("cannot create " + tmpRoot);
        long usable = tmpRoot.getUsableSpace();
        if (usable > 0 && s.totalBytes + FREE_SPACE_MARGIN > usable) {
            return Result.fail("not enough free space (" + BhComponentUi.humanSize(s.totalBytes)
                    + " needed, " + BhComponentUi.humanSize(usable) + " free)");
        }
        final File tmp = new File(tmpRoot, spec.name + "-" + System.currentTimeMillis());
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);

        final long total = Math.max(1, s.totalBytes);
        final long[] written = { 0 };
        final byte[] buf = new byte[1 << 18];
        try {
            BhArchiveReader.extract(insp.source, s.container, (path, size, body) -> {
                String dst = m.files.get(path);
                if (dst == null) return;                        // not part of the layout (walker skips the body)
                File out = new File(tmp, dst);
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = body.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        written[0] += n;
                        if (written[0] > BhArchiveReader.MAX_TOTAL_BYTES) {
                            throw new BhTzstReader.UnsafeEntryException("archive larger than "
                                    + BhComponentUi.humanSize(BhArchiveReader.MAX_TOTAL_BYTES));
                        }
                        progress(pl, "Extracting", written[0], total);
                    }
                }
                // The plugin's own installs are rwxrwx--x; box64 needs the x bit, the rest do not mind.
                out.setReadable(true, false);
                out.setExecutable(true, false);
            });

            // Descriptor fix-ups.
            File meta = new File(tmp, BhComponentLayout.META);
            if (spec.type == BhComponentType.TYPE_DRIVER && m.driverRenamedFrom != null && meta.isFile()) {
                String text = readText(meta);
                if (text != null) writeText(meta, BhComponentLayout.rewriteMetaLibraryName(text));
            }
            if (m.synthesizeProfile) {
                writeText(new File(tmp, BhComponentLayout.PROFILE),
                        BhComponentLayout.synthesizeProfile(spec.type, spec.version, spec.blurb, m));
            }
            String bad = BhComponentLayout.validateInstalled(spec.type, tmp);
            if (bad != null) return Result.fail(bad);

            File dest = BhInjectedRegistry.componentDir(ctx, spec.name);
            File destParent = dest.getParentFile();
            if (destParent != null && !destParent.isDirectory() && !destParent.mkdirs()) {
                throw new IOException("cannot create " + destParent);
            }
            if (dest.exists()) throw new IOException(dest + " already exists");
            if (!tmp.renameTo(dest)) {
                // Different mount after all (should not happen under filesDir): copy, then drop the temp.
                if (!dest.mkdirs()) throw new IOException("cannot create " + dest);
                long[] done = { 0 };
                copyTree(tmp, dest, done, Math.max(1, written[0]), pl, 0);
            }
            dest.setExecutable(true, false);
            dest.setReadable(true, false);

            BhInjectedRegistry.Entry e = entryFor(spec, insp, BhInjectedRegistry.STATE_EXTRACTED);
            e.fileMd5 = "";
            e.fileSize = written[0];
            if (!BhInjectedRegistry.register(ctx, e)) throw new IOException("registry write failed");
            Log.i(TAG, "extracted " + m.files.size() + " files (" + written[0] + " B) to " + dest
                    + (m.driverRenamedFrom != null ? ", " + m.driverRenamedFrom + " -> " + DRIVER_LIB : "")
                    + (m.synthesizeProfile ? ", profile.json generated" : ""));
            Result r = new Result();
            r.ok = true;
            r.installed = dest;
            r.entry = e;
            return r;
        } finally {
            BhInjectedRegistry.deleteTree(tmp);
        }
    }

    /** Temp dirs older than an hour are leftovers of a crash; remove them. */
    private static void sweepStale(File tmpRoot) {
        File[] kids = tmpRoot.listFiles();
        if (kids == null) return;
        long now = System.currentTimeMillis();
        for (File k : kids) {
            if (now - k.lastModified() > TMP_STALE_MS) BhInjectedRegistry.deleteTree(k);
        }
    }

    // ── Shared ────────────────────────────────────────────────────────────

    private static BhInjectedRegistry.Entry entryFor(ComponentSpec spec, Inspection insp, String state) {
        BhInjectedRegistry.Entry e = new BhInjectedRegistry.Entry();
        e.name = spec.name;
        e.displayName = spec.displayName;
        e.version = spec.version;
        e.type = spec.type;
        e.state = state;
        e.blurb = spec.blurb == null ? "" : spec.blurb;
        e.source = spec.source == null ? "" : spec.source;
        e.format = insp.formatTag;
        return e;
    }

    private static void copyTree(File src, File dst, long[] done, long total, ProgressListener pl, int depth)
            throws IOException {
        if (depth > 12) throw new IOException("folder nests too deep: " + src);
        File[] kids = src.listFiles();
        if (kids == null) throw new IOException("cannot list " + src);
        byte[] buf = new byte[1 << 18];
        for (File k : kids) {
            File target = new File(dst, k.getName());
            if (k.isDirectory()) {
                if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create " + target);
                copyTree(k, target, done, total, pl, depth + 1);
            } else {
                try (InputStream in = new FileInputStream(k);
                     OutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done[0] += n;
                        progress(pl, "Copying", done[0], total);
                    }
                }
                target.setReadable(true, false);
                target.setExecutable(true, false);
            }
        }
    }

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 1 << 20)];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File f, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        f.setReadable(true, false);
        f.setExecutable(true, false);
    }

    private static void progress(ProgressListener pl, String phase, long done, long total) {
        if (pl == null || total <= 0) return;
        try { pl.onProgress(phase, done, total); } catch (Throwable ignored) { }
    }

    private static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private static void dismissQuietly(ProgressDialog pd) {
        try { if (pd != null && pd.isShowing()) pd.dismiss(); } catch (Throwable ignored) { }
    }
}
