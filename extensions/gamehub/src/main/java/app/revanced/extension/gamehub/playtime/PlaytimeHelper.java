package app.revanced.extension.gamehub.playtime;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import app.revanced.extension.gamehub.util.GHLog;
import com.blankj.utilcode.util.Utils;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads game playtime from the local Steam PICS database instead of GameHub's server.
 *
 * <p>The app syncs real playtime data from Steam via its javasteam client and caches it
 * in a local Room database ({@code xj_steam_pics_v5}, table
 * {@code t_steam_user_pics_app_last_played_times}). This helper reads directly from
 * that cache, converting Steam's minute-based playtime to the seconds format that
 * GameHub's UI expects.
 *
 * <p>Returns {@code null} on any failure so the caller can fall back to the original
 * server-based API call.
 */
@SuppressWarnings("unused")
public class PlaytimeHelper {

    private static final GHLog L = GHLog.PLAYTIME;

    // Cached reflection handles (populated once, reused for every entity).
    private static Field steamAppidField;
    private static Field totalSecondsField;
    private static Field last14DaysField;
    private static Class<?> entityClass;

    /**
     * Entry point called from the patched {@code invokeSuspend} of
     * {@code GameLibraryRepository$loadRecentGameList$2}.
     *
     * @return a {@code List<RecentGameEntity>} (typed as Object for the smali bridge),
     *         or {@code null} to fall back to the original HTTP call.
     */
    public static Object fetchPlaytimeFromLocalDb() {
        try {
            long userId = getCurrentUserId();
            if (userId <= 0) {
                L.d("fetchPlaytimeFromLocalDb: no current Steam user");
                return null;
            }
            L.d("fetchPlaytimeFromLocalDb: userId=" + userId);
            return queryPlaytimes(userId);
        } catch (Exception e) {
            L.e("fetchPlaytimeFromLocalDb failed", e);
            return null;
        }
    }

    /**
     * Looks up the currently logged-in Steam account's row ID from the app's account database.
     * The PICS playtime table uses this row {@code id} (not the SteamID64) as its
     * {@code user_id} foreign key.
     *
     * @return the {@code steam_account.id} primary key, or {@code -1} if no current user is found.
     */
    private static long getCurrentUserId() {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            File dbFile = Utils.a().getDatabasePath("xj_steam_db");
            if (!dbFile.exists()) {
                L.d("getCurrentUserId: xj_steam_db not found");
                return -1;
            }
            db = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            cursor = db.rawQuery("SELECT id FROM steam_account WHERE is_current_user = 1 LIMIT 1", null);
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
            return -1;
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
    }

    /**
     * Queries the PICS playtime table and builds a list of {@code RecentGameEntity} objects.
     *
     * <p>Steam stores playtime in <b>minutes</b>; GameHub's entity uses <b>seconds</b>,
     * so each value is multiplied by 60.
     *
     * @return a non-empty list, or {@code null} if no playtime data exists.
     */
    private static Object queryPlaytimes(long userId) throws Exception {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            File dbFile = Utils.a().getDatabasePath("xj_steam_pics_v5");
            if (!dbFile.exists()) {
                L.d("queryPlaytimes: xj_steam_pics_v5 not found");
                return null;
            }
            db = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            cursor = db.rawQuery(
                    // spotless:off
                    "SELECT app_id, playtime_forever, playtime2weeks " +
                            "FROM t_steam_user_pics_app_last_played_times " +
                            "WHERE user_id = ? AND playtime_forever > 0 " +
                            "ORDER BY playtime2weeks DESC, playtime_forever DESC",
                    // spotless:on
                    new String[] {String.valueOf(userId)});

            ensureReflection();

            List<Object> result = new ArrayList<>();
            while (cursor.moveToNext()) {
                int appId = cursor.getInt(0);
                long playtimeForeverMin = cursor.getLong(1);
                long playtime2weeksMin = cursor.getLong(2);

                Object entity = entityClass.getDeclaredConstructor().newInstance();
                steamAppidField.set(entity, String.valueOf(appId));
                totalSecondsField.setLong(entity, playtimeForeverMin * 60L);
                last14DaysField.setLong(entity, playtime2weeksMin * 60L);

                result.add(entity);
            }

            L.d("queryPlaytimes: found " + result.size() + " games with playtime");
            return result.isEmpty() ? null : result;
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
    }

    /**
     * Lazily initialises the reflection handles for {@code RecentGameEntity} fields.
     * Field {@code a} = steamAppid (String, final), {@code d} = totalSeconds (long, final),
     * {@code e} = last14DaysSeconds (long, final).
     */
    private static void ensureReflection() throws Exception {
        if (entityClass != null) return;
        entityClass = Class.forName("com.xj.game.entity.RecentGameEntity");
        steamAppidField = entityClass.getDeclaredField("a");
        steamAppidField.setAccessible(true);
        totalSecondsField = entityClass.getDeclaredField("d");
        totalSecondsField.setAccessible(true);
        last14DaysField = entityClass.getDeclaredField("e");
        last14DaysField.setAccessible(true);
    }
}
