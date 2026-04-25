package app.revanced.extension.gamehub.token;

import android.content.SharedPreferences;

import app.revanced.extension.gamehub.util.GHLog;
import com.blankj.utilcode.util.Utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides dynamic token resolution based on which patches are applied.
 *
 * <p>Decision tree:
 * <ul>
 *   <li>API Switch patched AND EmuReady selected → {@code "fake-token"}</li>
 *   <li>Login bypassed → token from refresh service (3-tier cache)</li>
 *   <li>Otherwise → original token (pass-through)</li>
 * </ul>
 *
 * <p>Flags are set at class-init time by bytecode injection from the respective patches.
 */
@SuppressWarnings("unused")
public class TokenProvider {

    /** Set to {@code true} by API Server Switch patch via {@code <clinit>} injection. */
    public static boolean apiSwitchPatched = false;

    /** Set to {@code true} by Bypass Login patch via {@code <clinit>} injection. */
    public static boolean loginBypassed = false;

    private static final String TOKEN_SERVICE_URL = "https://gamehub-lite-token-refresher.emuready.workers.dev/token";
    private static final String AUTH_HEADER_VALUE = "gamehub-internal-token-fetch-2025";
    private static final long CACHE_TTL_MS = 4 * 60 * 60 * 1000L; // 4 hours

    private static final String PREFS_NAME = "token_provider_pref";
    private static final String KEY_CACHED_TOKEN = "cached_token";
    private static final String KEY_CACHED_TOKEN_EXPIRY = "cached_token_expiry";

    // L1 cache: in-memory, lock-free via AtomicReference.
    private static final AtomicReference<CachedToken> l1Cache = new AtomicReference<>(null);

    private static final class CachedToken {
        final String token;
        final long expiryMs;

        CachedToken(String token, long expiryMs) {
            this.token = token;
            this.expiryMs = expiryMs;
        }

        boolean isValid() {
            return token != null && !token.isEmpty() && System.currentTimeMillis() < expiryMs;
        }
    }

    /**
     * Clears all token caches (L1 in-memory + L2 SharedPreferences).
     * Called when the API source changes to force fresh token resolution.
     */
    public static void clearCache() {
        l1Cache.set(null);
        try {
            getTokenPrefs().edit().clear().apply();
        } catch (Exception e) {
            GHLog.TOKEN.w("clearCache: failed to clear token prefs", e);
        }
        GHLog.TOKEN.d("clearCache: token caches cleared");
    }

    /**
     * Called from the patched {@code UserManager.getToken()} return path.
     *
     * @param originalToken the token that the original method would have returned
     * @return the effective token based on the active patch combination
     */
    private static final GHLog L = GHLog.TOKEN;

    public static String resolveToken(String originalToken) {
        L.d("resolveToken: apiSwitchPatched=" + apiSwitchPatched
                + " loginBypassed=" + loginBypassed
                + " isExternalAPI=" + isExternalAPI()
                + " originalToken=" + (originalToken == null ? "null" : originalToken.length() + " chars"));

        // EmuReady API accepts any token — use a lightweight fake.
        if (apiSwitchPatched && isExternalAPI()) {
            L.d("resolveToken → fake-token (EmuReady path)");
            return "fake-token";
        }

        // Login is bypassed but we're talking to the Original API — need a real token.
        if (loginBypassed) {
            String token = getServiceToken(originalToken);
            L.d("resolveToken → service token: " + (token == null ? "null" : token.length() + " chars"));
            return token;
        }

        // No login bypass — the user is genuinely logged in; pass through.
        L.d("resolveToken → original pass-through");
        return originalToken;
    }

    /**
     * Reads the EmuReady-API toggle directly from SharedPreferences,
     * duplicating the key from {@code GameHubPrefs} to avoid a class dependency
     * that could cause issues during early init.
     */
    private static boolean isExternalAPI() {
        try {
            SharedPreferences prefs =
                    Utils.a().getSharedPreferences("steam_storage_pref", android.content.Context.MODE_PRIVATE);
            return prefs.getBoolean("use_external_api", true);
        } catch (Exception e) {
            return true;
        }
    }

    private static SharedPreferences getTokenPrefs() {
        return Utils.a().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    /**
     * 3-tier token resolution: L1 (memory) → L2 (SharedPreferences) → L3 (HTTP).
     * Falls back to stale cache → original token → {@code "fake-token"}.
     */
    private static String getServiceToken(String fallbackToken) {
        // L1: in-memory AtomicReference cache.
        CachedToken l1 = l1Cache.get();
        if (l1 != null && l1.isValid()) {
            L.d("getServiceToken → L1 hit");
            return l1.token;
        }
        L.d("getServiceToken: L1 miss");

        // L2: SharedPreferences persistent cache (survives app restarts).
        try {
            SharedPreferences prefs = getTokenPrefs();
            String cachedToken = prefs.getString(KEY_CACHED_TOKEN, null);
            long expiry = prefs.getLong(KEY_CACHED_TOKEN_EXPIRY, 0);
            long remaining = expiry - System.currentTimeMillis();
            L.d("getServiceToken: L2 cached=" + (cachedToken != null) + " remaining=" + remaining + "ms");
            if (cachedToken != null && !cachedToken.isEmpty() && remaining > 0) {
                l1Cache.set(new CachedToken(cachedToken, expiry));
                L.d("getServiceToken → L2 hit");
                return cachedToken;
            }
        } catch (Exception e) {
            L.w("getServiceToken: L2 error", e);
        }

        // L3: HTTP fetch from token-refresh service.
        try {
            L.d("getServiceToken: L3 fetching from " + TOKEN_SERVICE_URL);
            String freshToken = fetchTokenFromService();
            L.d("getServiceToken: L3 result=" + (freshToken == null ? "null" : freshToken.length() + " chars"));
            if (freshToken != null && !freshToken.isEmpty()) {
                long expiry = System.currentTimeMillis() + CACHE_TTL_MS;
                l1Cache.set(new CachedToken(freshToken, expiry));
                try {
                    getTokenPrefs()
                            .edit()
                            .putString(KEY_CACHED_TOKEN, freshToken)
                            .putLong(KEY_CACHED_TOKEN_EXPIRY, expiry)
                            .apply();
                } catch (Exception ignored) {
                }
                L.d("getServiceToken → L3 hit");
                return freshToken;
            }
        } catch (Exception e) {
            L.w("getServiceToken: L3 fetch failed", e);
        }

        // Fallback chain: stale L1 → stale L2 → original token → fake-token.
        CachedToken stale = l1Cache.get();
        if (stale != null && stale.token != null && !stale.token.isEmpty()) {
            L.d("getServiceToken → stale L1");
            return stale.token;
        }
        try {
            String stalePref = getTokenPrefs().getString(KEY_CACHED_TOKEN, null);
            if (stalePref != null && !stalePref.isEmpty()) {
                L.d("getServiceToken → stale L2");
                return stalePref;
            }
        } catch (Exception ignored) {
        }
        if (fallbackToken != null && !fallbackToken.isEmpty()) {
            L.d("getServiceToken → fallbackToken");
            return fallbackToken;
        }
        L.w("getServiceToken → last-resort fake-token");
        return "fake-token";
    }

    /**
     * Fetches a fresh token from the token-refresh Cloudflare Worker.
     * Uses {@link HttpURLConnection} (not OkHttp) to avoid circular dependency —
     * {@code getToken()} is called from within the OkHttp interceptor chain.
     */
    private static String fetchTokenFromService() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(TOKEN_SERVICE_URL).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Worker-Auth", AUTH_HEADER_VALUE);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);

            int code = conn.getResponseCode();
            L.d("fetchTokenFromService: HTTP " + code);
            if (code != 200) return null;

            String body;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                body = sb.toString();
            }

            L.d("fetchTokenFromService: body=" + body.substring(0, Math.min(body.length(), 200)));
            return parseTokenFromJson(body);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Called from the patched {@code TokenRefreshInterceptor.j()} when a 401 triggers
     * the official API's token refresh flow. Instead of using the official
     * {@code jwt/refresh/token} endpoint (which fails for service-issued tokens),
     * we re-fetch from the external token service.
     *
     * <ol>
     *   <li>Clear L1 + L2 caches to discard the stale token</li>
     *   <li>GET {@code /token} from the external service (may already have a newer token)</li>
     *   <li>Compare with the token the app currently holds ({@code UserManager.getToken()})
     *       — if different, the service already rotated it; return the new one</li>
     *   <li>If same, POST to {@code /refresh} to force a server-side refresh</li>
     *   <li>Cache and return the fresh token, or {@code null} on failure</li>
     * </ol>
     *
     * @return a fresh access token, or {@code null} to let the original interceptor handle it
     */
    public static String refreshTokenForOfficialApi() {
        if (!loginBypassed) {
            L.d("refreshTokenForOfficialApi: loginBypassed=false, deferring to original");
            return null;
        }
        L.d("refreshTokenForOfficialApi: starting service-based refresh");

        // 1. Clear stale caches.
        clearCache();

        try {
            // 2. Fetch current token from the service.
            String serviceToken = fetchTokenFromService();
            if (serviceToken == null || serviceToken.isEmpty()) {
                L.w("refreshTokenForOfficialApi: service returned no token");
                return null;
            }

            // 3. Compare with what the app currently holds.
            String currentToken = getCurrentAppToken();
            if (currentToken == null || !serviceToken.equals(currentToken)) {
                L.d("refreshTokenForOfficialApi: service has a newer token");
                cacheToken(serviceToken);
                return serviceToken;
            }

            // 4. Same token — force a server-side refresh.
            L.d("refreshTokenForOfficialApi: same token, forcing refresh");
            String refreshed = forceRefreshFromService(currentToken);
            if (refreshed != null && !refreshed.isEmpty()) {
                L.d("refreshTokenForOfficialApi: forced refresh succeeded");
                cacheToken(refreshed);
                return refreshed;
            }

            L.w("refreshTokenForOfficialApi: forced refresh returned no token");
            return null;
        } catch (Exception e) {
            L.w("refreshTokenForOfficialApi failed", e);
            return null;
        }
    }

    /**
     * POSTs to the external service's {@code /refresh} endpoint to force
     * a server-side token rotation.
     */
    private static String forceRefreshFromService(String currentToken) {
        HttpURLConnection conn = null;
        try {
            String refreshUrl = TOKEN_SERVICE_URL.replace("/token", "/refresh");
            conn = (HttpURLConnection) URI.create(refreshUrl).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-Worker-Auth", AUTH_HEADER_VALUE);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setDoOutput(true);

            String body = "{\"token\":\"" + currentToken + "\"}";
            java.io.OutputStream out = conn.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            L.d("forceRefreshFromService: HTTP " + code);
            if (code != 200) return null;

            String responseBody;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                responseBody = sb.toString();
            }
            L.d("forceRefreshFromService: body=" + responseBody.substring(0, Math.min(responseBody.length(), 200)));
            return parseTokenFromJson(responseBody);
        } catch (Exception e) {
            L.w("forceRefreshFromService failed", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Reads the current token from {@code UserManager.getToken()} via reflection.
     */
    private static String getCurrentAppToken() {
        try {
            Class<?> userManagerClass = Class.forName("com.xj.common.user.UserManager");
            Object instance = userManagerClass.getField("INSTANCE").get(null);
            return (String) userManagerClass.getMethod("getToken").invoke(instance);
        } catch (Exception e) {
            L.w("getCurrentAppToken failed", e);
            return null;
        }
    }

    /**
     * Stores a token in both L1 (memory) and L2 (SharedPreferences) caches.
     */
    private static void cacheToken(String token) {
        long expiry = System.currentTimeMillis() + CACHE_TTL_MS;
        l1Cache.set(new CachedToken(token, expiry));
        try {
            getTokenPrefs()
                    .edit()
                    .putString(KEY_CACHED_TOKEN, token)
                    .putLong(KEY_CACHED_TOKEN_EXPIRY, expiry)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * Minimal JSON parser — extracts the {@code "token"} value from a JSON object
     * like {@code {"token":"abc123"}} without requiring Gson.
     */
    private static String parseTokenFromJson(String json) {
        if (json == null || json.isEmpty()) return null;

        int keyIdx = json.indexOf("\"token\"");
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx + 7);
        if (colonIdx < 0) return null;

        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;

        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return null;

        return json.substring(startQuote + 1, endQuote);
    }
}
