package com.xj.winemu.login;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;

/**
 * Seeds a synthetic account directly into GameHub's Room database so the app
 * behaves as logged in.
 *
 * WHY THIS EXISTS — device-proven 2026-07-30, GameHub 6.1.0.
 * Faking the auth interface's StateFlow getters is NOT sufficient on 6.1.0. The
 * auth impl builds its flows with
 *   androidx.room3.coroutines.FlowUtil.createFlow(db, …, ["user_account","auth_token"], …)
 * and the Library path consumes DB-derived state. Five successive rounds of
 * accessor/extension fixes all demonstrably executed and the screen never
 * changed. Hand-seeding ONE row into each of those two tables unlocked the
 * Library instantly — real library chrome (PC/Steam/Epic/Retro tabs, Import
 * button, "No games. Import/Play.") instead of "Log in to view your game
 * library". So the gate is the DATABASE, and this is the fix that matches it.
 *
 * WHY RAW SQLITE INSTEAD OF ROOM.
 * 6.1.0 uses Room 3, which removed getOpenHelper()/SupportSQLiteDatabase; its
 * connection API is androidx.sqlite.SQLiteConnection and the useful entry points
 * are obfuscated suspend functions. Rather than fight that, we open the database
 * file with the plain framework SQLiteDatabase API — every name on that path is
 * platform API and can never be renamed by R8.
 *
 * SAFETY RULES observed here, in order of importance:
 *  1. NEVER create the database. If the file or the tables are absent we do
 *     nothing and retry later. Creating it ourselves would leave Room facing an
 *     empty file with no schema and no identity hash, which is a corruption /
 *     "cannot verify the data integrity" crash rather than a login bypass.
 *  2. INSERT OR IGNORE only — never UPDATE or DELETE. If the user has a real
 *     account, ours is a no-op and their data is untouched.
 *  3. Everything is wrapped; any failure logs and leaves the app exactly as it
 *     was. A broken bypass must never be a broken app.
 *
 * Room's InvalidationTracker picks the rows up, so seeding after the flows have
 * already been created still propagates.
 */
public final class BhAuthSeed {

    private static final String TAG = "BhAuthSeed";
    private static final String DB_NAME = "egggame.db";

    /**
     * Synthetic user id. Must stay identical to the value the game-library repo's
     * userId getter is patched to return, or library rows get written under one id
     * and queried under another — which looks exactly like an empty library.
     */
    private static final String USER_ID = "99999";

    /** Room creates the DB lazily, so poll briefly after app start. */
    private static final int MAX_ATTEMPTS = 12;
    private static final long RETRY_DELAY_MS = 1500L;

    private static volatile boolean done;
    private static volatile boolean started;

    private BhAuthSeed() {}

    /**
     * Zero-arg entry point. Resolves its own Context via ActivityThread, so it can
     * be injected anywhere convenient rather than only where a Context is in scope.
     *
     * The original design hooked the application class's onCreate and passed p0.
     * That anchor is correct in principle -- the class name is unobfuscated and
     * another patch already mutates it -- but the patcher rejected the injection
     * with "classDef is null". Rather than fight the tooling, this is injected into
     * a class the login patch already mutates successfully, which needs no Context
     * parameter.
     */
    public static void seed() {
        if (done) return;
        // Resolve the Context INSIDE the retry loop, not here: this is injected into
        // a constructor that may run exactly once, so bailing on a momentarily-null
        // Context would mean never seeding at all.
        startSeeding(null);
    }

    /** @return the Application context, or null if the process isn't far enough along. */
    private static Context resolveAppContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return (app instanceof Context) ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Entry point taking an explicit Context (may be null -- it will be resolved). */
    public static void seed(final Context ctx) {
        if (done) return;
        startSeeding(ctx);
    }

    private static void startSeeding(final Context supplied) {
        if (started) return;
        started = true;
        // Off the main thread: this is disk I/O on a constructor path.
        new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 1; attempt <= MAX_ATTEMPTS && !done; attempt++) {
                    Context ctx = (supplied != null) ? supplied : resolveAppContext();
                    if (ctx != null && trySeed(ctx, attempt)) return;
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!done) Log.e(TAG, "gave up after " + MAX_ATTEMPTS + " attempts");
            }
        }, "bh-auth-seed").start();
    }

    private static boolean trySeed(Context ctx, int attempt) {
        SQLiteDatabase db = null;
        try {
            File f = ctx.getDatabasePath(DB_NAME);
            // Rule 1: do not create it.
            if (f == null || !f.exists()) return false;

            db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
            if (!hasTable(db, "user_account") || !hasTable(db, "auth_token")) return false;

            if (rowCount(db, "user_account") > 0 && rowCount(db, "auth_token") > 0) {
                Log.e(TAG, "account rows already present; nothing to do");
                done = true;
                return true;
            }

            long now = System.currentTimeMillis();
            long year = 31536000000L;

            // Rule 2: INSERT OR IGNORE only. Column lists are explicit so that a
            // future schema gaining columns does not break this (new columns must
            // be nullable or defaulted, which Room migrations guarantee).
            db.execSQL(
                "INSERT OR IGNORE INTO user_account"
                    + " (user_id, uuid, username, nickname, is_guest, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, 0, ?, ?)",
                new Object[] { USER_ID, USER_ID, "bannerhub", "BannerHub", now, now });

            db.execSQL(
                "INSERT OR IGNORE INTO auth_token"
                    + " (user_id, access_token, refresh_token, token_type,"
                    + "  access_token_expires_at, refresh_token_expires_at, issued_at,"
                    + "  is_current, created_at, updated_at)"
                    + " VALUES (?, ?, ?, 'Bearer', ?, ?, ?, 1, ?, ?)",
                new Object[] { USER_ID, "bh-synthetic-token", "bh-synthetic-refresh",
                               now + year, now + year, now, now, now });

            Log.e(TAG, "seeded synthetic account user_id=" + USER_ID
                    + " (attempt " + attempt + ")");
            done = true;
            return true;
        } catch (Throwable t) {
            // Rule 3: never let this break the app.
            Log.e(TAG, "seed attempt " + attempt + " failed", t);
            return false;
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static boolean hasTable(SQLiteDatabase db, String name) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[] { name });
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
    }

    private static long rowCount(SQLiteDatabase db, String table) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM " + table, null);
            return (c != null && c.moveToFirst()) ? c.getLong(0) : 0L;
        } catch (Throwable t) {
            return 0L;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
    }
}
