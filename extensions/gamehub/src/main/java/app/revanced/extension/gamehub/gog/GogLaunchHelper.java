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
 * §41 (pre24): in-session refresh is NOT achievable from here, by design of the
 * base app — accepted. Decompile (2026-05-29) proved GameHub's library DB runs
 * on androidx.room 2.7+ with the BundledSQLiteDriver (its own statically-linked
 * native SQLite, libsqliteJni.so) — there is no android.database.sqlite
 * connection and no SupportSQLiteOpenHelper for it. Room's invalidation triggers
 * + room_table_modification_log are connection-local TEMP objects inside that
 * bundled connection, so ANY write we make from a separate android.database
 * .sqlite connection is invisible to Room's tracker and can never trigger an
 * in-session re-query. (This also means §37/§39/§40 never could have worked.)
 * The row still commits correctly and shows after a process restart (the
 * library is process-scoped). Per user decision 2026-05-29 we keep the simple
 * external write and tell the truth in the toast: "restart GameHub to see it".
 * Driving Room's own connection / DAO was rejected as too fragile + risky
 * (coroutine-mutex race on the live native connection).
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
     *  GameHub library tile like any other PC import. The row appears after a
     *  GameHub restart (see class header §41 for why in-session refresh is not
     *  possible from outside Room's bundled native connection). */
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

        SQLiteDatabase db = null;
        try {
            File dbFile = activity.getDatabasePath(DB_NAME);
            if (dbFile == null || !dbFile.exists()) {
                Log.e(TAG, "GogLaunchHelper.addToLibrary: " + DB_NAME + " not present");
                toast(activity, "Library DB not initialised — open GameHub once, then retry");
                return;
            }
            db = SQLiteDatabase.openDatabase(
                    dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            registerInLibrary(db, gameRowId, gogId, safeName, safeCover, exePath);
            // The row commits fine, but GameHub's Room (BundledSQLiteDriver, its
            // own native SQLite) can't see a foreign-connection write to invalidate
            // its library Flow — so it only appears after a restart. Say so. (§41)
            toast(activity, "Added “" + safeName + "” — restart GameHub to see it in your library");
        } catch (Throwable t) {
            Log.e(TAG, "GogLaunchHelper.addToLibrary failed (non-fatal)", t);
            toast(activity, "Add to library failed — " + t.getClass().getSimpleName());
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Insert the GOG row pair. The caller supplies the open connection and owns
     *  its lifecycle (closes it). We never close {@code db} here. */
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
