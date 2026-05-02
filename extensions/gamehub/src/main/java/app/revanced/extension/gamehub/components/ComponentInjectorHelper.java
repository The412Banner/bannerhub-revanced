package app.revanced.extension.gamehub.components;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.json.JSONObject;
import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * BannerHub Component Manager — extraction + sidecar registration.
 *
 * Port of BannerHub 3.5.0 {@code ComponentInjectorHelper.smali} adapted for the
 * GameHub 6.0 sidecar architecture: extraction still writes to
 * {@code files/usr/home/components/<name>/}, but registration goes to
 * {@link SidecarRegistry} instead of GameHub's {@code EmuComponents.D()}.
 *
 * <p>Type ints are 6.0's {@code EnvLayerEntity.type} values:
 * 2 = GPU driver, 3 = DXVK, 4 = VKD3D, 5 = settings pack, 6 = runtime dep.
 */
public final class ComponentInjectorHelper {

    private ComponentInjectorHelper() {}

    public static int getFirstByte(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            int b = in.read();
            return b & 0xff;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String getDisplayName(Context ctx, Uri uri) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String[] proj = { OpenableColumns.DISPLAY_NAME };
            try (Cursor c = cr.query(uri, proj, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String s = c.getString(0);
                    if (s != null && !s.isEmpty()) return s;
                }
            }
            String last = uri.getLastPathSegment();
            return last == null ? "" : last;
        } catch (Exception e) {
            String last = uri.getLastPathSegment();
            return last == null ? "" : last;
        }
    }

    public static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    public static File makeComponentDir(Context ctx, String name) {
        File base = new File(ctx.getFilesDir(), "usr/home/components");
        File dir = new File(base, name);
        dir.mkdirs();
        return dir;
    }

    private static TarArchiveInputStream openTar(Context ctx, Uri uri, int firstByte) throws Exception {
        InputStream raw = ctx.getContentResolver().openInputStream(uri);
        InputStream wrapped = (firstByte == 0x28)
                ? new ZstdInputStreamNoFinalizer(raw)
                : new XZInputStream(raw, -1);
        return new TarArchiveInputStream(wrapped);
    }

    public static String readWcpProfile(Context ctx, Uri uri, int firstByte) {
        try (TarArchiveInputStream tar = openTar(ctx, uri, firstByte)) {
            byte[] buf = new byte[0x400];
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/")) continue;
                if (!name.endsWith("profile.json")) continue;

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = tar.read(buf, 0, buf.length)) > 0) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), "UTF-8");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void extractWcp(Context ctx, Uri uri, int firstByte, File dir, boolean flatten) {
        try (TarArchiveInputStream tar = openTar(ctx, uri, firstByte)) {
            byte[] buf = new byte[0x2000];
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/")) continue;
                if (name.endsWith("profile.json")) continue;

                if (flatten) {
                    int slash = name.lastIndexOf('/');
                    if (slash >= 0) name = name.substring(slash + 1);
                }

                File outFile = new File(dir, name);
                File parent = outFile.getParentFile();
                if (parent != null) parent.mkdirs();

                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    int n;
                    while ((n = tar.read(buf, 0, buf.length)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static String extractZip(Context ctx, Uri uri, File dir) {
        String meta = "";
        try (InputStream raw = ctx.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(raw)) {

            byte[] buf = new byte[0x2000];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/")) {
                    zip.closeEntry();
                    continue;
                }

                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);

                if ("meta.json".equals(name)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int n;
                    while ((n = zip.read(buf, 0, buf.length)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    meta = new String(bos.toByteArray(), "UTF-8");
                } else {
                    File outFile = new File(dir, name);
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        int n;
                        while ((n = zip.read(buf, 0, buf.length)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                }
                zip.closeEntry();
            }
        } catch (Exception ignored) {
            return "";
        }
        return meta;
    }

    /**
     * Build a sidecar entry JSON in the official-registry shape and write it
     * via {@link SidecarRegistry}. Replaces 3.5.0's {@code EmuComponents.D()}
     * call so user-injected components survive API resyncs.
     */
    public static void registerComponent(Context ctx, String name, String version,
                                          String desc, int contentType, String sourceUri) {
        try {
            JSONObject inner = new JSONObject();
            inner.put("category", "COMPONENT");
            inner.put("blurb", desc == null ? "" : desc);
            inner.put("displayName", name);
            inner.put("downloadUrl", "");
            inner.put("fileMd5", "");
            inner.put("fileName", name + ".tzst");
            inner.put("fileSize", 0);
            inner.put("fileType", 4);
            inner.put("framework", "");
            inner.put("frameworkType", "");
            inner.put("id", -1);
            inner.put("isSteam", 0);
            inner.put("logo", "");
            inner.put("name", name);
            inner.put("state", "Extracted");
            inner.put("status", 0);
            inner.put("type", contentType);
            inner.put("upgradeMsg", "");
            inner.put("version", version == null ? "" : version);
            inner.put("versionCode", 1);

            inner.put(SidecarRegistry.FIELD_BH_INJECTED, true);
            inner.put(SidecarRegistry.FIELD_BH_SKIP_MD5, true);
            if (sourceUri != null) {
                inner.put(SidecarRegistry.FIELD_BH_SOURCE_URI, sourceUri);
            }

            JSONObject entry = new JSONObject();
            entry.put("category", "COMPONENT");
            entry.put("depInfo", JSONObject.NULL);
            entry.put("entry", inner);
            entry.put("isBase", false);
            entry.put("isDep", false);
            entry.put("name", name);
            entry.put("state", "Extracted");
            entry.put("version", version == null ? "" : version);

            SidecarRegistry.put(ctx, name, entry);
        } catch (Exception ignored) {
        }
    }

    /**
     * Top-level entry point. Detects the archive format from the first byte,
     * extracts to a fresh component dir, registers in the sidecar, and toasts
     * the user-visible result.
     *
     * @param contentType  6.0 type int (2 GPU / 3 DXVK / 4 VKD3D / 5 settings / 6 dep)
     */
    public static void injectComponent(Context ctx, Uri uri, int contentType) {
        try {
            int firstByte = getFirstByte(ctx, uri);
            String name;
            String version;
            String desc;
            File targetDir;

            if (firstByte == 0x50) {
                // ZIP: name = stripped filename, content extracted flat
                String filename = getDisplayName(ctx, uri);
                name = stripExt(filename);
                targetDir = makeComponentDir(ctx, name);

                String metaJson = extractZip(ctx, uri, targetDir);

                version = name;
                desc = "";

                if (!metaJson.isEmpty()) {
                    JSONObject meta = new JSONObject(metaJson);
                    String drvVer = meta.optString("driverVersion");
                    if (!drvVer.isEmpty()) version = drvVer;
                    desc = meta.optString("description");

                    String libName = meta.optString("libraryName");
                    if (!libName.isEmpty() && !"libvulkan_freedreno.so".equals(libName)) {
                        File from = new File(targetDir, libName);
                        File to = new File(targetDir, "libvulkan_freedreno.so");
                        from.renameTo(to);
                    }
                }
            } else {
                // WCP path (zstd or xz tar)
                String profileJson = readWcpProfile(ctx, uri, firstByte);
                if (profileJson == null) {
                    String filename = getDisplayName(ctx, uri);
                    name = stripExt(filename);
                    version = name;
                    desc = "";
                } else {
                    JSONObject profile = new JSONObject(profileJson);
                    name = profile.optString("versionName");
                    desc = profile.optString("description");
                    if (name.isEmpty()) {
                        String filename = getDisplayName(ctx, uri);
                        name = stripExt(filename);
                    }
                    version = name;
                }

                // Flatten only for FEXCore (6.0 has no FEXCore type — kept for parity, harmless)
                boolean flatten = contentType == 0x5f;
                targetDir = makeComponentDir(ctx, name);
                extractWcp(ctx, uri, firstByte, targetDir, flatten);
            }

            registerComponent(ctx, name, version, desc, contentType, uri.toString());

            Toast.makeText(ctx, "Added to GameHub: " + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = "Injection failed";
            Toast.makeText(ctx, "Error: " + msg, Toast.LENGTH_LONG).show();
        }
    }
}
