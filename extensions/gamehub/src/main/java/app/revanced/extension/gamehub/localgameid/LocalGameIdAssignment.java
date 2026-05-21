package app.revanced.extension.gamehub.localgameid;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scans {@code db_game_library.db} for rows where {@code server_game_id = -1}
 * and rewrites each one with a deterministic synthetic integer derived from
 * the row's stable {@code id} TEXT column.
 *
 * <p>Background: GameHub assigns {@code server_game_id = -1} to imported PC
 * games when its catalog lookup finds no match. Every unmatched game ends up
 * sharing the same {@code -1}, which collides at the external-launcher
 * dispatch surface ({@link app.revanced.extension.gamehub.launcher.ExternalLauncher})
 * because {@code DeepLinkActivity} parses {@code app_nav_game_id} as
 * Integer and routes by that value. Beacon / ES-DE / Daijishou therefore
 * can't address those games individually.
 *
 * <p>Fix: replace each {@code -1} with {@code (id.hashCode() & 0x3FFFFFFF) | 0x40000000}
 * = a positive 32-bit integer in {@code [0x40000000, 0x7FFFFFFF]} (1,073,741,824
 * – 2,147,483,647). That range sits well above any real GameHub catalog ID
 * (~10^5) and Steam appid (~10^7), and fits inside Java's signed 32-bit
 * Integer that 6.0.4's deep-link parser requires. The {@code id} column is
 * a {@code local_*} UUID assigned at game-import time and is stable across
 * app restarts, library refreshes, and game moves; same input → same output,
 * so an external launcher's saved config keeps working.
 *
 * <p>Idempotent: rows already inside the synthetic range are skipped on
 * re-run, and rows with a real catalog ID outside the range (which GameHub
 * may later back-fill if the catalog matches the title) are left untouched.
 *
 * <p>The scan runs once per app start, on a single background thread, with
 * a SQLite read-write open (WAL-mode-compatible with GameHub's own writer).
 * Any failure is logged and swallowed — Application startup must not be
 * gated on this.
 */
public final class LocalGameIdAssignment {
    private static final String TAG = "BhLocalGameId";
    private static final String DB_NAME = "db_game_library.db";
    private static final String TABLE = "t_game_library_base";

    /** Inclusive lower bound of the synthetic-id range: {@code 0x40000000}. */
    private static final long SYNTHETIC_RANGE_LOW = 0x40000000L;
    /** Inclusive upper bound of the synthetic-id range: {@code 0x7FFFFFFF}. */
    private static final long SYNTHETIC_RANGE_HIGH = 0x7FFFFFFFL;

    private static volatile boolean started = false;

    private LocalGameIdAssignment() {}

    /**
     * Entrypoint invoked from the bytecode patch at the top of
     * {@code BaseAndroidApp.onCreate}. Idempotent across re-calls in the
     * same process — the first call kicks off the scan thread, subsequent
     * calls no-op.
     */
    public static void scanAndAssign(Context context) {
        if (context == null) return;
        if (started) return;
        synchronized (LocalGameIdAssignment.class) {
            if (started) return;
            started = true;
        }
        final Context appContext = context.getApplicationContext();
        // Single dedicated thread — no executor, no shared pool. Daemon so
        // we never hold up app exit.
        Thread t = new Thread(() -> {
            try {
                int fixed = runScan(appContext);
                if (fixed > 0) {
                    Log.i(TAG, "assigned synthetic server_game_id to " + fixed + " row(s)");
                }
            } catch (Throwable err) {
                Log.w(TAG, "scan failed", err);
            }
        }, "BhLocalGameIdScan");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** @return number of rows updated; 0 if none / DB missing / table missing. */
    static int runScan(Context appContext) {
        File dbFile = appContext.getDatabasePath(DB_NAME);
        if (dbFile == null || !dbFile.exists()) {
            Log.i(TAG, "skip: " + DB_NAME + " not present (no library yet)");
            return 0;
        }

        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(),
                null,
                SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            return scanAndUpdate(db);
        } catch (Throwable err) {
            Log.w(TAG, String.format(Locale.ROOT,
                "open or scan failed (%s)", err.getClass().getSimpleName()), err);
            return 0;
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    private static int scanAndUpdate(SQLiteDatabase db) {
        List<long[]> targets = collectTargets(db);
        if (targets.isEmpty()) return 0;

        int updated = 0;
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            for (long[] row : targets) {
                long rowId = row[0];
                long synthetic = row[1];

                ContentValues cv = new ContentValues(2);
                cv.put("server_game_id", synthetic);
                cv.put("modify_time", now);

                int n = db.update(TABLE, cv, "_id = ?",
                    new String[]{ String.valueOf(rowId) });
                if (n > 0) updated += n;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return updated;
    }

    /**
     * @return list of {@code [_id, synthetic_server_game_id]} pairs for every
     *         row that currently has {@code server_game_id = -1}. Returns an
     *         empty list if the table is missing or no rows match.
     */
    private static List<long[]> collectTargets(SQLiteDatabase db) {
        List<long[]> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery(
                "SELECT _id, id FROM " + TABLE + " WHERE server_game_id = -1", null);
            while (c.moveToNext()) {
                long rowId = c.getLong(0);
                String idText = c.isNull(1) ? "" : c.getString(1);
                if (idText.isEmpty()) {
                    // No stable handle to hash. Skip rather than mint a
                    // collision-prone value off of, e.g., game_name.
                    Log.w(TAG, "row _id=" + rowId + " has empty id column; skipping");
                    continue;
                }
                long synthetic = deriveSyntheticId(idText);
                out.add(new long[]{ rowId, synthetic });
            }
        } catch (Throwable err) {
            // Most likely: table missing on a future schema bump. Caller will
            // see an empty list and skip the UPDATE phase.
            Log.w(TAG, "collectTargets query failed; assuming nothing to do", err);
            return new ArrayList<>();
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /**
     * Maps a stable {@code id} string into the synthetic range
     * {@code [0x40000000, 0x7FFFFFFF]}. {@code String.hashCode()} is
     * spec-stable across JVMs, so the same input always yields the same
     * output — Beacon / ES-DE configs survive across app restarts and even
     * GameHub updates as long as the {@code id} column itself doesn't change.
     *
     * <p>Collision probability for a few hundred unmatched games inside a
     * 2^30-value space is effectively zero (birthday-paradox 50% threshold is
     * ~32 768 games).
     *
     * @return a value in {@code [0x40000000, 0x7FFFFFFF]} as a {@code long}
     *         so callers can store it directly into the {@code INTEGER}
     *         column without sign-confusion at the JDBC boundary.
     */
    static long deriveSyntheticId(String idText) {
        int h = idText.hashCode();
        int synthetic = (h & 0x3FFFFFFF) | 0x40000000;
        return synthetic & 0xFFFFFFFFL;
    }

    /** @return true if {@code v} is in the synthetic range. Exposed for tests. */
    static boolean isInSyntheticRange(long v) {
        return v >= SYNTHETIC_RANGE_LOW && v <= SYNTHETIC_RANGE_HIGH;
    }
}
