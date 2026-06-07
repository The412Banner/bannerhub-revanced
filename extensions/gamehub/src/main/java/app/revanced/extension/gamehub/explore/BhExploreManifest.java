package app.revanced.extension.gamehub.explore;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Explore screen's content model + loader.
 *
 * v1 = a BUNDLED JSON manifest (offline, zero network). The JSON wire format
 * is the stable contract; v2 can drop in a remote/asset source without
 * touching {@link BannerExploreActivity} — see GOG_LIBRARY_TAB_DESIGN §42.
 *
 * Load order: (1) optional shipped asset {@code assets/bh_explore.json}
 * (not bundled in v1, reserved for a future resource patch); (2) the
 * {@link #BUNDLED_JSON} constant. Any parse failure falls back to the bundled
 * default so the screen always renders.
 *
 * Schema:
 * <pre>
 * { "rails": [ { "title": "GOG",
 *               "cards": [ { "label": "...",
 *                            "subtitle": "...",   // optional
 *                            "action": "gog",     // see BhExploreActions
 *                            "arg": "..." } ] } ] }
 * </pre>
 */
public final class BhExploreManifest {

    private static final String TAG = "BhExplore";

    /**
     * Prototype manifest exercising every rail style so we can judge how "fancy"
     * the classic-View Explore screen can look:
     *   - "hero"      one full-width featured banner (network image + scrim)
     *   - "news"      wide cards w/ 16:9 image + headline + date → article page
     *   - "games"     portrait cover-art cards
     *   - "shortcuts" the original compact cards (default when type omitted)
     *
     * Network images use picsum.photos (seeded, so they're stable) purely to
     * demonstrate cover-art loading — real content would point at game art /
     * our own CDN. With no network every image falls back to a gradient
     * placeholder and the screen still reads fine.
     */
    static final String BUNDLED_JSON =
        "{\"rails\":["

        // ── Hero banner ────────────────────────────────────────────────
        + "{\"type\":\"hero\",\"cards\":["
        +   "{\"label\":\"Features & What's New\",\"subtitle\":\"Everything BannerHub adds to GameHub\",\"badge\":\"WHAT'S NEW\",\"action\":\"article\","
        +     "\"icon\":\"bh_explore_logo\","
        +     "\"arg\":\"https://github.com/The412Banner/bannerhub-revanced\","
        +     "\"body\":\""
        +       "WHAT'S NEW IN v1.6.0\\n"
        +       "\\u2022 GOG integration \\u2014 sign in and play your GOG library\\n"
        +       "\\u2022 BannerHub Explore \\u2014 our own offline discovery tab (this screen)\\n"
        +       "\\u2022 Recording-compatible audio \\u2014 screen recordings keep their game sound\\n"
        +       "\\nEVERYTHING WE'VE ADDED\\n"
        +       "\\u2022 No-login launch \\u2014 straight to your library, no account needed\\n"
        +       "\\u2022 Offline play for imported PC games (works in airplane mode)\\n"
        +       "\\u2022 PC-accurate controller vibration & rumble, with per-game settings\\n"
        +       "\\u2022 GPU spoof for better game compatibility\\n"
        +       "\\u2022 Legacy GLES2 renderer toggle\\n"
        +       "\\u2022 Strict per-game settings store\\n"
        +       "\\u2022 PC Game Settings & Game ID menu rows\\n"
        +       "\\u2022 BannerHub component catalog \\u2014 drivers, DXVK, VKD3D, translators\\n"
        +       "\\u2022 Custom BannerHub branding & app icon\\n"
        +       "\\u2022 Muted UI click sounds\\n"
        +       "\\nTap below to view the project on GitHub.\"}"
        + "]},"

        // ── Stores rail (real bundled GOG logo) ──────────────
        + "{\"title\":\"Your stores\",\"type\":\"shortcuts\",\"cards\":["
        +   "{\"label\":\"GOG\",\"subtitle\":\"Sign in & browse your library\",\"action\":\"gog\",\"icon\":\"bh_explore_gog\"}"
        + "]}"

        + "]}";

    private BhExploreManifest() { }

    public static final class Card {
        public final String label;
        public final String subtitle;
        public final String action;
        public final String arg;
        /** Optional android drawable resource NAME (resolved at runtime via
         *  getIdentifier against the host app's res, e.g. our injected
         *  "bh_bt_gog"). Null → the screen draws an accent-colour placeholder. */
        public final String icon;
        /** Optional network image URL (hero / cover / news thumbnail). */
        public final String image;
        /** Optional corner pill text, e.g. "NEW", "DRIVER". */
        public final String badge;
        /** Optional date/meta line (news cards). */
        public final String date;
        /** Optional article body shown by the "article" action's detail page. */
        public final String body;

        Card(String label, String subtitle, String action, String arg,
             String icon, String image, String badge, String date, String body) {
            this.label = label;
            this.subtitle = subtitle;
            this.action = action;
            this.arg = arg;
            this.icon = icon;
            this.image = image;
            this.badge = badge;
            this.date = date;
            this.body = body;
        }
    }

    public static final class Rail {
        /** Render style: "hero" | "news" | "games" | "shortcuts" (default). */
        public final String type;
        public final String title;
        public final List<Card> cards;

        Rail(String type, String title, List<Card> cards) {
            this.type = type;
            this.title = title;
            this.cards = cards;
        }
    }

    /**
     * External override paths, checked (in order) BEFORE the shipped asset and
     * the bundled default. Drop a {@code bh_explore.json} at any of these and
     * just reopen the Explore tab — no rebuild needed. Lets us iterate on
     * content, rails, cards, images and article text with zero CI builds.
     * Removed/invalid file → silently falls through to the shipped content.
     */
    private static final String[] OVERRIDES = {
        "/sdcard/Download/bh_explore.json",
        "/sdcard/bh_explore.json",
    };

    /**
     * Remote manifest published as a GitHub Release asset. {@code latest}
     * resolves to the newest NON-prerelease (stable) release, so only stable
     * cuts change the live content — prerelease/artifact-only builds don't.
     * Fetched off the main thread by {@link #refreshFromNetwork(Context)} and
     * cached locally so it's instant + offline-safe afterwards.
     */
    private static final String REMOTE_URL =
        "https://github.com/The412Banner/bannerhub-revanced/releases/latest/download/bh_explore.json";
    private static final String CACHE_NAME = "bh_explore_cache.json";

    // ── Version / update detection ────────────────────────────────────────────
    // INSTALLED version = assets/bh_version.json, baked into the APK at build
    // time by ExploreVersionAssetPatch (stamped by explore/stamp_version.py).
    // LATEST version = the "version"/"build" root fields of the remote manifest
    // (releases/latest/download/bh_explore.json), persisted to prefs so the
    // banner survives going offline. We compare the integer "build" values —
    // deliberately NOT getPackageInfo().versionName, which is the host GameHub
    // version, not our BannerHub release tag.
    private static final String VERSION_ASSET = "bh_version.json";
    private static final String PREFS = "bh_explore";
    private static final String KEY_LATEST_VER = "latest_ver";
    private static final String KEY_LATEST_BUILD = "latest_build";

    private static volatile String sInstalledVer;
    private static volatile int sInstalledBuild = Integer.MIN_VALUE; // unread sentinel

    /** Installed BannerHub version, e.g. "1.6.0-604" — null if the asset is
     *  absent (e.g. the standalone preview harness). */
    public static String installedVersion(Context ctx) {
        ensureInstalled(ctx);
        return sInstalledVer;
    }

    /** Installed build int (BASE*1e6 + MAJOR*1e4 + MINOR*1e2 + PATCH — the
     *  GameHub base is folded above the semver so a reset semver on a newer
     *  base still outranks the old base; see stamp_version.py), or -1 if
     *  unknown. Compared only by strict {@code latest > installed}. */
    public static int installedBuild(Context ctx) {
        ensureInstalled(ctx);
        return sInstalledBuild;
    }

    private static void ensureInstalled(Context ctx) {
        if (sInstalledBuild != Integer.MIN_VALUE) return; // read once per process
        sInstalledBuild = -1;
        try {
            String json = readAsset(ctx, VERSION_ASSET);
            if (json != null) {
                JSONObject o = new JSONObject(json);
                sInstalledVer = emptyToNull(o.optString("version", null));
                sInstalledBuild = o.optInt("build", -1);
            }
        } catch (Throwable ignored) { }
    }

    /** Latest published version (from the last seen remote manifest), or null. */
    public static String latestVersion(Context ctx) {
        return prefs(ctx).getString(KEY_LATEST_VER, null);
    }

    /** Latest published build int, or -1 if never fetched. */
    public static int latestBuild(Context ctx) {
        return prefs(ctx).getInt(KEY_LATEST_BUILD, -1);
    }

    /** True iff we know both versions and the latest build is newer. */
    public static boolean updateAvailable(Context ctx) {
        int installed = installedBuild(ctx);
        int latest = latestBuild(ctx);
        return installed > 0 && latest > 0 && latest > installed;
    }

    /** Persist the manifest root's version/build as the "latest" we've seen.
     *  Only writes when a positive build is present, so version-less sources
     *  (the bundled/offline fallback) never clobber a real fetched value. */
    private static void captureMeta(Context ctx, String json) {
        try {
            JSONObject root = new JSONObject(json);
            int build = root.optInt("build", -1);
            if (build <= 0) return;
            prefs(ctx).edit()
                .putString(KEY_LATEST_VER, emptyToNull(root.optString("version", null)))
                .putInt(KEY_LATEST_BUILD, build)
                .apply();
        } catch (Throwable ignored) { }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * Synchronous load for first render — never touches the network. Order:
     * external override → last-fetched remote cache → shipped asset → bundled.
     * The async {@link #refreshFromNetwork(Context)} updates the cache in the
     * background and the screen re-renders if it changed.
     * Never null; never throws.
     */
    public static List<Rail> load(Context ctx) {
        // 1. External override always wins (on-device live-edit / testing).
        String json = readOverride(ctx);
        // 2. Otherwise pick the NEWER of the baked asset vs the network cache,
        //    compared by manifest root "build". The baked asset is stamped with
        //    THIS build's number, so a freshly-shipped feature (e.g. the Banner
        //    Tools card) wins over a stale cached manifest fetched from an older
        //    stable on a prior online visit — that stale cache was previously
        //    able to shadow our bundled rails indefinitely. A genuinely newer
        //    live update (higher build) still wins.
        if (json == null || json.trim().isEmpty()) {
            String asset = readAsset(ctx, "bh_explore.json");
            String cache = readFile(cacheFile(ctx));
            if (cache != null && !cache.trim().isEmpty()
                    && buildOf(cache) > buildOf(asset)) {
                json = cache;
            } else if (asset != null && !asset.trim().isEmpty()) {
                json = asset;
            } else if (cache != null && !cache.trim().isEmpty()) {
                json = cache;
            }
        }
        // 3. Last resort: the in-APK hardcoded default.
        if (json == null || json.trim().isEmpty()) {
            json = BUNDLED_JSON;
        }
        captureMeta(ctx, json); // pick up "latest" from a cached/override manifest
        try {
            return parse(json);
        } catch (Throwable t) {
            Log.w(TAG, "manifest parse failed; using bundled default", t);
            try {
                return parse(BUNDLED_JSON);
            } catch (Throwable t2) {
                return new ArrayList<>();
            }
        }
    }

    private static List<Rail> parse(String json) throws Exception {
        List<Rail> rails = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray railArr = root.optJSONArray("rails");
        if (railArr == null) return rails;
        for (int i = 0; i < railArr.length(); i++) {
            JSONObject rj = railArr.optJSONObject(i);
            if (rj == null) continue;
            String type = rj.optString("type", "shortcuts");
            String title = rj.optString("title", "");
            List<Card> cards = new ArrayList<>();
            JSONArray cardArr = rj.optJSONArray("cards");
            if (cardArr != null) {
                for (int c = 0; c < cardArr.length(); c++) {
                    JSONObject cj = cardArr.optJSONObject(c);
                    if (cj == null) continue;
                    String action = cj.optString("action", "");
                    if (action.isEmpty()) continue;
                    cards.add(new Card(
                        cj.optString("label", action),
                        cj.optString("subtitle", null),
                        action,
                        cj.optString("arg", null),
                        cj.optString("icon", null),
                        cj.optString("image", null),
                        cj.optString("badge", null),
                        cj.optString("date", null),
                        cj.optString("body", null)));
                }
            }
            if (!cards.isEmpty()) rails.add(new Rail(type, title, cards));
        }
        return rails;
    }

    /**
     * Reads a live-edit override JSON from external storage (or the app's own
     * external files dir, which needs no runtime permission). First readable
     * one wins. Any error → null (fall through to shipped content).
     */
    private static String readOverride(Context ctx) {
        // App-private external dir first (no permission required to read).
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir != null) {
                String s = readFile(new java.io.File(dir, "bh_explore.json"));
                if (s != null) return s;
            }
        } catch (Throwable ignored) { }
        for (String path : OVERRIDES) {
            String s = readFile(new java.io.File(path));
            if (s != null) return s;
        }
        return null;
    }

    private static String readFile(java.io.File f) {
        try {
            if (f == null || !f.isFile() || !f.canRead()) return null;
            try (InputStream in = new java.io.FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                String s = bos.toString("UTF-8");
                Log.i(TAG, "using override manifest: " + f.getAbsolutePath());
                return s;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Background refresh: download {@link #REMOTE_URL}, validate it parses to a
     * non-empty manifest, and if it differs from the cached copy, persist it and
     * return the fresh rails (so the screen can re-render). Returns null on no
     * change, no network, or any error — the caller keeps showing what it has.
     * MUST be called off the main thread.
     */
    public static List<Rail> refreshFromNetwork(Context ctx) {
        try {
            String remote = httpGet(REMOTE_URL);
            if (remote == null || remote.trim().isEmpty()) return null;
            List<Rail> rails = parse(remote);          // reject garbage/empty
            if (rails.isEmpty()) return null;
            // Ignore a remote manifest older than what we shipped — the live
            // release asset tracks the latest STABLE, which can be behind a
            // prerelease/newer build. Without this, an older stable would
            // overwrite the cache and (pre the build-gated load order) shadow
            // our newer bundled rails. Compare root "build" vs installed.
            if (buildOf(remote) < installedBuild(ctx)) return null;
            captureMeta(ctx, remote);                  // refresh "latest" even if rails unchanged
            java.io.File cache = cacheFile(ctx);
            String prev = readFile(cache);
            if (remote.equals(prev)) return null;      // unchanged → no re-render
            writeFile(cache, remote);
            Log.i(TAG, "explore manifest updated from release asset");
            return rails;
        } catch (Throwable t) {
            Log.d(TAG, "remote refresh skipped: " + t.getMessage());
            return null;
        }
    }

    private static java.io.File cacheFile(Context ctx) {
        return new java.io.File(ctx.getCacheDir(), CACHE_NAME);
    }

    /** Manifest root "build" int (monotonic MAJOR*1e6+MINOR*1e3+PATCH), or 0 if
     *  absent/unparseable. Used to rank baked-asset vs cache vs remote so an
     *  older manifest can't shadow a newer build's content. */
    private static int buildOf(String json) {
        if (json == null || json.trim().isEmpty()) return 0;
        try {
            return new JSONObject(json).optInt("build", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String httpGet(String url) throws Exception {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);   // latest/download → asset CDN
            conn.connect();
            if (conn.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void writeFile(java.io.File f, String content) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(content.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "cache write failed", t);
        }
    }

    private static String readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Throwable ignored) {
            // No shipped asset in v1 — expected; fall back to BUNDLED_JSON.
            return null;
        }
    }
}
