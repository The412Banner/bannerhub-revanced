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
 * GameLibraryDatabase so it appears as a tile in the library and launches via
 * LaunchType.GogGameByPcEmulator (start_type=1409=0x581) when the user taps
 * it.
 *
 * Implementation = Approach B from design doc §35 (programmatic DB insert).
 * Uses raw android.database.sqlite against db_game_library.db — bypasses
 * Room/Hilt/Continuation entirely; same shape as the proven retired
 * GogLibraryCard seeder. Row shape byte-verified from a live user row
 * (God of War) in §35.4: t_game_launch_method.extension_data is JSON with
 * gameId / name / coverImage / exePath / startType / isLocalGame / gogId.
 *
 * Launching is intentionally NOT done here. Per user spec (2026-05-20):
 * the only post-download action on any GOG screen is "Add to Library";
 * launching is done manually by the user from the GameHub library tile,
 * exactly like any other PC import. See §38.
 *
 * §39 (pre20): after the raw insert + RoomRefreshHelper, dispatch a no-payload
 * Intent to MainActivity with FLAG_ACTIVITY_REORDER_TO_FRONT. pre19 toast diag
 * confirmed RoomRefreshHelper resolves the tracker and invokes its refresh
 * method cleanly, but the library Flow still doesn't re-emit until the host
 * recomposes — Room's tracker scan finds no version delta for an
 * externally-written row. Bringing MainActivity to the front (without
 * clearing the GOG back stack) forces a recomposition, and the library Flow
 * re-collects from Room with the new row visible. No
 * app_nav_target=local_game_launch extra means MainActivity will NOT
 * auto-launch the game (§38 preserved); the bh_refresh_only=true marker is a
 * debug breadcrumb only.
 *
 * Fail-safe: any error logs + toasts a hint and leaves the user on the GOG
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

    /** Add the GOG game to GameHub's library.
     *  No auto-launch — launching is the user's job, done manually from the
     *  GameHub library tile like any other PC import. The library refresh
     *  (§37) fires so the new row appears in-session. */
    public static void addToLibrary(Activity activity, GogGame game, String exePath) {
        if (game == null) {
            Log.w(TAG, "GogLaunchHelper: null GogGame — abort");
            return;
        }
        addToLibrary(activity, exePath, game.gameId, game.title, game.imageUrl);
    }

    /** See {@link #addToLibrary(Activity, GogGame, String)}. */
    public static void addToLibrary(Activity activity, String exePath,
                                    String gogId, String title, String coverUrl) {
        if (activity == null || exePath == null || gogId == null) {
            Log.w(TAG, "GogLaunchHelper.addToLibrary: required args null — abort"
                    + " (activity=" + activity + " exe=" + exePath + " gogId=" + gogId + ")");
            return;
        }
        final String safeName  = (title    != null && !title.isEmpty())    ? title    : "GOG Game";
        final String safeCover = (coverUrl != null)                        ? coverUrl : "";
        final String gameRowId = "gog_" + gogId;

        try {
            File dbFile = activity.getDatabasePath(DB_NAME);
            if (dbFile == null || !dbFile.exists()) {
                Log.e(TAG, "GogLaunchHelper.addToLibrary: " + DB_NAME + " not present");
                toast(activity, "Library DB not initialised — open GameHub once, then retry");
                return;
            }
            registerInLibrary(activity, dbFile, gameRowId, gogId, safeName, safeCover, exePath);
            // §37: kick Room InvalidationTracker so the library Flow re-emits.
            RoomRefreshHelper.refreshLibrary(activity);
            // §39: REORDER_TO_FRONT MainActivity to force Compose recomposition
            // — the tracker call alone is necessary but not sufficient.
            dispatchLibraryRefreshNudge(activity);
            toast(activity, "Added “" + safeName + "” to library");
        } catch (Throwable t) {
            Log.e(TAG, "GogLaunchHelper.addToLibrary failed (non-fatal)", t);
            toast(activity, "Add to library failed — " + t.getClass().getSimpleName());
        }
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

    private static void dispatchLibraryRefreshNudge(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setClassName(activity.getPackageName(), "com.xiaoji.egggame.MainActivity");
            // REORDER_TO_FRONT: bring MainActivity to the top of the existing
            // task without clearing intermediate GOG activities. onResume +
            // Compose recomposition fires → library Flow re-collects from
            // Room and picks up our newly-inserted row.
            //
            // No app_nav_target=local_game_launch extra — that's the pre15
            // auto-launch path we explicitly killed in §38. bh_refresh_only is
            // a marker for any future receiver / debugging only.
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.putExtra("bh_refresh_only", true);
            activity.startActivity(intent);
            Log.i(TAG, "GogLaunchHelper: dispatched library-refresh nudge to MainActivity");
        } catch (Throwable t) {
            Log.w(TAG, "GogLaunchHelper: refresh-nudge dispatch failed (non-fatal)", t);
        }
    }

    private static void toast(Activity activity, String msg) {
        if (activity == null) return;
        try {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }
}
