package app.revanced.extension.gamehub.gog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One GOG catalog entry as the storefront cards draw it — the store-agnostic
 * {@code CatalogItem} of the Bannerlator storefront, cut down to the single
 * store this extension carries.
 *
 * Carries pre-resolved image URLs and PRE-FORMATTED price strings: GOG hands
 * us "$1.49" already localised, so the UI never does currency maths.
 *
 * Also the JSON codec the disk mirror ({@link GogStoreCatalog}) and the
 * catalog-detail intent ({@link GogCatalogDetailActivity}) share.
 */
public final class GogCatalogItem {

    /** What the action button on a catalog card should offer right now. */
    public enum Action {
        /** Owned and installed → open the owned detail page (Play lives there). */
        OPEN_INSTALLED,
        /** Owned, not installed → open the owned detail page (Install lives there). */
        OPEN_OWNED,
        /** Free and not owned → claim it on GOG.com. */
        CLAIM_FREE,
        /** Paid and not owned → view the store page. The app cannot buy anything. */
        VIEW_ON_STORE,
    }

    /** GOG product id. */
    public final String id;
    public final String title;
    /** Wide (landscape) art for rails and heroes; may be null. */
    public final String imageUrl;
    /** Tall (2:3) box art when the store publishes one; may be null. */
    public final String tallImageUrl;
    /** Comma-joined genre line under the title in result rows. */
    public final String tags;
    public final boolean isFree;
    /** False when the endpoint gave no price at all — the price row then draws nothing. */
    public final boolean hasPrice;
    /** Pre-formatted by the store ("$9.99"). */
    public final String finalPrice;
    public final String originalPrice;
    public final int discountPercent;
    /** The web product page — where "Get for free" / "View on GOG.com" land. */
    public final String storeUrl;
    public final String developer;
    public final String releaseDate;
    public final String description;
    /** slug / rating / type. */
    public final Map<String, String> extra;

    public GogCatalogItem(String id, String title, String imageUrl, String tallImageUrl,
                          String tags, boolean isFree, boolean hasPrice, String finalPrice,
                          String originalPrice, int discountPercent, String storeUrl,
                          String developer, String releaseDate, String description,
                          Map<String, String> extra) {
        this.id = id;
        this.title = title;
        this.imageUrl = blankToNull(imageUrl);
        this.tallImageUrl = blankToNull(tallImageUrl);
        this.tags = tags == null ? "" : tags;
        this.isFree = isFree;
        this.hasPrice = hasPrice;
        this.finalPrice = finalPrice == null ? "" : finalPrice;
        this.originalPrice = originalPrice == null ? "" : originalPrice;
        this.discountPercent = discountPercent;
        this.storeUrl = storeUrl == null ? "" : storeUrl;
        this.developer = developer == null ? "" : developer;
        this.releaseDate = releaseDate == null ? "" : releaseDate;
        this.description = description == null ? "" : description;
        this.extra = extra == null ? new HashMap<>() : extra;
    }

    public boolean isDiscounted() {
        return discountPercent > 0 && !originalPrice.isEmpty();
    }

    /** Wide art first, tall art as the fallback — for rails, rows and heroes. */
    public List<String> wideArt() {
        List<String> out = new ArrayList<>(2);
        if (imageUrl != null) out.add(imageUrl);
        if (tallImageUrl != null) out.add(tallImageUrl);
        return out;
    }

    /** Tall art first, wide as the fallback — for 2:3 library tiles. */
    public List<String> tallArt() {
        List<String> out = new ArrayList<>(2);
        if (tallImageUrl != null) out.add(tallImageUrl);
        if (imageUrl != null) out.add(imageUrl);
        return out;
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s;
    }

    // ── JSON codec ────────────────────────────────────────────────────────────

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id); o.put("title", title);
            o.put("image", imageUrl == null ? "" : imageUrl);
            o.put("tall", tallImageUrl == null ? "" : tallImageUrl);
            o.put("tags", tags); o.put("free", isFree); o.put("hasPrice", hasPrice);
            o.put("final", finalPrice); o.put("orig", originalPrice); o.put("disc", discountPercent);
            o.put("url", storeUrl); o.put("dev", developer); o.put("rel", releaseDate);
            o.put("desc", description);
            o.put("extra", new JSONObject(extra));
        } catch (Exception ignored) {}
        return o;
    }

    public static GogCatalogItem fromJson(JSONObject o) {
        if (o == null) return null;
        Map<String, String> extra = new HashMap<>();
        JSONObject e = o.optJSONObject("extra");
        if (e != null) {
            Iterator<String> keys = e.keys();
            while (keys.hasNext()) { String k = keys.next(); extra.put(k, e.optString(k)); }
        }
        String id = o.optString("id", "");
        if (id.isEmpty()) return null;
        return new GogCatalogItem(id, o.optString("title", ""),
                o.optString("image", ""), o.optString("tall", ""),
                o.optString("tags", ""), o.optBoolean("free", false), o.optBoolean("hasPrice", false),
                o.optString("final", ""), o.optString("orig", ""), o.optInt("disc", 0),
                o.optString("url", ""), o.optString("dev", ""), o.optString("rel", ""),
                o.optString("desc", ""), extra);
    }

    public static JSONArray listToJson(List<GogCatalogItem> list) {
        JSONArray a = new JSONArray();
        if (list != null) for (GogCatalogItem i : list) a.put(i.toJson());
        return a;
    }

    public static List<GogCatalogItem> listFromJson(JSONArray a) {
        List<GogCatalogItem> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            GogCatalogItem it = fromJson(a.optJSONObject(i));
            if (it != null) out.add(it);
        }
        return out;
    }
}
