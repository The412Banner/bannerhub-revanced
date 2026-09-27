package app.revanced.extension.gamehub.components;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.util.Log;
import android.view.Gravity;
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
import java.security.MessageDigest;

/**
 * The inject flow: sniff the pick on a worker thread → confirm dialog (type /
 * name / display name / version are all overridable) → copy + md5 on a worker
 * thread with a progress dialog → {@link BhInjectedRegistry#register} →
 * bounce {@code :pcengine}. Nothing touches the UI thread except the dialogs.
 *
 * <ul>
 *   <li>{@code .tzst} → parked verbatim as
 *       {@code xj_downloads/component/<name>/<version>/<md5>.tzst}, state
 *       {@code Downloaded}; the plugin's extractor installs it on first use
 *       (same as a catalog download).</li>
 *   <li>folder → copied into {@code usr/home/components/<name>/}, state
 *       {@code Extracted}. A driver folder whose {@code meta.json}
 *       {@code libraryName} is not {@code libvulkan_freedreno.so} gets the
 *       library renamed (3.8.1 "Fix 2").</li>
 * </ul>
 */
final class BhComponentInjector {

    private static final String TAG = "BhComponentInjector";
    private static final String DRIVER_LIB = "libvulkan_freedreno.so";

    interface Callback {
        /** {@code changed} = something was registered (the caller refreshes its list). */
        void onDone(boolean changed);
    }

    /** Everything the confirm dialog shows and the worker consumes. */
    private static final class Plan {
        File source;
        boolean isDir;
        BhTzstReader.Sniff sniff;
        int type;
        String name;
        String displayName;
        String version;
        String blurb;
        long sourceBytes;
        boolean typeFromNameOnly;
    }

    private BhComponentInjector() {}

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
            final Plan plan = new Plan();
            plan.source = picked;
            plan.isDir = picked.isDirectory();
            try {
                plan.sniff = plan.isDir
                        ? BhTzstReader.sniffFolder(picked)
                        : BhTzstReader.sniffArchive(picked);
                plan.sourceBytes = plan.isDir ? plan.sniff.totalBytes : picked.length();
                String base = plan.isDir ? picked.getName() : BhComponentType.stripExt(picked.getName());
                int byContent = BhComponentType.detectFromEntries(plan.sniff.entries);
                int byName = BhComponentType.detectFromName(base);
                plan.type = byContent != 0 ? byContent : (byName != 0 ? byName : BhComponentType.TYPE_LIBRARY);
                plan.typeFromNameOnly = byContent == 0;
                plan.name = BhInjectedRegistry.sanitizeName(base);
                String dn = BhComponentType.displayNameFromDescriptor(plan.sniff.metaJson, plan.sniff.profileJson);
                plan.displayName = dn != null ? dn : base;
                String v = BhComponentType.versionFromDescriptor(plan.sniff.metaJson, plan.sniff.profileJson);
                plan.version = BhInjectedRegistry.sanitizeVersion(v != null ? v : "Injected");
                if (plan.version.isEmpty()) plan.version = "Injected";
                plan.blurb = BhComponentType.blurbFromDescriptor(plan.sniff.metaJson, plan.sniff.profileJson);
            } catch (Throwable t) {
                Log.w(TAG, "inspect failed", t);
            }
            host.runOnUiThread(() -> {
                dismissQuietly(pd);
                if (host.isFinishing() || host.isDestroyed()) return;
                if (plan.sniff == null) {
                    Toast.makeText(host, "Could not read " + picked.getName(), Toast.LENGTH_LONG).show();
                    if (cb != null) cb.onDone(false);
                    return;
                }
                if (!plan.isDir && plan.sniff.notZstd) {
                    Toast.makeText(host, picked.getName() + " is not a zstd archive (.tzst = tar + zstd)",
                            Toast.LENGTH_LONG).show();
                    if (cb != null) cb.onDone(false);
                    return;
                }
                showConfirm(host, plan, cb);
            });
        }, "bh-component-inspect").start();
    }

    // ── Confirm dialog ────────────────────────────────────────────────────

    private static void showConfirm(final Activity host, final Plan plan, final Callback cb) {
        final int pad = BhComponentUi.dp(host, 16);
        LinearLayout content = BhComponentUi.column(host);
        content.setPadding(pad, BhComponentUi.dp(host, 8), pad, 0);

        content.addView(label(host, "Source"));
        TextView src = BhComponentUi.text(host, plan.source.getAbsolutePath(), 12f, BhComponentUi.TEXT2, false);
        content.addView(src);
        content.addView(BhComponentUi.text(host,
                (plan.isDir ? "Folder" : "Archive") + " · " + BhComponentUi.humanSize(plan.sourceBytes)
                        + " · " + plan.sniff.entries.size() + " entries",
                11f, BhComponentUi.MUTED, false));

        if (!plan.isDir && plan.sniff.unreadable) {
            content.addView(warn(host, "Could not look inside the archive — the category "
                    + "below is guessed from the file name. Check it."));
        } else if (plan.typeFromNameOnly) {
            content.addView(warn(host, "Contents were not decisive — the category below is "
                    + "guessed from the name. Check it."));
        }
        if (plan.sniff.singleTopDir != null) {
            content.addView(warn(host, "Everything sits under \"" + plan.sniff.singleTopDir
                    + "/\". The PC engine uses the layout as-is, so the component may not "
                    + "be found. Repack it flat if it does not show up."));
        }

        content.addView(label(host, "Category"));
        final Spinner typeSpinner = new Spinner(host);
        String[] labels = new String[BhComponentType.SELECTABLE.length];
        for (int i = 0; i < labels.length; i++) labels[i] = BhComponentType.label(BhComponentType.SELECTABLE[i]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(host,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(adapter);
        typeSpinner.setSelection(BhComponentType.indexInSelectable(plan.type));
        content.addView(typeSpinner, BhComponentUi.lp(-1, -2));

        content.addView(label(host, "Name (folder / registry key)"));
        final EditText nameEt = field(host, plan.name);
        content.addView(nameEt);

        content.addView(label(host, "Display name"));
        final EditText displayEt = field(host, plan.displayName);
        content.addView(displayEt);

        content.addView(label(host, "Version"));
        final EditText versionEt = field(host, plan.version);
        content.addView(versionEt);

        content.addView(BhComponentUi.text(host,
                plan.isDir
                        ? "Will be copied to usr/home/components/<name>/ and registered as Extracted."
                        : "Will be copied to xj_downloads/component/<name>/<version>/<md5>.tzst and "
                          + "registered as Downloaded; the PC engine extracts it on first use.",
                11f, BhComponentUi.MUTED, false));
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
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = BhInjectedRegistry.sanitizeName(nameEt.getText().toString());
            String display = displayEt.getText().toString().trim();
            String version = BhInjectedRegistry.sanitizeVersion(versionEt.getText().toString());
            if (version.isEmpty()) version = "Injected";
            if (name.isEmpty()) {
                Toast.makeText(host, "Name is empty (letters, digits, . _ - only)", Toast.LENGTH_SHORT).show();
                return;
            }
            String conflict = BhInjectedRegistry.nameConflict(host, name);
            if (conflict != null) {
                Toast.makeText(host, conflict, Toast.LENGTH_LONG).show();
                return;
            }
            plan.name = name;
            plan.displayName = display.isEmpty() ? name : display;
            plan.version = version;
            plan.type = BhComponentType.SELECTABLE[typeSpinner.getSelectedItemPosition()];
            dialog.dismiss();
            runInject(host, plan, cb);
        });
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

    // ── Worker ────────────────────────────────────────────────────────────

    private static void runInject(final Activity host, final Plan plan, final Callback cb) {
        final ProgressDialog pd = new ProgressDialog(host);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMessage((plan.isDir ? "Copying " : "Copying + hashing ") + plan.name + " …");
        pd.setCancelable(false);
        pd.setMax(1000);
        pd.show();

        new Thread(() -> {
            String error = null;
            boolean ok = false;
            try {
                if (plan.isDir) ok = injectFolder(host, plan, pd);
                else ok = injectArchive(host, plan, pd);
            } catch (Throwable t) {
                Log.w(TAG, "inject failed", t);
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            if (!ok) {
                // Leave nothing half-registered on disk.
                BhInjectedRegistry.deleteTree(BhInjectedRegistry.componentDir(host, plan.name));
                BhInjectedRegistry.deleteTree(BhInjectedRegistry.downloadDir(host, plan.name));
            }
            final boolean fOk = ok;
            final String fError = error;
            host.runOnUiThread(() -> {
                dismissQuietly(pd);
                if (host.isFinishing() || host.isDestroyed()) return;
                if (fOk) {
                    Toast.makeText(host, "Added: " + plan.displayName, Toast.LENGTH_SHORT).show();
                    BhInjectedRegistry.reloadPcEngineWithToast(host);
                } else {
                    Toast.makeText(host, "Injection failed" + (fError != null ? ": " + fError : ""),
                            Toast.LENGTH_LONG).show();
                }
                if (cb != null) cb.onDone(fOk);
            });
        }, "bh-component-inject").start();
    }

    /** Archive: stream-copy into the download cache while hashing, rename to {@code <md5>.tzst}. */
    private static boolean injectArchive(Activity host, Plan plan, ProgressDialog pd) throws Exception {
        File verDir = BhInjectedRegistry.downloadVersionDir(host, plan.name, plan.version);
        if (!verDir.isDirectory() && !verDir.mkdirs()) throw new IOException("cannot create " + verDir);
        File tmp = new File(verDir, ".copying-" + System.currentTimeMillis() + ".tzst");
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        long total = plan.source.length();
        long done = 0;
        byte[] buf = new byte[1 << 18];
        try (InputStream in = new FileInputStream(plan.source);
             OutputStream out = new FileOutputStream(tmp)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md5.update(buf, 0, n);
                done += n;
                progress(pd, done, total);
            }
            out.flush();
        }
        String hex = toHex(md5.digest());
        File dest = new File(verDir, hex + ".tzst");
        if (dest.exists() && !dest.delete()) throw new IOException("cannot replace " + dest);
        if (!tmp.renameTo(dest)) throw new IOException("cannot rename to " + dest);

        BhInjectedRegistry.Entry e = new BhInjectedRegistry.Entry();
        e.name = plan.name;
        e.displayName = plan.displayName;
        e.version = plan.version;
        e.type = plan.type;
        e.state = BhInjectedRegistry.STATE_DOWNLOADED;
        e.blurb = plan.blurb;
        e.source = plan.source.getAbsolutePath();
        e.fileMd5 = hex;
        e.fileSize = dest.length();
        if (!BhInjectedRegistry.register(host, e)) throw new IOException("registry write failed");
        Log.i(TAG, "archive parked at " + dest);
        return true;
    }

    /** Folder: recursive copy into the component layout, driver library rename, register Extracted. */
    private static boolean injectFolder(Activity host, Plan plan, ProgressDialog pd) throws Exception {
        File dest = BhInjectedRegistry.componentDir(host, plan.name);
        if (dest.exists()) throw new IOException(dest + " already exists");
        if (!dest.mkdirs()) throw new IOException("cannot create " + dest);
        long total = Math.max(1, plan.sourceBytes);
        long[] done = { 0 };
        copyTree(plan.source, dest, done, total, pd, 0);

        if (plan.type == BhComponentType.TYPE_DRIVER) {
            String lib = BhComponentType.libraryNameFromMeta(plan.sniff.metaJson);
            if (lib != null && !DRIVER_LIB.equals(lib)) {
                File from = new File(dest, lib);
                File to = new File(dest, DRIVER_LIB);
                if (from.isFile() && !to.exists() && !from.renameTo(to)) {
                    Log.w(TAG, "driver library rename failed: " + from + " -> " + to);
                }
            }
        }

        BhInjectedRegistry.Entry e = new BhInjectedRegistry.Entry();
        e.name = plan.name;
        e.displayName = plan.displayName;
        e.version = plan.version;
        e.type = plan.type;
        e.state = BhInjectedRegistry.STATE_EXTRACTED;
        e.blurb = plan.blurb;
        e.source = plan.source.getAbsolutePath();
        e.fileMd5 = "";
        e.fileSize = plan.sourceBytes;
        if (!BhInjectedRegistry.register(host, e)) throw new IOException("registry write failed");
        Log.i(TAG, "folder installed at " + dest);
        return true;
    }

    private static void copyTree(File src, File dst, long[] done, long total, ProgressDialog pd, int depth)
            throws IOException {
        if (depth > 12) throw new IOException("folder nests too deep: " + src);
        File[] kids = src.listFiles();
        if (kids == null) throw new IOException("cannot list " + src);
        byte[] buf = new byte[1 << 18];
        for (File k : kids) {
            File target = new File(dst, k.getName());
            if (k.isDirectory()) {
                if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create " + target);
                copyTree(k, target, done, total, pd, depth + 1);
            } else {
                try (InputStream in = new FileInputStream(k);
                     OutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done[0] += n;
                        progress(pd, done[0], total);
                    }
                }
            }
        }
    }

    private static void progress(ProgressDialog pd, long done, long total) {
        if (total <= 0) return;
        final int p = (int) Math.min(1000L, done * 1000L / total);
        pd.setProgress(p);   // ProgressDialog.setProgress is safe off the UI thread (posts internally)
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
