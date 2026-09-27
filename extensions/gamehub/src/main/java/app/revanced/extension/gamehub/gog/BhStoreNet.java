package app.revanced.extension.gamehub.gog;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Tiny blocking HTTP helper for the GOG storefront and profile fetches. Every
 * call is fail-soft (null on any non-2xx or exception) — the storefront
 * degrades, it never crashes.
 *
 * Call from a worker thread only.
 */
final class BhStoreNet {

    private static final String TAG = "BhStoreNet";

    /** A desktop-browser UA: GOG's public catalog answers this; it refuses the bare Java UA. */
    static final String BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** The UA GOG's account endpoints (embed.gog.com) expect. */
    static final String GALAXY_UA = "GOG Galaxy";

    private BhStoreNet() {}

    static String get(String url) {
        return get(url, null, BROWSER_UA, 20000, null);
    }

    static String get(String url, String bearer) {
        return get(url, bearer, BROWSER_UA, 20000, null);
    }

    static String get(String url, String bearer, String userAgent) {
        return get(url, bearer, userAgent, 20000, null);
    }

    static String get(String url, String bearer, String userAgent, int timeoutMs,
                      Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", userAgent == null ? BROWSER_UA : userAgent);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code > 299) {
                Log.w(TAG, "GET " + code + " " + stripQuery(url));
                return null;
            }
            return readAll(conn);
        } catch (Exception e) {
            Log.w(TAG, "GET failed " + stripQuery(url) + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
