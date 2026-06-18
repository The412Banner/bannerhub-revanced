package com.xj.winemu.vibration;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import com.xj.winemu.common.BhMenuGameId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import kotlin.jvm.functions.Function1;

/**
 * Onclick handler for the "PC Vibration Settings" row injected into the
 * per-game library popup menu (PC Game Settings / Add to Desktop / Remove
 * from Library / Edit Cover / **PC Vibration Settings**).
 *
 * Implements Compose's {@code (Any) -> Any} click type (Function1).
 * The argument from Compose is ignored — we fire startActivity with an
 * intent to {@link BhVibrationSettingsActivity}.
 *
 * The Context is resolved at click time by reflectively walking
 * ActivityThread.mActivities to find the currently-resumed Activity (same
 * pattern {@link BhVibrationController#maybeResolveContainerFromActivityStack}
 * uses). This avoids needing a captured Context at construction time,
 * which would otherwise require the bytecode patch to find an
 * appropriate Context register inside the heavily-obfuscated Compose
 * Composable that builds the menu.
 *
 * If a WineActivity is in the stack, its gameId Intent extra is forwarded
 * to BhVibrationSettingsActivity so per-game settings scope correctly.
 * Otherwise (typical for clicks from the My Games list) the dialog opens
 * scoped to global defaults.
 */
public final class BhMenuRowClick implements Function1<Object, Object> {

    private static final String TAG = "BhMenuRowClick";

    @Override
    public Object invoke(Object ignoredFromCompose) {
        try {
            Activity host = resolveTopActivity();
            if (host == null) {
                Log.w(TAG, "no top Activity resolvable; cannot launch settings");
                return kotlin.Unit.INSTANCE;
            }
            Intent intent = new Intent(host, BhVibrationSettingsActivity.class);
            // Per-game id captured from the menu data (shared
            // BhMenuGameId); fall back to a running WineActivity.
            String gameId = BhMenuGameId.getCaptured();
            if (gameId == null || gameId.isEmpty()) gameId = sniffGameIdFromStack();
            if (gameId != null && !gameId.isEmpty()) {
                // BhVibrationSettingsActivity reads EXTRA_GAME_ID
                // ("bh_vibration.gameId"), NOT "gameId" — using the wrong
                // key here is why per-game never took effect for vibration.
                intent.putExtra(BhVibrationSettingsActivity.EXTRA_GAME_ID, gameId);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            host.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "menu click failed", t);
        }
        return kotlin.Unit.INSTANCE;
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
                // Prefer non-paused activity; fall back to any non-finishing one.
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

    /**
     * Constructs a per-game-menu row Iae instance via reflection and appends
     * it to the passed-in list builder. Called from a 1-line smali injection
     * inside the menu Composable — keeps the bytecode patch trivial (no
     * register juggling, no verifier risk) at the cost of a runtime
     * reflection lookup.
     *
     * The obfuscated class names {@code iae}, {@code o05}, {@code pw6},
     * {@code zz4} are stable in the GameHub 6.0.4 base APK; if a future
     * R8-map shift renames them, this method silently no-ops (logged) and
     * the menu falls back to the original 4 rows.
     */
    public static void appendVibrationRowTo(Object menuList) {
        try {
            if (!(menuList instanceof java.util.List)) return;
            java.util.List list = (java.util.List) menuList;

            Class<?> iaeCls = Class.forName("iae");
            Class<?> o05Cls = Class.forName("o05");
            Class<?> pw6Cls = Class.forName("pw6");

            // Resolve a gear/settings icon. zz4 is the ComposableSingletons
            // class for menu-row icons; the `m` field holds an Lxrl wrapper
            // whose getValue() returns an Lo05 (Painter or vector ref).
            Class<?> zz4Cls = Class.forName("zz4");
            Field iconHolderField = zz4Cls.getDeclaredField("b0");
            iconHolderField.setAccessible(true);
            Object xrlWrapper = iconHolderField.get(null);
            if (xrlWrapper == null) {
                Log.w(TAG, "zz4.b0 is null; cannot resolve icon");
                return;
            }
            Object iconValue = xrlWrapper.getClass().getMethod("getValue").invoke(xrlWrapper);
            if (!o05Cls.isInstance(iconValue)) {
                Log.w(TAG, "zz4.b0.getValue() did not return Lo05");
                return;
            }

            // R8 renamed kotlin.jvm.functions.Function1 to Lpw6; in the host
            // APK, so our Java `implements Function1<Object, Object>` IS a
            // different JVM class from the host's Lpw6;. Iae's constructor
            // requires Lpw6; specifically — direct Java implements doesn't
            // satisfy the type check. Fix: create a Proxy that actually
            // implements Lpw6; at runtime, delegating its single invoke
            // method to our BhMenuRowClick.invoke().
            final BhMenuRowClick handler = new BhMenuRowClick();
            Object click = java.lang.reflect.Proxy.newProxyInstance(
                pw6Cls.getClassLoader(),
                new Class<?>[]{ pw6Cls },
                (proxy, method, args) -> {
                    if ("invoke".equals(method.getName()) && method.getParameterCount() == 1) {
                        return handler.invoke(args != null && args.length > 0 ? args[0] : null);
                    }
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("toString".equals(method.getName())) return "BhMenuRowClickProxy";
                    return null;
                }
            );

            // Find the Iae 3-arg ctor: Iae(o05, String, pw6)
            java.lang.reflect.Constructor<?> ctor =
                iaeCls.getDeclaredConstructor(o05Cls, String.class, pw6Cls);
            ctor.setAccessible(true);

            Object row = ctor.newInstance(iconValue, "PC Vibration Settings", click);
            list.add(row);
        } catch (Throwable t) {
            Log.w(TAG, "appendVibrationRowTo failed", t);
        }
    }

    /**
     * Library-tile popup variant. The library tile's 3-dot popup is rendered
     * by a different Composable than the game-details More Menu: rows use
     * {@code Lscd(String actionId, Lo05 icon, String label, Lnw6 onClick)}
     * with a Function0 click handler (no args), and the 4 rows are
     * collected into an immutable {@code List<Lscd>} via Arrays.asList
     * before being iterated for the focus tree.
     *
     * The smali injection replaces that list with a new ArrayList that
     * contains the original 4 rows plus our PC Vibration Settings row.
     * Returns the new list (the smali injection captures the return value
     * and re-assigns it to the list register).
     */
    public static java.util.List<Object> appendScdRowToTedList(Object original) {
        try {
            if (!(original instanceof java.util.List)) return safeReturn(original);
            java.util.List<?> origList = (java.util.List<?>) original;
            java.util.ArrayList<Object> augmented = new java.util.ArrayList<>(origList);

            Class<?> scdCls = Class.forName("scd");
            Class<?> o05Cls = Class.forName("o05");
            Class<?> nw6Cls = Class.forName("nw6");
            Class<?> zz4Cls = Class.forName("zz4");

            Field iconField = zz4Cls.getDeclaredField("b0");
            iconField.setAccessible(true);
            Object xrlWrapper = iconField.get(null);
            if (xrlWrapper == null) return safeReturn(original);
            Object iconValue = xrlWrapper.getClass().getMethod("getValue").invoke(xrlWrapper);
            if (!o05Cls.isInstance(iconValue)) return safeReturn(original);

            // Function0 onClick via Proxy that implements Lnw6;
            final BhMenuRowClick handler = new BhMenuRowClick();
            Object click = java.lang.reflect.Proxy.newProxyInstance(
                nw6Cls.getClassLoader(),
                new Class<?>[]{ nw6Cls },
                (proxy, method, args) -> {
                    if ("invoke".equals(method.getName()) && method.getParameterCount() == 0) {
                        return handler.invoke(null);
                    }
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("toString".equals(method.getName())) return "BhMenuRowClickProxy0";
                    return null;
                }
            );

            java.lang.reflect.Constructor<?> ctor =
                scdCls.getDeclaredConstructor(String.class, o05Cls, String.class, nw6Cls);
            ctor.setAccessible(true);

            Object row = ctor.newInstance(
                "local_detail_menu_pc_vibration",
                iconValue,
                "PC Vibration Settings",
                click
            );
            augmented.add(row);
            return augmented;
        } catch (Throwable t) {
            Log.w(TAG, "appendScdRowToTedList failed", t);
            return safeReturn(original);
        }
    }

    /**
     * Library-tile popup variant #2 — the actual library list popup
     * rendered by Lpzc;->j0(). Uses a third row data class:
     *   Lz4e(Lell label, Lnw6 onClick, int)  [synthetic 3-arg ctor]
     *     - Lell extends Ltdi(String key, Set<String> locales) — a Compose
     *       Multiplatform string-resource descriptor; resolved at render
     *       time by Lxd3.l1.
     *     - Lnw6 is Function0 (no-arg lambda), R8-renamed kotlin Function0.
     *
     * Our label key "bh_pc_vibration_label" is added to features.home's
     * CVR resource bundle via VibrationMenuLabelPatch; here we construct
     * an Lell that points at that key, plus an Lnw6 proxy delegating to
     * BhMenuRowClick.invoke.
     */
    public static java.util.List<Object> appendLibraryPopupRow(Object original) {
        try {
            if (!(original instanceof java.util.List)) return safeReturn(original);
            java.util.List<?> origList = (java.util.List<?>) original;
            java.util.ArrayList<Object> augmented = new java.util.ArrayList<>(origList);

            Class<?> z4eCls = Class.forName("z4e");
            Class<?> ellCls = Class.forName("ell");
            Class<?> tdiCls = Class.forName("tdi");
            Class<?> nw6Cls = Class.forName("nw6");

            // Construct the Lell label. Lell is a Kotlin empty subclass of
            // abstract Ltdi(String key, Set<String> locales) — at bytecode
            // level the host does `new-instance Lell; invoke Ltdi.<init>`,
            // but `Lell.class.getDeclaredConstructor(String.class, Set.class)`
            // returns nothing because Lell declares no constructors itself.
            // Workaround: allocate Lell via sun.misc.Unsafe.allocateInstance
            // (skips ctor entirely) and reflect-set the inherited Ltdi fields
            // a (key) and b (locales).
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Object label = unsafeCls.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, ellCls);
            // Ltdi.a holds the resource key, Ltdi.b holds the locale Set.
            Field aField = tdiCls.getDeclaredField("a");
            aField.setAccessible(true);
            aField.set(label, "string:bh_pc_vibration_label");
            Field bField = tdiCls.getDeclaredField("b");
            bField.setAccessible(true);
            bField.set(label, java.util.Collections.emptySet());

            // Function0 onClick via Proxy implementing Lnw6;
            final BhMenuRowClick handler = new BhMenuRowClick();
            Object click = java.lang.reflect.Proxy.newProxyInstance(
                nw6Cls.getClassLoader(),
                new Class<?>[]{ nw6Cls },
                (proxy, method, args) -> {
                    if ("invoke".equals(method.getName()) && method.getParameterCount() == 0) {
                        return handler.invoke(null);
                    }
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("toString".equals(method.getName())) return "BhLibPopupRowClick";
                    return null;
                }
            );

            // Use the synthetic 3-arg ctor: Lz4e(Lell;Lnw6;I)V
            // Pass int=0 — exact meaning is "category/group" or similar
            // marker; 0 should be a safe default that doesn't conflict
            // with reserved values for the existing rows.
            java.lang.reflect.Constructor<?> z4eCtor =
                z4eCls.getDeclaredConstructor(ellCls, nw6Cls, int.class);
            z4eCtor.setAccessible(true);
            Object row = z4eCtor.newInstance(label, click, 0);

            augmented.add(row);
            return augmented;
        } catch (Throwable t) {
            Log.w(TAG, "appendLibraryPopupRow failed", t);
            return safeReturn(original);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> safeReturn(Object o) {
        // Fall back to original list (cast) — silently skip our row if anything broke.
        if (o instanceof java.util.List) return (java.util.List<Object>) o;
        return new java.util.ArrayList<>();
    }

    /**
     * Diagnostic probe — logs an actionId when a row builder is called.
     * Wired into joc.invoke() to identify when (and with what input) the
     * library tile popup's row builder fires. Removable once the menu
     * extension lands on joc-based rows.
     */
    /**
     * Patched into the resolver Lxd3.l1 to short-circuit our sentinel keys
     * BEFORE they hit the Compose Multiplatform resource lookup (which
     * throws "Resource with ID='string:bh_..._label' not found" because the
     * runtime expects a manifest registration alongside the .cvr entry — and
     * just appending to the .cvr isn't enough).
     *
     * SHARED resolver for ALL BannerHub library-popup rows. There is
     * deliberately ONE Lxd3;->l1 head-block in the whole patch set (injected
     * by VibrationMenuRowPatch); a SECOND head-block stacked on it ANR'd
     * MainActivity cold start on slow devices (2026-05-17, gpuspoof saga).
     * So GPU Spoof and Renderer do NOT add their own l1 hook — they reuse
     * this single one by registering their sentinel key → label here. Adding
     * a new library-popup row = add one entry to the table below + an
     * appendLibraryPopupRow helper in that feature's click class (no patcher
     * change to l1).
     *
     * Returns the row label when a sentinel key matches; returns null
     * otherwise so the stock resolver path runs unchanged.
     */
    public static String maybeResolveCustomLabel(Object ell) {
        try {
            // 6.0.7: CMP resource base class renamed tdi → shg; 6.0.8: shg → vhg
            // (Lkwj; extends Lvhg;); 6.0.9: vhg → o4h (Llok; extends Lo4h;);
            // field `a` (the "string:<key>" id holder, b=locales Set) unchanged.
            Field aField = Class.forName("o4h").getDeclaredField("a");
            aField.setAccessible(true);
            Object key = aField.get(ell);
            if (key == null) return null;
            String label = null;
            if ("string:bh_pc_vibration_label".equals(key)) {
                label = "PC Vibration Settings";
            } else if ("string:bh_gpuspoof_label".equals(key)) {
                label = "GPU Spoof";
            } else if ("string:bh_renderer_label".equals(key)) {
                label = "Renderer";
            } else if ("string:bh_gog_label".equals(key)) {
                label = "GOG";
            } else if ("string:bh_gameid_label".equals(key)) {
                label = "Show Game ID";
            } else if ("string:bh_banner_tools_label".equals(key)) {
                label = "Banner Tools";
            }
            if (label != null) {
                Log.i(TAG, "maybeResolveCustomLabel key=" + key + " → '" + label + "'");
                return label;
            }
        } catch (Throwable t) {
            Log.w(TAG, "maybeResolveCustomLabel error", t);
        }
        return null;
    }

    public static void probeJocInvoke(Object self) {
        try {
            Log.i(TAG, "probeJocInvoke fired class=" + (self == null ? "null" : self.getClass().getName())
                + " hash=" + System.identityHashCode(self));
        } catch (Throwable ignored) { }
    }

    /** If a WineActivity is in the stack, grab its gameId Intent extra. */
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
                String clsName = a.getClass().getName();
                if (!clsName.endsWith(".WineActivity")) continue;
                Intent it = ((Activity) a).getIntent();
                if (it == null) continue;
                String gid = it.getStringExtra("gameId");
                if (gid != null && !gid.isEmpty()) return gid;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
