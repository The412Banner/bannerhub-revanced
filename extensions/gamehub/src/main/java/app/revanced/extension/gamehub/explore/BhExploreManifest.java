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

    /** v1 GOG-only rail. Full hijack, offline. */
    static final String BUNDLED_JSON =
        "{"
        + "\"rails\":["
        + "{\"title\":\"GOG\",\"cards\":["
        + "{\"label\":\"GOG\",\"subtitle\":\"Sign in & browse your GOG library\",\"action\":\"gog\",\"icon\":\"bh_explore_gog\"}"
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

        Card(String label, String subtitle, String action, String arg, String icon) {
            this.label = label;
            this.subtitle = subtitle;
            this.action = action;
            this.arg = arg;
            this.icon = icon;
        }
    }

    public static final class Rail {
        public final String title;
        public final List<Card> cards;

        Rail(String title, List<Card> cards) {
            this.title = title;
            this.cards = cards;
        }
    }

    /** Returns the rails to render. Never null; never throws. */
    public static List<Rail> load(Context ctx) {
        String json = readAsset(ctx, "bh_explore.json");
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
                        cj.optString("icon", null)));
                }
            }
            if (!cards.isEmpty()) rails.add(new Rail(title, cards));
        }
        return rails;
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
