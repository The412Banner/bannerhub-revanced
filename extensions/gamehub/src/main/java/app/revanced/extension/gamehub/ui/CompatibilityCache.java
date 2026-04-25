package app.revanced.extension.gamehub.ui;

import app.revanced.extension.gamehub.util.GHLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches {@code SimpleGameCompatibility} objects during game list loading
 * and provides fallback compatibility data for the game detail view
 * when the {@code card/getGameDetail} API fails (e.g. bypass login mode).
 */
@SuppressWarnings("unused")
public final class CompatibilityCache {
    private static final ConcurrentHashMap<String, Object> sCache = new ConcurrentHashMap<>();

    /**
     * Clears all cached compatibility data.
     * Called when the API source changes to discard stale data from the previous API.
     */
    public static void clear() {
        sCache.clear();
    }

    /**
     * Called from {@code SteamGameDataHandler.h()} after loading a
     * {@code SimpleGameCompatibility} from the compatibility map.
     * Stores it keyed by Steam app ID for later fallback lookup.
     */
    public static void cache(String steamAppId, Object simpleCompat) {
        if (steamAppId == null || steamAppId.isEmpty() || simpleCompat == null) return;
        sCache.put(steamAppId, simpleCompat);
    }

    /**
     * Called from {@code GameDetailHeadViewHolder.A()} as a replacement for
     * {@code GameDetailEntity.getCst_data()}. Returns the native compatibility
     * data if available, otherwise builds a {@code GameCompatibilityParams}
     * from cached {@code SimpleGameCompatibility} data.
     *
     * @param entity a {@code GameDetailEntity} instance
     * @return a {@code GameCompatibilityParams} instance, or {@code null}
     */
    public static Object getOrBuildCompat(Object entity) {
        try {
            // Try the native getCst_data() first.
            Method getCstData = entity.getClass().getMethod("getCst_data");
            Object cstData = getCstData.invoke(entity);
            if (cstData != null) return cstData;

            // Get the Steam app ID for cache lookup.
            Method getSteamAppId = entity.getClass().getMethod("getSteamAppId");
            String steamAppId = (String) getSteamAppId.invoke(entity);
            if (steamAppId == null || steamAppId.isEmpty()) return null;

            Object cached = sCache.get(steamAppId);
            if (cached == null) return null;

            // Extract fields from SimpleGameCompatibility via reflection.
            Class<?> sgcClass = cached.getClass();
            String title = (String) sgcClass.getMethod("getTitle").invoke(cached);
            String icon = (String) sgcClass.getMethod("getIcon").invoke(cached);
            String desc = (String) sgcClass.getMethod("getDesc").invoke(cached);
            int level = (int) sgcClass.getMethod("getLevel").invoke(cached);

            // Construct GameCompatibilityParams(String, String, String, int, List).
            Class<?> gcpClass = Class.forName("com.xj.common.service.bean.GameCompatibilityParams");
            Constructor<?> ctor =
                    gcpClass.getConstructor(String.class, String.class, String.class, int.class, java.util.List.class);
            return ctor.newInstance(
                    title != null ? title : "",
                    icon != null ? icon : "",
                    desc != null ? desc : "",
                    level,
                    new ArrayList<>());
        } catch (Exception e) {
            GHLog.COMPAT.w("getOrBuildCompat failed", e);
            return null;
        }
    }
}
