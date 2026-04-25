package app.revanced.extension.gamehub;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Html;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Toast;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * BannerHub Lite: CPU core multi-select dialog.
 *
 * Ported from BannerHub (5.3.5) CpuMultiSelectHelper.smali.
 * Invoked from SelectAndSingleInputDialog$Companion.f() when
 * contentType == CONTENT_TYPE_CORE_LIMIT (patch 14 in build workflow).
 *
 * Uses reflection to call 5.1.4 obfuscated methods:
 *   PcGameSettingDataHelper.a           (singleton field)
 *   PcGameSettingDataHelper.w(String)   (get PcGameSettingOperations)
 *   PcGameSettingOperations.h0()        (get SPUtils)
 *   PcGameSettingDataHelper.C(helper, type, null, 2, null) (get prefs key)
 *   PcGameSettingOperations.H(ops, 0, 1, null)             (read stored bitmask)
 *   SPUtils.m(String, int)              (write preference)
 *
 * After writing, fires callback.invoke(entity) so the settings list
 * updates the selected-item label immediately (matching BannerHub behaviour).
 */
public class CpuMultiSelectHelper {

    public static void show(View anchor, String gameId, int contentType, Object callback) {
        try {
            Context ctx = anchor.getContext();

            Class<?> dataHelperClass = Class.forName("com.xj.winemu.settings.PcGameSettingDataHelper");
            Class<?> opsClass       = Class.forName("com.xj.winemu.settings.PcGameSettingOperations");
            Class<?> spClass        = Class.forName("com.blankj.utilcode.util.SPUtils");

            Field helperField = dataHelperClass.getField("a");
            Object helper = helperField.get(null);

            // PcGameSettingOperations ops = helper.w(gameId)
            Object ops = dataHelperClass.getMethod("w", String.class).invoke(helper, gameId);

            // SPUtils sp = ops.h0()
            Object spUtils = opsClass.getMethod("h0").invoke(ops);

            // String key = PcGameSettingDataHelper.C(helper, contentType, null, 2, null)
            String key = (String) dataHelperClass
                    .getMethod("C", dataHelperClass, int.class, String.class, int.class, Object.class)
                    .invoke(null, helper, contentType, null, 2, null);

            // int currentMask = PcGameSettingOperations.H(ops, 0, 1, null)
            int currentMask = (Integer) opsClass
                    .getMethod("H", opsClass, int.class, int.class, Object.class)
                    .invoke(null, ops, 0, 1, null);

            Method writeMethod = spClass.getMethod("m", String.class, int.class);

            CharSequence[] labels = new CharSequence[8];
            labels[0] = Html.fromHtml("<small>Core 0 (Efficiency)</small>", 0);
            labels[1] = Html.fromHtml("<small>Core 1 (Efficiency)</small>", 0);
            labels[2] = Html.fromHtml("<small>Core 2 (Efficiency)</small>", 0);
            labels[3] = Html.fromHtml("<small>Core 3 (Efficiency)</small>", 0);
            labels[4] = Html.fromHtml("<small>Core 4 (Performance)</small>", 0);
            labels[5] = Html.fromHtml("<small>Core 5 (Performance)</small>", 0);
            labels[6] = Html.fromHtml("<small>Core 6 (Performance)</small>", 0);
            labels[7] = Html.fromHtml("<small>Core 7 (Prime)</small>", 0);

            boolean[] checked = new boolean[8];
            for (int i = 0; i < 8; i++) {
                checked[i] = (currentMask & (1 << i)) != 0;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
            builder.setTitle("CPU Core Limit");
            builder.setMultiChoiceItems(labels, checked,
                    (dialog, which, isChecked) -> checked[which] = isChecked);
            builder.setPositiveButton("Apply", (dialog, which) -> {
                int newMask = 0;
                for (int i = 0; i < 8; i++) {
                    if (checked[i]) newMask |= (1 << i);
                }
                if (newMask == 0) {
                    Toast.makeText(ctx, "Select at least one core", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (newMask == 0xFF) newMask = 0;  // all cores = No Limit
                try {
                    writeMethod.invoke(spUtils, key, newMask);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                fireCallback(callback, newMask);
            });
            builder.setNegativeButton("No Limit", (dialog, which) -> {
                try {
                    writeMethod.invoke(spUtils, key, 0);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                fireCallback(callback, 0);
            });
            builder.setNeutralButton("Cancel", null);

            AlertDialog dlg = builder.show();
            android.view.Window window = dlg.getWindow();
            if (window != null) {
                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                window.setLayout(dm.widthPixels / 2, dm.heightPixels * 9 / 10);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Fire the Function1 callback with a minimal DialogSettingListItemEntity
     * (id = mask, isSelected = true, all other fields = defaults).
     * This triggers the settings RecyclerView to refresh the selected-item label.
     */
    private static void fireCallback(Object callback, int mask) {
        if (callback == null) return;
        try {
            Object entity = buildEntity(mask);
            if (entity == null) return;
            // kotlin.jvm.functions.Function1.invoke(Object) -> Object
            callback.getClass().getMethod("invoke", Object.class).invoke(callback, entity);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Build a DialogSettingListItemEntity via the Kotlin defaults constructor.
     * Only id (param 0) and isSelected (param 2) are set explicitly.
     */
    private static Object buildEntity(int id) {
        try {
            Class<?> entityClass = Class.forName("com.xj.winemu.bean.DialogSettingListItemEntity");
            Class<?> dcmClass    = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker");

            // Find the Kotlin defaults constructor: real params + int mask + DCM
            Constructor<?> ctor = null;
            for (Constructor<?> c : entityClass.getDeclaredConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length > 2
                        && pt[pt.length - 1] == dcmClass
                        && pt[pt.length - 2] == int.class) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) return null;
            ctor.setAccessible(true);

            int totalParams    = ctor.getParameterCount();
            int numRealParams  = totalParams - 2; // subtract mask + DCM
            Class<?>[] pt      = ctor.getParameterTypes();
            Object[] args      = new Object[totalParams];

            // Fill defaults (null / 0 / false depending on type)
            for (int i = 0; i < numRealParams; i++) {
                Class<?> t = pt[i];
                if      (t == int.class)     args[i] = 0;
                else if (t == long.class)    args[i] = 0L;
                else if (t == boolean.class) args[i] = false;
                else                         args[i] = null;
            }

            // Set explicit params
            args[0] = id;    // id = bitmask value
            args[2] = true;  // isSelected = true

            // Kotlin default mask: all bits set except bit 0 (id) and bit 2 (isSelected)
            int defaultMask = (1 << numRealParams) - 1;
            defaultMask &= ~0x5; // clear bits 0 and 2
            args[numRealParams]     = defaultMask;
            args[numRealParams + 1] = null; // DefaultConstructorMarker = null

            return ctor.newInstance(args);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
