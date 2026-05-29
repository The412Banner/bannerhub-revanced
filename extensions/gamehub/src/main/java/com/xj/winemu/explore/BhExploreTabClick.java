package com.xj.winemu.explore;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Smali-callable hijack hook for GameHub 6.0.4's bottom-nav "Explore" tab.
 *
 * Injected at the head of the bottom-nav controller's tab-select dispatch
 * {@code w1a.q(Lyw9;)V} by {@code ExploreTabHijackPatch}:
 * <pre>
 *   move-object/from16 v0, p1
 *   invoke-static {v0}, Lcom/xj/winemu/explore/BhExploreTabClick;->maybeHijack(Ljava/lang/Object;)Z
 *   move-result v0
 *   if-eqz v0, :continue
 *   return-void
 *   :continue
 *   ... original q() body ...
 * </pre>
 *
 * {@code q()} is the convergence point for BOTH the UI tap (n(r1a) → q) and
 * programmatic deep-links (zu9 home_tab_selection_request → q), and w1a is a
 * SINGLE shared ViewModel across handheld + explore modes — so one seam
 * covers every route in both modes (GOG_LIBRARY_TAB_DESIGN §42). The default
 * tab is seeded directly into state by w1a's ctor (not via q()), so this
 * never fires on cold start.
 *
 * yw9 is the live selected-tab enum: HOME(0)=the "Explore" bar item, PLAY(1),
 * LEADERBOARD(2), LIBRARY(3), PROFILE(4). We hijack ordinal 0 only.
 *
 * FAIL-SAFE: any failure (not the explore tab, no resolvable Activity, missing
 * class, throw) returns {@code false} → GameHub falls through to its own native
 * Explore screen. We never crash the nav bar.
 *
 * resolveTopActivity() is the same ActivityThread walk used by
 * {@link com.xj.winemu.gog.BhGogMenuRowClick} (w1a is a Context-less
 * ViewModel, so we resolve the foreground Activity ourselves).
 */
public final class BhExploreTabClick {

    private static final String TAG = "BhExploreTab";

    /** yw9.a = HOME = the "Explore" bottom-nav bar item. */
    private static final int EXPLORE_TAB_ORDINAL = 0;

    private static final String EXPLORE_ACTIVITY =
        "app.revanced.extension.gamehub.explore.BannerExploreActivity";

    private BhExploreTabClick() { }

    /**
     * @param selectedTab the {@code yw9} enum value passed to {@code q(Lyw9;)V}
     * @return {@code true} if we opened our own Explore screen (caller must
     *         {@code return} and skip the native tab switch); {@code false} to
     *         let GameHub proceed normally.
     */
    public static boolean maybeHijack(Object selectedTab) {
        try {
            if (!(selectedTab instanceof Enum)) return false;
            if (((Enum<?>) selectedTab).ordinal() != EXPLORE_TAB_ORDINAL) return false;

            Activity host = resolveTopActivity();
            if (host == null) {
                Log.w(TAG, "no top Activity resolvable; deferring to native Explore");
                return false;
            }
            Class<?> screen = Class.forName(EXPLORE_ACTIVITY);
            Intent intent = new Intent(host, screen);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            host.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Explore hijack failed; falling back to native Explore", t);
            return false;
        }
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
}
