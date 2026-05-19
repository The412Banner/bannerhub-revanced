package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;

/**
 * WS5 bridge — registers an installed GOG game into GameHub's own
 * GameLibraryDatabase so it appears in the library and launches via
 * LaunchType.GogGameByPcEmulator (start_type=1409=0x581).
 *
 * Implementation = Approach B from design doc §35 (programmatic DB insert).
 * Uses raw android.database.sqlite against db_game_library.db — bypasses
 * Room/Hilt/Continuation entirely; same shape as the proven retired
 * GogLibraryCard seeder.
 *
 * Row shape was byte-verified from a live user row (God of War) in §35.4:
 *   t_game_launch_method.extension_data is JSON with gameId / name /
 *   coverImage / exePath / startType / isLocalGame / (GOG)Id.
 *
 * After insert, fires an Intent to com.xiaoji.egggame.MainActivity with
 * app_nav_target=local_game_launch + app_nav_game_id=<row id> — MainActivity
 * already handles this case (smali :173) and auto-launches the game via
 * the Wine container.
 *
 * Fail-safe: any error logs + toasts a hint and leaves the user in the GOG
 * activity. Never crashes; never throws past this class.
 */
public final class GogLaunchHelper {

    private static final String TAG = "BannerHub";

    private static final String DB_NAME = "db_game_library.db";

    /** LaunchType.GogGameByPcEmulator.id (LaunchType.smali:455-472). */
    private static final int START_TYPE_GOG = 1409;

    /** Used only if the library is empty (first-ever import). Matches the
     *  pre12 retired-seeder fallback; the same FakeUserAccount bypass id. */
    private static final String FALLBACK_USER_ID = "99999";
    private static final int    FALLBACK_EXT_TYPE = 1;

    private GogLaunchHelper() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /** Convenience overload taking a GogGame from the library list. */
    public static void triggerLaunch(Activity activity, GogGame game, String exePath) {
        if (game == null) {
            Log.w(TAG, "GogLaunchHelper: null GogGame — abort");
            return;
        }
        triggerLaunch(activity, exePath, game.gameId, game.title, game.imageUrl);
    }

    /**
     * Register the GOG game in GameHub's library and immediately launch it.
     *
     * @param activity caller (used for ctx + finish()); may be null only if
     *                 the auto-launch step is undesirable
     * @param exePath  filesystem path to the .exe to run (absolute)
     * @param gogId    GOG game id (manifest's "gameId"); becomes our DB id
     *                 with a "gog_" prefix
     * @param title    display name; pre-fills game_name / start_name
     * @param coverUrl cover image URL; pre-fills cover_image / logo / etc.
     */
    public static void triggerLaunch(Activity activity, String exePath,
                                     String gogId, String title, String coverUrl) {
        if (activity == null || exePath == null || gogId == null) {
            Log.w(TAG, "GogLaunchHelper: required args null — abort"
                    + " (activity=" + activity + " exe=" + exePath + " gogId=" + gogId + ")");
            return;
        }
        final String safeName  = (title    != null && !title.isEmpty())    ? title    : "GOG Game";
        final String safeCover = (coverUrl != null)                        ? coverUrl : "";
        final String gameRowId = "gog_" + gogId;

        try {
            File dbFile = activity.getDatabasePath(DB_NAME);
            if (dbFile == null || !dbFile.exists()) {
                Log.e(TAG, "GogLaunchHelper: " + DB_NAME + " not present — "
                        + "open GameHub once first, then retry");
                toast(activity, "Library DB not initialised — open GameHub once, then retry");
                return;
            }
            registerInLibrary(activity, dbFile, gameRowId, gogId, safeName, safeCover, exePath);
            // §37: kick Room's InvalidationTracker so the library Flow re-emits
            // in the running GameHub process. Our raw write is on a separate
            // SQLite connection; Room would otherwise stay on its pre-write
            // snapshot until cold-restart.
            RoomRefreshHelper.refreshLibrary(activity);
            dispatchLaunch(activity, gameRowId);
            activity.finish();
        } catch (Throwable t) {
            Log.e(TAG, "GogLaunchHelper.triggerLaunch failed (non-fatal)", t);
            toast(activity, "Add to library failed — " + t.getClass().getSimpleName());
        }
    }

    /** Legacy ABI — kept so the old single-arg call site (if any) still compiles.
     *  Without metadata we can't produce a useful row, so this just logs+toasts. */
    public static void triggerLaunch(Activity activity, String exePath) {
        Log.w(TAG, "GogLaunchHelper: legacy 2-arg triggerLaunch called — "
                + "callers should pass GogGame or (gogId,title,coverUrl) "
                + "for the WS5 bridge to write a library row. exe=" + exePath);
        toast(activity, "GOG game installed — but library metadata missing");
    }

    /** Phase-1 no-op. The new flow doesn't use SharedPrefs handoff to onResume. */
    public static void checkPendingLaunch(Activity activity) {
        // Intentionally empty.
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static void registerInLibrary(Context ctx, File dbFile, String gameRowId,
                                          String gogId, String name, String coverUrl,
                                          String exePath) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            String userId = FALLBACK_USER_ID;
            int    extType = FALLBACK_EXT_TYPE;
            try (Cursor c = db.rawQuery(
                    "SELECT extension_type,user_id FROM t_game_library_base "
                            + "WHERE id<>? LIMIT 1", new String[]{gameRowId})) {
                if (c.moveToFirst()) {
                    extType = c.getInt(0);
                    String u = c.getString(1);
                    if (u != null && !u.isEmpty()) userId = u;
                }
            }

            String extData = buildExtensionData(gameRowId, gogId, name, coverUrl, exePath);

            db.beginTransaction();
            try {
                // Idempotent: a re-install of the same GOG game replaces its rows
                // rather than failing on the (id,user_id) UNIQUE index.
                db.execSQL("DELETE FROM t_game_launch_method WHERE linked_game_id=?",
                        new Object[]{gameRowId});
                db.execSQL("DELETE FROM t_game_library_base WHERE id=?",
                        new Object[]{gameRowId});

                db.execSQL(
                        "INSERT INTO t_game_launch_method "
                                + "(linked_game_id,start_type,start_name,extension_data) "
                                + "VALUES (?,?,?,?)",
                        new Object[]{gameRowId, START_TYPE_GOG, name, extData});

                long lmId;
                try (Cursor c = db.rawQuery("SELECT last_insert_rowid()", null)) {
                    c.moveToFirst();
                    lmId = c.getLong(0);
                }

                db.execSQL(
                        "INSERT INTO t_game_library_base "
                                + "(id,user_id,server_game_id,extension_type,launch_method_id,"
                                + "game_name,game_source,source_type,`from`,source_id,"
                                + "cover_image,cover_ver_image,logo,icon_url,square_image) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        new Object[]{gameRowId, userId, 0, extType, lmId,
                                name, 3, 0, 0, gogId,
                                coverUrl, coverUrl, coverUrl, coverUrl, coverUrl});

                db.setTransactionSuccessful();
                Log.i(TAG, "GogLaunchHelper: registered " + gameRowId
                        + " (lm=" + lmId + " user=" + userId + " ext=" + extType + ")");
            } finally {
                db.endTransaction();
            }
        } finally {
            try { db.close(); } catch (Throwable ignored) {}
        }
    }

    private static String buildExtensionData(String gameRowId, String gogId,
                                             String name, String coverUrl, String exePath) {
        try {
            JSONObject o = new JSONObject();
            o.put("gameId",      gameRowId);
            o.put("isLocalGame", true);
            o.put("coverImage",  coverUrl);
            o.put("name",        name);
            o.put("startType",   START_TYPE_GOG);
            o.put("gogId",       gogId);
            o.put("exePath",     exePath);
            return o.toString();
        } catch (Throwable t) {
            // JSONObject.put can throw on bad keys (shouldn't with these literals).
            // Manual fallback so a freak failure can't kill the import.
            return "{\"gameId\":\"" + esc(gameRowId)
                    + "\",\"isLocalGame\":true,\"coverImage\":\"" + esc(coverUrl)
                    + "\",\"name\":\"" + esc(name)
                    + "\",\"startType\":" + START_TYPE_GOG
                    + ",\"gogId\":\"" + esc(gogId)
                    + "\",\"exePath\":\"" + esc(exePath) + "\"}";
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void dispatchLaunch(Activity activity, String gameRowId) {
        Intent intent = new Intent();
        intent.setClassName(activity.getPackageName(), "com.xiaoji.egggame.MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // MainActivity.smali:134-200 handles this pair (DeepLinkActivity uses
        // the same convention — see DeepLinkActivity.smali:3951+).
        intent.putExtra("app_nav_target", "local_game_launch");
        intent.putExtra("app_nav_game_id", gameRowId);
        activity.startActivity(intent);
        Log.i(TAG, "GogLaunchHelper: dispatched local_game_launch id=" + gameRowId);
    }

    private static void toast(Activity activity, String msg) {
        if (activity == null) return;
        try {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }
}
