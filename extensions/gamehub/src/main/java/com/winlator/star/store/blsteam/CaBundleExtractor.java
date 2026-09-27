package com.winlator.star.store.blsteam;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The native engine (rustls) takes a single PEM trust-bundle FILE for its HTTPS calls. Android's
 * CA store is a directory of hashed-filename PEMs, so this helper concatenates every {@code *.0}
 * there into {@code filesDir/blsteam_cacert.pem} on first run and reuses it afterwards.
 *
 * Sources, in order: {@code /system/etc/security/cacerts} and (Android 14+, mainline conscrypt)
 * {@code /apex/com.android.conscrypt/cacerts}. If neither is readable (some OEM builds, work
 * profiles) it falls back to the bundled {@code assets/blsteam_cacert.pem} (shipped by the
 * GogEngineLibBundlePatch). Returns "" on total failure — the engine then uses its built-in
 * webpki roots.
 */
public final class CaBundleExtractor {

    private static final String TAG = "BH_GOG";
    private static final String OUT_NAME = "blsteam_cacert.pem";
    private static final String ASSET_NAME = "blsteam_cacert.pem";
    private static final String[] SYS_CA_DIRS = {
            "/system/etc/security/cacerts",
            "/apex/com.android.conscrypt/cacerts",
    };
    /** A single empty / stub file means a prior extraction failed; rebuild below this size. */
    private static final long MIN_BUNDLE_BYTES = 1024;

    private CaBundleExtractor() {}

    /** Ensures the bundle exists; returns its absolute path, or "" when nothing could be built. */
    public static String ensureBundle(Context context) {
        if (context == null) return "";
        File out = new File(context.getFilesDir(), OUT_NAME);
        if (out.isFile() && out.length() > MIN_BUNDLE_BYTES) {
            return out.getAbsolutePath();
        }

        List<File> pems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String dirName : SYS_CA_DIRS) {
            File dir = new File(dirName);
            if (!dir.isDirectory()) continue;
            File[] entries = dir.listFiles();
            if (entries == null) continue;
            for (File f : entries) {
                if (f.isFile() && f.getName().endsWith(".0") && seen.add(f.getName())) pems.add(f);
            }
        }

        if (!pems.isEmpty()) {
            int copied = 0;
            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(out), "UTF-8"))) {
                char[] buf = new char[8192];
                for (File f : pems) {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                        int n;
                        while ((n = r.read(buf)) != -1) w.write(buf, 0, n);
                        w.newLine();
                        copied++;
                    } catch (Exception e) {
                        Log.w(TAG, "CA bundle: skipped " + f.getName() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "CA bundle: system-store write failed", e);
            }
            if (out.isFile() && out.length() > MIN_BUNDLE_BYTES) {
                Log.i(TAG, "CA bundle ready from system store: " + out.getAbsolutePath()
                        + " (" + copied + " certs, " + out.length() + " bytes)");
                return out.getAbsolutePath();
            }
        } else {
            Log.w(TAG, "CA bundle: no readable system cacerts dir — using bundled fallback");
        }

        // Fallback: the bundled PEM asset.
        try (InputStream in = context.getAssets().open(ASSET_NAME);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        } catch (Exception e) {
            Log.e(TAG, "CA bundle: asset extraction failed — engine falls back to webpki roots", e);
            out.delete();
            return "";
        }
        if (out.isFile() && out.length() > MIN_BUNDLE_BYTES) {
            Log.i(TAG, "CA bundle ready from asset: " + out.getAbsolutePath()
                    + " (" + out.length() + " bytes)");
            return out.getAbsolutePath();
        }
        out.delete();
        return "";
    }
}
