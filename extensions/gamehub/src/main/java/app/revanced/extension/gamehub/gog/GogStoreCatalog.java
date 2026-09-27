package app.revanced.extension.gamehub.gog;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GOG's PUBLIC catalog — the same unauthenticated service the gog.com store page and Heroic use:
 *
 *   GET https://catalog.gog.com/v1/catalog?limit=…&order=desc:trending&productType=in:game,pack
 *       &page=1&countryCode=US&locale=en-US&currencyCode=USD
 *       [&query=like:<term>] [&discounted=eq:true] [&price=between:0,0]
 *
 * {@code products[]} carry {@code id}, {@code title}, {@code slug}, {@code coverHorizontal},
 * {@code coverVertical}, {@code developers[]}, {@code genres[{name}]}, {@code operatingSystems[]},
 * {@code releaseDate} ("YYYY.MM.DD"), {@code storeLink}, {@code reviewsRating} and a {@code price}
 * object with PRE-FORMATTED {@code final} / {@code base} strings plus {@code finalMoney.amount} /
 * {@code baseMoney.amount} and {@code discount} ("-85%"). Products that are not for sale have
 * {@code price == null}.
 *
 * Product detail (description / screenshots / videos) comes from the equally public
 * {@code api.gog.com/products/{id}?expand=description,screenshots,videos}.
 *
 * Everything is cached in-process (rails 30 min, searches 5 min, details 60 min) and the last good
 * rails are mirrored to SharedPreferences so the Store tab paints instantly — and offline — on the
 * next open. Undocumented endpoints: cache hard, degrade gracefully, never hard-fail the screen.
 *
 * Blocking; every public fetch runs on the caller's worker thread.
 */
public final class GogStoreCatalog {

    private static final String TAG = "GogStore";
    private static final String CATALOG = "https://catalog.gog.com/v1/catalog";
    private static final long FEATURED_TTL_MS = 30L * 60L * 1000L;
    private static final long SEARCH_TTL_MS = 5L * 60L * 1000L;
    private static final long DETAIL_TTL_MS = 60L * 60L * 1000L;
    private static final String PREFS = "gog_store_cache";
    private static final String KEY_FEATURED = "featured_json";
    private static final String KEY_FEATURED_AT = "featured_at";

    private static final Set<String> SUPPORTED_CURRENCIES = new HashSet<>(java.util.Arrays.asList(
        "USD", "EUR", "GBP", "AUD", "CAD", "CHF", "DKK", "NOK", "PLN", "SEK", "BRL", "CNY",
        "HKD", "ILS", "JPY", "KRW", "MXN", "NZD", "SGD", "TRY", "UAH", "ZAR", "INR", "RUB"));

    private GogStoreCatalog() {}

    /** The Store tab's rails. */
    public static final class Featured {
        public final GogCatalogItem hero;
        public final List<GogCatalogItem> trending;
        public final List<GogCatalogItem> newReleases;
        public final List<GogCatalogItem> deals;
        public final List<GogCatalogItem> free;

        Featured(GogCatalogItem hero, List<GogCatalogItem> trending, List<GogCatalogItem> newReleases,
                 List<GogCatalogItem> deals, List<GogCatalogItem> free) {
            this.hero = hero;
            this.trending = trending;
            this.newReleases = newReleases;
            this.deals = deals;
            this.free = free;
        }

        public boolean isEmpty() {
            return hero == null && trending.isEmpty() && newReleases.isEmpty()
                    && deals.isEmpty() && free.isEmpty();
        }
    }

    /** One screenshot: {@code thumb} for the strip, {@code full} for the viewer. */
    public static final class MediaImage {
        public final String thumb;
        public final String full;
        MediaImage(String thumb, String full) { this.thumb = thumb; this.full = full; }
    }

    /** A YouTube trailer (GOG only embeds YouTube). */
    public static final class MediaVideo {
        public final String youtubeId;
        public final String poster;
        public final String title;
        MediaVideo(String youtubeId, String poster, String title) {
            this.youtubeId = youtubeId; this.poster = poster; this.title = title;
        }
    }

    /** Everything GOG published for one title. Screenshot list capped at {@link #MAX_SCREENSHOTS}. */
    public static final class StoreMedia {
        public static final int MAX_SCREENSHOTS = 24;
        public final List<MediaImage> screenshots;
        public final List<MediaVideo> videos;
        StoreMedia(List<MediaImage> screenshots, List<MediaVideo> videos) {
            this.screenshots = screenshots; this.videos = videos;
        }
        public boolean isEmpty() { return screenshots.isEmpty() && videos.isEmpty(); }
        public int count() { return screenshots.size() + videos.size(); }
    }

    public static final class ProductDetail {
        public final String lead;
        public final String full;
        /** Strip-size screenshot URLs; the Media tab uses {@link #media} instead. */
        public final List<String> screenshots;
        public final String releaseDate;
        public final String background;
        public final String logo;
        public final StoreMedia media;
        ProductDetail(String lead, String full, List<String> screenshots, String releaseDate,
                      String background, String logo, StoreMedia media) {
            this.lead = lead; this.full = full; this.screenshots = screenshots;
            this.releaseDate = releaseDate; this.background = background; this.logo = logo;
            this.media = media;
        }
    }

    private static final class Cached<T> {
        final T value; final long at;
        Cached(T value, long at) { this.value = value; this.at = at; }
    }

    private static volatile Cached<Featured> featuredCache;
    private static final Map<String, Cached<List<GogCatalogItem>>> searchCache = new ConcurrentHashMap<>();
    private static final Map<String, Cached<ProductDetail>> detailCache = new ConcurrentHashMap<>();
    /** Ids whose detail fetch came back empty (GOG has no page) — cached as a miss. */
    private static final Map<String, Long> detailMiss = new ConcurrentHashMap<>();

    // ── Region ────────────────────────────────────────────────────────────────

    private static String country() {
        String c = Locale.getDefault().getCountry();
        return (c != null && c.length() == 2) ? c.toUpperCase(Locale.ROOT) : "US";
    }

    private static String currency() {
        try {
            String cc = Currency.getInstance(new Locale("", country())).getCurrencyCode();
            if (cc != null && SUPPORTED_CURRENCIES.contains(cc)) return cc;
        } catch (Exception ignored) {}
        return "USD";
    }

    private static String baseQuery(int limit, String order, String extra) {
        return CATALOG + "?limit=" + limit + "&order=" + order + "&productType=in:game,pack&page=1"
                + "&countryCode=" + country() + "&locale=en-US&currencyCode=" + currency() + extra;
    }

    /** GET the catalog; on a refusal of the locale currency, retry as US / USD. */
    private static List<GogCatalogItem> fetchProducts(String url) {
        String body = BhStoreNet.get(url);
        if (body == null && !url.contains("countryCode=US&locale=en-US&currencyCode=USD")) {
            String fallback = url
                    .replaceAll("countryCode=[A-Z]{2}", "countryCode=US")
                    .replaceAll("currencyCode=[A-Z]{3}", "currencyCode=USD");
            body = BhStoreNet.get(fallback);
        }
        if (body == null) return new ArrayList<>();
        return parseProducts(body);
    }

    // ── Rails ─────────────────────────────────────────────────────────────────

    /** The Store tab's rails. Null only when EVERY feed failed AND nothing is cached. */
    public static Featured featured(Context ctx, boolean force) {
        long now = System.currentTimeMillis();
        Cached<Featured> c = featuredCache;
        if (c != null && !force && now - c.at < FEATURED_TTL_MS) return c.value;

        List<GogCatalogItem> trending = fetchProducts(baseQuery(24, "desc:trending", ""));
        List<GogCatalogItem> newestAll = fetchProducts(baseQuery(40, "desc:releaseDate", ""));
        List<GogCatalogItem> newest = new ArrayList<>();
        for (GogCatalogItem i : newestAll) {
            if (!isFuture(i.releaseDate)) newest.add(i);
            if (newest.size() >= 24) break;
        }
        List<GogCatalogItem> deals = fetchProducts(baseQuery(24, "desc:discount", "&discounted=eq:true"));
        List<GogCatalogItem> free = fetchProducts(baseQuery(24, "desc:trending", "&price=between:0,0"));

        GogCatalogItem hero = null;
        for (GogCatalogItem i : trending) if (i.imageUrl != null) { hero = i; break; }
        if (hero == null && !deals.isEmpty()) hero = deals.get(0);
        List<GogCatalogItem> trendingRest = new ArrayList<>();
        for (GogCatalogItem i : trending) if (hero == null || !i.id.equals(hero.id)) trendingRest.add(i);

        Featured result = new Featured(hero, trendingRest, newest, deals, free);
        if (!result.isEmpty()) {
            featuredCache = new Cached<>(result, now);
            persistFeatured(ctx, result);
            Log.i(TAG, "rails: trending=" + trending.size() + " new=" + newest.size()
                    + " deals=" + deals.size() + " free=" + free.size());
            return result;
        }
        Log.w(TAG, "every catalog feed came back empty — falling back to the on-disk mirror");
        return loadPersistedFeatured(ctx);
    }

    /** Instant paint before the network answers: the last good rails from disk, if any. */
    public static Featured cachedFeatured(Context ctx) {
        Cached<Featured> c = featuredCache;
        return c != null ? c.value : loadPersistedFeatured(ctx);
    }

    public static List<GogCatalogItem> search(String query) {
        String term = query == null ? "" : query.trim();
        if (term.length() < 2) return new ArrayList<>();
        String key = term.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Cached<List<GogCatalogItem>> c = searchCache.get(key);
        if (c != null && now - c.at < SEARCH_TTL_MS) return c.value;
        String enc;
        try { enc = URLEncoder.encode(term, "UTF-8"); } catch (Exception e) { enc = term; }
        List<GogCatalogItem> results = fetchProducts(baseQuery(40, "desc:score", "&query=like:" + enc));
        searchCache.put(key, new Cached<>(results, now));
        Log.i(TAG, "search(\"" + term + "\") -> " + results.size() + " result(s)");
        return results;
    }

    /** Description + screenshots + videos for one product; null when GOG has no page for it. */
    public static ProductDetail product(String id) {
        long now = System.currentTimeMillis();
        Cached<ProductDetail> c = detailCache.get(id);
        if (c != null && now - c.at < DETAIL_TTL_MS) return c.value;
        Long miss = detailMiss.get(id);
        if (miss != null && now - miss < DETAIL_TTL_MS) return null;
        String body = BhStoreNet.get("https://api.gog.com/products/" + id
                + "?expand=description,screenshots,videos");
        ProductDetail detail = null;
        if (body != null) {
            try { detail = parseDetail(new JSONObject(body)); } catch (Exception ignored) {}
        }
        if (detail != null) detailCache.put(id, new Cached<>(detail, now));
        else detailMiss.put(id, now);
        return detail;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private static List<GogCatalogItem> parseProducts(String body) {
        try {
            JSONArray arr = new JSONObject(body).optJSONArray("products");
            if (arr == null) arr = new JSONArray();
            LinkedHashMap<String, GogCatalogItem> out = new LinkedHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                GogCatalogItem item = parseProduct(p);
                if (item != null && !out.containsKey(item.id)) out.put(item.id, item);
            }
            return new ArrayList<>(out.values());
        } catch (Exception e) {
            Log.w(TAG, "catalog parse failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    static GogCatalogItem parseProduct(JSONObject p) {
        String id = p.optString("id", "");
        String title = p.optString("title", "");
        if (id.isEmpty() || title.isEmpty()) return null;
        // Windows only — the app cannot run anything else.
        JSONArray os = p.optJSONArray("operatingSystems");
        if (os != null && os.length() > 0) {
            boolean windows = false;
            for (int i = 0; i < os.length(); i++) if ("windows".equalsIgnoreCase(os.optString(i))) windows = true;
            if (!windows) return null;
        }
        JSONObject price = p.optJSONObject("price");
        Double finalAmount = null;
        String finalStr = "", baseStr = "", discountStr = "";
        if (price != null) {
            JSONObject fm = price.optJSONObject("finalMoney");
            if (fm != null) {
                try { finalAmount = Double.parseDouble(fm.optString("amount", "")); } catch (Exception ignored) {}
            }
            finalStr = price.optString("final", "");
            baseStr = price.optString("base", "");
            discountStr = price.optString("discount", "");
        }
        int discount = 0;
        StringBuilder digits = new StringBuilder();
        for (char ch : discountStr.toCharArray()) if (Character.isDigit(ch)) digits.append(ch);
        if (digits.length() > 0) { try { discount = Integer.parseInt(digits.toString()); } catch (Exception ignored) {} }
        boolean isFree = price != null && ((finalAmount != null && finalAmount == 0.0)
                || "$0.00".equals(finalStr) || "0".equals(finalStr));

        JSONArray genres = p.optJSONArray("genres");
        StringBuilder tags = new StringBuilder();
        if (genres != null) {
            for (int i = 0; i < Math.min(3, genres.length()); i++) {
                JSONObject g = genres.optJSONObject(i);
                String name = g != null ? g.optString("name", "") : "";
                if (name.trim().isEmpty()) continue;
                if (tags.length() > 0) tags.append(", ");
                tags.append(name);
            }
        }
        JSONArray devs = p.optJSONArray("developers");
        String developer = devs != null ? devs.optString(0, "") : "";
        String slug = p.optString("slug", "");
        String storeLink = p.optString("storeLink", "");
        if (storeLink.trim().isEmpty()) {
            storeLink = slug.trim().isEmpty() ? "" : "https://www.gog.com/en/game/" + slug;
        }
        int rating = p.optInt("reviewsRating", 0);
        Map<String, String> extra = new HashMap<>();
        if (!slug.trim().isEmpty()) extra.put("slug", slug);
        if (rating > 0) extra.put("rating", String.valueOf(rating));
        String type = p.optString("productType", "");
        if (!type.trim().isEmpty()) extra.put("type", type);

        return new GogCatalogItem(id, title,
                p.optString("coverHorizontal", ""), p.optString("coverVertical", ""),
                tags.toString(), isFree,
                price != null && !finalStr.trim().isEmpty(),
                finalStr,
                discount > 0 ? baseStr : "",
                (discount > 0 && !baseStr.trim().isEmpty()) ? discount : 0,
                storeLink, developer, p.optString("releaseDate", ""), "", extra);
    }

    /** Pure parse of an {@code api.gog.com/products/{id}} body (no I/O). */
    static ProductDetail parseDetail(JSONObject o) {
        JSONObject desc = o.optJSONObject("description");
        List<String> shots = new ArrayList<>();
        List<MediaImage> images = new ArrayList<>();
        JSONArray arr = o.optJSONArray("screenshots");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            // Strip size: the pre-formatted medium jpg (`ggvgm`), else the template with that
            // formatter. Viewer size: the same template at `ggvgm_2x` (thumb when there is none).
            String url = "";
            JSONArray formatted = s.optJSONArray("formatted_images");
            if (formatted != null) for (int j = 0; j < formatted.length(); j++) {
                JSONObject f = formatted.optJSONObject(j);
                if (f == null) continue;
                if ("ggvgm".equals(f.optString("formatter_name"))) { url = f.optString("image_url"); break; }
            }
            String tpl = s.optString("formatter_template_url", "");
            if (url.trim().isEmpty() && tpl.contains("{formatter}")) url = renderTemplate(tpl, "ggvgm");
            url = absolutize(url);
            if (url.isEmpty()) continue;
            shots.add(url);
            if (images.size() < StoreMedia.MAX_SCREENSHOTS) {
                String full = tpl.contains("{formatter}") ? absolutize(renderTemplate(tpl, "ggvgm_2x")) : url;
                images.add(new MediaImage(url, full));
            }
        }
        List<MediaVideo> videos = new ArrayList<>();
        JSONArray vids = o.optJSONArray("videos");
        if (vids != null) for (int i = 0; i < vids.length(); i++) {
            JSONObject v = vids.optJSONObject(i);
            if (v == null) continue;
            // GOG only embeds YouTube; anything else has no player here.
            if (!"youtube".equalsIgnoreCase(v.optString("provider", "youtube"))) continue;
            String id = v.optString("video_id", "").trim();
            if (id.isEmpty()) continue;
            String poster = absolutize(v.optString("thumbnail_url", ""));
            if (poster.isEmpty()) poster = "https://img.youtube.com/vi/" + id + "/hqdefault.jpg";
            videos.add(new MediaVideo(id, poster, ""));
        }
        // GOG names none of its videos: "Trailer" alone, "Trailer 1..N" when there are several.
        List<MediaVideo> named = new ArrayList<>(videos.size());
        for (int i = 0; i < videos.size(); i++) {
            MediaVideo v = videos.get(i);
            named.add(new MediaVideo(v.youtubeId, v.poster,
                    videos.size() == 1 ? "Trailer" : "Trailer " + (i + 1)));
        }
        JSONObject imgs = o.optJSONObject("images");
        String background = imgs != null ? absolutize(imgs.optString("background", "")) : "";
        String logo = imgs != null ? absolutize(imgs.optString("logo2x", "")) : "";
        return new ProductDetail(
                desc != null ? desc.optString("lead", "") : "",
                desc != null ? desc.optString("full", "") : "",
                shots,
                o.optString("release_date", ""),
                background.isEmpty() ? null : background,
                logo.isEmpty() ? null : logo,
                new StoreMedia(images, named));
    }

    /** {@code …_{formatter}.png} template → the jpg rendition for {@code formatter}. */
    private static String renderTemplate(String template, String formatter) {
        return template.replace("{formatter}", formatter).replace(".png", ".jpg");
    }

    /** GOG returns protocol-relative {@code //images…} URLs in a few places. */
    public static String absolutize(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        return url.startsWith("//") ? "https:" + url : url;
    }

    /** "YYYY.MM.DD" later than today → an unreleased pre-order, kept out of "New releases". */
    private static boolean isFuture(String date) {
        if (date == null || date.length() < 10) return false;
        String[] parts = date.substring(0, 10).split("[.\\-]");
        if (parts.length != 3) return false;
        try {
            int y = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            int d = Integer.parseInt(parts[2]);
            Calendar cal = Calendar.getInstance();
            int today = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100
                    + cal.get(Calendar.DAY_OF_MONTH);
            return y * 10000 + m * 100 + d > today;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Disk mirror ───────────────────────────────────────────────────────────

    private static void persistFeatured(Context ctx, Featured f) {
        try {
            JSONObject o = new JSONObject();
            if (f.hero != null) o.put("hero", f.hero.toJson());
            o.put("trending", GogCatalogItem.listToJson(f.trending));
            o.put("new", GogCatalogItem.listToJson(f.newReleases));
            o.put("deals", GogCatalogItem.listToJson(f.deals));
            o.put("free", GogCatalogItem.listToJson(f.free));
            ctx.getSharedPreferences(PREFS, 0).edit()
                    .putString(KEY_FEATURED, o.toString())
                    .putLong(KEY_FEATURED_AT, System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}
    }

    private static Featured loadPersistedFeatured(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            String s = p.getString(KEY_FEATURED, null);
            if (s == null) return null;
            JSONObject o = new JSONObject(s);
            Featured f = new Featured(
                    GogCatalogItem.fromJson(o.optJSONObject("hero")),
                    GogCatalogItem.listFromJson(o.optJSONArray("trending")),
                    GogCatalogItem.listFromJson(o.optJSONArray("new")),
                    GogCatalogItem.listFromJson(o.optJSONArray("deals")),
                    GogCatalogItem.listFromJson(o.optJSONArray("free")));
            return f.isEmpty() ? null : f;
        } catch (Exception e) {
            return null;
        }
    }
}
