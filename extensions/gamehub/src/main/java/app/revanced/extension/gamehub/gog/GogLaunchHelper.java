package app.revanced.extension.gamehub.gog;

import android.app.Activity;
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
 * §40 (pre23): in-session refresh root-caused & fixed. Device-verified
 * 2026-05-29 that GameHub's Room keeps NO persisted invalidation triggers and
 * NO persisted room_table_modification_log in db_game_library.db — both are
 * connection-local TEMP objects on Room's own connection. A write on the
 * foreign SQLiteDatabase.openDatabase connection (the old path) therefore
 * never fires Room's triggers, so refreshLibrary() scanned an empty log
 * (no-op) and the §39 REORDER_TO_FRONT nudge only re-read an unchanged
 * StateFlow. FIX: run the INSERTs on Room's OWN connection (located via
 * RoomRefreshHelper.getRoomConnection) so its TEMP triggers fire and mark the
 * log dirty; refreshLibrary() then makes the tracker poll and the library Flow
 * re-emits in-session — no restart, no nudge. The §39 nudge is removed. The
 * foreign-connection write is kept ONLY as a fallback so behavior never
 * regresses below "restart shows it".
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

        SQLiteDatabase roomDb = null;   // Room's own connection — must NOT be closed by us.
        SQLiteDatabase ownDb  = null;   // fallback foreign connection — we own & close it.
        try {
            // §40 preferred path: write on Room's own connection so its TEMP
            // invalidation triggers fire (the only way the library Flow can
            // re-emit in-session). getRoomConnection returns null if the live
            // Room SQLiteDatabase can't be reached reflectively.
            roomDb = RoomRefreshHelper.getRoomConnection(activity);
            SQLiteDatabase db = roomDb;
            if (db == null) {
                // Fallback (old behavior): foreign connection. Row still lands
                // correctly — it just won't appear until the app is restarted.
                File dbFile = activity.getDatabasePath(DB_NAME);
                if (dbFile == null || !dbFile.exists()) {
                    Log.e(TAG, "GogLaunchHelper.addToLibrary: " + DB_NAME + " not present");
                    toast(activity, "Library DB not initialised — open GameHub once, then retry");
                    return;
                }
                ownDb = SQLiteDatabase.openDatabase(
                        dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
                db = ownDb;
                Log.w(TAG, "GogLaunchHelper: Room connection unavailable — foreign-connection"
                        + " write; row needs an app restart to appear");
            }

            registerInLibrary(db, gameRowId, gogId, safeName, safeCover, exePath);

            // Make Room poll its modification log. On the Room-connection path
            // our INSERT just fired the TEMP triggers so the log is dirty and
            // this re-emits the library Flow; harmless on the fallback path.
            RoomRefreshHelper.refreshLibrary(activity);
            toast(activity, "Added “" + safeName + "” to library");
        } catch (Throwable t) {
            Log.e(TAG, "GogLaunchHelper.addToLibrary failed (non-fatal)", t);
            toast(activity, "Add to library failed — " + t.getClass().getSimpleName());
        } finally {
            if (ownDb != null) {
                try { ownDb.close(); } catch (Throwable ignored) {}
            }
            // roomDb intentionally left open — it belongs to Room.
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Insert the GOG row pair. The caller supplies the open connection and owns
     *  its lifecycle (it may be Room's live connection, which we must not close).
     *  We never close {@code db} here. */
    private static void registerInLibrary(SQLiteDatabase db, String gameRowId,
                                          String gogId, String name, String coverUrl,
                                          String exePath) {
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

    private static void toast(Activity activity, String msg) {
        if (activity == null) return;
        try {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }
}
