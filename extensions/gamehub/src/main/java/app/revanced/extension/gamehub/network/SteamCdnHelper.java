package app.revanced.extension.gamehub.network;

import android.content.SharedPreferences;

import app.revanced.extension.gamehub.util.GHLog;
import com.blankj.utilcode.util.Utils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves Steam game header image URLs via Steam's official CDN.
 *
 * <p>Replaces the third-party bigeyes CDN URLs with URLs resolved from
 * Steam's {@code IStoreBrowseService/GetItems/v1} API, using a 3-tier cache:
 * <ol>
 *   <li>L1: {@link ConcurrentHashMap} (in-memory, instant)</li>
 *   <li>L2: {@link SharedPreferences} (persistent, 7-day TTL)</li>
 *   <li>L3: Steam Store API (HTTP, async batched — up to 50 IDs per request)</li>
 * </ol>
 *
 * <p>On L1/L2 miss, returns a generic Steam CDN fallback URL immediately and
 * queues the appId for an async batch fetch. IDs are accumulated for 150ms,
 * then resolved in a single API call (up to 50 per batch).
 */
@SuppressWarnings("unused")
public class SteamCdnHelper {

    private static final GHLog L = GHLog.CDN;

    private static final String STORE_API_URL = "https://api.steampowered.com/IStoreBrowseService/GetItems/v1/";
    private static final String CDN_FALLBACK_BASE = "https://shared.steamstatic.com/store_item_assets/";
    private static final String BIGEYES_CDN_BASE = "https://cdn-library-logo-global.bigeyes.com/";
    private static final String PREFS_NAME = "steam_cdn_cache";
    private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000; // 7 days
    private static final int BATCH_SIZE = 50;
    private static final long BATCH_DELAY_MS = 150;

    // L1: in-memory thread-safe cache.
    private static final ConcurrentHashMap<Integer, String> l1Cache = new ConcurrentHashMap<>();

    // Queue of appIds waiting for the next batch fetch.
    private static final ConcurrentLinkedQueue<Integer> fetchQueue = new ConcurrentLinkedQueue<>();

    // Guards against scheduling multiple overlapping batch timers.
    private static final AtomicBoolean batchScheduled = new AtomicBoolean(false);

    // Single daemon background thread for batched L3 API calls.
    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "steam-cdn");
        t.setDaemon(true);
        return t;
    });

    /**
     * Resolves the header image URL for the given Steam app ID.
     * Called from patched {@code SteamUrlHelper.b()} and {@code SteamUrlHelper.e()}.
     *
     * <p>Safe to call from any thread (including main). L1 and L2 return instantly;
     * L3 fetches run asynchronously and populate the cache for subsequent calls.
     *
     * @param appId Steam application ID
     * @return resolved header image URL (never null)
     */
    public static String resolveHeaderUrl(int appId) {
        if (appId <= 0) {
            return CDN_FALLBACK_BASE + "steam/apps/0/header.jpg";
        }

        // L1: in-memory cache.
        String cached = l1Cache.get(appId);
        if (cached != null) {
            return cached;
        }

        // L2: SharedPreferences persistent cache.
        try {
            SharedPreferences prefs = getCdnPrefs();
            String url = prefs.getString("url_" + appId, null);
            long expiry = prefs.getLong("exp_" + appId, 0);
            if (url != null && !url.isEmpty() && System.currentTimeMillis() < expiry) {
                l1Cache.put(appId, url);
                L.d("resolveHeaderUrl(" + appId + ") → L2 hit");
                return url;
            }
        } catch (Exception e) {
            L.w("resolveHeaderUrl: L2 error", e);
        }

        // L3: return fallback immediately, queue for async batch fetch.
        fetchQueue.add(appId);
        if (batchScheduled.compareAndSet(false, true)) {
            executor.schedule(SteamCdnHelper::processBatch, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
        return CDN_FALLBACK_BASE + "steam/apps/" + appId + "/header.jpg";
    }

    /**
     * Drains the fetch queue and resolves all queued appIds in batches of
     * {@value BATCH_SIZE} via the Steam Store API. Populates L1 + L2 caches.
     */
    private static void processBatch() {
        batchScheduled.set(false);

        List<Integer> batch = new ArrayList<>();
        Integer id;
        while ((id = fetchQueue.poll()) != null) {
            // Skip if already resolved while queued (e.g. by a prior batch).
            if (l1Cache.containsKey(id)) continue;
            batch.add(id);

            if (batch.size() >= BATCH_SIZE) {
                executeBatch(batch);
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) {
            executeBatch(batch);
        }

        // If more IDs arrived while we were processing, schedule another round.
        if (!fetchQueue.isEmpty() && batchScheduled.compareAndSet(false, true)) {
            executor.schedule(SteamCdnHelper::processBatch, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Executes a single batch API call and caches the results.
     */
    private static void executeBatch(List<Integer> appIds) {
        L.d("executeBatch: " + appIds.size() + " IDs");
        try {
            Map<Integer, String> results = fetchBatchFromSteamApi(appIds);
            if (results.isEmpty()) {
                L.d("executeBatch: no results from API");
                return;
            }

            // Batch the SharedPreferences writes into a single apply().
            SharedPreferences.Editor editor = getCdnPrefs().edit();
            long expiry = System.currentTimeMillis() + CACHE_TTL_MS;
            for (Map.Entry<Integer, String> entry : results.entrySet()) {
                l1Cache.put(entry.getKey(), entry.getValue());
                editor.putString("url_" + entry.getKey(), entry.getValue());
                editor.putLong("exp_" + entry.getKey(), expiry);
            }
            editor.apply();

            L.d("executeBatch: cached " + results.size() + " URLs");
        } catch (Exception e) {
            L.w("executeBatch failed", e);
        }
    }

    private static SharedPreferences getCdnPrefs() {
        return Utils.a().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    /**
     * Fetches header image URLs for a batch of appIds from Steam's
     * {@code IStoreBrowseService/GetItems/v1} API in a single HTTP call.
     *
     * @return map of appId → resolved header URL (only for successful lookups)
     */
    private static Map<Integer, String> fetchBatchFromSteamApi(List<Integer> appIds) throws Exception {
        StringBuilder idsJson = new StringBuilder("[");
        for (int i = 0; i < appIds.size(); i++) {
            if (i > 0) idsJson.append(',');
            idsJson.append("{\"appid\":").append(appIds.get(i)).append('}');
        }
        idsJson.append(']');

        String inputJson = "{\"ids\":" + idsJson + ","
                + "\"context\":{\"language\":\"english\",\"country_code\":\"US\"},"
                + "\"data_request\":{\"include_assets\":true}}";
        String url = STORE_API_URL + "?input_json=" + URLEncoder.encode(inputJson, "UTF-8");

        L.d("fetchBatch: GET " + appIds.size() + " IDs, URL length=" + url.length());

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);

            int code = conn.getResponseCode();
            L.d("fetchBatch: HTTP " + code);
            if (code != 200) return new HashMap<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return parseBatchResponse(sb.toString());
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Parses a batch response from {@code IStoreBrowseService/GetItems/v1}.
     *
     * <p>Iterates through {@code response.store_items[]}, extracting each item's
     * {@code appid}, {@code assets.asset_url_format}, and {@code assets.header}
     * to build the resolved URL.
     */
    private static Map<Integer, String> parseBatchResponse(String json) {
        Map<Integer, String> results = new HashMap<>();
        if (json == null || json.isEmpty()) return results;

        try {
            JSONObject root = new JSONObject(json);
            JSONObject response = root.optJSONObject("response");
            if (response == null) return results;

            JSONArray items = response.optJSONArray("store_items");
            if (items == null) return results;

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;

                int appId = item.optInt("appid", -1);
                if (appId <= 0) continue;

                JSONObject assets = item.optJSONObject("assets");
                if (assets == null) continue;

                String formatUrl = assets.optString("asset_url_format", null);
                String header = assets.optString("header", null);
                if (formatUrl != null && header != null) {
                    results.put(appId, formatUrl.replace("${FILENAME}", header));
                }
            }
        } catch (Exception e) {
            L.w("parseBatchResponse failed", e);
        }

        return results;
    }

    /**
     * Rewrites a bigeyes CDN URL or relative {@code steam/} path to a full Steam CDN URL.
     *
     * @return the rewritten URL, or {@code null} if no rewriting was needed
     */
    private static String rewriteCdnUrl(String url) {
        if (url.startsWith("steam/")) {
            return CDN_FALLBACK_BASE + url;
        }
        if (url.startsWith(BIGEYES_CDN_BASE)) {
            return CDN_FALLBACK_BASE + url.substring(BIGEYES_CDN_BASE.length());
        }
        return null;
    }

    /**
     * Normalizes an image URL from the API response. If the URL is a relative
     * {@code steam/} path or a bigeyes CDN URL, rewrites it to a Steam CDN URL.
     *
     * <p>Called from the patched {@code ResizeQueryParamGlideModelLoader} to fix
     * URLs that the EmuReady API returns pointing to the wrong CDN.
     *
     * @return the full URL if normalization was needed, or {@code null} to
     *         let the original model loader logic handle it
     */
    public static String resolveImageUrl(String url) {
        if (url == null) return null;
        String rewritten = rewriteCdnUrl(url);
        if (rewritten != null) {
            L.d("resolveImageUrl: " + url + " → " + rewritten);
        }
        return rewritten;
    }

    /**
     * Normalizes an image URL, returning the original if no normalization is needed.
     * Called from patched getters ({@code CardItemData.getCoverImagePath()},
     * {@code GameDetailEntity.getBack_image()}, etc.) to ensure all image URLs
     * have a full scheme+domain before reaching Glide.
     *
     * @return the normalized URL, or the original if it already has a scheme
     */
    public static String normalizeOrPass(String url) {
        if (url == null) return null;
        String rewritten = rewriteCdnUrl(url);
        return rewritten != null ? rewritten : url;
    }
}
