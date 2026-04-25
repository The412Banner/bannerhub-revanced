package app.revanced.extension.gamehub;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * BannerHub Lite — Component Injector.
 *
 * Handles WCP (zstd/XZ tar) and ZIP extraction, then registers the
 * installed component with GameHub 5.1.4's EmuComponents system via
 * reflection so it appears in game-settings component pickers.
 *
 * Reflection targets (5.1.4, obfuscated by R8):
 *   com.xj.winemu.EmuComponents          — singleton via field c (companion) → a()
 *   EmuComponents.C(ComponentRepo)        — puts in HashMap + saves to SharedPreferences
 *   EmuComponents.w(List<String>)         — removes names from HashMap + SharedPreferences
 *   ComponentRepo                         — default-package class, 7-param ctor
 *   com.xj.winemu.api.bean.EnvLayerEntity — 18-param ctor
 *   State (default package)               — enum; Extracted value
 *   com.xj.winemu.bean.DialogSettingListItemEntity — 21-param ctor
 *
 * TarArchiveInputStream obfuscation (5.1.4):
 *   getNextTarEntry() → f()
 *   TarArchiveEntry.getName() → p()
 */
@SuppressWarnings("unused")
public final class ComponentInjectorHelper {

    private static final String TAG = "BannerHub";
    private static final int    BUF = 8192;

    // Content-type ints used by GameHub EmuComponents
    static final int TYPE_GPU_DRIVER = 10;
    static final int TYPE_DXVK      = 12;
    static final int TYPE_VKD3D     = 13;
    static final int TYPE_BOX64     = 94;
    static final int TYPE_FEXCORE   = 95;
    static final int TYPE_TRANSLATOR = 32; // matches Box64 + FEXCore in game settings

    private ComponentInjectorHelper() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called from ComponentManagerActivity after user picks a file (new component, mode=3).
     * Extracts the WCP/ZIP, registers with EmuComponents, stamps .bh_injected.
     */
    public static void injectComponent(Context ctx, Uri uri, int contentType) {
        try {
            File componentsDir = new File(ctx.getFilesDir(), "usr/home/components");
            String displayName = getDisplayName(ctx, uri);
            if (displayName == null || displayName.isEmpty()) displayName = "component";
            // Strip extension for initial folder name
            String folderName = stripExt(displayName);

            File destDir = new File(componentsDir, folderName);
            destDir.mkdirs();

            try (InputStream raw = ctx.getContentResolver().openInputStream(uri)) {
                if (raw == null) throw new IOException("Cannot open URI: " + uri);
                doExtract(raw, destDir, contentType);
            }

            // Read profile.json/meta.json written into destDir to get actual name/version
            ProfileInfo info = readProfile(destDir, contentType);

            // Rename folder if profile has a different canonical name
            if (info.name != null && !info.name.isEmpty() && !info.name.equals(folderName)) {
                File renamed = new File(componentsDir, info.name);
                if (!renamed.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    destDir.renameTo(renamed);
                    destDir = renamed;
                }
                folderName = info.name;
            }

            String version = info.version != null ? info.version : "1.0";
            String desc    = info.desc    != null ? info.desc    : "";

            registerComponent(ctx, folderName, version, desc, contentType);
            new File(destDir, ".bh_injected").createNewFile();

            final String finalName = folderName;
            if (ctx instanceof android.app.Activity) {
                ((android.app.Activity) ctx).runOnUiThread(() ->
                        Toast.makeText(ctx, "Added to GameHub: " + finalName,
                                Toast.LENGTH_SHORT).show());
            }
            Log.d(TAG, "injectComponent: registered " + finalName + " type=" + contentType);
        } catch (Exception e) {
            Log.e(TAG, "injectComponent failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (ctx instanceof android.app.Activity) {
                ((android.app.Activity) ctx).runOnUiThread(() ->
                        Toast.makeText(ctx, "Inject failed: " + msg, Toast.LENGTH_LONG).show());
            }
        }
    }

    /**
     * Called from ComponentDownloadActivity after downloading a file to cache.
     * Folder name is known in advance (from asset filename strip ext).
     */
    public static void injectFromCachedFile(Context ctx, File cachedFile,
                                             String folderName, int contentType) throws Exception {
        File componentsDir = new File(ctx.getFilesDir(), "usr/home/components");
        File destDir = new File(componentsDir, folderName);
        destDir.mkdirs();

        try (InputStream raw = new FileInputStream(cachedFile)) {
            doExtract(raw, destDir, contentType);
        }

        ProfileInfo info = readProfile(destDir, contentType);
        String version = info.version != null ? info.version : "1.0";
        String desc    = info.desc    != null ? info.desc    : "";

        registerComponent(ctx, folderName, version, desc, contentType);
        new File(destDir, ".bh_injected").createNewFile();
        Log.d(TAG, "injectFromCachedFile: registered " + folderName + " type=" + contentType);
    }

    /**
     * Removes a component from EmuComponents (HashMap + SharedPreferences).
     * Called before deleting the component directory.
     */
    public static void unregisterComponent(String name) {
        try {
            Object emuInstance = getEmuComponentsInstance();
            if (emuInstance == null) { Log.w(TAG, "unregisterComponent: no EmuComponents"); return; }
            Method w = emuInstance.getClass().getMethod("w", java.util.List.class);
            w.invoke(emuInstance, Collections.singletonList(name));
            Log.d(TAG, "unregisterComponent: removed " + name);
        } catch (Exception e) {
            Log.e(TAG, "unregisterComponent failed", e);
        }
    }

    /**
     * Injected into GameSettingViewModel$fetchList$1 before setData(v13).
     * Appends locally-registered BH components of matching type into the live list.
     *
     * @param list        the component list about to be delivered to the UI
     * @param contentType the content type the game settings are filtering for
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void appendLocalComponents(List list, int contentType) {
        try {
            Object emuInstance = getEmuComponentsInstance();
            if (emuInstance == null) return;

            Field mapField = emuInstance.getClass().getDeclaredField("a");
            mapField.setAccessible(true);
            HashMap<?, ?> map = (HashMap<?, ?>) mapField.get(emuInstance);
            if (map == null) return;

            Class<?> repoClass   = Class.forName("ComponentRepo");
            Method   getEntry    = repoClass.getMethod("getEntry");
            Method   getVersion  = repoClass.getMethod("getVersion");
            Method   getName     = repoClass.getMethod("getName");

            Class<?> entityClass     = Class.forName("com.xj.winemu.api.bean.EnvLayerEntity");
            Method   getIdM          = entityClass.getMethod("getId");
            Method   getTypeM        = entityClass.getMethod("getType");
            Method   getDisplayNameM = entityClass.getMethod("getDisplayName");
            Method   getNameM        = entityClass.getMethod("getName");
            Method   getVersionM     = entityClass.getMethod("getVersion");
            Method   getVersionCodeM = entityClass.getMethod("getVersionCode");
            Method   getLogoM        = entityClass.getMethod("getLogo");
            Method   getBlurbM       = entityClass.getMethod("getBlurb");
            Method   getFileMd5M     = entityClass.getMethod("getFileMd5");
            Method   getFileSizeM    = entityClass.getMethod("getFileSize");
            Method   getFileNameM    = entityClass.getMethod("getFileName");

            Class<?> itemClass = Class.forName("com.xj.winemu.bean.DialogSettingListItemEntity");
            // Constructor: (int id, int type, boolean isSelected,
            //   String title, String desc, int width, int height,
            //   String value, int valueInt, String fileMd5, long fileSize,
            //   String fileName, String downloadUrl, String version,
            //   int versionCode, String logo, int downloadPercent,
            //   int downloadState, EnvLayerEntity envLayerEntity,
            //   boolean isDownloaded, int isSteam)
            Constructor<?> itemCtor = itemClass.getDeclaredConstructor(
                    int.class, int.class, boolean.class,
                    String.class, String.class, int.class, int.class,
                    String.class, int.class, String.class, long.class,
                    String.class, String.class, String.class,
                    int.class, String.class, int.class,
                    int.class, entityClass,
                    boolean.class, int.class);

            for (Object value : map.values()) {
                if (!repoClass.isInstance(value)) continue;

                Object entity = getEntry.invoke(value);
                if (entity == null) continue;

                int storedType = (int) getTypeM.invoke(entity);
                if (!typeMatches(storedType, contentType)) continue;

                int    id          = (int)  getIdM.invoke(entity);
                String displayName = (String) getDisplayNameM.invoke(entity);
                String entityName  = (String) getNameM.invoke(entity);
                String version     = (String) getVersionM.invoke(entity);
                int    versionCode = (int)  getVersionCodeM.invoke(entity);
                String logo        = (String) getLogoM.invoke(entity);
                String blurb       = (String) getBlurbM.invoke(entity);
                String fileMd5     = (String) getFileMd5M.invoke(entity);
                long   fileSize    = (long)  getFileSizeM.invoke(entity);
                String fileName    = (String) getFileNameM.invoke(entity);

                if (displayName == null) displayName = entityName != null ? entityName : "";
                if (version     == null) version     = "";
                if (logo        == null) logo        = "";
                if (blurb       == null) blurb       = "";
                if (fileMd5     == null) fileMd5     = "";
                if (fileName    == null) fileName    = "";
                if (entityName  == null) entityName  = "";

                Object item = itemCtor.newInstance(
                        id, storedType, false,
                        displayName, blurb, 0, 0,
                        entityName, 0, fileMd5, fileSize,
                        fileName, "", version,
                        versionCode, logo, 100,
                        3 /* downloadState = installed */, entity,
                        true, 0);
                //noinspection unchecked
                list.add(item);
                Log.d(TAG, "appendLocalComponents: added " + displayName + " type=" + storedType);
            }
        } catch (Exception e) {
            Log.e(TAG, "appendLocalComponents failed", e);
        }
    }

    /** Returns the filename (display name) for the given content URI. */
    public static String getDisplayName(Context ctx, Uri uri) {
        if (uri == null) return null;
        try (Cursor c = ctx.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) return c.getString(col);
            }
        } catch (Exception ignored) {}
        // Fallback: last path segment
        String path = uri.getPath();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0) return path.substring(slash + 1);
        }
        return null;
    }

    // ── Registration ──────────────────────────────────────────────────────────

    private static void registerComponent(Context ctx, String name,
                                          String version, String desc,
                                          int contentType) throws Exception {
        // Build EnvLayerEntity (18 params):
        // (String blurb, String fileMd5, long fileSize, int id,
        //  String logo, String displayName, String name, String fileName,
        //  int type, String version, int versionCode, String downloadUrl,
        //  String upgradeMsg, SubData subData, EnvLayerEntity base,
        //  String framework, String frameworkType, int isSteam)
        Class<?> entityClass = Class.forName("com.xj.winemu.api.bean.EnvLayerEntity");
        Constructor<?> entityCtor = findCtor(entityClass, 18);
        if (entityCtor == null)
            throw new Exception("EnvLayerEntity 18-param ctor not found");

        int idHash = Math.abs(name.hashCode()) % 100000 + 10000;
        Object entity = entityCtor.newInstance(
                desc,           // blurb
                "",             // fileMd5
                0L,             // fileSize
                idHash,         // id
                "",             // logo
                name,           // displayName
                name,           // name
                name,           // fileName
                contentType,    // type
                version,        // version
                0,              // versionCode
                "",             // downloadUrl
                "",             // upgradeMsg
                null,           // subData
                null,           // base
                "",             // framework
                "",             // frameworkType
                0               // isSteam
        );

        // Build ComponentRepo (7 params):
        // (String name, String version, State state, EnvLayerEntity entry,
        //  boolean isDep, boolean isBase, DependencyManager$Companion$Info depInfo)
        Class<?> repoClass  = Class.forName("ComponentRepo");
        Class<?> stateClass = Class.forName("State");
        Object   stateExtracted = stateClass.getField("Extracted").get(null);
        Constructor<?> repoCtor = findCtor(repoClass, 7);
        if (repoCtor == null)
            throw new Exception("ComponentRepo 7-param ctor not found");

        Object repo = repoCtor.newInstance(
                name, version, stateExtracted, entity,
                false, true, null);

        // Register: call EmuComponents.C(ComponentRepo)
        // C() puts in HashMap and saves to SharedPreferences without touching state.
        Object emuInstance = getEmuComponentsInstance();
        if (emuInstance == null) throw new Exception("EmuComponents instance is null");

        Method cMethod = emuInstance.getClass().getMethod("C", repoClass);
        cMethod.invoke(emuInstance, repo);
        Log.d(TAG, "registerComponent: C() called for " + name);
    }

    // ── Extraction ────────────────────────────────────────────────────────────

    private static void doExtract(InputStream raw, File destDir, int contentType) throws Exception {
        BufferedInputStream bis = new BufferedInputStream(raw);
        bis.mark(4);
        byte[] hdr = new byte[4];
        int read = bis.read(hdr, 0, 4);
        bis.reset();
        if (read < 2) throw new IOException("File too short");

        int b0 = hdr[0] & 0xFF, b1 = hdr[1] & 0xFF,
            b2 = hdr[2] & 0xFF, b3 = hdr[3] & 0xFF;

        // Clear destDir before extraction
        clearDir(destDir);
        destDir.mkdirs();

        if (b0 == 0x50 && b1 == 0x4B) {
            // ZIP (flat extraction — GPU driver format)
            extractZip(bis, destDir);
        } else if (b0 == 0x28 && b1 == 0xB5 && b2 == 0x2F && b3 == 0xFD) {
            // zstd-compressed tar (WCP format)
            InputStream zstd = openZstd(bis);
            try { extractTar(zstd, destDir, contentType); } finally { zstd.close(); }
        } else if (b0 == 0xFD && b1 == 0x37 && b2 == 0x7A && b3 == 0x58) {
            // XZ-compressed tar (FEXCore nightly WCP format)
            InputStream xz = openXz(bis);
            try { extractTar(xz, destDir, contentType); } finally { xz.close(); }
        } else {
            throw new Exception(String.format(
                    "Unknown format (magic: %02X %02X %02X %02X)", b0, b1, b2, b3));
        }
    }

    private static void extractZip(InputStream in, File destDir) throws IOException {
        byte[] buf = new byte[BUF];
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) { zip.closeEntry(); continue; }
            // Flat extraction: strip path, keep only filename
            String name = new File(entry.getName()).getName();
            if (name.isEmpty()) { zip.closeEntry(); continue; }
            try (FileOutputStream fos = new FileOutputStream(new File(destDir, name))) {
                pipe(zip, fos, buf);
            }
            zip.closeEntry();
        }
        zip.close();
    }

    private static void extractTar(InputStream in, File destDir,
                                   int contentType) throws Exception {
        Class<?> tarClass = Class.forName(
                "org.apache.commons.compress.archivers.tar.TarArchiveInputStream");
        Constructor<?> tarCtor = tarClass.getConstructor(InputStream.class);
        Object tar = tarCtor.newInstance(in);

        // Obfuscated method names in GameHub 5.1.4:
        //   getNextTarEntry() → f()
        //   TarArchiveEntry.getName() → p()
        Method nextEntry = tarClass.getMethod("f");
        Method readMethod = tarClass.getMethod("read", byte[].class, int.class, int.class);
        Method getName = null; // resolved from first entry's class

        byte[] buf    = new byte[BUF];
        boolean flattenToRoot = (contentType == TYPE_FEXCORE);

        Object entry;
        while ((entry = nextEntry.invoke(tar)) != null) {
            if (getName == null) {
                getName = entry.getClass().getMethod("p");
            }
            String name = (String) getName.invoke(entry);
            if (name == null || name.endsWith("/")) continue;
            if (name.startsWith("./")) name = name.substring(2);
            if (name.isEmpty()) continue;

            if (name.equals("profile.json") || name.endsWith("/profile.json")) {
                // Extract profile.json to destDir so readProfile() can find it
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                pipeReflected(readMethod, tar, baos, buf);
                String json = baos.toString("UTF-8");
                // Also detect FEXCore from profile.json in case contentType not set
                if (json.contains("FEXCore") || json.contains("fexcore")) {
                    flattenToRoot = true;
                }
                // Write profile.json to destDir for later readProfile()
                try (FileOutputStream fos = new FileOutputStream(new File(destDir, "profile.json"))) {
                    fos.write(baos.toByteArray());
                }
                continue;
            }

            File dest;
            if (flattenToRoot) {
                dest = new File(destDir, new File(name).getName());
            } else {
                dest = new File(destDir, name);
                File parent = dest.getParentFile();
                if (parent != null) parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                pipeReflected(readMethod, tar, fos, buf);
            }
        }
        try { tarClass.getMethod("close").invoke(tar); } catch (Exception ignored) {}
    }

    // ── Profile metadata ──────────────────────────────────────────────────────

    private static class ProfileInfo {
        String name;
        String version;
        String desc;
    }

    private static ProfileInfo readProfile(File destDir, int contentType) {
        ProfileInfo info = new ProfileInfo();
        // WCP: profile.json
        File pf = new File(destDir, "profile.json");
        if (pf.exists()) {
            try {
                byte[] bytes = readFile(pf);
                JSONObject obj = new JSONObject(new String(bytes, "UTF-8"));
                info.name    = obj.optString("name",        null);
                info.version = obj.optString("versionName", null);
                info.desc    = obj.optString("description", null);
                if (info.version == null) info.version = obj.optString("version", null);
            } catch (Exception e) {
                Log.w(TAG, "readProfile: failed to parse profile.json", e);
            }
        }
        // ZIP (GPU driver): meta.json
        File mf = new File(destDir, "meta.json");
        if (mf.exists() && info.version == null) {
            try {
                byte[] bytes = readFile(mf);
                JSONObject obj = new JSONObject(new String(bytes, "UTF-8"));
                if (info.version == null) info.version = obj.optString("driverVersion", null);
                if (info.desc    == null) info.desc    = obj.optString("description",   null);
            } catch (Exception e) {
                Log.w(TAG, "readProfile: failed to parse meta.json", e);
            }
        }
        return info;
    }

    private static byte[] readFile(File f) throws IOException {
        byte[] buf = new byte[BUF];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(f)) {
            int n;
            while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private static Object getEmuComponentsInstance() throws Exception {
        Class<?> emuClass = Class.forName("com.xj.winemu.EmuComponents");
        // EmuComponents$Companion is accessed via static field `c`
        Field companionField = emuClass.getField("c");
        Object companion = companionField.get(null);
        if (companion == null) return null;
        // EmuComponents$Companion.a() returns the singleton EmuComponents instance
        Method getInst = companion.getClass().getMethod("a");
        return getInst.invoke(companion);
    }

    /** Find a constructor with exactly {@code paramCount} parameters. */
    private static Constructor<?> findCtor(Class<?> cls, int paramCount) {
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (c.getParameterTypes().length == paramCount) {
                c.setAccessible(true);
                return c;
            }
        }
        return null;
    }

    /** Returns true if a component of {@code storedType} should appear for {@code requestedType}. */
    private static boolean typeMatches(int storedType, int requestedType) {
        if (storedType == requestedType) return true;
        // TRANSLATOR (32) covers Box64 (94) and FEXCore (95)
        if (requestedType == TYPE_TRANSLATOR &&
                (storedType == TYPE_BOX64 || storedType == TYPE_FEXCORE)) return true;
        return false;
    }

    // ── Compression helpers ───────────────────────────────────────────────────

    private static InputStream openZstd(InputStream in) throws Exception {
        Class<?> cls = Class.forName("com.github.luben.zstd.ZstdInputStreamNoFinalizer");
        Constructor<?> ctor = cls.getConstructor(InputStream.class);
        return (InputStream) ctor.newInstance(in);
    }

    private static InputStream openXz(InputStream in) throws Exception {
        Class<?> cls = Class.forName("org.tukaani.xz.XZInputStream");
        Constructor<?> ctor = cls.getConstructor(InputStream.class, int.class);
        return (InputStream) ctor.newInstance(in, -1);
    }

    // ── I/O helpers ───────────────────────────────────────────────────────────

    private static void pipe(InputStream in, OutputStream out, byte[] buf) throws IOException {
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void pipeReflected(Method readMethod, Object tar,
                                      OutputStream out, byte[] buf) throws Exception {
        int n;
        while ((n = (int) readMethod.invoke(tar, buf, 0, buf.length)) > 0) {
            out.write(buf, 0, n);
        }
    }

    static void clearDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) clearDir(f);
            f.delete();
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }
}
