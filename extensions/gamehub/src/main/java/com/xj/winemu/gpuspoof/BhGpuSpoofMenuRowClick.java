package com.xj.winemu.gpuspoof;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import com.xj.winemu.common.BhMenuGameId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import kotlin.jvm.functions.Function1;

/**
 * Onclick handler for the "GPU Spoof" row injected into GameHub's per-game
 * menus (game-details More Menu + library-tile popups). Direct sibling of
 * {@code BhMenuRowClick} (vibration) — same reflection strategy against the
 * R8-mangled Compose menu data classes, same activity-stack Context/gameId
 * resolution. See that class's javadoc for the why behind each technique.
 */
public final class BhGpuSpoofMenuRowClick implements Function1<Object, Object> {

    private static final String TAG = "BhGpuSpoofRow";

    private static final String ROW_LABEL  = "GPU Spoof";
    private static final String ACTION_ID  = "local_detail_menu_gpu_spoof";

    @Override
    public Object invoke(Object ignoredFromCompose) {
        try {
            Activity host = resolveTopActivity();
            if (host == null) {
                Log.w(TAG, "no top Activity resolvable; cannot launch settings");
                return kotlin.Unit.INSTANCE;
            }
            Intent intent = new Intent(host, BhGpuSpoofSettingsActivity.class);
            // Per-game id captured from the menu data (shared
            // BhMenuGameId); fall back to a running WineActivity.
            String gameId = BhMenuGameId.getCaptured();
            if (gameId == null || gameId.isEmpty()) gameId = sniffGameIdFromStack();
            if (gameId != null && !gameId.isEmpty()) {
                intent.putExtra(BhGpuSpoofSettingsActivity.EXTRA_GAME_ID, gameId);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            host.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "menu click failed", t);
        }
        return kotlin.Unit.INSTANCE;
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

    /** Game-details More Menu (Lx57;->a): appends an Liae row. */
    public static void appendGpuSpoofRowTo(Object menuList) {
        try {
            if (!(menuList instanceof java.util.List)) return;
            java.util.List list = (java.util.List) menuList;

            Class<?> iaeCls = Class.forName("iae");
            Class<?> o05Cls = Class.forName("o05");
            Class<?> pw6Cls = Class.forName("pw6");

            Class<?> zz4Cls = Class.forName("zz4");
            Field iconHolderField = zz4Cls.getDeclaredField("m");
            iconHolderField.setAccessible(true);
            Object xrlWrapper = iconHolderField.get(null);
            if (xrlWrapper == null) {
                Log.w(TAG, "zz4.m is null; cannot resolve icon");
                return;
            }
            Object iconValue = xrlWrapper.getClass().getMethod("getValue").invoke(xrlWrapper);
            if (!o05Cls.isInstance(iconValue)) {
                Log.w(TAG, "zz4.m.getValue() did not return Lo05");
                return;
            }

            Object click = newFunction1Proxy(pw6Cls);
            java.lang.reflect.Constructor<?> ctor =
                iaeCls.getDeclaredConstructor(o05Cls, String.class, pw6Cls);
            ctor.setAccessible(true);
            list.add(ctor.newInstance(iconValue, ROW_LABEL, click));
        } catch (Throwable t) {
            Log.w(TAG, "appendGpuSpoofRowTo failed", t);
        }
    }

    /** Library-tile popup (ted.f): rebuilds the Lscd list with our row added. */
    public static java.util.List<Object> appendScdRowToTedList(Object original) {
        try {
            if (!(original instanceof java.util.List)) return safeReturn(original);
            java.util.ArrayList<Object> augmented =
                new java.util.ArrayList<>((java.util.List<?>) original);

            Class<?> scdCls = Class.forName("scd");
            Class<?> o05Cls = Class.forName("o05");
            Class<?> nw6Cls = Class.forName("nw6");
            Class<?> zz4Cls = Class.forName("zz4");

            Field iconField = zz4Cls.getDeclaredField("k");
            iconField.setAccessible(true);
            Object xrlWrapper = iconField.get(null);
            if (xrlWrapper == null) return safeReturn(original);
            Object iconValue = xrlWrapper.getClass().getMethod("getValue").invoke(xrlWrapper);
            if (!o05Cls.isInstance(iconValue)) return safeReturn(original);

            Object click = newFunction0Proxy(nw6Cls);
            java.lang.reflect.Constructor<?> ctor =
                scdCls.getDeclaredConstructor(String.class, o05Cls, String.class, nw6Cls);
            ctor.setAccessible(true);
            augmented.add(ctor.newInstance(ACTION_ID, iconValue, ROW_LABEL, click));
            return augmented;
        } catch (Throwable t) {
            Log.w(TAG, "appendScdRowToTedList failed", t);
            return safeReturn(original);
        }
    }

    // NOTE: appendLibraryPopupRow (Lpzc;->j0/Lz4e) and maybeResolveCustomLabel
    // (the Lxd3;->l1 resolver short-circuit) were removed — the l1 hook ran
    // main-thread reflection on every Compose string resolve and ANR'd cold
    // start when stacked on the vibration patch's identical hook. The row now
    // ships only on the More Menu + tile popup (raw String labels, no l1).

    // R8 renamed kotlin Function1/Function0; a Java `implements` is a
    // different JVM type than the host's Lpw6;/Lnw6;. Proxy actually
    // implements the host interface and delegates to invoke().
    private static Object newFunction1Proxy(Class<?> pw6Cls) {
        final BhGpuSpoofMenuRowClick handler = new BhGpuSpoofMenuRowClick();
        return java.lang.reflect.Proxy.newProxyInstance(
            pw6Cls.getClassLoader(), new Class<?>[]{ pw6Cls },
            (proxy, method, args) -> {
                if ("invoke".equals(method.getName()) && method.getParameterCount() == 1) {
                    return handler.invoke(args != null && args.length > 0 ? args[0] : null);
                }
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "BhGpuSpoofRowClickProxy";
                return null;
            });
    }

    private static Object newFunction0Proxy(Class<?> nw6Cls) {
        final BhGpuSpoofMenuRowClick handler = new BhGpuSpoofMenuRowClick();
        return java.lang.reflect.Proxy.newProxyInstance(
            nw6Cls.getClassLoader(), new Class<?>[]{ nw6Cls },
            (proxy, method, args) -> {
                if ("invoke".equals(method.getName()) && method.getParameterCount() == 0) {
                    return handler.invoke(null);
                }
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "BhGpuSpoofRowClick0";
                return null;
            });
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> safeReturn(Object o) {
        if (o instanceof java.util.List) return (java.util.List<Object>) o;
        return new java.util.ArrayList<>();
    }

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
                Intent it = ((Activity) a).getIntent();
                if (it == null) continue;
                String gid = it.getStringExtra("gameId");
                if (gid != null && !gid.isEmpty()) return gid;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
