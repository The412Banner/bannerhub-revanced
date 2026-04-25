package com.xj.winemu.sidebar;

import android.view.View;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Wires the Task Manager sidebar button found in the patched layout. */
public class BhTaskClickHelper {

    public static void setup(View drawerContent) {
        try {
            int resId = drawerContent.getResources().getIdentifier(
                "bh_sidebar_taskmanager", "id",
                drawerContent.getContext().getPackageName());
            if (resId == 0) return;
            View btn = drawerContent.findViewById(resId);
            if (btn == null) return;

            Class<?> function0Class = Class.forName("kotlin.jvm.functions.Function0");
            Object clickListener = Proxy.newProxyInstance(
                drawerContent.getClass().getClassLoader(),
                new Class<?>[]{function0Class},
                (proxy, method, args) -> {
                    if ("invoke".equals(method.getName())) {
                        drawerContent.getClass()
                            .getMethod("U", String.class)
                            .invoke(drawerContent, "BhTaskManagerFragment");
                    }
                    return null;
                });

            for (Method m : btn.getClass().getMethods()) {
                if ("setClickListener".equals(m.getName()) && m.getParameterCount() == 1) {
                    m.invoke(btn, clickListener);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }
}
