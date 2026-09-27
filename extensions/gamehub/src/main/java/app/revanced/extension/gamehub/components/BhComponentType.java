package app.revanced.extension.gamehub.components;

import org.json.JSONObject;

import java.util.Collection;
import java.util.Locale;

/**
 * Component categories = the plugin registry's {@code entry.type} ids (from
 * our catalog: 1 translator, 2 GPU driver, 3 DXVK, 4 VKD3D, 5 settings pack
 * — not injectable —, 6 libs/redist, 8 steam client — not injectable), plus
 * the 3.8.1 detection heuristics, ported from
 * {@code ComponentDownloadActivity.detectType()} / {@code ComponentInjectorHelper}
 * and extended with content sniffing so an archive named "component (3).tzst"
 * still lands in the right picker. Precedence: profile.json {@code type} →
 * entry names → file name.
 */
public final class BhComponentType {

    public static final int TYPE_TRANSLATOR = 1;   // Box64 / FEX
    public static final int TYPE_DRIVER     = 2;   // GPU driver (Turnip / Adreno / ...)
    public static final int TYPE_DXVK       = 3;
    public static final int TYPE_VKD3D      = 4;
    public static final int TYPE_LIBRARY    = 6;   // libs / redist

    /** Picker order for the category spinner; {@link #label(int)} gives the text. */
    public static final int[] SELECTABLE = {
        TYPE_DRIVER, TYPE_DXVK, TYPE_VKD3D, TYPE_TRANSLATOR, TYPE_LIBRARY,
    };

    private BhComponentType() {}

    public static String label(int type) {
        switch (type) {
            case TYPE_TRANSLATOR: return "Translator (Box64 / FEX)";
            case TYPE_DRIVER:     return "GPU driver";
            case TYPE_DXVK:       return "DXVK";
            case TYPE_VKD3D:      return "VKD3D";
            case TYPE_LIBRARY:    return "Library / redist";
            default:              return "Type " + type;
        }
    }

    /** Short badge text for the list screen. */
    public static String badge(int type) {
        switch (type) {
            case TYPE_TRANSLATOR: return "TRANSLATOR";
            case TYPE_DRIVER:     return "DRIVER";
            case TYPE_DXVK:       return "DXVK";
            case TYPE_VKD3D:      return "VKD3D";
            case TYPE_LIBRARY:    return "LIBRARY";
            default:              return "TYPE " + type;
        }
    }

    public static int indexInSelectable(int type) {
        for (int i = 0; i < SELECTABLE.length; i++) if (SELECTABLE[i] == type) return i;
        return SELECTABLE.length - 1;
    }

    /**
     * Type from the file/folder name alone (the 3.8.1 keyword table). 0 when
     * nothing matches — the caller then falls back to content or asks.
     */
    public static int detectFromName(String name) {
        if (name == null) return 0;
        String n = name.toLowerCase(Locale.ROOT);
        // Order matters: "vkd3d" before the generic driver words, "dxvk" last
        // among the DX ones so "dxvk-vkd3d-bundle" reads as VKD3D like 3.8.1 did.
        if (n.contains("box64") || n.contains("fex")) return TYPE_TRANSLATOR;
        if (n.contains("vkd3d")) return TYPE_VKD3D;
        if (n.contains("turnip") || n.contains("adreno") || n.contains("driver")
                || n.contains("qualcomm") || n.contains("mesa") || n.contains("freedreno")) {
            return TYPE_DRIVER;
        }
        if (n.contains("dxvk")) return TYPE_DXVK;
        return 0;
    }

    /**
     * Type from the entry names inside an archive / folder (paths relative to
     * the component root, '/'-separated, lower-case not required). 0 when the
     * contents are not decisive. Checked most-specific first: a VKD3D wcp also
     * carries profile.json, and a translator bundle may carry a dll or two.
     */
    public static int detectFromEntries(Collection<String> entries) {
        if (entries == null || entries.isEmpty()) return 0;
        boolean fexDll = false, box64 = false, d3d12 = false, d3d11 = false,
                profile = false, freedreno = false, meta = false, anyVulkanSo = false;
        for (String raw : entries) {
            if (raw == null) continue;
            String e = raw.toLowerCase(Locale.ROOT).replace('\\', '/');
            String base = e.substring(e.lastIndexOf('/') + 1);
            if (base.equals("libarm64ecfex.dll") || base.equals("libwow64fex.dll")
                    || base.equals("libfex.so") || base.startsWith("fexcore")) fexDll = true;
            if (base.equals("box64") || base.equals("wowbox64.dll") || base.equals("libwowbox64.dll")
                    || base.startsWith("box64")) box64 = true;
            if (base.equals("d3d12.dll") || base.equals("d3d12core.dll")) d3d12 = true;
            if (base.equals("d3d11.dll") || base.equals("dxgi.dll") || base.equals("d3d9.dll")) d3d11 = true;
            if (base.equals("profile.json")) profile = true;
            if (base.equals("meta.json")) meta = true;
            if (base.equals("libvulkan_freedreno.so")) freedreno = true;
            // Driver libraries come in every naming: GameHub's libvulkan_freedreno.so,
            // adrenotools' vulkan.ad07XX.so / vulkan.adreno.so, Mesa's libvulkan_*.so.
            if (base.endsWith(".so") && (base.contains("vulkan") || base.contains("adreno")
                    || base.contains("freedreno") || base.contains("turnip"))) anyVulkanSo = true;
        }
        if (fexDll || box64) return TYPE_TRANSLATOR;
        if (d3d12) return TYPE_VKD3D;
        if (d3d11 && profile) return TYPE_DXVK;
        if (freedreno || (anyVulkanSo && meta)) return TYPE_DRIVER;
        if (d3d11) return TYPE_DXVK;      // bare dxvk dump without a profile
        if (anyVulkanSo) return TYPE_DRIVER;
        if (meta && !profile) return TYPE_DRIVER;   // meta.json is the adrenotools descriptor
        return 0;
    }

    /** Content first, then name, else 0. */
    public static int detect(String name, Collection<String> entries) {
        int t = detectFromEntries(entries);
        if (t == 0) t = detectFromName(name);
        return t;
    }

    /**
     * Version string from the descriptor the component ships, when it ships
     * one. GPU drivers (adrenotools layout) carry {@code meta.json} with
     * {@code driverVersion}; wcp-style DXVK / VKD3D / FEX archives carry
     * {@code profile.json} with {@code versionName}. Null when absent.
     */
    public static String versionFromDescriptor(String metaJson, String profileJson) {
        try {
            if (metaJson != null && !metaJson.isEmpty()) {
                JSONObject m = new JSONObject(metaJson);
                String v = m.optString("driverVersion", "");
                if (v.isEmpty()) v = m.optString("version", "");
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) { }
        try {
            if (profileJson != null && !profileJson.isEmpty()) {
                JSONObject p = new JSONObject(profileJson);
                String v = p.optString("versionName", "");
                if (v.isEmpty()) v = p.optString("version", "");
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Display name hint from the descriptor (meta.json "name" / profile.json "name"); null when absent. */
    public static String displayNameFromDescriptor(String metaJson, String profileJson) {
        try {
            if (metaJson != null && !metaJson.isEmpty()) {
                String v = new JSONObject(metaJson).optString("name", "");
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) { }
        try {
            if (profileJson != null && !profileJson.isEmpty()) {
                String v = new JSONObject(profileJson).optString("name", "");
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Blurb from the descriptor ("description"); "" when absent. */
    public static String blurbFromDescriptor(String metaJson, String profileJson) {
        try {
            if (metaJson != null && !metaJson.isEmpty()) {
                String v = new JSONObject(metaJson).optString("description", "");
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) { }
        try {
            if (profileJson != null && !profileJson.isEmpty()) {
                String v = new JSONObject(profileJson).optString("description", "");
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** A driver folder's {@code meta.json} {@code libraryName}, or null. */
    public static String libraryNameFromMeta(String metaJson) {
        try {
            if (metaJson != null && !metaJson.isEmpty()) {
                String v = new JSONObject(metaJson).optString("libraryName", "");
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Category from a Winlator {@code profile.json} {@code type} ("DXVK",
     * "VKD3D", "FEXCore", "Box64"); 0 when absent or not a component we
     * inject ("Wine", "Proton", ...). Beats the entry heuristics: a Wine
     * package also ships d3d11.dll.
     */
    public static int typeFromProfile(String profileJson) {
        String t = profileTypeName(profileJson);
        if (t == null) return 0;
        switch (t.toLowerCase(Locale.ROOT)) {
            case "dxvk":    return TYPE_DXVK;
            case "vkd3d":   return TYPE_VKD3D;
            case "fexcore":
            case "fex":
            case "box64":   return TYPE_TRANSLATOR;
            default:        return 0;
        }
    }

    /** The raw {@code type} string of a profile.json, or null. */
    public static String profileTypeName(String profileJson) {
        try {
            if (profileJson != null && !profileJson.isEmpty()) {
                String v = new JSONObject(profileJson).optString("type", "");
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Archive extensions the picker lists, lower-case. */
    public static final String[] ARCHIVE_EXTS = { ".tzst", ".wcp", ".zip", ".tar.zst", ".tar.xz", ".tar.gz", ".tar" };

    public static boolean isArchiveName(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : ARCHIVE_EXTS) if (lower.endsWith(ext)) return true;
        return false;
    }

    /** "foo.tzst" / "foo.wcp" / "foo.zip" / "foo.tar.xz" → "foo"; folders come back unchanged. */
    public static String stripExt(String fileName) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : ARCHIVE_EXTS) {
            if (lower.endsWith(ext)) return fileName.substring(0, fileName.length() - ext.length());
        }
        return fileName;
    }
}
