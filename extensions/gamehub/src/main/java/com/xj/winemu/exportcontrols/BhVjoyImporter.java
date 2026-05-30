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
 *   - {@link #SAVE_HELPER_CLASS}  = "o0n"     — has static `i(...)`
 *   - {@link #WITH_CONTEXT_CLASS} = "w0o"     — has static `s0(...)`
 *   - {@link #DISPATCHER_HOLDER}  = "f80"     — static field `a:Ll14;`
 *                                              is Dispatchers.IO
 *   - {@link #SAVE_BLOCK_CLASS}   = "m0n"     — the save-coroutine state
 *                                              class; ctor takes
 *                                              (String, VJoyLayout, Lbi3;)
 *   - {@link #CONTINUATION_INTERFACE} = "bi3" — the R8-renamed
 *                                              kotlin.coroutines.Continuation
 *
 * All paths fail-soft: on any reflection / parse / save error we log and
 * return null. The caller (BhVjoyShareHook#interceptApply) toasts a
 * generic error.
 */
public final class BhVjoyImporter {

    private static final String TAG = "BhVjoyImporter";

    // === R8-mangled anchors (6.0.4 / branch feature/vjoy-export-import) ===
    private static final String WITH_CONTEXT_CLASS    = "w0o";  // BuildersKt (has s0 = withContext)
    private static final String DISPATCHER_HOLDER     = "f80";  // Dispatchers (has a = IO)
    private static final String COROUTINE_CONTEXT_IF  = "dm3";  // CoroutineContext interface (first arg type)
    private static final String FUNCTION2_IF          = "dx6";  // Function2 interface (block param)
    private static final String CONTINUATION_INTERFACE = "bi3"; // Continuation interface (completion param)
    private static final String SAVE_BLOCK_CLASS      = "m0n";  // suspend lambda; ctor takes (String, VJoyLayout, Continuation)
    private static final String VJOY_LAYOUT_FQN =
        "com.xiaoji.egggame.common.ui.vjoy.model.VJoyLayout";

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

            // Pull values for the row.
            long now = System.currentTimeMillis();
            String name = readLayoutName(layout);
            if (name == null || name.isEmpty()) name = "Imported Layout";
            String titleI18n = "{\"default\":" + jsonString(name) + "}";
            String configHash = readReceiptString(saveReceipt, "getConfigHash");

            android.content.ContentValues v = new android.content.ContentValues();
            v.put("user_id",              "99999");
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
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "registerInDatabase failed", t);
            return false;
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) { }
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
     * with the save coroutine block ({@code Lm0n;}) directly. Bypasses
     * the {@code Lo0n;->i} static wrapper because its declared third-arg
     * type is the abstract {@code Lci3;} class — Java reflection can't
     * accept our {@code Lbi3;} Proxy as that type even though it would
     * work at JVM bytecode level. {@code Lw0o;->s0} accepts the {@code Lbi3;}
     * interface directly, which our Proxy satisfies.
     *
     * Call shape (from o0n.i smali):
     *   sget-object v0, Lf80;->a:Ll14;     ; Dispatchers.IO
     *   new-instance v1, Lm0n;
     *   const/4 v2, 0x0
     *   invoke-direct {v1, layoutId, layout, null}, Lm0n;-><init>(String, VJoyLayout, Lbi3;)V
     *   invoke-static {v0, v1, ourContinuation}, Lw0o;->s0(Ldm3;Ldx6;Lbi3;)Ljava/lang/Object;
     */
    private static Object saveLayoutLocal(String layoutId, Object vJoyLayout)
            throws Exception {
        Class<?> vJoyLayoutCls = Class.forName(VJOY_LAYOUT_FQN);
        Class<?> continuationCls = Class.forName(CONTINUATION_INTERFACE);     // bi3
        Class<?> coroutineCtxCls = Class.forName(COROUTINE_CONTEXT_IF);       // dm3
        Class<?> function2Cls = Class.forName(FUNCTION2_IF);                  // dx6
        Class<?> withContextCls = Class.forName(WITH_CONTEXT_CLASS);          // w0o
        Class<?> dispatchersCls = Class.forName(DISPATCHER_HOLDER);           // f80
        Class<?> saveBlockCls = Class.forName(SAVE_BLOCK_CLASS);              // m0n

        // 1. Pull Dispatchers.IO singleton from f80.a.
        Field dispatcherField = dispatchersCls.getDeclaredField("a");
        dispatcherField.setAccessible(true);
        Object dispatcher = dispatcherField.get(null);
        if (dispatcher == null) throw new IllegalStateException("f80.a is null");

        // 2. Construct the save coroutine block. ctor: (String, VJoyLayout, Lbi3;)
        Constructor<?> blockCtor = saveBlockCls.getDeclaredConstructor(
            String.class, vJoyLayoutCls, continuationCls);
        blockCtor.setAccessible(true);
        Object saveBlock = blockCtor.newInstance(layoutId, vJoyLayout, null);

        // 3. Build a Continuation proxy and bridge to a CompletableFuture.
        CompletableFuture<Object> done = new CompletableFuture<>();
        Object continuation = makeContinuation(continuationCls, done, dispatcher);

        // 4. Find w0o.s0(Ldm3;Ldx6;Lbi3;)Object — withContext.
        Method withContext = withContextCls.getDeclaredMethod(
            "s0", coroutineCtxCls, function2Cls, continuationCls);
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
