package com.xj.winemu.sidebar;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

/** Static helpers called from WineActivity bytecode hooks. */
public class BhHudInjector {

    /**
     * Called from WineActivity.onResume(). Creates or shows/hides the correct
     * HUD overlay based on bh_prefs:
     *   winlator_hud=false              → all HUDs hidden
     *   hud=true, konkr=false, extra=false → BhFrameRating
     *   hud=true, konkr=false, extra=true  → BhDetailedHud
     *   hud=true, konkr=true               → BhKonkrHud (extra ignored)
     */
    public static void injectOrUpdate(Activity activity) {
        if (activity == null) return;
        Window win = activity.getWindow();
        if (win == null) return;
        View decorRaw = win.getDecorView();
        if (decorRaw == null) return;
        ViewGroup decorView = (ViewGroup) decorRaw;

        SharedPreferences sp = activity.getSharedPreferences("bh_prefs", 0);
        boolean hudOn       = sp.getBoolean("winlator_hud", false);
        boolean extraDetail = sp.getBoolean("hud_extra_detail", false);
        boolean konkrStyle  = sp.getBoolean("hud_konkr_style", false);

        // BhFrameRating — compact HUD (hud=true, extra=false, konkr=false)
        View frView = decorView.findViewWithTag("bh_frame_rating");
        boolean frShow = hudOn && !extraDetail && !konkrStyle;
        if (frView == null) {
            if (frShow) {
                BhFrameRating fr = new BhFrameRating(activity);
                fr.setTag("bh_frame_rating");
                fr.setVisibility(View.VISIBLE);
                decorView.addView(fr, makeHudParams());
            }
        } else {
            frView.setVisibility(frShow ? View.VISIBLE : View.GONE);
        }

        // BhDetailedHud — detailed two-row HUD (hud=true, extra=true, konkr=false)
        View dhView = decorView.findViewWithTag("bh_detailed_hud");
        boolean dhShow = hudOn && extraDetail && !konkrStyle;
        if (dhView == null) {
            if (dhShow) {
                BhDetailedHud dh = new BhDetailedHud(activity);
                dh.setTag("bh_detailed_hud");
                dh.setVisibility(View.VISIBLE);
                decorView.addView(dh, makeHudParams());
            }
        } else {
            dhView.setVisibility(dhShow ? View.VISIBLE : View.GONE);
        }

        // BhKonkrHud — Konkr-style HUD (hud=true, konkr=true)
        View khView = decorView.findViewWithTag("bh_konkr_hud");
        boolean khShow = hudOn && konkrStyle;
        if (khView == null) {
            if (khShow) {
                BhKonkrHud kh = new BhKonkrHud(activity);
                kh.setTag("bh_konkr_hud");
                kh.setVisibility(View.VISIBLE);
                decorView.addView(kh, makeHudParams());
            }
        } else {
            khView.setVisibility(khShow ? View.VISIBLE : View.GONE);
        }
    }

    private static FrameLayout.LayoutParams makeHudParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
    }

    /**
     * Called from WineActivity.onCreate(). Applies Sustained Performance Mode
     * and Max Adreno Clocks if enabled in bh_prefs. Exceptions are swallowed so
     * unsupported devices or missing root don't crash the container launch.
     */
    public static void onWineCreate(Activity activity) {
        try {
            SharedPreferences sp = activity.getSharedPreferences("bh_prefs", 0);

            if (sp.getBoolean("sustained_perf", false)) {
                activity.getWindow().setSustainedPerformanceMode(true);
                Runtime.getRuntime().exec(new String[]{
                        "su", "-c",
                        "for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor;" +
                                " do echo performance > $f; done"});
            }

            if (sp.getBoolean("max_adreno_clocks", false)) {
                Runtime.getRuntime().exec(new String[]{
                        "su", "-c",
                        "cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq" +
                                " > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq"});
            }
        } catch (Exception ignored) {
        }
    }
}
