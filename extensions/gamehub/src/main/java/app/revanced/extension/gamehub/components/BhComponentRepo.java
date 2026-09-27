package app.revanced.extension.gamehub.components;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The online component sources of the "Download components" screen — the
 * 3.8.1 list verbatim (Arihany WCPHub, four GPU-driver mirrors, the
 * Nightlies feed) plus the Nightlies GitHub releases as "Nightly builds".
 *
 * <p>Every feed is one JSON shape, {@code [{type, verName, verCode, remoteUrl}]}.
 * The GitHub source is the releases API ({@code tag_name} starting
 * {@code nightly-}, {@code assets[].name / browser_download_url}). Both are
 * flattened to {@link Item}s here so the screen has one list to filter.
 *
 * <p>Fetch = {@code HttpURLConnection}, User-Agent {@code BannerHub/1.0},
 * caller's worker thread. Each successful body is cached verbatim under
 * {@code <filesDir>/bh_component_repos/<id>.json}; a failed fetch falls back
 * to that cache ({@link Loaded#fromCache}) so the list still opens offline.
 *
 * <p>Type mapping: the feed's {@code type} string decides first, the
 * 3.8.1 keyword table on the name / file name second; Wine / Proton and
 * anything that maps nowhere is hidden — the PC engine ships its own
 * Wine containers and nothing here injects one. Families keep Box64 and
 * FEX apart for the category chips although both land in the injector's
 * translator slot ({@link BhComponentType#TYPE_TRANSLATOR}).
 */
final class BhComponentRepo {

    private static final String TAG = "BhComponentRepo";
    static final String USER_AGENT = "BannerHub/1.0";
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_BODY = 8 * 1024 * 1024;
    static final String CACHE_DIR = "bh_component_repos";

    static final int KIND_FEED = 0;
    static final int KIND_GITHUB_RELEASES = 1;

    // ── Families (category chips) → injector type ids ─────────────────────

    static final String FAM_DRIVER = "driver";
    static final String FAM_DXVK   = "dxvk";
    static final String FAM_VKD3D  = "vkd3d";
    static final String FAM_BOX64  = "box64";
    static final String FAM_FEX    = "fex";

    /** Chip order, the 3.8.1 category order. */
    static final String[] FAMILIES = { FAM_DXVK, FAM_VKD3D, FAM_BOX64, FAM_FEX, FAM_DRIVER };

    static String familyLabel(String fam) {
        if (fam == null) return "All";
        switch (fam) {
            case FAM_DXVK:   return "DXVK";
            case FAM_VKD3D:  return "VKD3D-Proton";
            case FAM_BOX64:  return "Box64";
            case FAM_FEX:    return "FEXCore";
            case FAM_DRIVER: return "GPU Driver / Turnip";
            default:         return fam;
        }
    }

    /** Short badge text (the list card). */
    static String familyBadge(String fam) {
        if (fam == null) return "?";
        switch (fam) {
            case FAM_DXVK:   return "DXVK";
            case FAM_VKD3D:  return "VKD3D";
            case FAM_BOX64:  return "BOX64";
            case FAM_FEX:    return "FEX";
            case FAM_DRIVER: return "DRIVER";
            default:         return fam.toUpperCase(Locale.ROOT);
        }
    }

    /** The injector's category id for a family ({@link BhComponentType}). */
    static int typeId(String fam) {
        if (fam == null) return 0;
        switch (fam) {
            case FAM_DXVK:   return BhComponentType.TYPE_DXVK;
            case FAM_VKD3D:  return BhComponentType.TYPE_VKD3D;
            case FAM_BOX64:
            case FAM_FEX:    return BhComponentType.TYPE_TRANSLATOR;
            case FAM_DRIVER: return BhComponentType.TYPE_DRIVER;
            default:         return 0;
        }
    }

    /**
     * Family from the feed's {@code type} string. Null = hidden (Wine,
     * Proton, unknown) — the caller then tries the name.
     */
    static String familyFromFeedType(String type) {
        if (type == null) return null;
        switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "gpudriver":
            case "gpu_driver":
            case "driver":
            case "turnip":       return FAM_DRIVER;
            case "dxvk":
            case "d7vk":         return FAM_DXVK;   // 3.8.1 listed d7vk under DXVK too
            case "vkd3d":
            case "vkd3d-proton": return FAM_VKD3D;
            case "box64":
            case "wowbox64":     return FAM_BOX64;
            case "fexcore":
            case "fex":          return FAM_FEX;
            default:             return null;
        }
    }

    /** Wine / Proton feed rows — containers, not components; the PC engine ships its own. */
    static boolean isContainerType(String type) {
        if (type == null) return false;
        String t = type.trim().toLowerCase(Locale.ROOT);
        return t.contains("wine") || t.contains("proton");
    }

    /** The 3.8.1 {@code detectType()} keyword table, order preserved. Null when nothing matches. */
    static String familyFromName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("box64")) return FAM_BOX64;
        if (n.contains("fex")) return FAM_FEX;
        if (n.contains("vkd3d")) return FAM_VKD3D;
        if (n.contains("turnip") || n.contains("adreno") || n.contains("driver")
                || n.contains("qualcomm") || n.contains("mesa") || n.contains("freedreno")) {
            return FAM_DRIVER;
        }
        if (n.contains("dxvk") || n.contains("d7vk")) return FAM_DXVK;
        return null;
    }

    // ── Sources ───────────────────────────────────────────────────────────

    static final class Repo {
        final String id;
        final String name;
        final String blurb;
        final String url;
        final int kind;

        Repo(String id, String name, String blurb, String url, int kind) {
            this.id = id;
            this.name = name;
            this.blurb = blurb;
            this.url = url;
            this.kind = kind;
        }
    }

    private static final String NIGHTLIES_RAW =
            "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/";

    static final Repo[] REPOS = {
        new Repo("arihany", "Arihany WCPHub", "DXVK, VKD3D-Proton and FEXCore .wcp packs",
                "https://raw.githubusercontent.com/Arihany/WinlatorWCPHub/refs/heads/main/pack.json",
                KIND_FEED),
        new Repo("kimchi", "Kimchi GPU Drivers", "Adreno / Turnip driver zips",
                NIGHTLIES_RAW + "kimchi_drivers.json", KIND_FEED),
        new Repo("stevenmxz", "StevenMXZ GPU Drivers", "Adreno / Turnip driver zips",
                NIGHTLIES_RAW + "stevenmxz_drivers.json", KIND_FEED),
        new Repo("mtr", "MTR GPU Drivers", "Adreno / Turnip driver zips",
                NIGHTLIES_RAW + "mtr_drivers.json", KIND_FEED),
        new Repo("white", "Whitebelyash GPU Drivers", "Adreno / Turnip driver zips",
                NIGHTLIES_RAW + "white_drivers.json", KIND_FEED),
        new Repo("nightlies", "The412Banner Nightlies", "Box64, FEX, DXVK, VKD3D and drivers — every build",
                NIGHTLIES_RAW + "nightlies_components.json", KIND_FEED),
        new Repo("nightly_builds", "Nightly builds", "The newest nightly-* GitHub releases, per build",
                "https://api.github.com/repos/The412Banner/Nightlies/releases?per_page=30",
                KIND_GITHUB_RELEASES),
    };

    static Repo repoById(String id) {
        for (Repo r : REPOS) if (r.id.equals(id)) return r;
        return null;
    }

    // ── Items ─────────────────────────────────────────────────────────────

    /** One downloadable component, whichever source it came from. */
    static final class Item {
        String repoId;
        String repoName;
        String family;
        /** The feed's verName / the asset name without extension — the display + registry name. */
        String name;
        /** Feed verCode ("0" = none) or the nightly tag's date part. */
        String verCode;
        String url;
        /** Last path segment of the URL, decoded, safe as a file name. */
        String fileName;
        String ext;

        boolean hasVerCode() {
            return verCode != null && !verCode.isEmpty() && !"0".equals(verCode);
        }

        /** Lower-case haystack for the search box. */
        String searchKey() {
            return (name + " " + fileName + " " + (verCode == null ? "" : verCode)).toLowerCase(Locale.ROOT);
        }
    }

    /** Result of {@link #load}: items (possibly empty) + where they came from. */
    static final class Loaded {
        final List<Item> items = new ArrayList<>();
        boolean fromCache;
        long cachedAt;
        /** Fetch failure reason (set when the network was tried and failed, even if the cache saved us). */
        String error;
    }

    private BhComponentRepo() {}

    /**
     * Fetch (or, unless {@code forceNetwork}, when the fetch fails, read the
     * cache) and parse. Worker thread only.
     */
    static Loaded load(Context ctx, Repo repo, boolean forceNetwork) {
        Loaded out = new Loaded();
        String body = null;
        try {
            body = fetch(repo.url);
            writeCache(ctx, repo, body);
        } catch (Throwable t) {
            out.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            Log.w(TAG, "fetch failed for " + repo.id + ": " + out.error);
        }
        if (body == null) {
            File cache = cacheFile(ctx, repo);
            if (cache.isFile()) {
                try {
                    body = readFile(cache);
                    out.fromCache = true;
                    out.cachedAt = cache.lastModified();
                } catch (Throwable t) {
                    Log.w(TAG, "cache read failed for " + repo.id, t);
                }
            }
        }
        if (body == null) return out;
        try {
            if (repo.kind == KIND_GITHUB_RELEASES) parseGithubReleases(repo, body, out.items);
            else parseFeed(repo, body, out.items);
        } catch (Throwable t) {
            Log.w(TAG, "parse failed for " + repo.id, t);
            if (out.error == null) out.error = "Bad JSON: " + t.getMessage();
        }
        return out;
    }

    static File cacheFile(Context ctx, Repo repo) {
        return new File(new File(ctx.getFilesDir(), CACHE_DIR), repo.id + ".json");
    }

    /** Cache mtime, or 0 when this source was never fetched. */
    static long cachedAt(Context ctx, Repo repo) {
        File f = cacheFile(ctx, repo);
        return f.isFile() ? f.lastModified() : 0;
    }

    // ── Parsers ───────────────────────────────────────────────────────────

    /** {@code [{type, verName, verCode, remoteUrl}]} — the shape of every feed. */
    static void parseFeed(Repo repo, String body, List<Item> into) throws Exception {
        JSONArray arr = new JSONArray(body.trim());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("remoteUrl", "").trim();
            if (url.isEmpty()) continue;
            String verName = o.optString("verName", "").trim();
            String fileName = fileNameFromUrl(url);
            String type = o.optString("type", "").trim();
            if (isContainerType(type)) continue;              // Wine / Proton: never listed
            String fam = familyFromFeedType(type);
            if (fam == null) fam = familyFromName(verName + " " + fileName);
            if (fam == null) continue;                        // maps nowhere
            String ext = extOf(fileName);
            if (!injectableExt(ext)) continue;                // a bare .so etc.
            Item it = new Item();
            it.repoId = repo.id;
            it.repoName = repo.name;
            it.family = fam;
            it.name = verName.isEmpty() ? stripKnownExt(fileName) : verName;
            it.verCode = o.optString("verCode", "").trim();
            it.url = url;
            it.fileName = fileName;
            it.ext = ext;
            into.add(it);
        }
    }

    /**
     * GitHub releases API → one item per injectable asset of every
     * {@code nightly-*} release, newest release first. Twins are collapsed
     * by the user's format rule (drivers ship as {@code .zip}, everything
     * else as {@code .wcp}); Linux-runtime variants ({@code -Linux} /
     * {@code -unix}) and tooling ({@code glslang}) are not PC-engine
     * components and are skipped.
     */
    static void parseGithubReleases(Repo repo, String body, List<Item> into) throws Exception {
        JSONArray rels = new JSONArray(body.trim());
        for (int i = 0; i < rels.length(); i++) {
            JSONObject rel = rels.optJSONObject(i);
            if (rel == null) continue;
            String tag = rel.optString("tag_name", "");
            if (!tag.startsWith("nightly-")) continue;
            if (rel.optBoolean("draft", false)) continue;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) continue;
            String stamp = tag.substring("nightly-".length());
            Set<String> seen = new HashSet<>();
            List<Item> batch = new ArrayList<>();
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.optJSONObject(j);
                if (a == null) continue;
                String name = a.optString("name", "");
                String url = a.optString("browser_download_url", "").trim();
                if (name.isEmpty() || url.isEmpty()) continue;
                String ext = extOf(name);
                if (!".wcp".equals(ext) && !".zip".equals(ext) && !".xz".equals(ext)) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("-linux") || lower.contains("-unix") || lower.startsWith("glslang")) continue;
                String fam = familyFromName(name);
                if (fam == null) continue;
                // Format rule: drivers are .zip, everything else .wcp (.xz stays as a fallback only).
                if (FAM_DRIVER.equals(fam) ? !".zip".equals(ext) : !".wcp".equals(ext)) continue;
                String stem = stripKnownExt(name);
                if (!seen.add(stem)) continue;
                Item it = new Item();
                it.repoId = repo.id;
                it.repoName = repo.name;
                it.family = fam;
                it.name = stem;
                it.verCode = stamp;
                it.url = url;
                it.fileName = fileNameFromUrl(url);
                it.ext = ext;
                batch.add(it);
            }
            into.addAll(batch);
        }
    }

    // ── Names ─────────────────────────────────────────────────────────────

    static boolean injectableExt(String ext) {
        return ".wcp".equals(ext) || ".zip".equals(ext) || ".xz".equals(ext)
                || ".tzst".equals(ext) || ".zst".equals(ext);
    }

    /** Lower-case extension including the dot, "" when none. */
    static String extOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** Drop {@code .wcp / .zip / .xz / .tzst / .tar.zst / .tar.xz}; anything else stays. */
    static String stripKnownExt(String fileName) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase(Locale.ROOT);
        String[] exts = { ".tar.zst", ".tar.xz", ".tzst", ".wcp", ".zip", ".xz", ".zst" };
        for (String e : exts) {
            if (lower.endsWith(e)) return fileName.substring(0, fileName.length() - e.length());
        }
        return fileName;
    }

    /** Decoded last path segment, then made safe for a file name (no separators, no control chars). */
    static String fileNameFromUrl(String url) {
        String seg = url;
        int q = seg.indexOf('?');
        if (q >= 0) seg = seg.substring(0, q);
        int slash = seg.lastIndexOf('/');
        if (slash >= 0) seg = seg.substring(slash + 1);
        try {
            seg = URLDecoder.decode(seg, "UTF-8");
        } catch (Throwable ignored) { }
        seg = seg.replaceAll("[\\\\/\\p{Cntrl}]+", "_").trim();
        if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) seg = "component.bin";
        return seg;
    }

    // ── IO ────────────────────────────────────────────────────────────────

    private static String fetch(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            int code = conn.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = br.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                    if (sb.length() > MAX_BODY) throw new IOException("feed too large");
                }
            }
            return sb.toString();
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static void writeCache(Context ctx, Repo repo, String body) {
        try {
            File f = cacheFile(ctx, repo);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, repo.id + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cache write failed for " + repo.id, t);
        }
    }

    private static String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
