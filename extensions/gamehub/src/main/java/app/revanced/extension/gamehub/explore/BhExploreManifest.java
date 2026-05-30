package app.revanced.extension.gamehub.explore;

import android.content.Context;
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

        // ── News / changelog rail ──────────────────────────────────────
        + "{\"title\":\"What's New\",\"type\":\"news\",\"cards\":["
        +   "{\"label\":\"New Turnip R4 driver available\",\"date\":\"May 29\",\"badge\":\"DRIVER\",\"action\":\"article\","
        +     "\"image\":\"https://picsum.photos/seed/turnip/600/340\","
        +     "\"body\":\"A fresh Turnip R4 build is out with a One UI gralloc fix and a7xx performance work. Grab it from the in-app driver downloader.\"},"
        +   "{\"label\":\"Dirt 3 now runs end-to-end\",\"date\":\"May 27\",\"badge\":\"COMPAT\",\"action\":\"article\","
        +     "\"image\":\"https://picsum.photos/seed/dirt3/600/340\","
        +     "\"body\":\"Dirt 3 launches cleanly with DXVK 2.4.1 + Proton 9 arm64ec. Pin DXVK 2.4.1 to avoid the Turnip timeline-semaphore regression in 2.5+.\"},"
        +   "{\"label\":\"Join the community\",\"date\":\"\",\"badge\":\"DISCORD\",\"action\":\"url\",\"arg\":\"https://discord.gg/\","
        +     "\"image\":\"https://picsum.photos/seed/discord/600/340\"}"
        + "]},"

        // ── Featured games rail (cover art) ────────────────────────────
        + "{\"title\":\"Plays great on BannerHub\",\"type\":\"games\",\"cards\":["
        +   "{\"label\":\"GTA V\",\"action\":\"article\",\"image\":\"https://picsum.photos/seed/gtav/300/430\",\"body\":\"Verified in airplane mode. DXVK 2.4.1, Proton 9 arm64ec, Turnip R4.\"},"
        +   "{\"label\":\"DOOM\",\"action\":\"article\",\"image\":\"https://picsum.photos/seed/doom/300/430\",\"body\":\"Controller + audio confirmed with the proton10 arm64x xinput fix.\"},"
        +   "{\"label\":\"Tomb Raider\",\"action\":\"article\",\"image\":\"https://picsum.photos/seed/tomb/300/430\",\"body\":\"Solid with Zink renderer; try Vegas FrameGen for higher frame rates.\"},"
        +   "{\"label\":\"Genshin\",\"action\":\"article\",\"image\":\"https://picsum.photos/seed/genshin/300/430\",\"body\":\"Runs in the Genshin variant build.\"}"
        + "]},"

        // ── Stores rail (real bundled GOG logo + compact cards) ────────
        + "{\"title\":\"Your stores\",\"type\":\"shortcuts\",\"cards\":["
        +   "{\"label\":\"GOG\",\"subtitle\":\"Sign in & browse your library\",\"action\":\"gog\",\"icon\":\"bh_explore_gog\"},"
        +   "{\"label\":\"Epic Games\",\"subtitle\":\"Coming soon\",\"action\":\"soon\"},"
        +   "{\"label\":\"Steam\",\"subtitle\":\"Coming soon\",\"action\":\"soon\"}"
        + "]},"

        // ── Tips rail (compact) ────────────────────────────────────────
        + "{\"title\":\"Tips & tricks\",\"type\":\"shortcuts\",\"cards\":["
        +   "{\"label\":\"Recording-safe audio\",\"subtitle\":\"Banner Tools \\u2192 Audio\",\"action\":\"article\",\"body\":\"Enable the recording-compatible audio toggle so MediaProjection captures game sound instead of silence.\"},"
        +   "{\"label\":\"Pick a renderer\",\"subtitle\":\"Zink works today\",\"action\":\"article\",\"body\":\"The per-container Renderer dropdown lets you choose the GL backend. Zink is the shipped, working option.\"},"
        +   "{\"label\":\"Frame generation\",\"subtitle\":\"Vegas FrameGen\",\"action\":\"article\",\"body\":\"Vegas FrameGen (GameHub AI Frame Gen) boosts perceived frame rate on supported titles.\"}"
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

    /** Returns the rails to render. Never null; never throws. */
    public static List<Rail> load(Context ctx) {
        String json = readOverride(ctx);
        if (json == null || json.trim().isEmpty()) {
            json = readAsset(ctx, "bh_explore.json");
        }
        if (json == null || json.trim().isEmpty()) {
            json = BUNDLED_JSON;
        }
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
