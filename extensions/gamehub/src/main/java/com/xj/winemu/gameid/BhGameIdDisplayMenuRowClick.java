package com.xj.winemu.gameid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.xj.winemu.common.BhMenuGameId;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import kotlin.jvm.functions.Function1;

/**
 * Onclick handler for the "Show Game ID" row injected into GameHub's per-game
 * menus. Surfaces the gameId GameHub already captured via the shared
 * {@link BhMenuGameId} channel so users can wire external launchers (Beacon /
 * ES-DE / Daijishou) without grepping a logcat. Tapping the row pops a small
 * dialog with the integer id and a Copy-to-clipboard button.
 *
 * Direct sibling of vibration / gpuspoof / renderer click classes — same
 * reflection strategy against the R8-mangled menu data classes (Liae / Lscd
 * / Lz4e), same Activity-stack Context resolution, same Proxy trick to
 * satisfy R8-renamed Function0/Function1 type checks at row construction.
 *
 * Click action is in-process only (a Dialog on the top Activity) — no
 * settings Activity, no Intent, no service. Cheapest possible row.
 */
public final class BhGameIdDisplayMenuRowClick implements Function1<Object, Object> {

    private static final String TAG = "BhGameIdRow";

    private static final String ROW_LABEL = "Show Game ID";
    private static final String ACTION_ID = "local_detail_menu_show_game_id";

    @Override
    public Object invoke(Object ignoredFromCompose) {
        try {
            Activity host = resolveTopActivity();
            if (host == null) {
                Log.w(TAG, "no top Activity resolvable; cannot show dialog");
                return kotlin.Unit.INSTANCE;
            }
            final String gameId = resolveGameId();
            host.runOnUiThread(() -> showDialog(host, gameId));
        } catch (Throwable t) {
            Log.w(TAG, "menu click failed", t);
        }
        return kotlin.Unit.INSTANCE;
    }

    private static String resolveGameId() {
        String gameId = BhMenuGameId.getCaptured();
        if (gameId == null || gameId.isEmpty()) gameId = sniffGameIdFromStack();
        return (gameId == null || gameId.isEmpty()) ? null : gameId;
    }

    private static void showDialog(Activity host, String gameId) {
        try {
            final String shown = (gameId == null) ? "(unknown — open from a specific game)" : gameId;
            final String message =
                "Game ID: " + shown + "\n\n" +
                "Use this value as the game id in Beacon / ES-DE / Daijishou " +
                "when configuring an external launch entry for GameHub.\n\n" +
                "Tap \"View All Games\" to browse the full library and copy any " +
                "game's ID.";

            AlertDialog.Builder b = new AlertDialog.Builder(host)
                .setTitle("Game ID")
                .setMessage(message)
                .setPositiveButton("Close", (d, w) -> d.dismiss())
                .setNeutralButton("View All Games", (d, w) -> showAllGamesDialog(host));

            if (gameId != null) {
                final String toCopy = gameId;
                // Negative button used for Copy here so View All can take
                // the visually-central neutral slot — the View All flow is
                // the more discoverable use case.
                b.setNegativeButton("Copy", (d, w) -> copyToClipboard(host, toCopy));
            }
            b.show();
        } catch (Throwable t) {
            Log.w(TAG, "showDialog failed", t);
        }
    }

    private static void copyToClipboard(Activity host, String value) {
        try {
            ClipboardManager cm = (ClipboardManager)
                host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("GameHub gameId", value));
                Toast.makeText(host, "Game ID copied", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "clipboard copy failed", t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // View All Games — queries db_game_library.db (Room-backed) for the
    // full library and shows it in a scrollable list dialog. Tap a row to
    // copy that game's id.
    //
    // Schema (t_game_library_base, confirmed via on-device dump 2026-05-20):
    //   server_game_id INTEGER  — the int gameId external launchers want
    //   game_name      TEXT     — display name
    //   steam_app_id   TEXT     — when source is Steam
    //   epic_app_name  TEXT     — when source is Epic
    //
    // Read-only open is safe alongside the host's WAL-mode writer. If the
    // file is missing (fresh install / no library yet) or the table is
    // absent (schema migration in a future GameHub release), we toast and
    // bail rather than crashing.
    // ─────────────────────────────────────────────────────────────────────

    private static final String DB_NAME = "db_game_library.db";

    private static final class GameEntry {
        final String name;
        final long serverGameId;
        final String steamAppId;
        final String epicAppName;
        GameEntry(String n, long sg, String s, String e) {
            name = n; serverGameId = sg; steamAppId = s; epicAppName = e;
        }
        String display() {
            StringBuilder sb = new StringBuilder();
            sb.append(name == null || name.isEmpty() ? "(no name)" : name);
            sb.append("\nID: ").append(serverGameId);
            if (steamAppId != null && !steamAppId.isEmpty()) {
                sb.append("  ·  Steam: ").append(steamAppId);
            }
            if (epicAppName != null && !epicAppName.isEmpty()) {
                sb.append("  ·  Epic: ").append(epicAppName);
            }
            return sb.toString();
        }
    }

    private static void showAllGamesDialog(Activity host) {
        try {
            List<GameEntry> games = loadAllGames(host);
            if (games == null) {
                Toast.makeText(host,
                    "Game library DB not found — open a game once to initialise it",
                    Toast.LENGTH_LONG).show();
                return;
            }
            if (games.isEmpty()) {
                new AlertDialog.Builder(host)
                    .setTitle("All Games")
                    .setMessage("No games in your library yet.")
                    .setPositiveButton("Close", (d, w) -> d.dismiss())
                    .show();
                return;
            }

            ArrayAdapter<GameEntry> adapter = new ArrayAdapter<GameEntry>(
                host, android.R.layout.simple_list_item_1, games) {
                @Override
                public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                    android.view.View v = super.getView(position, convertView, parent);
                    android.widget.TextView tv = v.findViewById(android.R.id.text1);
                    tv.setText(getItem(position).display());
                    tv.setMaxLines(3);
                    return v;
                }
            };

            new AlertDialog.Builder(host)
                .setTitle("All Games (" + games.size() + ")")
                .setAdapter(adapter, (d, which) -> {
                    GameEntry g = adapter.getItem(which);
                    if (g != null) copyToClipboard(host, String.valueOf(g.serverGameId));
                })
                .setPositiveButton("Close", (d, w) -> d.dismiss())
                .show();
        } catch (Throwable t) {
            Log.w(TAG, "showAllGamesDialog failed", t);
            Toast.makeText(host, "Couldn't open library DB", Toast.LENGTH_SHORT).show();
        }
    }

    /** Returns null when the DB file is absent; empty list when the table is empty. */
    private static List<GameEntry> loadAllGames(Context host) {
        Context app = host.getApplicationContext();
        File dbFile = app.getDatabasePath(DB_NAME);
        if (dbFile == null || !dbFile.exists()) {
            Log.i(TAG, "loadAllGames: " + DB_NAME + " not present");
            return null;
        }

        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            c = db.rawQuery(
                "SELECT game_name, server_game_id, steam_app_id, epic_app_name " +
                "FROM t_game_library_base " +
                "ORDER BY game_name COLLATE NOCASE", null);

            ArrayList<GameEntry> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                String name = c.isNull(0) ? "" : c.getString(0);
                long sgid = c.isNull(1) ? -1L : c.getLong(1);
                String steam = c.isNull(2) ? "" : c.getString(2);
                String epic = c.isNull(3) ? "" : c.getString(3);
                out.add(new GameEntry(name, sgid, steam, epic));
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, String.format(Locale.ROOT,
                "loadAllGames query failed (%s)", t.getClass().getSimpleName()), t);
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Row construction helpers — one per injection site.
    //
    // Each mirrors the vibration/gpuspoof equivalents: resolve an icon from
    // Lzz4 ComposableSingletons fields, build the row data class via its
    // 3-arg ctor, wrap our Function1/Function0 in a Proxy that actually
    // implements the R8-renamed Lpw6;/Lnw6; interface.
    // ─────────────────────────────────────────────────────────────────────

    /** Game-details More Menu (Lx57;->a). Liae row, Function1 click. */
    public static void appendGameIdRowTo(Object menuList) {
        try {
            if (!(menuList instanceof List)) return;
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) menuList;

            Class<?> iaeCls = Class.forName("iae");
            Class<?> o05Cls = Class.forName("o05");
            Class<?> pw6Cls = Class.forName("pw6");

            Object iconValue = resolveZz4Icon("m", o05Cls);
            if (iconValue == null) return;

            Object click = buildFunction1Proxy(pw6Cls);

            Constructor<?> ctor =
                iaeCls.getDeclaredConstructor(o05Cls, String.class, pw6Cls);
            ctor.setAccessible(true);
            list.add(ctor.newInstance(iconValue, ROW_LABEL, click));
        } catch (Throwable t) {
            Log.w(TAG, "appendGameIdRowTo failed", t);
        }
    }

    /** Library-tile popup (Lted;->f). Lscd row with String actionId + Function0. */
    public static List<Object> appendScdRowToTedList(Object original) {
        try {
            if (!(original instanceof List)) return safeReturn(original);
            List<?> origList = (List<?>) original;
            ArrayList<Object> augmented = new ArrayList<>(origList);

            Class<?> scdCls = Class.forName("scd");
            Class<?> o05Cls = Class.forName("o05");
            Class<?> nw6Cls = Class.forName("nw6");

            Object iconValue = resolveZz4Icon("k", o05Cls);
            if (iconValue == null) return safeReturn(original);

            Object click = buildFunction0Proxy(nw6Cls);

            Constructor<?> ctor =
                scdCls.getDeclaredConstructor(String.class, o05Cls, String.class, nw6Cls);
            ctor.setAccessible(true);
            augmented.add(ctor.newInstance(ACTION_ID, iconValue, ROW_LABEL, click));
            return augmented;
        } catch (Throwable t) {
            Log.w(TAG, "appendScdRowToTedList failed", t);
            return safeReturn(original);
        }
    }

    /**
     * Library-list popup (Lpzc;->j0). Lz4e row with Lell label + Function0.
     *
     * Label routing — the row's Lell is resolved at render time by Lxd3;->l1,
     * which is hooked ONCE (in vibrationMenuRowPatch) to short-circuit our
     * sentinel keys. We must register "string:bh_gameid_label" → "Show Game
     * ID" in that one hook (BhMenuRowClick.maybeResolveCustomLabel) — adding
     * a 2nd Lxd3;->l1 head-block ANR'd MainActivity (2026-05-17 gpuspoof
     * saga). The label-resource patch ALSO appends the CVR entry so a future
     * runtime-strict Compose resolver doesn't reject the unknown key before
     * the head-block fires.
     */
    public static List<Object> appendLibraryPopupRow(Object original) {
        try {
            if (!(original instanceof List)) return safeReturn(original);
            List<?> origList = (List<?>) original;
            ArrayList<Object> augmented = new ArrayList<>(origList);

            Class<?> z4eCls = Class.forName("z4e");
            Class<?> ellCls = Class.forName("ell");
            Class<?> tdiCls = Class.forName("tdi");
            Class<?> nw6Cls = Class.forName("nw6");

            // Allocate Lell with ctor skipped — Lell declares no ctors of
            // its own; the host bytecode super-calls Ltdi.<init>(String,Set)
            // explicitly, and reflection can't reproduce that. Unsafe.
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Object label = unsafeCls.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, ellCls);

            Field aField = tdiCls.getDeclaredField("a");
            aField.setAccessible(true);
            aField.set(label, "string:bh_gameid_label");
            Field bField = tdiCls.getDeclaredField("b");
            bField.setAccessible(true);
            bField.set(label, Collections.emptySet());

            Object click = buildFunction0Proxy(nw6Cls);

            Constructor<?> z4eCtor =
                z4eCls.getDeclaredConstructor(ellCls, nw6Cls, int.class);
            z4eCtor.setAccessible(true);
            augmented.add(z4eCtor.newInstance(label, click, 0));
            return augmented;
        } catch (Throwable t) {
            Log.w(TAG, "appendLibraryPopupRow failed", t);
            return safeReturn(original);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Lzz4 has multiple Lxrl-wrapped icon fields; pull the one named field. */
    private static Object resolveZz4Icon(String fieldName, Class<?> o05Cls) {
        try {
            Class<?> zz4Cls = Class.forName("zz4");
            Field iconHolder = zz4Cls.getDeclaredField(fieldName);
            iconHolder.setAccessible(true);
            Object xrlWrapper = iconHolder.get(null);
            if (xrlWrapper == null) return null;
            Object iconValue = xrlWrapper.getClass().getMethod("getValue").invoke(xrlWrapper);
            return o05Cls.isInstance(iconValue) ? iconValue : null;
        } catch (Throwable t) {
            Log.w(TAG, "resolveZz4Icon(" + fieldName + ") failed", t);
            return null;
        }
    }

    /** R8 renamed Function1 to Lpw6;; our `implements Function1` is the JVM
     * Function1, not Lpw6;. The row ctor wants Lpw6; specifically. Proxy. */
    private static Object buildFunction1Proxy(Class<?> pw6Cls) {
        final BhGameIdDisplayMenuRowClick handler = new BhGameIdDisplayMenuRowClick();
        return Proxy.newProxyInstance(
            pw6Cls.getClassLoader(),
            new Class<?>[]{ pw6Cls },
            (proxy, method, args) -> {
                if ("invoke".equals(method.getName()) && method.getParameterCount() == 1) {
                    return handler.invoke(args != null && args.length > 0 ? args[0] : null);
                }
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "BhGameIdRowClickProxy1";
                return null;
            }
        );
    }

    /** Function0 (no-arg) variant for Lscd / Lz4e rows. */
    private static Object buildFunction0Proxy(Class<?> nw6Cls) {
        final BhGameIdDisplayMenuRowClick handler = new BhGameIdDisplayMenuRowClick();
        return Proxy.newProxyInstance(
            nw6Cls.getClassLoader(),
            new Class<?>[]{ nw6Cls },
            (proxy, method, args) -> {
                if ("invoke".equals(method.getName()) && method.getParameterCount() == 0) {
                    return handler.invoke(null);
                }
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "BhGameIdRowClickProxy0";
                return null;
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Object> safeReturn(Object o) {
        if (o instanceof List) return (List<Object>) o;
        return new ArrayList<>();
    }

    private static Activity resolveTopActivity() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method cur = atCls.getMethod("currentActivityThread");
            Object at = cur.invoke(null);
            if (at == null) return null;
            Field fActs = atCls.getDeclaredField("mActivities");
            fActs.setAccessible(true);
            Object acts = fActs.get(at);
            if (!(acts instanceof Map)) return null;
            Activity best = null;
            for (Object record : ((Map<?, ?>) acts).values()) {
                if (record == null) continue;
                Field fAct = record.getClass().getDeclaredField("activity");
                fAct.setAccessible(true);
                Object a = fAct.get(record);
                if (!(a instanceof Activity)) continue;
                Activity activity = (Activity) a;
                if (activity.isFinishing()) continue;
                try {
                    Field fPaused = record.getClass().getDeclaredField("paused");
                    fPaused.setAccessible(true);
                    Object paused = fPaused.get(record);
                    if (paused instanceof Boolean && !((Boolean) paused)) {
                        return activity;
                    }
                } catch (NoSuchFieldException ignored) { }
                best = activity;
            }
            return best;
        } catch (Throwable t) {
            Log.w(TAG, "resolveTopActivity failed", t);
            return null;
        }
    }

    /** Fallback: if a WineActivity is in the stack, grab its gameId extra. */
    private static String sniffGameIdFromStack() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method cur = atCls.getMethod("currentActivityThread");
            Object at = cur.invoke(null);
            if (at == null) return null;
            Field fActs = atCls.getDeclaredField("mActivities");
            fActs.setAccessible(true);
            Object acts = fActs.get(at);
            if (!(acts instanceof Map)) return null;
            for (Object record : ((Map<?, ?>) acts).values()) {
                if (record == null) continue;
                Field fAct = record.getClass().getDeclaredField("activity");
                fAct.setAccessible(true);
                Object a = fAct.get(record);
                if (!(a instanceof Activity)) continue;
                if (!a.getClass().getName().endsWith(".WineActivity")) continue;
                android.content.Intent it = ((Activity) a).getIntent();
                if (it == null) continue;
                String gid = it.getStringExtra("gameId");
                if (gid != null && !gid.isEmpty()) return gid;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
