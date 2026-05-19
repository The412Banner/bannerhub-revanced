package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;

/**
 * WS4 — permanent synthetic "GOG" card in the game library (design doc §28).
 *
 * Seeds one sentinel row into GameHub's own GameLibraryDatabase
 * (db_game_library.db: t_game_library_base + t_game_launch_method) so a "GOG"
 * card always renders in the library grid alongside imported/Steam/Epic games.
 * Tapping it is intercepted upstream (GogLibraryCardPatch hooks po7's launch
 * dispatch) → opens GogMainActivity instead of a Wine launch.
 *
 * Persistence (§28): GameHub only ever single-row-deletes library entries on
 * explicit user removal; there is NO bulk/server-reconcile delete, and server
 * sync ignores rows with server_game_id=0. So a server_game_id=0 sentinel is
 * effectively permanent; ensureSeeded() runs every app start (MainActivity
 * .onCreate hook) so even a manual delete self-heals.
 *
 * Field values are SELF-DERIVED at runtime from any existing real library row
 * (extension_type, user_id) so this survives base-APK bumps without a
 * hardcoded obfuscated enum int; falls back to user_id="99999"
 * (FakeUserAccount bypass id) + extension_type=2 if the library is empty.
 *
 * Pure android.database (no Room) — matches the zero-third-party-dep
 * extension rule. Fail-safe by construction: any error → no-op, never
 * crashes GameHub.
 */
public final class GogLibraryCard {

    private static final String TAG = "BannerHub";

    /** Sentinel game id — also the launch-intercept marker (GogLibraryCardPatch). */
    public static final String SENTINEL_ID = "bh_gog_launcher";

    private static final String DB_NAME    = "db_game_library.db";
    private static final String CARD_NAME  = "GOG";
    private static final String FALLBACK_USER_ID = "99999"; // FakeUserAccount bypass id
    private static final int    FALLBACK_EXT_TYPE = 2;
    // Card art: stable public GOG.com logo (Coil loads cover_image/logo as a
    // remote URL). Swappable to a self-hosted bannerhub-api asset later.
    private static final String CARD_ART =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/8/80/GOG.com_logo.svg/512px-GOG.com_logo.svg.png";

    private GogLibraryCard() {}

    // ── seed (idempotent, self-healing) ──────────────────────────────────────

    /** Called at MainActivity.onCreate. Inserts the sentinel if absent. */
    public static void ensureSeeded(Context ctx) {
        if (ctx == null) return;
        SQLiteDatabase db = null;
        try {
            File f = ctx.getDatabasePath(DB_NAME);
            if (f == null || !f.exists()) {
                Log.i(TAG, "GogLibraryCard: " + DB_NAME + " not present yet — skip seed this start");
                return;
            }
            db = SQLiteDatabase.openDatabase(
                    f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);

            // Self-derive extension_type + user_id from a real row.
            String userId = FALLBACK_USER_ID;
            int extType = FALLBACK_EXT_TYPE;
            try (Cursor c = db.rawQuery(
                    "SELECT extension_type,user_id FROM t_game_library_base " +
                    "WHERE id<>? LIMIT 1", new String[]{SENTINEL_ID})) {
                if (c.moveToFirst()) {
                    extType = c.getInt(0);
                    String u = c.getString(1);
                    if (u != null && !u.isEmpty()) userId = u;
                }
            }

            db.beginTransaction();
            try {
                // Self-healing: drop any prior sentinel (and its launch
                // method) and reinsert fresh, so art / schema / fixes from
                // newer builds always apply without the user clearing data.
                db.execSQL("DELETE FROM t_game_launch_method WHERE linked_game_id=?",
                        new Object[]{SENTINEL_ID});
                db.execSQL("DELETE FROM t_game_library_base WHERE id=?",
                        new Object[]{SENTINEL_ID});

                // 1) launch-method row (linked by game id).
                db.execSQL(
                    "INSERT INTO t_game_launch_method " +
                    "(linked_game_id,start_type,start_name) VALUES (?,?,?)",
                    new Object[]{SENTINEL_ID, 0, CARD_NAME});
                long lmId;
                try (Cursor c = db.rawQuery("SELECT last_insert_rowid()", null)) {
                    c.moveToFirst();
                    lmId = c.getLong(0);
                }

                // 2) library row. server_game_id=0 → invisible to server sync
                //    (the §28 permanence guarantee). Most columns have NOT NULL
                //    DEFAULT '' so a narrow insert is valid.
                db.execSQL(
                    "INSERT INTO t_game_library_base " +
                    "(id,user_id,server_game_id,extension_type,launch_method_id," +
                    "game_name,game_source,source_type,`from`," +
                    "cover_image,cover_ver_image,logo,icon_url,square_image) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{SENTINEL_ID, userId, 0, extType, lmId,
                                 CARD_NAME, 0, 0, 0,
                                 CARD_ART, CARD_ART, CARD_ART, CARD_ART, CARD_ART});

                db.setTransactionSuccessful();
                Log.i(TAG, "GogLibraryCard: seeded sentinel (user_id=" + userId
                        + " ext_type=" + extType + " lm=" + lmId + ")");
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            // Never crash GameHub over a cosmetic card.
            Log.e(TAG, "GogLibraryCard.ensureSeeded failed (non-fatal)", t);
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * §34: the seeded card was retired in favour of the per-game "GOG" menu
     * row (mode-independent; the card only ever rendered in the handheld
     * library surface). Called at MainActivity.onCreate INSTEAD of
     * ensureSeeded — deletes the legacy sentinel rows so the card also
     * disappears for users who ran a seeding build. Idempotent, fail-safe;
     * never crashes GameHub over cleanup.
     */
    public static void ensureRemoved(Context ctx) {
        if (ctx == null) return;
        SQLiteDatabase db = null;
        try {
            File f = ctx.getDatabasePath(DB_NAME);
            if (f == null || !f.exists()) return;
            db = SQLiteDatabase.openDatabase(
                    f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM t_game_launch_method WHERE linked_game_id=?",
                        new Object[]{SENTINEL_ID});
                db.execSQL("DELETE FROM t_game_library_base WHERE id=?",
                        new Object[]{SENTINEL_ID});
                db.setTransactionSuccessful();
                Log.i(TAG, "GogLibraryCard: removed legacy sentinel card");
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            Log.e(TAG, "GogLibraryCard.ensureRemoved failed (non-fatal)", t);
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    // ── launch intercept ─────────────────────────────────────────────────────

    /**
     * Called from GogLibraryCardPatch's injection at the head of po7's
     * launch dispatch. {@code idOrGameInfo} is either the launch id String
     * or a GameInfo (kept-name class with getId()). Returns true if this is
     * the sentinel (caller must then abort the normal launch); on true we
     * have already started GogMainActivity.
     */
    public static boolean maybeOpenHub(Context ctx, Object idOrGameInfo) {
        try {
            String id = extractId(idOrGameInfo);
            if (!SENTINEL_ID.equals(id)) return false;
            if (ctx == null) return true; // is sentinel, but can't launch — still abort
            Intent i = new Intent(ctx, GogMainActivity.class);
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            Log.i(TAG, "GogLibraryCard: sentinel tapped → GogMainActivity");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "GogLibraryCard.maybeOpenHub failed (non-fatal)", t);
            return false; // fail open → normal path, never break launching real games
        }
    }

    /**
     * Context-free variant for the po7 by-id launch dispatch (which has no
     * Context in scope). Resolves the app Context via ActivityThread — a
     * stable in-process hook — then defers to {@link #maybeOpenHub}.
     */
    public static boolean maybeOpenHubById(String id) {
        if (!SENTINEL_ID.equals(id)) return false;
        Context ctx = null;
        try {
            @SuppressWarnings("PrivateApi")
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) ctx = (Context) app;
        } catch (Throwable ignored) {}
        return maybeOpenHub(ctx, id);
    }

    /**
     * Intercept for the REAL card-tap launch resolver `wel.b(wel, w4c, ci3)`
     * (anchored by the stable string "No strategy found: type="). The arg is
     * the obfuscated launch-context `w4c`, which holds a kept-name
     * `…model.game.GameInfo` field. We resolve the game id obfuscation-proof:
     * try the object's own getId(), else scan its fields for a GameInfo (or
     * String id) and read getId() off that. Sentinel → open GogMainActivity
     * and tell the caller to abort (it then completes with Unit, no launch,
     * no "No strategy found").
     */
    public static boolean maybeOpenHubFromLaunchCtx(Object launchCtx) {
        try {
            String id = deepExtractId(launchCtx);
            if (!SENTINEL_ID.equals(id)) return false;
            return maybeOpenHubById(SENTINEL_ID); // resolves Context via ActivityThread
        } catch (Throwable t) {
            Log.e(TAG, "GogLibraryCard.maybeOpenHubFromLaunchCtx failed (non-fatal)", t);
            return false; // fail open — never break launching real games
        }
    }

    private static String deepExtractId(Object o) {
        if (o == null) return null;
        String direct = extractId(o);
        if (direct != null) return direct;
        try {
            for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
                Class<?> t = f.getType();
                String tn = t.getName();
                if (tn.endsWith(".GameInfo") || tn.equals("java.lang.String")) {
                    try {
                        f.setAccessible(true);
                        String id = extractId(f.get(o));
                        if (id != null && !id.isEmpty()) return id;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractId(Object o) {
        if (o == null) return null;
        if (o instanceof String) return (String) o;
        try {
            // GameInfo (kept name com.xiaoji.egggame.game.di.model.game.GameInfo)
            Object r = o.getClass().getMethod("getId").invoke(o);
            return r == null ? null : r.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── launch-router intercept (yv3.invoke head) ────────────────────────────
    // §32: the GOG card tap → game-detail dialog → "Launch Game" routes through
    // GameHub's LaunchRouter interceptor chain, NOT po7.F0/G0 (logcat
    // 2026-05-19: `buildLibraryInfoWithContext GOG , startType = 0` then
    // `No strategy found: type=Unknown`). The first non-suspend point with the
    // game in hand is the multiplexed launch lambda yv3.invoke() (anchored by
    // the stable non-obf string "buildLibraryInfoWithContext "). It holds the
    // launch context (t07 → GameInfo). Side-effect-only: if that game is the
    // sentinel we open GogMainActivity and let the original launch proceed —
    // it harmlessly logs "No strategy found" behind the now-foregrounded hub.
    // Never touches control flow / return / registers (minimal verifier-safe
    // intercept), fully fail-safe, de-duplicated (yv3.invoke fires repeatedly).

    private static volatile long sLastHubLaunchMs = 0L;

    public static void openHubIfSentinel(Object launchLambda) {
        try {
            // §32a: yv3.invoke(buildLibraryInfoWithContext) runs for the
            // sentinel BOTH at library precompute (background thread, app
            // start) and on the user's "Launch Game" press (main/UI thread)
            // — confirmed by logcat TID: bg 20830/20894 at startup vs main
            // 20830/20830 on tap. Only the user-initiated launch is on the
            // main thread, so gate on it: this suppresses the premature
            // auto-open on app start while still firing on the real tap.
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                return;
            java.util.Set<Object> seen = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<Object, Boolean>());
            if (!findSentinel(launchLambda, 0, seen)) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - sLastHubLaunchMs < 4000L) return; // dedupe rapid re-fires
            sLastHubLaunchMs = now;
            maybeOpenHubById(SENTINEL_ID); // resolves Context via ActivityThread
        } catch (Throwable t) {
            Log.e(TAG, "GogLibraryCard.openHubIfSentinel failed (non-fatal)", t);
        }
    }

    /**
     * Bounded reflective search for the sentinel GameInfo/id in an object
     * graph. Stops at any {@code …GameInfo} (exact getId() check, no deeper),
     * skips primitives/arrays and java/kotlin/android types, identity-cycle
     * guarded, depth-capped — so a real game resolves to one getId() compare
     * then false, with no behaviour change for non-sentinel launches.
     */
    private static boolean findSentinel(Object o, int depth, java.util.Set<Object> seen) {
        if (o == null || depth > 3 || !seen.add(o)) return false;
        if (o instanceof String) return SENTINEL_ID.equals(o);
        Class<?> c = o.getClass();
        String cn = c.getName();
        if (cn.endsWith(".GameInfo")) return SENTINEL_ID.equals(extractId(o));
        if (c.isArray() || cn.startsWith("android.") || cn.startsWith("java.")
                || cn.startsWith("kotlin.")) return false;
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            Class<?> ft = f.getType();
            if (ft.isPrimitive()) continue;
            String tn = ft.getName();
            if ((tn.startsWith("java.") && !"java.lang.String".equals(tn))
                    || tn.startsWith("kotlin.") || tn.startsWith("android.")) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof String) {
                    if (SENTINEL_ID.equals(v)) return true;
                    continue;
                }
                if (findSentinel(v, depth + 1, seen)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
