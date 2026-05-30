package com.xj.winemu.exportcontrols;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Static smali-entry-points for the VJoy share/apply hijack.
 *
 * Two injection sites in the host VJoy share-code repository:
 *
 *   1. Repository.shareMap(layout):
 *        Stock body: serialize {@code layout} → POST /vcontroller/shareMap
 *                    → return Result(ShareCode).
 *        Hook at method ENTRY: call {@link #interceptShare(Object)}; if it
 *        returns non-null, return that as the wrapped result and skip the
 *        HTTP call.
 *
 *   2. Repository.getByShareCode(code):
 *        Stock body: GET /vcontroller/getMapByShareCode?code=<code>
 *                    → deserialize → return Result(SchemeDetailEntity).
 *        Hook at method ENTRY: call {@link #interceptApply()}; if it
 *        returns a non-null Layout, return it wrapped as the success
 *        Result and skip the HTTP call.
 *
 * Bytecode-side wiring (in ExportControlsPatch.kt):
 *   - Both methods are {@code suspend fun}, which after Kotlin compilation
 *     means the JVM signature is
 *       {@code (LVJoyLayout;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;}
 *     for share, and
 *       {@code (Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;}
 *     for apply. The hook returns a {@code Result}-compatible object that
 *     the suspend-state-machine wrapper passes back to the caller.
 *
 * UI assumption: the host's relabeled "Share" button still calls the same
 * repo method, and the relabeled "Apply share code" button likewise. The
 * relabel is purely a string swap; the underlying click path is unchanged.
 */
public final class BhVjoyShareHook {

    private static final String TAG = "BhVjoyShareHook";

    private BhVjoyShareHook() { }

    /**
     * Called at the head of Repository.shareMap(layout, continuation).
     *
     * @param layoutOrJson The {@code VJoyLayout} kotlinx-serializable
     *                     instance (or, if a different injection site is
     *                     used after serialization, the raw JSON String).
     * @return A {@code Result.success(syntheticShareCode)}-style wrapper,
     *         or null on cancel / failure (in which case the caller should
     *         fall through to the stock HTTP path).
     */
    /**
     * Most-recently-captured user-typed share name, written at the head of
     * `Lnrn;->h` (the Share/Export entry point) and consumed by
     * {@link #interceptUpload} as the SAF suggested filename.
     *
     * The layout's INTERNAL name (in layout.json) is independent — the user
     * can keep the layout named "868-HACK" but type "868-HACK-v2" in the
     * "Name Profile" dialog, and the file picker should suggest "v2".
     */
    private static final java.util.concurrent.atomic.AtomicReference<String>
        LAST_SHARE_NAME = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Called at the head of `Lnrn;->h(gameId, typedName, callback, cont)`.
     *
     * The host's h() is a Kotlin suspend method, so the JVM signature gets
     * re-entered on every coroutine resume — with the original String
     * argument replaced by null (the state is rehydrated from the
     * Continuation, not the call frame). Ignore those re-entries; the
     * FIRST call carries the user-typed name and is the only one we want.
     */
    public static void captureShareName(String name) {
        if (name == null || name.isEmpty()) return;
        LAST_SHARE_NAME.set(name);
        Log.i(TAG, "captureShareName: " + name);
    }

    public static Object interceptShare(Object dto) throws Exception {
        // THROW to abort the cloud-share cleanly. The local .gtheme is
        // already saved by {@link #interceptUpload} before this point, so
        // the cloud publish is pure unwanted side-effect: it pops a
        // "Cloud Backup Code" dialog AND navigates the user to the cloud-
        // share tab on success.
        //
        // This method is hooked at the HEAD of Lrqn;->i (the
        // /vcontroller/shareMap publish call), which the host invokes from
        // inside Lnrn;->h at a call site wrapped in a try/catch
        // (catchall_b4). Throwing here:
        //   1. Skips the real HTTP publish entirely (no cloud orphan).
        //   2. Is caught by h's catchall, which deletes the host's temp
        //      .gtheme cache file and RE-THROWS (verified in 6.0.4 smali:
        //      nrn.h :goto_29f → FileSystem.delete + throw v0).
        //   3. The re-thrown exception becomes a coroutine FAILURE result,
        //      so the share-button's SUCCESS path — which does the cloud-
        //      tab navigation and the "Cloud Backup Code" dialog — never
        //      runs.
        //
        // HISTORY:
        //   - pre9-pre26 returned kotlin.Unit.INSTANCE to suppress the
        //     result dialog. Crashed with ClassCastException because the
        //     caller check-casts the result to Lo55 (Unit isn't Lo55).
        //   - pre28a returned null → stock publish ran → cloud-tab
        //     navigation (user-reported regression).
        //   - pre28b (this): throw → clean failure, no publish, no
        //     dialog, no navigation, temp file cleaned up by the host.
        if (dto == null) return null; // resume/edge — let host proceed
        throw new java.io.IOException("bh_export_local_only");
    }

    /**
     * Called at the head of the pre-upload step `Lrqn;->j` (the
     * /vcontroller/uploadGtheme POST). The DTO at this point is
     * {@code Lasn;} — wrapping an {@code Lvnm;} that has the local file
     * path of the freshly-written, kotlinx-serialized .gtheme. The file
     * on disk is PRISTINE: full UTF-8, no CDN mangling, valid ZIP.
     *
     * We read those bytes synchronously here, launch SAF with them, and
     * return null so the host's upload-to-CDN proceeds normally (the
     * resulting cloud copy is orphaned — see {@link #interceptShare}).
     *
     * Reflection path: dto.a (Lvnm) → vnm.d (Lvja) → vja.b (Function0
     * concrete impl, with one Lokio.Path field) → invoke Path.toString()
     * to get an absolute filesystem path → read bytes via java.io.File.
     */
    public static Object interceptUpload(Object dto) {
        if (dto == null) return null;
        try {
            Activity host = resolveTopActivity();
            if (host == null) {
                Log.w(TAG, "interceptUpload: no foreground activity");
                return null;
            }
            String absPath = resolveUploadFilePath(dto);
            if (absPath == null) {
                Log.w(TAG, "interceptUpload: could not resolve local file path");
                return null;
            }
            java.io.File f = new java.io.File(absPath);
            if (!f.exists() || !f.canRead()) {
                Log.w(TAG, "interceptUpload: path does not exist or unreadable: "
                    + absPath);
                return null;
            }
            byte[] bytes = readAllBytes(f);
            if (bytes == null) return null;
            // Suggested filename precedence:
            //   1. The user-typed name from the "Name Profile" dialog
            //      (captured at Lnrn;->h head into LAST_SHARE_NAME).
            //   2. The layout's own internal name (read from layout.json
            //      inside the ZIP) — used as a fallback when capture
            //      didn't fire (e.g. a future host-flow change).
            //   3. The host's cache filename (vjoy_share_<id>_<ts>.gtheme)
            //      — last resort, ugly but always available.
            String typed = LAST_SHARE_NAME.getAndSet(null);
            String suggested;
            if (typed != null && !typed.isEmpty()) {
                suggested = sanitizeFilename(typed) + ".gtheme";
            } else {
                String layoutName = readLayoutNameFromGtheme(bytes);
                if (layoutName != null && !layoutName.isEmpty()) {
                    suggested = sanitizeFilename(layoutName) + ".gtheme";
                } else {
                    suggested = sanitizeFilename(f.getName());
                    if (!suggested.endsWith(".gtheme")) suggested = suggested + ".gtheme";
                }
            }
            Log.i(TAG, "interceptUpload reading " + absPath + " (" +
                bytes.length + " bytes), suggested=" + suggested);
            BhSafProxyActivity.startExportFromBytes(host, bytes, suggested);
        } catch (Throwable t) {
            Log.w(TAG, "interceptUpload failed", t);
        }
        return null;
    }

    /**
     * Walk the upload-DTO ({@code Lasn -> Lvnm -> Lvja -> Function0}) to
     * find a {@code Lokio.Path} field on the Function0 implementation,
     * convert it to an absolute filesystem path.
     *
     * Done by FIELD-TYPE shape, not by R8-letter, so it survives version
     * reshuffles. PREFERS paths whose toString ends in .gtheme (the host's
     * cache filename pattern is `vjoy_share/vjoy_share_<id>_<ts>.gtheme`).
     * Falls back to the first Path-typed value found if no .gtheme match
     * exists — defensive against host renaming the cache suffix.
     */
    private static String resolveUploadFilePath(Object dto) {
        try {
            Object pathObj = findFieldOfType(dto, "okio.Path", 4 /*depth*/,
                ".gtheme");
            if (pathObj == null) return null;
            return pathObj.toString();
        } catch (Throwable t) {
            Log.w(TAG, "resolveUploadFilePath failed", t);
            return null;
        }
    }

    /**
     * BFS through {@code root}'s reachable object graph up to {@code maxDepth}
     * levels, returning a non-null field value whose runtime class is (or
     * extends) {@code typeName}.
     *
     * Picking strategy: if {@code preferredToStringSuffix} is non-null,
     * prefer a candidate whose {@code toString()} ends with that suffix.
     * The BFS continues past the first match until the queue empties so a
     * better-matching candidate later in the graph wins. If no preferred
     * match is found, the FIRST candidate seen is returned. Pass null to
     * disable preference and return the first match.
     *
     * Bypasses primitive fields, statics, and already-visited objects.
     */
    private static Object findFieldOfType(
            Object root, String typeName, int maxDepth,
            String preferredToStringSuffix) {
        java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        queue.add(new Object[]{root, 0});
        seen.put(root, Boolean.TRUE);
        Object firstMatch = null;
        while (!queue.isEmpty()) {
            Object[] node = queue.poll();
            Object obj = node[0];
            int depth = (Integer) node[1];
            if (obj == null) continue;
            Class<?> cls = obj.getClass();
            // Match by class hierarchy.
            Class<?> c = cls;
            boolean typeMatch = false;
            while (c != null) {
                if (typeName.equals(c.getName())) { typeMatch = true; break; }
                c = c.getSuperclass();
            }
            if (typeMatch) {
                if (preferredToStringSuffix == null) return obj;
                try {
                    String s = obj.toString();
                    if (s != null && s.endsWith(preferredToStringSuffix)) return obj;
                } catch (Throwable ignored) { }
                if (firstMatch == null) firstMatch = obj;
                // continue searching in case a preferred match exists
            }
            if (depth >= maxDepth) continue;
            // Walk declared fields (skip statics and primitives).
            for (Field f : cls.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mods)) continue;
                if (f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                Object v;
                try { v = f.get(obj); } catch (IllegalAccessException e) { continue; }
                if (v == null || seen.containsKey(v)) continue;
                seen.put(v, Boolean.TRUE);
                queue.add(new Object[]{v, depth + 1});
            }
        }
        return firstMatch;
    }

    /**
     * Pull the user-visible layout name out of a .gtheme byte buffer.
     * Unzips the buffer in-memory, finds layout.json, and scans its JSON
     * for the `name.locales.default` field with a simple regex (avoids
     * pulling kotlinx-serialization onto this hot path — the field shape
     * is stable and ASCII-keyed). Returns null on any extraction failure;
     * caller falls back to the host's cache filename.
     */
    private static String readLayoutNameFromGtheme(byte[] bytes) {
        try (java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(bytes);
             java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(bin)) {
            java.util.zip.ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) { zip.closeEntry(); continue; }
                String name = e.getName();
                if (!name.equals("layout.json") &&
                    !name.toLowerCase().endsWith("layout.json")) {
                    zip.closeEntry();
                    continue;
                }
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
                String json = new String(out.toByteArray(), "UTF-8");
                // Find "name":{ "locales":{ "default":"..."
                java.util.regex.Matcher m = NAME_LOCALES_DEFAULT.matcher(json);
                if (m.find()) {
                    String raw = m.group(1);
                    return unescapeJsonString(raw);
                }
                return null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "readLayoutNameFromGtheme failed", t);
        }
        return null;
    }

    private static final java.util.regex.Pattern NAME_LOCALES_DEFAULT =
        java.util.regex.Pattern.compile(
            "\"name\"\\s*:\\s*\\{\\s*\"locales\"\\s*:\\s*\\{\\s*\"default\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Minimal JSON string-literal unescape (handles standard backslash escapes). */
    private static String unescapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { sb.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(
                                s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException nfe) { sb.append(n); }
                    } else { sb.append(n); }
                    break;
                default: sb.append(n); break;
            }
        }
        return sb.toString();
    }

    /** Read the entire file into a byte array. */
    private static byte[] readAllBytes(java.io.File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "readAllBytes failed for " + f.getAbsolutePath(), t);
            return null;
        }
    }

    /**
     * In-flight guard. Suspend methods compile to JVM methods that get
     * called twice per user-tap (initial entry + coroutine resume), and
     * the composition-time resolver hook fires many times per dialog show
     * (one per Compose recomposition). Without this gate we'd spawn
     * multiple parallel SAF launches.
     *
     * The gate stays held from the moment an import worker starts until
     * {@link #dismissAndReleaseGate} runs. We release on a short delay
     * (~500ms) after the BACK keypress so the dismiss-recompose cascade
     * doesn't see the gate as open and re-fire the import.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean
        IMPORT_IN_FLIGHT = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Called at the head of Repository.getByShareCode(code, continuation).
     * The {@code code} arg is ignored — we present a file picker instead.
     * Used as a fallback in case the composition-time hook
     * {@link #kickImportFromDialogOpen} doesn't fire (e.g. resource key
     * renamed by a future host update).
     */
    public static Object interceptApply() {
        launchImportWorker(false);
        return null;
    }

    /**
     * pre16 skip-the-dialog entry point. Called from the label-resolver
     * hook every time the host resolves
     * {@code features_vjoy_dialog_import_share_code_title} — which is at
     * composition of the Import dialog. SAF takes focus over the briefly-
     * composed dialog, and after the user picks a file (or cancels) we
     * dismiss the leftover dialog via a programmatic BACK keypress.
     *
     * Fires many times per dialog show; {@link #IMPORT_IN_FLIGHT} gates
     * so only one SAF launch happens per session.
     */
    public static void kickImportFromDialogOpen() {
        Log.i(TAG, "kickImportFromDialogOpen entry, inFlight=" + IMPORT_IN_FLIGHT.get()
            + " pid=" + android.os.Process.myPid());
        launchImportWorker(true);
    }

    /**
     * Spawn the SAF-driven import worker. Atomically takes the in-flight
     * gate; the gate is released by {@link #dismissAndReleaseGate}
     * (when {@code dismissDialog}=true) or in the worker's finally block
     * (when {@code dismissDialog}=false, i.e. the non-dialog interceptApply
     * fallback path).
     */
    private static void launchImportWorker(boolean dismissDialog) {
        if (!IMPORT_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        Log.i(TAG, "launchImportWorker dismissDialog=" + dismissDialog);
        final Activity host = resolveTopActivity();
        if (host == null) {
            Log.w(TAG, "launchImportWorker: no foreground activity");
            IMPORT_IN_FLIGHT.set(false);
            return;
        }
        new Thread(() -> {
            try {
                byte[] gtheme = BhSafProxyActivity.startImport(host)
                        .get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (gtheme == null) {
                    Log.i(TAG, "import cancelled by user");
                    host.runOnUiThread(() -> {
                        Toast.makeText(host, "Import cancelled",
                                Toast.LENGTH_SHORT).show();
                        if (dismissDialog) dismissAndReleaseGate(host);
                    });
                    return;
                }
                Log.i(TAG, "got " + gtheme.length + " bytes from SAF");
                boolean ok = BhVjoyImporter.importFromGthemeBytes(gtheme);
                final String msg = ok ? "Layout imported"
                        : "Import failed — see logcat";
                host.runOnUiThread(() -> {
                    Toast.makeText(host, msg, Toast.LENGTH_LONG).show();
                    if (dismissDialog) dismissAndReleaseGate(host);
                });
            } catch (Throwable t) {
                Log.w(TAG, "import worker failed", t);
                host.runOnUiThread(() -> {
                    Toast.makeText(host, "Import failed: " + t.getMessage(),
                            Toast.LENGTH_LONG).show();
                    if (dismissDialog) dismissAndReleaseGate(host);
                });
            } finally {
                // When dismissDialog=true the gate is released by
                // dismissAndReleaseGate after the recompose cascade
                // settles. When false (fallback interceptApply), no
                // dialog dismiss happens, so release here.
                if (!dismissDialog) IMPORT_IN_FLIGHT.set(false);
            }
        }, "BhVjoyImportWorker").start();
    }

    /**
     * Dismiss the leftover Import dialog and release the in-flight gate
     * after the dismiss-recompose cascade settles. Sending BACK triggers
     * one final round of title-resource resolutions as Compose tears down
     * the dialog; releasing the gate immediately would let those
     * resolutions re-fire {@link #kickImportFromDialogOpen}. 500ms is
     * empirically generous (the cascade finishes in ~50ms).
     *
     * Run on UI thread.
     */
    private static void dismissAndReleaseGate(Activity host) {
        dismissTopDialog(host);
        new android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed(() -> IMPORT_IN_FLIGHT.set(false), 500L);
    }

    /**
     * Programmatically dismiss the topmost Compose dialog. Two mechanisms
     * run back-to-back so whichever the dialog responds to wins:
     *
     *   1. KEYCODE_BACK dispatchKeyEvent — dismisses Compose Dialogs via
     *      their BackHandler in the main UI.
     *   2. Synthetic outside-tap — MotionEvent ACTION_DOWN/UP at (10, 10),
     *      well outside any centered dialog. Triggers dismissOnClickOutside
     *      (the Compose Dialog default). This is what catches in-game
     *      dialogs, whose BackHandler is intercepted by WineActivity's own
     *      game-pause back handler before our BACK reaches the dialog.
     *
     * A successful dismiss by the first makes the second harmless (a tap on
     * empty space). Only called from the SAF-worker callback, by which
     * point the dialog is the topmost focusable.
     *
     * NOTE: pre26 also tried an OnBackPressedDispatcher.onBackPressed()
     * reflection prong, but R8 renames that method on the dispatcher class
     * (NoSuchMethodException: <renamed>.onBackPressed), so it never
     * succeeded and only spammed logs. Dropped in pre29 — the two prongs
     * below cover both the main-UI and in-game cases.
     */
    private static void dismissTopDialog(Activity host) {
        try {
            long when = android.os.SystemClock.uptimeMillis();
            android.view.KeyEvent down = new android.view.KeyEvent(
                when, when, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_BACK, 0);
            android.view.KeyEvent up = new android.view.KeyEvent(
                when, when, android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_BACK, 0);
            host.dispatchKeyEvent(down);
            host.dispatchKeyEvent(up);
        } catch (Throwable t) {
            Log.w(TAG, "dismissTopDialog BACK dispatch failed", t);
        }
        dispatchOutsideTap(host);
    }

    /** Send a MotionEvent at the top-left corner (well outside any
     *  centered dialog) to trigger dismiss-on-outside-tap. */
    private static void dispatchOutsideTap(Activity host) {
        try {
            long when = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(
                when, when, android.view.MotionEvent.ACTION_DOWN,
                10f, 10f, 0);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(
                when, when + 50, android.view.MotionEvent.ACTION_UP,
                10f, 10f, 0);
            host.dispatchTouchEvent(down);
            host.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "dispatchOutsideTap failed", t);
        }
    }

    // -------- helpers --------------------------------------------------------

    private static String sanitizeFilename(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    /** Walk ActivityThread.mActivities to find the most-recently-resumed Activity. */
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
}
