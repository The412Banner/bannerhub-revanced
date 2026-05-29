package com.xj.winemu.audio;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import kotlin.jvm.functions.Function1;

/**
 * Launches {@link BhAudioSettingsActivity}. Invoked from the Banner Tools
 * dialog's "Audio" tile (dispatch case). GLOBAL toggle — no per-game id is
 * needed, so this is a trimmed clone of {@code BhRendererMenuRowClick}
 * (same activity-stack resolution, minus the gameId plumbing).
 */
public final class BhAudioMenuRowClick implements Function1<Object, Object> {

    private static final String TAG = "BhAudioRow";

    @Override
    public Object invoke(Object ignoredFromCompose) {
        try {
            Activity host = resolveTopActivity();
            if (host == null) {
                Log.w(TAG, "no top Activity resolvable; cannot launch settings");
                return kotlin.Unit.INSTANCE;
            }
            Intent intent = new Intent(host, BhAudioSettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            host.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "menu click failed", t);
        }
        return kotlin.Unit.INSTANCE;
    }

    /** Walk ActivityThread.mActivities for a live activity to use as launch Context. */
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
                try {
                    Field fAct = record.getClass().getDeclaredField("activity");
                    fAct.setAccessible(true);
                    Object a = fAct.get(record);
                    if (!(a instanceof Activity)) continue;
                    Activity activity = (Activity) a;
                    // Prefer a not-finishing activity; fall back to last seen.
                    if (!activity.isFinishing()) best = activity;
                    else if (best == null) best = activity;
                } catch (Throwable ignored) { }
            }
            return best;
        } catch (Throwable t) {
            Log.w(TAG, "resolveTopActivity failed", t);
            return null;
        }
    }
}
