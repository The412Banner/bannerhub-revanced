package com.xj.winemu.exportcontrols;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Local-save side of the VJoy file-import pipeline.
 *
 * Pipeline:
 *   1. Caller hands us the raw bytes of a {@code .gtheme} file (a ZIP
 *      archive containing a single {@code layout.json} entry — Xiaoji's
 *      own shareable format, produced by our pre9 export).
 *   2. We ZIP-unwrap to get the layout JSON bytes.
 *   3. Deserialize JSON to a {@code VJoyLayout} via kotlinx-serialization
 *      reflection (the class FQN is kept by R8 keep-rules).
 *   4. Generate a fresh UUID as the layoutId for the imported copy.
 *   5. Invoke the host's static save helper
 *        {@code Lo0n;->i(String, VJoyLayout, Continuation): Object}
 *      via reflection — it wraps the actual save coroutine
 *      ({@code Lm0n;}) in {@code withContext(Dispatchers.IO, ...)}.
 *   6. Block on a {@link CompletableFuture} that our synthetic
 *      Continuation completes from {@code resumeWith()}.
 *   7. Return the resulting {@code VJoyLayoutSaveReceipt} (or null on
 *      failure).
 *
 * Reflection anchors (R8-renamed; these letters need re-derivation on
 * each GameHub minor bump):
 *   - {@link #WITH_CONTEXT_CLASS} = "dig"     — has static `V(...)` =
 *                                              withContext (6.0.4 w0o.s0;
 *                                              the bgl.i/o0n.i wrapper is
 *                                              bypassed, see saveLayoutLocal)
 *   - {@link #DISPATCHER_HOLDER}  = "n80"     — static field `a:Li84;`
 *                                              is Dispatchers.IO (6.0.4 f80)
 *   - {@link #SAVE_BLOCK_CLASS}   = "ggl"     — the save-coroutine state
 *                                              class; ctor takes
 *                                              (String, VJoyLayout, Ljq3;)
 *                                              (6.0.4 m0n)
 *   - {@link #CONTINUATION_INTERFACE} = "jq3" — the R8-renamed
 *                                              kotlin.coroutines.Continuation
 *                                              (6.0.4 bi3)
 *
 * All paths fail-soft: on any reflection / parse / save error we log and
 * return null. The caller (BhVjoyShareHook#interceptApply) toasts a
 * generic error.
 */
public final class BhVjoyImporter {

    private static final String TAG = "BhVjoyImporter";

    // === R8-mangled anchors (GameHub 6.0.9; 6.0.4 letters in parens) ===
    private static final String WITH_CONTEXT_CLASS    = "g8i";  // BuildersKt (has L = withContext; 6.0.4 w0o.s0)
    private static final String WITH_CONTEXT_METHOD   = "L";    // withContext method name (6.0.8 "V"; 6.0.4 "s0")
    private static final String DISPATCHER_HOLDER     = "u90";  // Dispatchers (has a = IO; 6.0.4 f80)
    private static final String COROUTINE_CONTEXT_IF  = "yy3";  // CoroutineContext interface, first arg type (6.0.4 dm3)
    private static final String FUNCTION2_IF          = "h57";  // Function2 interface, block param (6.0.4 dx6)
    // Continuation INTERFACE (getContext()+resumeWith) — what the Proxy must
    // implement. 6.0.4 bi3. NB: NOT pv3 (that is ContinuationImpl, the
    // abstract class, = 6.0.4 ci3 — a Proxy can't implement it).
    private static final String CONTINUATION_INTERFACE = "ov3";
    private static final String SAVE_BLOCK_CLASS      = "qpm";  // suspend lambda; ctor (String, VJoyLayout, Continuation) (6.0.4 m0n)
    private static final String VJOY_LAYOUT_FQN =
        "com.xiaoji.egggame.common.ui.vjoy.model.VJoyLayout";

    // Kept (non-obfuscated) host DB FQNs, used by the post-import Room
    // invalidation nudge that restores the live My Layouts refresh on 6.0.9
    // (see nudgeRoomInvalidation). These names are R8-keep-stable.
    private static final String APP_DATABASE_CLS =
        "com.xiaoji.egggame.core.database.AppDatabase";
    private static final String VKL_DAO_CLS =
        "com.xiaoji.egggame.core.database.dao.VirtualKeyLayoutDao";
    private static final String VKL_ENTITY_CLS =
        "com.xiaoji.egggame.core.database.entity.VirtualKeyLayoutEntity";
    /** Koin's Java interop entrypoint: get(Class) resolves a single by Java type. */
    private static final String KOIN_JAVA_COMPONENT = "org.koin.java.KoinJavaComponent";

    /** Save coroutine can take a while (file IO + index update). */
    private static final long SAVE_TIMEOUT_SECONDS = 30L;

    private BhVjoyImporter() { }

    /** Returns true and toasts success on local save. */
    public static boolean importFromGthemeBytes(byte[] fileBytes) {
        try {
            // The exported `.gtheme` files contain a JSON layout payload
            // wrapped in a ZIP, BUT the ZIP's binary headers are corrupted
            // by Xiaoji's upload pipeline (`tencent-cos` CDN serves
            // already-mangled bytes — UTF-8 replacement chars where binary
            // bytes ≥ 0x80 should be). The good news: the JSON content is
            // valid UTF-8 so it survives intact inside the broken
            // container. Skip ZIP parsing and just byte-scan for the JSON
            // object. Works on clean `.json` files too.
            String json = extractJsonPayload(fileBytes);
            if (json == null) {
                Log.w(TAG, "could not extract JSON payload from file");
                return false;
            }
            Log.i(TAG, "extracted layout JSON, " + json.length() + " chars");
            // Diagnostic: log the first 240 chars and the last 80 chars so we
            // can see whether we picked the right brace-block. Layout JSON
            // starts with {"formatVersion":... or {"id":... — anything else
            // means we matched a sub-object inside a ZIP's metadata.
            int head = Math.min(240, json.length());
            int tail = Math.min(80, json.length());
            Log.i(TAG, "JSON head: " + json.substring(0, head));
            Log.i(TAG, "JSON tail: " + json.substring(json.length() - tail));

            Object layout = BhVjoyJson.decodeLayout(json);
            if (layout == null) {
                Log.w(TAG, "could not deserialize VJoyLayout from JSON");
                return false;
            }
            Log.i(TAG, "decoded layout: " + layout.getClass().getName());

            // Use the JSON's own `id` field as the layoutId. The host's
            // visible-layouts list (My Layouts) appears to match folder
            // name against layout.json's "id" field — a fresh UUID
            // folder containing a layout with a different `id` doesn't
            // show up in the UI. Confirmed empirically 2026-05-24: 6
            // import-test folders with UUID names + foreign ids were
            // all invisible; the only on-disk layout with matching
            // folder+id (`a9813ab58d304ad98365ad77c94d0241_copy_2`)
            // was rendered.
            //
            // Side-effect: re-importing the same .gtheme twice
            // overwrites the previous import (same id → same folder).
            // Acceptable; matches the host's "copy" suffix convention
            // for duplicates.
            String layoutId = readLayoutId(layout);
            if (layoutId == null || layoutId.isEmpty()) {
                layoutId = UUID.randomUUID().toString();
                Log.w(TAG, "no id in layout JSON; falling back to UUID " + layoutId);
            } else {
                Log.i(TAG, "using layout id from JSON: " + layoutId);
            }
            Object receipt = saveLayoutLocal(layoutId, layout);
            if (receipt == null) {
                Log.w(TAG, "save returned null receipt");
                return false;
            }
            Log.i(TAG, "save success: " + receipt);

            // The save coroutine writes layout.json + assets/ to disk but
            // does NOT register the layout in egggame.db's virtual_key_layout
            // table — the host's Create flow registers via its Lytm
            // ViewModel which dispatches an insert command. Without that
            // row, the layout is invisible in My Layouts.
            //
            // Direct DB insert mirrors what the Create flow writes:
            //   source=local, catalog=local, acquire=created, etc.
            // See virtual_key_layout schema dump for column meanings.
            boolean registered = registerInDatabase(layoutId, layout, receipt);
            if (!registered) {
                Log.w(TAG, "DB insert failed (layout still on disk, " +
                    "may be picked up later by host's rebuild path)");
                // Still report success — disk write succeeded.
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "import failed", t);
            return false;
        }
    }

    /**
     * Insert a row into egggame.db's virtual_key_layout table so the
     * imported layout appears in My Layouts. Mirrors the columns the
     * host's Create flow writes (source=local, catalog=local,
     * acquire=created).
     *
     * The host has the DB open in WAL mode; opening a second read-write
     * connection via SQLiteDatabase.openDatabase() is fine — SQLite is
     * multi-connection safe. The host's Room invalidation tracker may
     * not pick up our write in real-time, so the user must close +
     * reopen My Layouts (or restart the app) to see the new row.
     */
    private static boolean registerInDatabase(
            String layoutId, Object layout, Object saveReceipt) {
        android.database.sqlite.SQLiteDatabase db = null;
        long insertedRowId = -1;
        String insertedUserId = null;
        try {
            // Resolve the on-disk DB path via the app's Context.
            android.content.Context ctx = currentApplicationContext();
            if (ctx == null) {
                Log.w(TAG, "no Application context for DB path");
                return false;
            }
            java.io.File dbFile = ctx.getDatabasePath("egggame.db");
            if (!dbFile.exists()) {
                Log.w(TAG, "egggame.db not found at " + dbFile.getAbsolutePath());
                return false;
            }

            // CRITICAL: the host opens egggame.db in WAL mode via Room.
            // Opening our second connection in default (journal) mode
            // and then writing corrupted the DB file (SQLITE_CORRUPT
            // code 11 on the host's next access — verified pre10f). Use
            // OpenParams with journalMode=WAL so our writes go to the
            // shared -wal file the host already has open instead of
            // forcing a journal-mode switch on the main DB file.
            android.database.sqlite.SQLiteDatabase.OpenParams params =
                new android.database.sqlite.SQLiteDatabase.OpenParams.Builder()
                    .setOpenFlags(android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
                    .setJournalMode("WAL")
                    .setSynchronousMode("NORMAL")
                    .build();
            db = android.database.sqlite.SQLiteDatabase.openDatabase(dbFile, params);

            // Detect the logged-in user's id from the host's own existing rows
            // (upstream hardcoded "99999" — a sentinel from their guest test
            // session — but My Layouts filters by the real account id, so a
            // hardcoded value renders the imported row invisible to a logged-in
            // user). Diagnostic-log the distribution + retroactively re-stamp
            // any orphaned rows we ourselves inserted with the sentinel.
            String userId = pickUserIdFor(db);
            logUserIdDistribution(db);
            Log.i(TAG, "user_id chosen for import: " + userId);
            repairOrphanedImports(db, userId);

            // Pull values for the row.
            long now = System.currentTimeMillis();
            String name = readLayoutName(layout);
            if (name == null || name.isEmpty()) name = "Imported Layout";
            String titleI18n = "{\"default\":" + jsonString(name) + "}";
            String configHash = readReceiptString(saveReceipt, "getConfigHash");

            android.content.ContentValues v = new android.content.ContentValues();
            v.put("user_id",              userId);
            v.put("folder_key",           layoutId);
            v.put("folder_path",          "vjoy_layouts/" + layoutId + "/");
            v.put("title_i18n_json",      titleI18n);
            // desc_i18n_json: nullable
            v.put("title_search",         name);
            v.put("layout_type",          "common");
            // game_id: nullable
            v.put("source",               "local");
            v.put("catalog",              "local");
            v.put("acquire",              "created");
            v.put("source_key",           "local:" + layoutId);
            // upstream_key, remote_id, share_code, author_name: nullable
            v.put("apply_count",          0);
            // recommend_rank: nullable
            v.put("publish_status",       "none");
            // publish_name: nullable
            v.put("last_upload_result",   "none");
            v.put("last_download_result", "none");
            // last_error, last_upload_at, last_download_at: nullable
            v.put("index_mtime",          now);
            if (configHash != null) v.put("index_hash", configHash);
            v.put("broken",               0);
            v.put("created_at",           now);
            v.put("updated_at",           now);
            // deleted_at: nullable

            long rowId = db.insertWithOnConflict(
                "virtual_key_layout",
                null,
                v,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
            if (rowId == -1) {
                Log.w(TAG, "INSERT returned -1");
                return false;
            }
            Log.i(TAG, "registered layout in virtual_key_layout (row id=" +
                rowId + ", folder_key=" + layoutId + ")");
            insertedRowId = rowId;
            insertedUserId = userId;
        } catch (Throwable t) {
            Log.w(TAG, "registerInDatabase failed", t);
            return false;
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) { }
        }
        // Our raw write is committed and our second connection is closed.
        // Route a real write through the host's own Room so its (2.7)
        // connection-scoped invalidation tracker fires and the live My
        // Layouts list refreshes immediately. 6.0.4's older Room observed our
        // external raw write; 6.0.7+'s does not (the regression the user hit).
        nudgeRoomInvalidation(insertedUserId, insertedRowId);
        return true;
    }

    /**
     * Make the host's Room invalidate the virtual_key_layout table so the
     * live My Layouts list (a Room Flow on observeMyLayoutsRevision) re-emits
     * right after an import — instead of only when the screen is re-entered.
     *
     * Mechanism: resolve the host's AppDatabase from Koin, read our
     * just-inserted row back via the DAO's findById(user_id, id), and re-
     * upsert it. That UPDATE goes through Room's own SQLiteConnection, so its
     * 2.7 invalidation tracker fires (a 0-row no-op would NOT fire the table
     * trigger, which is why we re-write a real row rather than poke a bogus
     * id). All names used here are R8-keep-stable host FQNs; the suspend DAO
     * calls reuse the same Continuation/await bridge as saveLayoutLocal.
     *
     * Best-effort: any failure just logs and leaves the prior behavior
     * (layout still saved; visible after re-entering My Layouts).
     */
    private static void nudgeRoomInvalidation(String userId, long rowId) {
        if (userId == null || userId.isEmpty() || rowId <= 0) return;
        try {
            Class<?> appDbCls = Class.forName(APP_DATABASE_CLS);
            Object appDb = Class.forName(KOIN_JAVA_COMPONENT)
                .getMethod("get", Class.class).invoke(null, appDbCls);
            if (appDb == null) {
                Log.w(TAG, "nudge: AppDatabase not resolvable from Koin");
                return;
            }
            Object dao = appDbCls.getMethod("virtualKeyLayoutDao").invoke(appDb);
            if (dao == null) { Log.w(TAG, "nudge: virtualKeyLayoutDao null"); return; }

            Class<?> daoCls = Class.forName(VKL_DAO_CLS);
            Class<?> entityCls = Class.forName(VKL_ENTITY_CLS);
            Class<?> contCls = Class.forName(CONTINUATION_INTERFACE);
            Object dispatcher = Class.forName(DISPATCHER_HOLDER)
                .getDeclaredField("a").get(null);

            // 1. findById(user_id, id) -> our row as a host Entity (no fragile
            //    33-field construction; the Entity comes straight from the DB).
            Method findById = daoCls.getMethod(
                "findById", String.class, long.class, contCls);
            Object entity = awaitSuspend(findById, dao, dispatcher, contCls,
                new Object[]{ userId, rowId });
            if (entity == null || !entityCls.isInstance(entity)) {
                Log.w(TAG, "nudge: findById(" + userId + "," + rowId
                    + ") returned no entity; skipping re-upsert");
                return;
            }
            // 2. upsert(entity) -> UPDATE on virtual_key_layout via Room ->
            //    invalidation tracker fires -> observeMyLayoutsRevision re-emits.
            Method upsert = daoCls.getMethod("upsert", entityCls, contCls);
            awaitSuspend(upsert, dao, dispatcher, contCls, new Object[]{ entity });
            Log.i(TAG, "nudge: re-upserted row " + rowId
                + " through Room (invalidation fired)");
        } catch (Throwable t) {
            Log.w(TAG, "nudgeRoomInvalidation failed (live refresh skipped)", t);
        }
    }

    /**
     * Invoke a Kotlin suspend method via reflection (its last parameter is the
     * Continuation) and await the result. Mirrors saveLayoutLocal: pass a
     * Continuation Proxy, and if the call returns COROUTINE_SUSPENDED, block
     * on the CompletableFuture the Proxy completes in resumeWith.
     */
    private static Object awaitSuspend(Method m, Object target, Object dispatcher,
            Class<?> contCls, Object[] leadingArgs) throws Exception {
        CompletableFuture<Object> done = new CompletableFuture<>();
        Object continuation = makeContinuation(contCls, done, dispatcher);
        Object[] args = new Object[leadingArgs.length + 1];
        System.arraycopy(leadingArgs, 0, args, 0, leadingArgs.length);
        args[leadingArgs.length] = continuation;
        Object immediate = m.invoke(target, args);
        if (isCoroutineSuspended(immediate)) {
            return done.get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        return immediate;
    }

    /**
     * Pick a user_id for our INSERT. Strategy: take the most recent
     * non-deleted row's user_id — that's the value the host's own Create
     * flow uses, which is also what My Layouts filters by.
     *
     * MUST exclude "99999" — that's upstream's sentinel which earlier
     * builds wrote and which our own orphan rows still carry; without
     * the exclusion the picker prefers our own (newest) bad rows over
     * legitimate older host rows. Falls back to "99999" only if there's
     * no non-sentinel row to copy from.
     */
    private static String pickUserIdFor(android.database.sqlite.SQLiteDatabase db) {
        try (android.database.Cursor c = db.rawQuery(
                "SELECT user_id FROM virtual_key_layout "
                + "WHERE deleted_at IS NULL "
                + "AND user_id IS NOT NULL AND user_id != '' "
                + "AND user_id != '99999' "
                + "ORDER BY created_at DESC LIMIT 1", null)) {
            if (c != null && c.moveToFirst()) {
                String id = c.getString(0);
                if (id != null && !id.isEmpty()) return id;
            }
        } catch (Throwable t) {
            Log.w(TAG, "pickUserIdFor failed", t);
        }
        Log.w(TAG, "pickUserIdFor: no non-sentinel user_id found; "
            + "falling back to '99999' (imported layout will be invisible "
            + "until the host creates a layout first)");
        return "99999";
    }

    /** Diagnostic: log (user_id, count) groupings for virtual_key_layout. */
    private static void logUserIdDistribution(android.database.sqlite.SQLiteDatabase db) {
        try (android.database.Cursor c = db.rawQuery(
                "SELECT user_id, COUNT(*) FROM virtual_key_layout "
                + "GROUP BY user_id ORDER BY COUNT(*) DESC", null)) {
            StringBuilder sb = new StringBuilder("user_id distribution:");
            if (c != null) {
                while (c.moveToNext()) {
                    sb.append(' ').append(c.getString(0)).append('=')
                      .append(c.getLong(1));
                }
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable t) {
            Log.w(TAG, "logUserIdDistribution failed", t);
        }
    }

    /**
     * Re-stamp any rows we previously inserted under the "99999" sentinel
     * (orphaned because they don't match the logged-in user's id). Only
     * touches rows that match our own source_key marker ("local:%") so we
     * never overwrite a host-created row whose user_id legitimately is 99999.
     */
    private static void repairOrphanedImports(
            android.database.sqlite.SQLiteDatabase db, String correctUserId) {
        if (correctUserId == null || "99999".equals(correctUserId)) return;
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("user_id", correctUserId);
            int n = db.update(
                "virtual_key_layout",
                v,
                "user_id = ? AND source_key LIKE 'local:%'",
                new String[]{ "99999" });
            if (n > 0) {
                Log.i(TAG, "repaired " + n + " orphaned import row(s): "
                    + "user_id 99999 -> " + correctUserId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "repairOrphanedImports failed", t);
        }
    }

    /** Read the layout's display name from VJoyLayout.getName().getLocales().get("default"). */
    private static String readLayoutName(Object layout) {
        try {
            Method getName = layout.getClass().getMethod("getName");
            Object localizedString = getName.invoke(layout);
            if (localizedString == null) return null;
            Method getLocales = localizedString.getClass().getMethod("getLocales");
            Object locales = getLocales.invoke(localizedString);
            if (!(locales instanceof java.util.Map)) return null;
            Object v = ((java.util.Map<?, ?>) locales).get("default");
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            Log.w(TAG, "readLayoutName failed", t);
            return null;
        }
    }

    /** Read a String property off the VJoyLayoutSaveReceipt via reflection. */
    private static String readReceiptString(Object receipt, String getter) {
        try {
            Method m = receipt.getClass().getMethod(getter);
            Object v = m.invoke(receipt);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Minimal JSON string-encoder for the name field. */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Get the current Application Context via ActivityThread reflection. */
    private static android.content.Context currentApplicationContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            return (android.content.Context) app;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Extract the layout JSON from a .gtheme byte buffer.
     *
     * Try in order:
     *   1. Proper ZIP parse: a fresh .gtheme is a valid ZIP (the host
     *      writes layout.json DEFLATE-compressed). java.util.zip handles
     *      both STORED and DEFLATED entries — this is the fast path for
     *      anything exported from a recent build.
     *   2. Byte-scan fallback for the first {...} block: older / hand-
     *      built / corrupted files (e.g. a pre-pre18 export with CDN-
     *      mangled headers but STORED JSON) won't ZIP-parse cleanly.
     *      Brace-matching the raw bytes recovers JSON IF it happens to
     *      be uncompressed. Useless on DEFLATE bytes — but we only get
     *      here when the ZIP path failed.
     */
    private static String extractJsonPayload(byte[] bytes) {
        if (bytes == null) return null;
        String fromZip = tryExtractFromZip(bytes);
        if (fromZip != null) return fromZip;
        return tryExtractByBraceMatch(bytes);
    }

    private static String tryExtractFromZip(byte[] bytes) {
        try (java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(bytes);
             java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(bin)) {
            java.util.zip.ZipEntry e;
            byte[] best = null;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) { zip.closeEntry(); continue; }
                String name = e.getName();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
                zip.closeEntry();
                byte[] data = out.toByteArray();
                // Prefer layout.json by name; fall back to any entry that
                // looks like JSON (starts with '{').
                if ("layout.json".equals(name) ||
                    name.toLowerCase().endsWith("layout.json")) {
                    return new String(data, "UTF-8");
                }
                if (best == null && data.length > 0 && data[0] == '{') {
                    best = data;
                }
            }
            if (best != null) return new String(best, "UTF-8");
        } catch (Throwable t) {
            Log.i(TAG, "tryExtractFromZip: not a valid ZIP, will brace-scan: " + t.getMessage());
        }
        return null;
    }

    private static String tryExtractByBraceMatch(byte[] bytes) {
        int start = -1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '{') { start = i; break; }
        }
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int end = -1;
        for (int i = start; i < bytes.length; i++) {
            byte b = bytes[i];
            if (escape) { escape = false; continue; }
            if (inString) {
                if (b == '\\') escape = true;
                else if (b == '"') inString = false;
            } else {
                if (b == '"') inString = true;
                else if (b == '{') depth++;
                else if (b == '}') {
                    depth--;
                    if (depth == 0) { end = i; break; }
                }
            }
        }
        if (end < 0) return null;
        try {
            return new String(bytes, start, end - start + 1, "UTF-8");
        } catch (Throwable t) {
            Log.w(TAG, "brace-match UTF-8 decode failed", t);
            return null;
        }
    }

    /** Pull the `id` getter off a VJoyLayout instance via reflection. */
    private static String readLayoutId(Object vJoyLayout) {
        try {
            Method m = vJoyLayout.getClass().getMethod("getId");
            Object id = m.invoke(vJoyLayout);
            return id == null ? null : id.toString();
        } catch (Throwable t) {
            Log.w(TAG, "readLayoutId failed", t);
            return null;
        }
    }

    /**
     * Invoke the host's coroutine-builder withContext (kotlinx-coroutines
     * `BuildersKt.withContext(CoroutineContext, Function2, Continuation)`)
     * with the save coroutine block ({@code Lagl;}, 6.0.4 {@code Lm0n;})
     * directly. Bypasses the {@code Lbgl;->i} static wrapper (6.0.4
     * {@code Lo0n;->i}) because its declared third-arg type is the abstract
     * {@code Lkq3;} class (6.0.4 {@code Lci3;}) — Java reflection can't accept
     * our {@code Ljq3;} Proxy as that type even though it would work at JVM
     * bytecode level. {@code Laig;->V} (6.0.4 {@code Lw0o;->s0}) accepts the
     * {@code Ljq3;} interface directly, which our Proxy satisfies.
     *
     * Call shape (from bgl.i smali):
     *   sget-object v0, Ln80;->a:Li84;     ; Dispatchers.IO
     *   new-instance v1, Lagl;
     *   const/4 v2, 0x0
     *   invoke-direct {v1, layoutId, layout, null}, Lagl;-><init>(String, VJoyLayout, Ljq3;)V
     *   invoke-static {v0, v1, ourContinuation}, Laig;->V(Lst3;Luv6;Ljq3;)Ljava/lang/Object;
     */
    private static Object saveLayoutLocal(String layoutId, Object vJoyLayout)
            throws Exception {
        Class<?> vJoyLayoutCls = Class.forName(VJOY_LAYOUT_FQN);
        Class<?> continuationCls = Class.forName(CONTINUATION_INTERFACE);     // jq3 (6.0.4 bi3)
        Class<?> coroutineCtxCls = Class.forName(COROUTINE_CONTEXT_IF);       // st3 (6.0.4 dm3)
        Class<?> function2Cls = Class.forName(FUNCTION2_IF);                  // uv6 (6.0.4 dx6)
        Class<?> withContextCls = Class.forName(WITH_CONTEXT_CLASS);          // aig (6.0.4 w0o)
        Class<?> dispatchersCls = Class.forName(DISPATCHER_HOLDER);           // n80 (6.0.4 f80)
        Class<?> saveBlockCls = Class.forName(SAVE_BLOCK_CLASS);              // agl (6.0.4 m0n)

        // 1. Pull Dispatchers.IO singleton from n80.a.
        Field dispatcherField = dispatchersCls.getDeclaredField("a");
        dispatcherField.setAccessible(true);
        Object dispatcher = dispatcherField.get(null);
        if (dispatcher == null) throw new IllegalStateException("n80.a is null");

        // 2. Construct the save coroutine block. ctor: (String, VJoyLayout, Ljq3;)
        Constructor<?> blockCtor = saveBlockCls.getDeclaredConstructor(
            String.class, vJoyLayoutCls, continuationCls);
        blockCtor.setAccessible(true);
        Object saveBlock = blockCtor.newInstance(layoutId, vJoyLayout, null);

        // 3. Build a Continuation proxy and bridge to a CompletableFuture.
        CompletableFuture<Object> done = new CompletableFuture<>();
        Object continuation = makeContinuation(continuationCls, done, dispatcher);

        // 4. Find aig.V(Lst3;Luv6;Ljq3;)Object — withContext (6.0.4 w0o.s0).
        Method withContext = withContextCls.getDeclaredMethod(
            WITH_CONTEXT_METHOD, coroutineCtxCls, function2Cls, continuationCls);
        withContext.setAccessible(true);

        Object immediate = withContext.invoke(null, dispatcher, saveBlock, continuation);

        if (isCoroutineSuspended(immediate)) {
            Log.i(TAG, "save suspended; awaiting Continuation.resumeWith");
            return done.get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } else {
            Log.i(TAG, "save completed synchronously");
            return immediate;
        }
    }

    /**
     * Build a Proxy that implements the host's Continuation interface
     * (R8-renamed kotlin.coroutines.Continuation). Two methods:
     *   - getContext() -> CoroutineContext
     *   - resumeWith(Object) -> Unit
     *
     * For getContext() we return the IO dispatcher itself — every
     * Dispatcher implements CoroutineContext via CoroutineContext.Element,
     * so returning a Dispatcher singleton satisfies the return type and
     * gives the coroutine machinery a usable context.
     *
     * resumeWith(Object) receives kotlin.Result-wrapped value. The raw
     * Object IS the Result (Result is an inline class erased to its
     * wrapped value), so we just hand it to the CompletableFuture.
     */
    private static Object makeContinuation(
            Class<?> continuationCls,
            CompletableFuture<Object> done,
            Object dispatcher
    ) throws Exception {
        final Object contextHolder = dispatcher; // captured by lambda below

        return Proxy.newProxyInstance(
            continuationCls.getClassLoader(),
            new Class<?>[]{ continuationCls },
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getContext".equals(name)) {
                    // Return the dispatcher as the CoroutineContext.
                    return contextHolder;
                }
                if ("resumeWith".equals(name)) {
                    Object result = args != null && args.length > 0 ? args[0] : null;
                    Log.i(TAG, "Continuation.resumeWith fired, result=" +
                        (result == null ? "null" : result.getClass().getName()));
                    done.complete(result);
                    return null; // Unit / void
                }
                if ("equals".equals(name)) return proxy == args[0];
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("toString".equals(name)) return "BhVjoyImporterContinuation";
                return null;
            }
        );
    }

    /**
     * Detect Kotlin's COROUTINE_SUSPENDED sentinel. Kotlin defines this
     * as {@code kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED}
     * — a singleton object. Rather than chase its R8-renamed FQN, we
     * sniff by the well-known toString form: "COROUTINE_SUSPENDED".
     *
     * This is brittle but cheap. If the sentinel detection fails the
     * symptom is just a save timeout (we'll log and toast).
     */
    private static boolean isCoroutineSuspended(Object o) {
        if (o == null) return false;
        try {
            String name = o.getClass().getSimpleName();
            return "CoroutineSingletons".equals(name) ||
                   "COROUTINE_SUSPENDED".equals(o.toString());
        } catch (Throwable t) {
            return false;
        }
    }
}
