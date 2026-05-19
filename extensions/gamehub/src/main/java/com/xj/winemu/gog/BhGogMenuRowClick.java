package com.xj.winemu.gog;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import kotlin.jvm.functions.Function1;

/**
 * Onclick handler for the "GOG" row injected into GameHub's game-details
 * "More Menu" (Lx57;->a). Mode-independent entry point to the GOG hub
 * (login / owned-library) — the per-game menu exists in BOTH handheld and
 * explore modes, unlike the seeded library card which only renders in the
 * handheld library surface (GOG_LIBRARY_TAB_DESIGN §32–§32b, the
 * explore-mode investigation). Tapping it opens GogMainActivity.
 *
 * Structural clone of {@code BhGpuSpoofMenuRowClick} (the device-confirmed
 * menu-injection playbook — see [[bannerhub-revanced-menu-injection-playbook]])
 * but trimmed to Menu A only (raw String label, no resolver / Unsafe / Lell)
 * and with no per-game id (the GOG hub is global, not game-scoped).
 */
public final class BhGogMenuRowClick implements Function1<Object, Object> {

    private static final String TAG = "BhGogRow";

    private static final String ROW_LABEL = "GOG";
    private static final String HUB_ACTIVITY =
        "app.revanced.extension.gamehub.gog.GogMainActivity";

    @Override
    public Object invoke(Object ignoredFromCompose) {
        try {
            Activity host = resolveTopActivity();
            if (host == null) {
                Log.w(TAG, "no top Activity resolvable; cannot open GOG hub");
                return kotlin.Unit.INSTANCE;
            }
            Class<?> hub = Class.forName(HUB_ACTIVITY);
            Intent intent = new Intent(host, hub);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            host.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "GOG menu click failed", t);
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
    public static void appendGogRowTo(Object menuList) {
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
            Log.w(TAG, "appendGogRowTo failed", t);
        }
    }

    // R8 renamed kotlin Function1 to Lpw6;; a Java `implements Function1` is a
    // different JVM type than the host's renamed one and fails the host's
    // isInstance() check. A Proxy actually implements the host interface and
    // delegates to invoke(). (Playbook pre9→pre10.)
    private static Object newFunction1Proxy(Class<?> pw6Cls) {
        final BhGogMenuRowClick handler = new BhGogMenuRowClick();
        return java.lang.reflect.Proxy.newProxyInstance(
            pw6Cls.getClassLoader(), new Class<?>[]{ pw6Cls },
            (proxy, method, args) -> {
                if ("invoke".equals(method.getName()) && method.getParameterCount() == 1) {
                    return handler.invoke(args != null && args.length > 0 ? args[0] : null);
                }
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "BhGogRowClickProxy";
                return null;
            });
    }
}
