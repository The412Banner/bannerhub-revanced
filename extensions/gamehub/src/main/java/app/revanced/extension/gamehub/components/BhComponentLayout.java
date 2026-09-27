package app.revanced.extension.gamehub.components;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps whatever an archive ships onto the folder shape the PC engine reads
 * from {@code usr/home/components/<name>/} (layouts verified on device
 * against the plugin's own installs, 2026-09-27), and validates the result
 * before it is moved into place. Pure functions over entry names — no I/O
 * except {@link #validateInstalled}.
 *
 * <h3>Target layouts</h3>
 * <pre>
 * GPU driver (2)   libvulkan_freedreno.so [+ meta.json] [+ helper .so files]
 * DXVK (3)         profile.json + system32/*.dll + syswow64/*.dll
 * VKD3D (4)        profile.json + system32/*.dll + syswow64/*.dll
 * Translator (1)   libarm64ecfex.dll + libwow64fex.dll   (FEX)
 *                  box64 / wowbox64.dll                  (Box64)   — all flat at the root
 * Library (6)      copied through
 * </pre>
 *
 * <h3>Source shapes handled</h3>
 * <ul>
 *   <li>Winlator {@code .wcp}: {@code profile.json} + {@code system32/} /
 *       {@code syswow64/} (DXVK, VKD3D — copied through) or
 *       {@code system32/libarm64ecfex.dll} (FEX — flattened, as 3.8.1 did).</li>
 *   <li>adrenotools driver zip: {@code meta.json} + {@code <libraryName>.so}
 *       + helper libraries. The library named by {@code meta.json} is written
 *       AS {@code libvulkan_freedreno.so} (3.8.1 "Fix 2") and
 *       {@code meta.json}'s {@code libraryName} is rewritten to match, so a
 *       reader of either finds the same file. Helpers are copied through.</li>
 *   <li>GitHub release zips: {@code dxvk-2.4.1/x64/*.dll} + {@code x32/}
 *       (or {@code x86/} for vkd3d-proton) → {@code system32/} +
 *       {@code syswow64/}; a minimal {@code profile.json} is synthesised in
 *       the Winlator shape when the archive has none.</li>
 *   <li>a single wrapper directory ({@code dxvk-2.4.1/…}, {@code ./…}) is
 *       stripped, up to {@value #MAX_STRIP} levels, unless it IS a payload
 *       directory (system32 / x64 / …); macOS resource forks and
 *       {@code __MACOSX/} are dropped.</li>
 * </ul>
 */
final class BhComponentLayout {

    static final String DRIVER_LIB = "libvulkan_freedreno.so";
    static final String PROFILE = "profile.json";
    static final String META = "meta.json";

    private static final int MAX_STRIP = 4;

    /** Directory names that are payload, never a wrapper to strip. */
    private static final Set<String> PAYLOAD_DIRS = new HashSet<>(Arrays.asList(
            "system32", "syswow64", "x64", "x32", "x86", "lib", "bin", "drive_c"));

    private static final Set<String> FEX_FILES = new HashSet<>(Arrays.asList(
            "libarm64ecfex.dll", "libwow64fex.dll"));
    private static final Set<String> BOX64_FILES = new HashSet<>(Arrays.asList(
            "box64", "wowbox64.dll", "libwowbox64.dll"));
    private static final Set<String> DXVK_DLLS = new HashSet<>(Arrays.asList(
            "d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll"));
    private static final Set<String> VKD3D_DLLS = new HashSet<>(Arrays.asList(
            "d3d12.dll", "d3d12core.dll"));

    /** The plan: archive path (as the walker reports it) → destination path (relative to the component root). */
    static final class Mapping {
        final LinkedHashMap<String, String> files = new LinkedHashMap<>();
        final List<String> skipped = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        /** Wrapper prefix removed from every path ("dxvk-2.4.1/"), or "". */
        String strippedPrefix = "";
        /** Archive path of the driver library that becomes {@link #DRIVER_LIB}, or null. */
        String driverRenamedFrom;
        /** True when DXVK/VKD3D has no profile.json and one must be written. */
        boolean synthesizeProfile;
        /** Blocking reason, or null when the plan can be applied. */
        String error;

        /** Short human lines for the confirm dialog ("system32/d3d11.dll ← x64/d3d11.dll"). */
        List<String> preview(int max) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> e : files.entrySet()) {
                if (out.size() >= max) {
                    out.add("… " + (files.size() - max) + " more");
                    break;
                }
                String src = e.getKey();
                out.add(src.equals(e.getValue()) ? e.getValue() : e.getValue() + "  ←  " + src);
            }
            if (synthesizeProfile) out.add(PROFILE + "  (generated)");
            if (!skipped.isEmpty()) out.add(skipped.size() + " file(s) not needed, skipped");
            return out;
        }
    }

    private BhComponentLayout() {}

    // ── Planning ──────────────────────────────────────────────────────────

    /**
     * @param type     one of {@link BhComponentType}'s injectable ids
     * @param entries  file entries (no directories), '/'-separated, already safe
     * @param metaJson the archive's meta.json text when it has one (driver rename), else null
     */
    static Mapping plan(int type, List<String> entries, String metaJson) {
        Mapping m = new Mapping();
        List<String> files = new ArrayList<>();
        for (String e : entries) {
            if (e == null || e.endsWith("/")) continue;
            if (isJunk(e)) {
                m.skipped.add(e);
                continue;
            }
            files.add(e);
        }
        if (files.isEmpty()) {
            m.error = "the archive holds no files";
            return m;
        }

        // Strip wrapper directories. The plan* methods see the stripped names; the
        // mapping is keyed by the ORIGINAL name because that is what the walker
        // reports during extraction.
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < MAX_STRIP; i++) {
            String top = BhTzstReader.singleTopDir(files);
            if (top == null || PAYLOAD_DIRS.contains(top.toLowerCase(Locale.ROOT))) break;
            List<String> next = new ArrayList<>(files.size());
            for (String f : files) next.add(f.substring(top.length() + 1));
            files = next;
            prefix.append(top).append('/');
        }
        m.strippedPrefix = prefix.toString();

        switch (type) {
            case BhComponentType.TYPE_DRIVER:     planDriver(m, files, metaJson); break;
            case BhComponentType.TYPE_DXVK:
            case BhComponentType.TYPE_VKD3D:      planDlls(m, files, type); break;
            case BhComponentType.TYPE_TRANSLATOR: planTranslator(m, files); break;
            default:                              planCopyThrough(m, files); break;
        }
        return m;
    }

    private static void put(Mapping m, String src, String dst) {
        String orig = m.strippedPrefix + src;
        if (m.files.containsValue(dst)) {
            m.warnings.add("duplicate " + dst + " — kept the first, skipped " + orig);
            m.skipped.add(orig);
            return;
        }
        m.files.put(orig, dst);
    }

    private static void planCopyThrough(Mapping m, List<String> files) {
        for (String f : files) put(m, f, f);
    }

    private static void planDriver(Mapping m, List<String> files, String metaJson) {
        String libName = BhComponentType.libraryNameFromMeta(metaJson);
        String libPath = null;
        if (libName != null) libPath = findByBase(files, libName);
        if (libPath == null) libPath = findByBase(files, DRIVER_LIB);
        if (libPath == null) {
            // No descriptor (or it names a file that is not there): the one Vulkan-looking .so.
            List<String> candidates = new ArrayList<>();
            for (String f : files) {
                String b = base(f).toLowerCase(Locale.ROOT);
                if (b.endsWith(".so") && (b.contains("vulkan") || b.contains("adreno")
                        || b.contains("turnip") || b.contains("freedreno"))) candidates.add(f);
            }
            if (candidates.size() == 1) libPath = candidates.get(0);
            else if (candidates.size() > 1) {
                m.error = "several driver libraries and no meta.json naming the main one: " + candidates;
                return;
            }
        }
        if (libPath == null) {
            m.error = "no Vulkan driver library (.so) found";
            return;
        }
        for (String f : files) {
            if (f.equals(libPath)) {
                put(m, f, DRIVER_LIB);
                if (!base(f).equals(DRIVER_LIB)) m.driverRenamedFrom = f;
            } else {
                put(m, f, f);   // meta.json, helper .so files, readme — as-is
            }
        }
    }

    private static void planDlls(Mapping m, List<String> files, int type) {
        // Payload dirs: the shallowest system32/syswow64 pair wins (a .wcp has them at
        // the root); GitHub release archives use x64 / x32 (dxvk) or x64 / x86 (vkd3d).
        String s32 = findDir(files, "system32"), s64 = findDir(files, "syswow64");
        if (s32 == null && s64 == null) {
            s32 = findDir(files, "x64");
            s64 = findDir(files, "x32");
            if (s64 == null) s64 = findDir(files, "x86");
        }
        if (s32 == null && s64 == null) {
            m.error = "no system32/ + syswow64/ (or x64/ + x32/) directories found";
            return;
        }
        String profile = null;
        for (String f : files) {
            if (base(f).equals(PROFILE) && (profile == null || depth(f) < depth(profile))) profile = f;
        }
        for (String f : files) {
            String b = base(f);
            if (f.equals(profile)) { put(m, f, PROFILE); continue; }
            if (s32 != null && f.startsWith(s32 + "/")) { put(m, f, "system32/" + f.substring(s32.length() + 1)); continue; }
            if (s64 != null && f.startsWith(s64 + "/")) { put(m, f, "syswow64/" + f.substring(s64.length() + 1)); continue; }
            if (b.equals(META)) { put(m, f, f); continue; }
            m.skipped.add(m.strippedPrefix + f);   // README, LICENSE, ...
        }
        m.synthesizeProfile = profile == null;
        Set<String> want = type == BhComponentType.TYPE_VKD3D ? VKD3D_DLLS : DXVK_DLLS;
        boolean any = false;
        for (String dst : m.files.values()) {
            if ((dst.startsWith("system32/") || dst.startsWith("syswow64/"))
                    && want.contains(base(dst).toLowerCase(Locale.ROOT))) any = true;
        }
        if (!any) {
            m.warnings.add("none of the usual " + BhComponentType.badge(type) + " DLLs ("
                    + want + ") are in system32/ or syswow64/ — check the category");
        }
    }

    private static void planTranslator(Mapping m, List<String> files) {
        // Flat at the root by basename, like the plugin's own FEXCore-2608 / box64 installs.
        for (String f : files) put(m, f, base(f));
        boolean marker = false;
        for (String dst : m.files.values()) {
            String b = dst.toLowerCase(Locale.ROOT);
            if (FEX_FILES.contains(b) || BOX64_FILES.contains(b)) marker = true;
        }
        if (!marker) {
            m.warnings.add("no FEX dll (libarm64ecfex.dll / libwow64fex.dll) or box64 binary "
                    + "at the root after flattening — check the category");
        }
    }

    // ── Validation of the extracted folder ────────────────────────────────

    /** Null when the folder has what the plugin needs for {@code type}; else the reason. */
    static String validateInstalled(int type, File dir) {
        if (dir == null || !dir.isDirectory()) return "component folder missing";
        switch (type) {
            case BhComponentType.TYPE_DRIVER:
                return new File(dir, DRIVER_LIB).isFile() ? null : DRIVER_LIB + " missing after extraction";
            case BhComponentType.TYPE_DXVK:
            case BhComponentType.TYPE_VKD3D: {
                boolean any = hasFiles(new File(dir, "system32")) || hasFiles(new File(dir, "syswow64"));
                if (!any) return "system32/ and syswow64/ are both empty";
                return new File(dir, PROFILE).isFile() ? null : PROFILE + " missing";
            }
            case BhComponentType.TYPE_TRANSLATOR: {
                for (String f : FEX_FILES) if (new File(dir, f).isFile()) return null;
                for (String f : BOX64_FILES) if (new File(dir, f).isFile()) return null;
                return "no FEX dll or box64 binary at the component root";
            }
            default:
                return hasFiles(dir) ? null : "nothing was extracted";
        }
    }

    // ── Descriptors ───────────────────────────────────────────────────────

    /**
     * A {@code profile.json} in the Winlator {@code ContentProfile} shape —
     * the fields every on-device DXVK/VKD3D install carries ({@code type},
     * {@code versionName}, {@code versionCode}, {@code description},
     * {@code files[{source,target}]}). Which of them the plugin actually
     * reads is not known; it certainly tolerates this exact set because the
     * catalog's own .wcp-derived installs ship it verbatim.
     */
    static String synthesizeProfile(int type, String version, String blurb, Mapping m) {
        try {
            JSONObject p = new JSONObject();
            p.put("type", type == BhComponentType.TYPE_VKD3D ? "VKD3D" : "DXVK");
            p.put("versionName", version == null ? "" : version);
            p.put("versionCode", 0);
            p.put("description", blurb == null || blurb.isEmpty() ? "Injected by BannerHub" : blurb);
            JSONArray files = new JSONArray();
            for (String dst : m.files.values()) {
                if (!dst.startsWith("system32/") && !dst.startsWith("syswow64/")) continue;
                int slash = dst.indexOf('/');
                JSONObject f = new JSONObject();
                f.put("source", dst);
                f.put("target", "${" + dst.substring(0, slash) + "}" + dst.substring(slash));
                files.put(f);
            }
            p.put("files", files);
            return p.toString(2);
        } catch (Throwable t) {
            return "{\"type\":\"" + (type == BhComponentType.TYPE_VKD3D ? "VKD3D" : "DXVK")
                    + "\",\"versionName\":\"\",\"versionCode\":0,\"description\":\"\",\"files\":[]}";
        }
    }

    /** {@code meta.json} with {@code libraryName} set to {@link #DRIVER_LIB}; the input back when it cannot be parsed. */
    static String rewriteMetaLibraryName(String metaJson) {
        try {
            JSONObject m = new JSONObject(metaJson);
            m.put("libraryName", DRIVER_LIB);
            return m.toString(2);
        } catch (Throwable t) {
            return metaJson;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    static boolean isJunk(String path) {
        String b = base(path);
        return path.startsWith("__MACOSX/") || path.contains("/__MACOSX/")
                || b.startsWith("._") || b.equals(".DS_Store") || b.equals("Thumbs.db")
                || b.equals(BhInjectedRegistry.MARKER_FILE);
    }

    static String base(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static int depth(String path) {
        int d = 0;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') d++;
        return d;
    }

    /** Shallowest path with this basename, or null. */
    private static String findByBase(List<String> files, String base) {
        String best = null;
        for (String f : files) {
            if (base(f).equals(base) && (best == null || depth(f) < depth(best))) best = f;
        }
        return best;
    }

    /** Shallowest directory path (no trailing '/') whose last segment is {@code name}, or null. */
    private static String findDir(List<String> files, String name) {
        String best = null;
        for (String f : files) {
            String[] segs = f.split("/");
            for (int i = 0; i < segs.length - 1; i++) {
                if (segs[i].equalsIgnoreCase(name)) {
                    String dir = join(segs, i + 1);
                    if (best == null || depth(dir) < depth(best)) best = dir;
                    break;
                }
            }
        }
        return best;
    }

    private static String join(String[] segs, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append('/');
            sb.append(segs[i]);
        }
        return sb.toString();
    }

    private static boolean hasFiles(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File k : kids) if (k.isFile() && !k.getName().equals(BhInjectedRegistry.MARKER_FILE)) return true;
        return false;
    }
}
