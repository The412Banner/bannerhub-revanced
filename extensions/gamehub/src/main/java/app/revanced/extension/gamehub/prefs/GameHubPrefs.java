package app.revanced.extension.gamehub.prefs;

import android.content.SharedPreferences;

import app.revanced.extension.gamehub.token.TokenProvider;
import app.revanced.extension.gamehub.ui.CompatibilityCache;
import app.revanced.extension.gamehub.util.GHLog;
import com.blankj.utilcode.util.Utils;

@SuppressWarnings("unused")
public class GameHubPrefs {

    // Settings content-type constants (must match values used in patch injection).
    public static final int CONTENT_TYPE_SD_CARD_STORAGE = 0x18;
    public static final int CONTENT_TYPE_API = 0x1a;
    public static final int CONTENT_TYPE_LOG_REQUESTS = 0x1b;
    public static final int CONTENT_TYPE_CPU_USAGE = 0x1c;
    public static final int CONTENT_TYPE_PERF_METRICS = 0x1d;
    public static final int CONTENT_TYPE_MUTE_SOUNDS = 0x1e;
    public static final int CONTENT_TYPE_CREDITS = 0x1f;

    private static final String PREFS_NAME = "steam_storage_pref";
    private static final String KEY_EXTERNAL_API = "use_external_api";
    private static final String KEY_CUSTOM_STORAGE = "use_custom_storage";
    private static final String KEY_STORAGE_PATH = "steam_storage_path";
    private static final String KEY_LAST_API_SOURCE = "last_api_source";
    private static final String KEY_LOG_ALL_REQUESTS = "log_all_requests";
    private static final String KEY_CPU_USAGE = "cpu_usage_display";
    private static final String KEY_PERF_METRICS = "perf_metrics_display";
    private static final String KEY_MUTE_SOUNDS = "mute_ui_sounds";

    private static volatile boolean startupCheckDone = false;

    private static SharedPreferences getPrefs() {
        return Utils.a().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    public static boolean isExternalAPI() {
        return getPrefs().getBoolean(KEY_EXTERNAL_API, true);
    }

    /**
     * Returns whether Steam Input should be forced enabled.
     * When EmuReady API is active, always returns true because the bundled
     * SteamAgent.exe does not support the --disablesteaminput flag.
     * When using the official API, returns the original setting value unchanged.
     */
    public static boolean shouldForceEnableSteamInput(boolean originalValue) {
        return isExternalAPI() || originalValue;
    }

    public static boolean isLogAllRequestsEnabled() {
        return getPrefs().getBoolean(KEY_LOG_ALL_REQUESTS, false);
    }

    public static boolean isCpuUsageEnabled() {
        return getPrefs().getBoolean(KEY_CPU_USAGE, true);
    }

    public static boolean isPerfMetricsEnabled() {
        return getPrefs().getBoolean(KEY_PERF_METRICS, true);
    }

    public static boolean isSoundMuted() {
        return getPrefs().getBoolean(KEY_MUTE_SOUNDS, false);
    }

    public static void toggleAPI() {
        SharedPreferences prefs = getPrefs();
        prefs.edit()
                .putBoolean(KEY_EXTERNAL_API, !prefs.getBoolean(KEY_EXTERNAL_API, true))
                .apply();
    }

    /**
     * Clears cached component data, tokens, HTTP response cache, cookies, and in-memory
     * compatibility data so the app re-downloads everything from the new API source.
     * Must be called whenever the API source changes (toggle or startup mismatch detection).
     */
    private static void clearComponentAndTokenCaches() {
        try {
            android.content.Context ctx = Utils.a();
            int mode = android.content.Context.MODE_PRIVATE;
            // Clear component catalogs (populated per-API, version codes may collide across sources).
            ctx.getSharedPreferences("sp_winemu_all_components12", mode)
                    .edit()
                    .clear()
                    .apply();
            ctx.getSharedPreferences("sp_winemu_all_containers", mode)
                    .edit()
                    .clear()
                    .apply();
            ctx.getSharedPreferences("sp_winemu_all_imageFs", mode)
                    .edit()
                    .clear()
                    .apply();
            // Clear global component metadata (dxvk, vkd3d, imagefs, steam_client, general_component).
            ctx.getSharedPreferences("pc_g_setting", mode).edit().clear().apply();
            // Clear persistent cookies (PersistentCookieJar stores session cookies here).
            ctx.getSharedPreferences("net_cookies", mode).edit().clear().apply();
            // Flush token caches (in-memory L1 + SharedPreferences L2).
            TokenProvider.clearCache();
            // Clear in-memory game compatibility cache.
            CompatibilityCache.clear();
            // Delete OkHttp HTTP response cache (128 MB disk cache in getCacheDir/).
            // DiskLruCache handles missing/corrupted files gracefully on next startup.
            deleteCacheContents(ctx.getCacheDir());
            GHLog.PREFS.d("Cleared all caches for API source change");
        } catch (Exception e) {
            GHLog.PREFS.w("clearComponentAndTokenCaches failed", e);
        }
    }

    /**
     * Recursively deletes all files and subdirectories inside the given directory.
     * The directory itself is preserved.
     */
    private static void deleteCacheContents(java.io.File dir) {
        if (dir == null || !dir.isDirectory()) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File file : files) {
            if (file.isDirectory()) {
                deleteCacheContents(file);
            }
            file.delete();
        }
    }

    public static boolean isCustomStorageEnabled() {
        return getPrefs().getBoolean(KEY_CUSTOM_STORAGE, false);
    }

    public static void toggleStorageLocation() {
        SharedPreferences prefs = getPrefs();
        prefs.edit()
                .putBoolean(KEY_CUSTOM_STORAGE, !prefs.getBoolean(KEY_CUSTOM_STORAGE, false))
                .apply();
    }

    public static String getCustomStoragePath() {
        return getPrefs().getString(KEY_STORAGE_PATH, "");
    }

    /**
     * Returns the persisted enabled state for a custom settings toggle.
     * Called from SettingItemViewModel.l() to initialise each switch's visual state.
     *
     * @param contentType the content-type constant of the toggle item
     * @return true if the toggle should be shown as ON
     */
    public static boolean isSettingEnabled(int contentType) {
        if (contentType == CONTENT_TYPE_SD_CARD_STORAGE) return isCustomStorageEnabled();
        if (contentType == CONTENT_TYPE_API) return isExternalAPI();
        if (contentType == CONTENT_TYPE_LOG_REQUESTS) return isLogAllRequestsEnabled();
        if (contentType == CONTENT_TYPE_CPU_USAGE) return isCpuUsageEnabled();
        if (contentType == CONTENT_TYPE_PERF_METRICS) return isPerfMetricsEnabled();
        if (contentType == CONTENT_TYPE_MUTE_SOUNDS) return isSoundMuted();
        return false;
    }

    /**
     * Returns the persisted switch value for our custom settings types, or defaultValue for others.
     * Called from SettingSwitchHolder.u() after CloudGameSettingDataHelper.j() returns a value,
     * overriding it for content types 0x18 and 0x1a with our SharedPreferences values.
     *
     * @param contentType  the entity's content-type
     * @param defaultValue the value returned by CloudGameSettingDataHelper for this type
     * @return the effective switch value to display
     */
    public static boolean getInitialSwitchValue(int contentType, boolean defaultValue) {
        if (contentType == CONTENT_TYPE_SD_CARD_STORAGE) return isCustomStorageEnabled();
        if (contentType == CONTENT_TYPE_API) return isExternalAPI();
        if (contentType == CONTENT_TYPE_LOG_REQUESTS) return isLogAllRequestsEnabled();
        if (contentType == CONTENT_TYPE_CPU_USAGE) return isCpuUsageEnabled();
        if (contentType == CONTENT_TYPE_PERF_METRICS) return isPerfMetricsEnabled();
        if (contentType == CONTENT_TYPE_MUTE_SOUNDS) return isSoundMuted();
        return defaultValue;
    }

    /**
     * Translates an internal path to the SD card path if custom storage is enabled.
     *
     * @param originalPath the original install path from SteamDownloadInfoHelper
     * @return the effective path (SD card or original)
     */
    public static String getEffectiveStoragePath(String originalPath) {
        if (!isCustomStorageEnabled()) return originalPath;
        if (originalPath == null || originalPath.isEmpty()) return originalPath;

        String customPath = getCustomStoragePath();
        if (customPath == null || customPath.isEmpty()) return originalPath;

        java.io.File customDir = new java.io.File(customPath);
        if (!customDir.exists() || !customDir.isDirectory()) return originalPath;

        if (originalPath.startsWith(customPath)) return originalPath;

        int steamIdx = originalPath.indexOf("/files/Steam");
        if (steamIdx < 0) return originalPath;

        return customPath + originalPath.substring(steamIdx);
    }

    private static final String EMUREADY_URL = "https://gamehub-lite-api.emuready.workers.dev/";

    /**
     * Returns the effective API URL: EmuReady when not using the official API, original otherwise.
     * On first call each app launch, checks whether the API source changed since last run
     * and clears stale component/token caches if so.
     */
    public static String getEffectiveApiUrl(String officialUrl) {
        if (!startupCheckDone) {
            startupCheckDone = true;
            try {
                SharedPreferences prefs = getPrefs();
                boolean currentSource = isExternalAPI();
                // KEY_LAST_API_SOURCE defaults to true (EmuReady) if absent, matching isExternalAPI() default.
                boolean lastSource = prefs.getBoolean(KEY_LAST_API_SOURCE, true);
                if (!prefs.contains(KEY_LAST_API_SOURCE) || currentSource != lastSource) {
                    GHLog.PREFS.d("API source mismatch on startup (current=" + currentSource + ", last=" + lastSource
                            + ") — clearing caches");
                    clearComponentAndTokenCaches();
                    prefs.edit().putBoolean(KEY_LAST_API_SOURCE, currentSource).apply();
                }
            } catch (Exception e) {
                GHLog.PREFS.w("Startup API source check failed", e);
            }
        }
        return isExternalAPI() ? EMUREADY_URL : officialUrl;
    }

    /**
     * Returns the display name for a custom settings item, or null if the content type
     * is not one of our registered items (falls through to the app's own switch).
     * Called from SettingItemEntity.getContentName() via replaceInstruction.
     */
    public static String getCustomSettingName(int contentType) {
        if (contentType == CONTENT_TYPE_SD_CARD_STORAGE) return "SD Card Storage";
        if (contentType == CONTENT_TYPE_API) return "EmuReady API";
        if (contentType == CONTENT_TYPE_LOG_REQUESTS) return "Log All Requests";
        if (contentType == CONTENT_TYPE_CPU_USAGE) return "CPU Usage Display";
        if (contentType == CONTENT_TYPE_PERF_METRICS) return "Performance Metrics";
        if (contentType == CONTENT_TYPE_MUTE_SOUNDS) return "Mute UI Sounds";
        if (contentType == CONTENT_TYPE_CREDITS) return "Credits";
        return null;
    }

    /**
     * Handles a settings-switch toggle for the two Steam-related content types.
     * Called from SettingSwitchHolder.w() just before CommFocusSwitchBtn.b() sets the
     * visual state. Returning false here causes the switch to stay/revert to OFF;
     * returning true confirms it goes ON.
     *
     * @param contentType   the content-type constant of the toggle item
     * @param proposedState the state the user is trying to set (true=ON, false=OFF)
     * @return the actual state to apply visually
     */
    public static boolean handleSettingToggle(int contentType, boolean proposedState) {
        if (contentType == CONTENT_TYPE_SD_CARD_STORAGE) {
            if (proposedState) {
                String path = autoDetectSDCardStorage();
                if (path == null) {
                    android.widget.Toast.makeText(Utils.a(), "No SD card found", android.widget.Toast.LENGTH_SHORT)
                            .show();
                    return false; // revert visual state immediately
                }
                getPrefs().edit().putBoolean(KEY_CUSTOM_STORAGE, true).apply();
                return true;
            } else {
                useInternalStorage();
                return false;
            }
        } else if (contentType == CONTENT_TYPE_LOG_REQUESTS) {
            boolean newState = !isLogAllRequestsEnabled();
            getPrefs().edit().putBoolean(KEY_LOG_ALL_REQUESTS, newState).apply();
            String msg = newState ? "Logging all API requests" : "Logging 4xx requests only";
            android.widget.Toast.makeText(Utils.a(), msg, android.widget.Toast.LENGTH_SHORT)
                    .show();
            return newState;
        } else if (contentType == CONTENT_TYPE_CPU_USAGE) {
            boolean newState = !isCpuUsageEnabled();
            getPrefs().edit().putBoolean(KEY_CPU_USAGE, newState).apply();
            String msg = newState ? "CPU usage display enabled" : "CPU usage display disabled";
            android.widget.Toast.makeText(Utils.a(), msg, android.widget.Toast.LENGTH_SHORT)
                    .show();
            return newState;
        } else if (contentType == CONTENT_TYPE_PERF_METRICS) {
            boolean newState = !isPerfMetricsEnabled();
            getPrefs().edit().putBoolean(KEY_PERF_METRICS, newState).apply();
            String msg = newState ? "Performance metrics enabled" : "Performance metrics disabled";
            android.widget.Toast.makeText(Utils.a(), msg, android.widget.Toast.LENGTH_SHORT)
                    .show();
            return newState;
        } else if (contentType == CONTENT_TYPE_MUTE_SOUNDS) {
            boolean newState = !isSoundMuted();
            getPrefs().edit().putBoolean(KEY_MUTE_SOUNDS, newState).apply();
            String msg = newState ? "UI sounds muted" : "UI sounds enabled";
            android.widget.Toast.makeText(Utils.a(), msg, android.widget.Toast.LENGTH_SHORT)
                    .show();
            return newState;
        } else if (contentType == CONTENT_TYPE_API) {
            toggleAPI();
            clearComponentAndTokenCaches();
            getPrefs().edit().putBoolean(KEY_LAST_API_SOURCE, proposedState).apply();
            String msg = proposedState
                    ? "Switched to EmuReady API — restart to refresh components"
                    : "Switched to Official API — restart to refresh components";
            android.widget.Toast.makeText(Utils.a(), msg, android.widget.Toast.LENGTH_SHORT)
                    .show();
            return proposedState;
        }
        return proposedState;
    }

    /**
     * Adds standard browser-like HTTP headers to an okhttp3.Request.Builder.
     * Needed so Cloudflare Worker endpoints accept requests from the app.
     *
     * @param builder an okhttp3.Request.Builder instance
     * @return the same builder with headers added
     */
    @SuppressWarnings("JavaReflectionMemberAccess")
    public static Object addCompatibilityHeaders(Object builder) {
        try {
            java.lang.reflect.Method addHeader = builder.getClass().getMethod("addHeader", String.class, String.class);
            addHeader.invoke(
                    builder,
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36");
            addHeader.invoke(builder, "Accept", "application/json, text/plain, */*");
            addHeader.invoke(builder, "Accept-Language", "en-US,en;q=0.9");
            addHeader.invoke(builder, "Connection", "keep-alive");
        } catch (Exception e) {
            GHLog.PREFS.w("addCompatibilityHeaders failed", e);
        }
        return builder;
    }

    /**
     * Returns the number of bytes available on the effective storage location.
     * Uses the custom storage path when enabled, otherwise falls back to external storage.
     * Called from DownloadGameSizeInfoDialog to show correct free space.
     */
    public static long getAvailableStorage() {
        String path = isCustomStorageEnabled() ? getCustomStoragePath() : null;
        java.io.File dir = (path != null && !path.isEmpty())
                ? new java.io.File(path)
                : android.os.Environment.getExternalStorageDirectory();
        if (!dir.exists()) dir = android.os.Environment.getExternalStorageDirectory();
        android.os.StatFs sf = new android.os.StatFs(dir.getAbsolutePath());
        return sf.getAvailableBlocksLong() * sf.getBlockSizeLong();
    }

    /**
     * Sets the custom storage path directly (used by StorageBroadcastReceiver).
     */
    public static void setStoragePath(String path) {
        getPrefs().edit().putString(KEY_STORAGE_PATH, path).apply();
    }

    /**
     * Reverts to internal/default storage by disabling custom storage.
     */
    public static void useInternalStorage() {
        getPrefs().edit().putBoolean(KEY_CUSTOM_STORAGE, false).apply();
    }

    /**
     * Scans external storage volumes for a writable /GHL folder and saves the path.
     *
     * @return the detected storage root path, or null if none was found.
     */
    public static String autoDetectSDCardStorage() {
        try {
            android.content.Context ctx = Utils.a();
            java.io.File[] externalDirs = ctx.getExternalFilesDirs(null);
            for (java.io.File dir : externalDirs) {
                if (dir == null) continue;
                // Find the storage root (up to Android/data)
                String path = dir.getAbsolutePath();
                int androidIdx = path.indexOf("/Android/data");
                if (androidIdx < 0) continue;
                String storageRoot = path.substring(0, androidIdx);
                java.io.File ghlDir = new java.io.File(storageRoot, "GHL");
                if (ghlDir.exists() && ghlDir.isDirectory() && ghlDir.canWrite()) {
                    getPrefs().edit().putString(KEY_STORAGE_PATH, storageRoot).apply();
                    return storageRoot;
                }
            }
        } catch (Exception e) {
            GHLog.PREFS.w("autoDetectSDCardStorage failed", e);
        }
        return null;
    }

    /**
     * Logs every API request/response when "Log All Requests" is enabled.
     * Injected at the start of GsonConverter.a() so it fires for ALL HTTP calls.
     * Uses {@code peekBody()} to read the response body without consuming it.
     *
     * @param response okhttp3.Response object
     */
    @SuppressWarnings("JavaReflectionMemberAccess")
    public static void logApiRequest(Object response) {
        if (!isLogAllRequestsEnabled()) return;
        try {
            Object request = response.getClass().getMethod("request").invoke(response);
            Object url = request.getClass().getMethod("url").invoke(request);
            String httpMethod = (String) request.getClass().getMethod("method").invoke(request);
            int code = (int) response.getClass().getMethod("code").invoke(response);

            GHLog.NET.d("=== API Request ===");
            GHLog.NET.d(httpMethod + " " + url + " → HTTP " + code);

            logRequestDetails(request);

            // Use peekBody() to read response body without consuming the stream.
            try {
                String contentType = getContentType(response);
                if (isTextContentType(contentType)) {
                    Object peekBody = response.getClass()
                            .getMethod("peekBody", long.class)
                            .invoke(response, 1048576L); // 1 MB max
                    String bodyString =
                            (String) peekBody.getClass().getMethod("string").invoke(peekBody);
                    if (bodyString != null && !bodyString.isEmpty()) {
                        GHLog.NET.d("Response body: " + bodyString);
                    }
                } else {
                    long contentLength = getContentLength(response);
                    GHLog.NET.d("Response body: <binary " + contentType
                            + (contentLength >= 0 ? ", " + contentLength + " bytes>" : ">"));
                }
            } catch (Exception e) {
                GHLog.NET.d("Response body: <unreadable>");
            }
        } catch (Exception e) {
            GHLog.NET.w("logApiRequest failed", e);
        }
    }

    /**
     * Logs the full request/response details for a failed API call.
     * Called from the GsonConverter 4xx path via bytecode injection.
     * Skipped when "Log All Requests" is enabled (already logged by {@link #logApiRequest}).
     *
     * @param response okhttp3.Response object
     * @param bodyString the response body already read as a String (may be null)
     */
    @SuppressWarnings("JavaReflectionMemberAccess")
    public static void logFailedApiRequest(Object response, String bodyString) {
        if (isLogAllRequestsEnabled()) return; // already logged by logApiRequest
        try {
            Object request = response.getClass().getMethod("request").invoke(response);
            Object url = request.getClass().getMethod("url").invoke(request);
            String httpMethod = (String) request.getClass().getMethod("method").invoke(request);
            int code = (int) response.getClass().getMethod("code").invoke(response);

            GHLog.NET.d("=== Failed API Request ===");
            GHLog.NET.d(httpMethod + " " + url + " → HTTP " + code);

            logRequestDetails(request);

            if (bodyString != null) {
                GHLog.NET.d("Response body: " + bodyString);
            }
        } catch (Exception e) {
            GHLog.NET.w("logFailedApiRequest failed", e);
        }
    }

    /**
     * Shared helper: logs request headers and body via reflection.
     */
    @SuppressWarnings("JavaReflectionMemberAccess")
    private static void logRequestDetails(Object request) {
        // Iterate headers via reflection to bypass OkHttp's redaction.
        try {
            Object headers = request.getClass().getMethod("headers").invoke(request);
            java.lang.reflect.Method sizeMethod = headers.getClass().getMethod("size");
            java.lang.reflect.Method nameMethod = headers.getClass().getMethod("name", int.class);
            java.lang.reflect.Method valueMethod = headers.getClass().getMethod("value", int.class);
            int headerCount = (int) sizeMethod.invoke(headers);
            for (int i = 0; i < headerCount; i++) {
                String name = (String) nameMethod.invoke(headers, i);
                String value = (String) valueMethod.invoke(headers, i);
                GHLog.NET.d("  " + name + ": " + value);
            }
        } catch (Exception e) {
            GHLog.NET.d("Request headers: <unreadable>");
        }

        // Log request body for POST/PUT/PATCH requests.
        try {
            Object body = request.getClass().getMethod("body").invoke(request);
            if (body != null) {
                String reqContentType = getBodyContentType(body);
                if (isTextContentType(reqContentType)) {
                    Class<?> bufferClass = Class.forName("okio.Buffer");
                    Object buffer = bufferClass.getDeclaredConstructor().newInstance();
                    body.getClass()
                            .getMethod("writeTo", Class.forName("okio.BufferedSink"))
                            .invoke(body, buffer);
                    String reqBody = (String) bufferClass.getMethod("readUtf8").invoke(buffer);
                    if (reqBody != null && !reqBody.isEmpty()) {
                        GHLog.NET.d("Request body: " + reqBody);
                    }
                } else {
                    long reqLen = getBodyContentLength(body);
                    GHLog.NET.d("Request body: <binary " + reqContentType
                            + (reqLen >= 0 ? ", " + reqLen + " bytes>" : ">"));
                }
            }
        } catch (Exception e) {
            GHLog.NET.d("Request body: <unreadable>");
        }
    }

    /**
     * Extracts the Content-Type header value from an okhttp3.Response via reflection.
     */
    private static String getContentType(Object response) {
        try {
            return (String)
                    response.getClass().getMethod("header", String.class).invoke(response, "Content-Type");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the Content-Length from a response header (-1 if absent/unparseable).
     */
    private static long getContentLength(Object response) {
        try {
            String val = (String)
                    response.getClass().getMethod("header", String.class).invoke(response, "Content-Length");
            return val != null ? Long.parseLong(val) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Extracts the media type string from a RequestBody's contentType().
     */
    private static String getBodyContentType(Object body) {
        try {
            Object mediaType = body.getClass().getMethod("contentType").invoke(body);
            return mediaType != null ? mediaType.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the content length from a RequestBody (-1 if unknown).
     */
    private static long getBodyContentLength(Object body) {
        try {
            return (long) body.getClass().getMethod("contentLength").invoke(body);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Returns true if the given Content-Type string represents text-based content
     * that is safe to log as a string.
     */
    private static boolean isTextContentType(String contentType) {
        if (contentType == null) return true; // assume text if unknown
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("text/")
                || lower.contains("json")
                || lower.contains("xml")
                || lower.contains("html")
                || lower.contains("form-urlencoded");
    }
}
