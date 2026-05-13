package com.xj.winemu.vibration;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

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
            String gameId = sniffGameIdFromStack();
            if (gameId != null && !gameId.isEmpty()) {
                intent.putExtra("gameId", gameId);
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
