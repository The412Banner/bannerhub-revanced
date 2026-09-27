package app.revanced.extension.gamehub.login;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Seeds the fake BannerHub account into the app's Room database.
 *
 * Why this exists (6.3.1, device-diagnosed 2026-09-26): the pcengine PLUGIN no
 * longer asks the host for the signed-in user. Its own auth impl (`xjp/q20`)
 * builds the profile/token StateFlows straight from Room
 * `createFlow(egggame.db, ["user_account", "auth_token"])` — the profile is the
 * join of the `user_account` row with the CURRENT `auth_token` row. Everything
 * the bypass-login patch fakes lives in the HOST's in-memory flows, so the
 * plugin saw no user at all: its download bookkeeping is keyed by userId
 * (`je2.b()` → profile.userId), the per-user download flow became
 * `emptyFlow()`, and every launch died with "Download observer ended before
 * terminal state" while the actual download kept running underneath.
 *
 * So write the two rows ourselves, with the same synthetic user id the
 * in-memory fakes use ({@link FakeAuthToken#FAKE_USER_ID}). Room's own tables,
 * `INSERT OR IGNORE`, no schema changes.
 *
 * ⚠️ MUST go through the SAME SQLite library Room uses. The first cut opened
 * the file with android.database.sqlite (the platform library) while Room was
 * on androidx.sqlite's BUNDLED library in the same process. Two SQLite builds
 * in one process each keep their own in-process inode/lock table, so their
 * POSIX fcntl locks silently cancel each other → "database disk image is
 * malformed" (SQLITE_CORRUPT) in Room on the first launch of a fresh install
 * (device-seen 2026-09-26 22:10). Using androidx.sqlite.driver.bundled's
 * BundledSQLiteDriver — the exact driver the app ships and R8 keeps by name —
 * is what Room itself does for extra connections. The androidx.sqlite
 * interfaces (SQLiteConnection.prepare, SQLiteStatement.bindText/bindLong/
 * step/close) are library API and survive R8 un-renamed (verified in the
 * 6.3.1 host smali), so plain reflection on them is stable.
 *
 * Re-checked (throttled) on every {@link FakeAuthToken#get()} because the
 * plugin's TokenRefreshPlugin answers ANY 401 that has no refresh token with
 * onTokenInvalid() → DELETE FROM auth_token — the Worker now stubs the known
 * offender (heartbeat/game/*) but a future 401 would otherwise silently put us
 * back to "no user". Self-healing beats hoping.
 *
 * The DB file is created by Room on the host's first open; until then there is
 * nothing to seed, so a missing file/table is simply "try again later".
 */
public final class FakeAccountDbSeeder {
    private static final String TAG = "GH600-DEBUG";
    private static final String DB_NAME = "egggame.db";
    private static final String USER_ID = FakeAuthToken.FAKE_USER_ID;
    private static final String ACCESS_TOKEN = "bannerhub-fake-token";
    private static final String REFRESH_TOKEN = "bannerhub-fake-refresh";
    // ~100 years; the plugin compares expiry against wall-clock millis.
    private static final long EXPIRES_IN_MS = 3153600000000L;
    // Cheap re-verification cadence once both rows have been seen present.
    private static final long RECHECK_MS = 60_000L;
    // Back-off while the DB/tables do not exist yet (fresh install, before Room
    // has created them), while Room holds the write lock (SQLITE_BUSY), or
    // while a write keeps failing.
    private static final long RETRY_MS = 5_000L;

    private static final String DRIVER_CLASS = "androidx.sqlite.driver.bundled.BundledSQLiteDriver";
    private static final String CONNECTION_IFACE = "androidx.sqlite.SQLiteConnection";
    private static final String STATEMENT_IFACE = "androidx.sqlite.SQLiteStatement";

    private static volatile long nextCheckAt;
    private static volatile boolean seeding;

    private FakeAccountDbSeeder() {}

    /** Non-blocking: schedules a check/seed on a background thread if due. */
    public static void ensure() {
        long now = System.currentTimeMillis();
        if (now < nextCheckAt || seeding) return;
        synchronized (FakeAccountDbSeeder.class) {
            if (now < nextCheckAt || seeding) return;
            seeding = true;
        }
        Thread t = new Thread(() -> {
            long delay = RETRY_MS;
            try {
                delay = seedNow() ? RECHECK_MS : RETRY_MS;
            } catch (Throwable e) {
                Log.w(TAG, "FakeAccountDbSeeder: seed failed", e);
            } finally {
                nextCheckAt = System.currentTimeMillis() + delay;
                seeding = false;
            }
        }, "bh-account-seeder");
        t.setDaemon(true);
        t.start();
    }

    /**
     * @return true when both rows are present after this call (nothing to do
     *         until the next re-check), false when the DB is not ready yet.
     */
    static boolean seedNow() throws Exception {
        Context ctx = appContext();
        if (ctx == null) return false;
        File dbFile = ctx.getDatabasePath(DB_NAME);
        if (!dbFile.exists()) return false;

        Class<?> connCls = Class.forName(CONNECTION_IFACE);
        Class<?> stmtCls = Class.forName(STATEMENT_IFACE);
        Object driver = Class.forName(DRIVER_CLASS).getDeclaredConstructor().newInstance();
        Object conn = driver.getClass().getMethod("open", String.class).invoke(driver, dbFile.getPath());
        Db db = new Db(conn, connCls, stmtCls);
        try {
            if (!db.exists("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'user_account'")
                || !db.exists("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'auth_token'")) {
                return false;
            }

            long now = System.currentTimeMillis();
            boolean wrote = false;

            if (!db.exists("SELECT 1 FROM user_account WHERE user_id = '" + USER_ID + "'")) {
                // Every NOT NULL column set; the rest default to NULL like a
                // freshly-registered account. is_guest = 0 to match the
                // in-memory fake profile (isGuest false → non-guest paths).
                db.exec("INSERT OR IGNORE INTO user_account (user_id, uuid, remote_numeric_id, username, "
                    + "nickname, is_guest, created_at, updated_at) VALUES ('" + USER_ID + "', 'bannerhub-"
                    + USER_ID + "', " + Long.parseLong(USER_ID) + ", 'bannerhub', 'BannerHub', 0, "
                    + now + ", " + now + ")");
                wrote = true;
            }

            if (!db.exists("SELECT 1 FROM auth_token WHERE user_id = '" + USER_ID + "' AND is_current = 1")) {
                long exp = now + EXPIRES_IN_MS;
                db.exec("INSERT INTO auth_token (user_id, access_token, refresh_token, token_type, "
                    + "access_token_expires_at, refresh_token_expires_at, issued_at, is_current, "
                    + "created_at, updated_at) VALUES ('" + USER_ID + "', '" + ACCESS_TOKEN + "', '"
                    + REFRESH_TOKEN + "', 'Bearer', " + exp + ", " + exp + ", " + now + ", 1, "
                    + now + ", " + now + ")");
                wrote = true;
            }

            if (wrote) Log.i(TAG, "FakeAccountDbSeeder: seeded user_account/auth_token for " + USER_ID);
            return true;
        } finally {
            db.close();
        }
    }

    /** Thin reflective wrapper over androidx.sqlite.SQLiteConnection/Statement. */
    private static final class Db {
        private final Object conn;
        private final Method prepare;
        private final Method connClose;
        private final Method step;
        private final Method stmtClose;

        Db(Object conn, Class<?> connCls, Class<?> stmtCls) throws Exception {
            this.conn = conn;
            this.prepare = connCls.getMethod("prepare", String.class);
            this.connClose = connCls.getMethod("close");
            this.step = stmtCls.getMethod("step");
            this.stmtClose = stmtCls.getMethod("close");
        }

        // All literals are ours (digits / fixed ASCII), so inline SQL is safe.
        boolean exists(String sql) throws Exception {
            Object st = prepare.invoke(conn, sql);
            try {
                return (Boolean) step.invoke(st);
            } finally {
                stmtClose.invoke(st);
            }
        }

        void exec(String sql) throws Exception {
            Object st = prepare.invoke(conn, sql);
            try {
                step.invoke(st);
            } finally {
                stmtClose.invoke(st);
            }
        }

        void close() throws Exception {
            connClose.invoke(conn);
        }
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable e) {
            return null;
        }
    }
}
