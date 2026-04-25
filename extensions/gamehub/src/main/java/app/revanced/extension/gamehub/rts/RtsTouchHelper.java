package app.revanced.extension.gamehub.rts;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.revanced.extension.gamehub.util.GHLog;
import com.winemu.openapi.WinUIBridge;
import com.xj.pcvirtualbtn.inputcontrols.InputControlsView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Static helpers called from bytecode patches to integrate RTS touch controls
 * into WineActivity and SidebarControlsFragment.
 */
@SuppressWarnings("unused")
public class RtsTouchHelper {

    private static WeakReference<RtsTouchOverlayView> overlayRef;
    private static WeakReference<Activity> activityRef;
    private static WeakReference<WinUIBridge> winUIBridgeRef;

    /**
     * Called from WineActivity after InputControlsView is added to btnLayout.
     * Creates the RtsTouchOverlayView and adds it on top.
     */
    public static void initOverlay(
            Activity activity, ViewGroup parentLayout, WinUIBridge winUIBridge, InputControlsView inputControlsView) {
        try {
            if (winUIBridge == null) {
                GHLog.RTS.w("initOverlay: WinUIBridge is null, skipping");
                return;
            }

            activityRef = new WeakReference<>(activity);
            winUIBridgeRef = new WeakReference<>(winUIBridge);

            // Scope per-profile settings to this game
            RtsTouchPrefs.setProfileId(getCurrentProfileId());

            RtsTouchOverlayView overlay = new RtsTouchOverlayView(activity, winUIBridge, inputControlsView);
            overlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            boolean enabled = RtsTouchPrefs.isEnabled();
            overlay.setVisibility(enabled ? View.VISIBLE : View.GONE);

            parentLayout.addView(overlay);
            overlayRef = new WeakReference<>(overlay);

            // Disable screen trackpad when RTS starts enabled
            if (enabled) {
                disableTrackpad(winUIBridge);
            }

            GHLog.RTS.d("RTS overlay initialized, enabled=" + enabled + ", parent="
                    + parentLayout.getClass().getSimpleName() + ", childCount=" + parentLayout.getChildCount());
        } catch (Exception e) {
            GHLog.RTS.e("initOverlay failed", e);
        }
    }

    /**
     * Called from WineActivity.onDestroy to prevent memory leaks.
     */
    public static void cleanup() {
        overlayRef = null;
        activityRef = null;
        winUIBridgeRef = null;
        GHLog.RTS.d("RTS overlay cleaned up");
    }

    /**
     * Toggles the RTS overlay visibility, updates the preference,
     * and manages screen trackpad conflict.
     */
    public static void toggleOverlay(boolean enabled) {
        RtsTouchPrefs.setEnabled(enabled);
        RtsTouchOverlayView overlay = overlayRef != null ? overlayRef.get() : null;
        if (overlay != null) {
            overlay.setVisibility(enabled ? View.VISIBLE : View.GONE);
        } else {
            GHLog.RTS.w("toggleOverlay: overlay not available (game not started yet?)");
        }

        // Manage screen trackpad to prevent input conflicts
        WinUIBridge bridge = winUIBridgeRef != null ? winUIBridgeRef.get() : null;
        if (bridge != null) {
            if (enabled) {
                disableTrackpad(bridge);
            } else {
                restoreTrackpad(bridge);
            }
        }

        GHLog.RTS.d("RTS overlay toggled: " + enabled);
    }

    /**
     * Disables the screen trackpad via WinUIBridge.o0(false) and saves the
     * disabled state to the current game profile.
     */
    private static void disableTrackpad(WinUIBridge bridge) {
        try {
            bridge.o0(false);
        } catch (Exception e) {
            GHLog.RTS.w("Failed to disable trackpad via o0()", e);
        }
        try {
            String profileId = getCurrentProfileId();
            if (profileId != null) {
                Class<?> icm = Class.forName("com.xj.pcvirtualbtn.inputcontrols.InputControlsManager");
                icm.getMethod("h", boolean.class, String.class).invoke(null, false, profileId);
            }
        } catch (Exception e) {
            GHLog.RTS.w("Failed to save trackpad state for profile", e);
        }
    }

    /**
     * Restores the screen trackpad to whatever the game profile had saved.
     */
    private static void restoreTrackpad(WinUIBridge bridge) {
        try {
            String profileId = getCurrentProfileId();
            boolean trackpadEnabled = true; // default: re-enable trackpad if lookup fails
            if (profileId != null) {
                Class<?> icm = Class.forName("com.xj.pcvirtualbtn.inputcontrols.InputControlsManager");
                trackpadEnabled = (boolean) icm.getMethod("B", String.class).invoke(null, profileId);
            }
            bridge.o0(trackpadEnabled);
        } catch (Exception e) {
            GHLog.RTS.w("Failed to restore trackpad state", e);
        }
    }

    /**
     * Gets the current game profile ID from WineActivity.u.e().
     */
    private static String getCurrentProfileId() {
        try {
            Activity activity = activityRef != null ? activityRef.get() : null;
            if (activity == null) return null;
            Field uField = activity.getClass().getDeclaredField("u");
            uField.setAccessible(true);
            Object activityData = uField.get(activity);
            if (activityData == null) return null;
            return (String) activityData.getClass().getMethod("e").invoke(activityData);
        } catch (Exception e) {
            GHLog.RTS.w("Failed to get profile ID", e);
            return null;
        }
    }

    /**
     * Sets up the RTS controls in the sidebar fragment.
     * Finds the RTS switch and gear button by resource ID and wires up listeners.
     *
     * @param fragmentView the root view of SidebarControlsFragment
     */
    @SuppressWarnings("JavaReflectionMemberAccess")
    public static void setupSidebarControls(View fragmentView) {
        try {
            if (fragmentView == null) {
                GHLog.RTS.w("setupSidebarControls: fragmentView is null");
                return;
            }

            Context context = fragmentView.getContext();
            String pkg = context.getPackageName();
            boolean enabled = RtsTouchPrefs.isEnabled();

            // -- RTS toggle switch --
            int switchId = fragmentView.getResources().getIdentifier("switch_rts_touch_controls", "id", pkg);
            if (switchId == 0) {
                GHLog.RTS.w("setupSidebarControls: switch resource ID not found");
                return;
            }

            View rtsSwitch = fragmentView.findViewById(switchId);
            if (rtsSwitch == null) {
                GHLog.RTS.w("setupSidebarControls: switch view not found by ID " + switchId);
                return;
            }

            // Set initial state via reflection: setSwitch(boolean)
            Method setSwitch = rtsSwitch.getClass().getMethod("setSwitch", boolean.class);
            Method getSwitchState = rtsSwitch.getClass().getMethod("getSwitchState");
            setSwitch.invoke(rtsSwitch, enabled);

            // -- Gear button for gesture settings --
            int gearId = fragmentView.getResources().getIdentifier("btn_rts_gesture_settings", "id", pkg);
            View gearBtn = gearId != 0 ? fragmentView.findViewById(gearId) : null;
            if (gearBtn != null) {
                gearBtn.setVisibility(enabled ? View.VISIBLE : View.GONE);
                gearBtn.setOnClickListener(v -> showGestureConfigDialog(v.getContext()));
            }

            // Toggle click on switch
            rtsSwitch.setOnClickListener(v -> {
                try {
                    boolean current = (boolean) getSwitchState.invoke(v);
                    boolean newState = !current;
                    setSwitch.invoke(v, newState);
                    toggleOverlay(newState);
                    // Update gear button visibility
                    if (gearBtn != null) {
                        gearBtn.setVisibility(newState ? View.VISIBLE : View.GONE);
                    }
                } catch (Exception e) {
                    GHLog.RTS.w("RTS switch toggle failed", e);
                }
            });

            // Long press on switch also opens gesture config dialog
            rtsSwitch.setOnLongClickListener(v -> {
                showGestureConfigDialog(v.getContext());
                return true;
            });

            GHLog.RTS.d("Sidebar RTS controls set up, initial state=" + enabled);
        } catch (Exception e) {
            GHLog.RTS.e("setupSidebarControls failed", e);
        }
    }

    // -- Dialog colors (matching gamehub-lite dark theme) --
    private static final int COLOR_BG = 0xFF111827;
    private static final int COLOR_OVERLAY = 0xCC000000;
    private static final int COLOR_BLUE = 0xFF3B82F6;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HINT = 0xFF888E99;
    private static final int COLOR_SPINNER_BG = 0x0AFFFFFF;
    private static final int COLOR_CHECKBOX_OFF = 0xFF6B7280;

    /**
     * Shows the gesture configuration dialog styled to match the app's dark theme.
     */
    public static void showGestureConfigDialog(Context context) {
        try {
            Dialog dialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);

            // Root: dark overlay, tap outside to dismiss
            FrameLayout root = new FrameLayout(context);
            root.setBackgroundColor(COLOR_OVERLAY);
            root.setOnClickListener(v -> dialog.dismiss());

            // Dialog panel: dark rounded rectangle, centered, capped at 80% screen height
            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setOnClickListener(v -> {}); // consume clicks so overlay dismiss doesn't trigger
            GradientDrawable panelBg = new GradientDrawable();
            panelBg.setColor(COLOR_BG);
            panelBg.setCornerRadius(dpToPx(context, 8));
            panel.setBackground(panelBg);
            int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.8f);
            FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                    Math.min(
                            dpToPx(context, 460),
                            context.getResources().getDisplayMetrics().widthPixels - dpToPx(context, 32)),
                    maxH,
                    Gravity.CENTER);
            panel.setLayoutParams(panelLp);
            panel.setPadding(0, 0, 0, dpToPx(context, 16));

            // Title
            TextView title = new TextView(context);
            title.setText("RTS Gesture Settings");
            title.setTextColor(COLOR_TEXT);
            title.setTextSize(16);
            title.setTypeface(null, Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dpToPx(context, 14), 0, 0);
            panel.addView(title);

            // Scrollable gesture list
            ScrollView scrollView = new ScrollView(context);
            int hPad = dpToPx(context, 24);
            scrollView.setPadding(hPad, dpToPx(context, 16), hPad, 0);

            LinearLayout gestures = new LinearLayout(context);
            gestures.setOrientation(LinearLayout.VERTICAL);

            for (RtsTouchPrefs.GestureDef def : RtsTouchPrefs.GESTURES.values()) {
                addGestureRow(context, gestures, dialog, def);
            }

            // Timing section divider
            View divider = new View(context);
            divider.setBackgroundColor(0x1AFFFFFF);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
            divLp.setMargins(0, dpToPx(context, 12), 0, dpToPx(context, 4));
            gestures.addView(divider, divLp);

            // Section header
            TextView timingHeader = new TextView(context);
            timingHeader.setText("TIMING");
            timingHeader.setTextColor(COLOR_HINT);
            timingHeader.setTextSize(11);
            timingHeader.setLetterSpacing(0.1f);
            timingHeader.setPadding(0, dpToPx(context, 4), 0, dpToPx(context, 4));
            gestures.addView(timingHeader);

            // Stepper rows
            gestures.addView(createStepperRow(
                    context,
                    "Long Press",
                    RtsTouchPrefs.TIMING_LONG_PRESS_MS,
                    RtsTouchPrefs.DEFAULT_LONG_PRESS_MS,
                    100,
                    1000,
                    50,
                    "ms"));
            gestures.addView(createStepperRow(
                    context,
                    "Double Tap",
                    RtsTouchPrefs.TIMING_DOUBLE_TAP_MS,
                    RtsTouchPrefs.DEFAULT_DOUBLE_TAP_MS,
                    100,
                    500,
                    50,
                    "ms"));
            gestures.addView(createStepperRow(
                    context,
                    "Drag Threshold",
                    RtsTouchPrefs.TIMING_DRAG_THRESHOLD,
                    RtsTouchPrefs.DEFAULT_DRAG_THRESHOLD,
                    5,
                    50,
                    5,
                    "px"));
            gestures.addView(createStepperRow(
                    context,
                    "Zoom Sensitivity",
                    RtsTouchPrefs.TIMING_ZOOM_STEP,
                    RtsTouchPrefs.DEFAULT_ZOOM_STEP,
                    1,
                    20,
                    1,
                    "px"));

            scrollView.addView(gestures);
            // Weight=1 makes ScrollView fill remaining space and scroll when content overflows
            panel.addView(scrollView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            // Close button
            TextView closeBtn = new TextView(context);
            closeBtn.setText("Close");
            closeBtn.setTextColor(COLOR_TEXT);
            closeBtn.setTextSize(14);
            closeBtn.setGravity(Gravity.CENTER);
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(COLOR_BLUE);
            btnBg.setCornerRadius(dpToPx(context, 4));
            closeBtn.setBackground(btnBg);
            LinearLayout.LayoutParams btnLp =
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(context, 44));
            int btnMargin = dpToPx(context, 24);
            btnLp.setMargins(btnMargin, dpToPx(context, 16), btnMargin, 0);
            closeBtn.setLayoutParams(btnLp);
            closeBtn.setOnClickListener(v -> dialog.dismiss());
            panel.addView(closeBtn);

            root.addView(panel);
            dialog.setContentView(root);
            dialog.show();

            // Full-screen window
            android.view.Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
        } catch (Exception e) {
            GHLog.RTS.e("showGestureConfigDialog failed", e);
        }
    }

    private static void addGestureRow(
            Context context, LinearLayout parent, Dialog mainDialog, RtsTouchPrefs.GestureDef def) {
        String gesture = def.key;
        boolean enabled = RtsTouchPrefs.isGestureEnabled(gesture);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dpToPx(context, 48));

        // Checkbox with blue/gray tinting
        CheckBox cb = new CheckBox(context);
        cb.setChecked(enabled);
        cb.setButtonTintList(new ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}}, new int[] {COLOR_BLUE, COLOR_CHECKBOX_OFF}));
        final String g = gesture;
        cb.setOnCheckedChangeListener((v, isChecked) -> RtsTouchPrefs.setGestureEnabled(g, isChecked));

        // Gesture name (left)
        TextView tv = new TextView(context);
        tv.setText(def.label);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(14);
        tv.setPadding(dpToPx(context, 8), 0, 0, 0);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(cb);
        row.addView(tv);

        // Right side: action spinner for configurable gestures, static description for others
        if (gesture.equals(RtsTouchPrefs.GESTURE_PINCH)) {
            row.addView(createActionSpinner(context, mainDialog, gesture, RtsTouchPrefs.PINCH_ACTION_LABELS));
        } else if (gesture.equals(RtsTouchPrefs.GESTURE_TWO_FINGER_DRAG)) {
            row.addView(createActionSpinner(context, mainDialog, gesture, RtsTouchPrefs.TWO_FINGER_ACTION_LABELS));
        } else if (def.description != null) {
            TextView desc = new TextView(context);
            desc.setText(def.description);
            desc.setTextColor(COLOR_HINT);
            desc.setTextSize(12);
            row.addView(desc);
        }

        parent.addView(row);
    }

    private static TextView createActionSpinner(Context context, Dialog mainDialog, String gesture, String[] labels) {
        int currentAction = RtsTouchPrefs.getGestureAction(gesture);

        TextView spinner = new TextView(context);
        spinner.setText(labels[currentAction] + "  \u25BE");
        spinner.setTextColor(COLOR_HINT);
        spinner.setTextSize(12);
        spinner.setGravity(Gravity.CENTER);
        spinner.setMinWidth(dpToPx(context, 120));
        int sp = dpToPx(context, 8);
        spinner.setPadding(sp, dpToPx(context, 6), sp, dpToPx(context, 6));

        GradientDrawable spinnerBg = new GradientDrawable();
        spinnerBg.setColor(COLOR_SPINNER_BG);
        spinnerBg.setCornerRadius(dpToPx(context, 4));
        spinner.setBackground(spinnerBg);

        spinner.setOnClickListener(v -> showActionPicker(context, gesture, labels, spinner));
        return spinner;
    }

    /**
     * Shows a dark-themed action picker dialog with checkmark on the current selection.
     */
    private static void showActionPicker(Context context, String gesture, String[] labels, TextView spinnerToUpdate) {
        Dialog picker = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);

        FrameLayout overlay = new FrameLayout(context);
        overlay.setBackgroundColor(0x80000000);
        overlay.setOnClickListener(v -> picker.dismiss());

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setOnClickListener(v -> {}); // consume
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_BG);
        bg.setCornerRadius(dpToPx(context, 8));
        panel.setBackground(bg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dpToPx(context, 300), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panel.setLayoutParams(panelLp);
        int pad = dpToPx(context, 8);
        panel.setPadding(0, pad, 0, pad);

        int currentAction = RtsTouchPrefs.getGestureAction(gesture);

        for (int i = 0; i < labels.length; i++) {
            final int index = i;

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dpToPx(context, 48));
            row.setPadding(dpToPx(context, 16), 0, dpToPx(context, 16), 0);

            TextView text = new TextView(context);
            text.setText(labels[i]);
            text.setTextColor(COLOR_TEXT);
            text.setTextSize(14);
            text.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(text);

            // Checkmark for current selection
            if (i == currentAction) {
                TextView check = new TextView(context);
                check.setText("\u2713"); // checkmark
                check.setTextColor(COLOR_BLUE);
                check.setTextSize(18);
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                RtsTouchPrefs.setGestureAction(gesture, index);
                spinnerToUpdate.setText(labels[index] + "  \u25BE");
                picker.dismiss();
            });

            panel.addView(row);
        }

        overlay.addView(panel);
        picker.setContentView(overlay);
        picker.show();

        android.view.Window w = picker.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    /**
     * Creates a stepper row: [ label ]  [ - ]  value  [ + ]
     */
    private static LinearLayout createStepperRow(
            Context context, String label, String prefKey, int defaultVal, int min, int max, int step, String unit) {
        int current = RtsTouchPrefs.getTimingValue(prefKey, defaultVal);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dpToPx(context, 44));

        // Label
        TextView labelTv = new TextView(context);
        labelTv.setText(label);
        labelTv.setTextColor(COLOR_TEXT);
        labelTv.setTextSize(13);
        labelTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Value display
        TextView valueTv = new TextView(context);
        valueTv.setText(current + unit);
        valueTv.setTextColor(COLOR_TEXT);
        valueTv.setTextSize(13);
        valueTv.setGravity(Gravity.CENTER);
        valueTv.setMinWidth(dpToPx(context, 56));

        // Minus button
        TextView minus = createStepperButton(context, "\u2212");
        minus.setOnClickListener(v -> {
            int cur = RtsTouchPrefs.getTimingValue(prefKey, defaultVal);
            int newVal = Math.max(min, cur - step);
            RtsTouchPrefs.setTimingValue(prefKey, newVal);
            valueTv.setText(newVal + unit);
        });

        // Plus button
        TextView plus = createStepperButton(context, "+");
        plus.setOnClickListener(v -> {
            int cur = RtsTouchPrefs.getTimingValue(prefKey, defaultVal);
            int newVal = Math.min(max, cur + step);
            RtsTouchPrefs.setTimingValue(prefKey, newVal);
            valueTv.setText(newVal + unit);
        });

        row.addView(labelTv);
        row.addView(minus);
        row.addView(valueTv);
        row.addView(plus);
        return row;
    }

    private static TextView createStepperButton(Context context, String text) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextColor(COLOR_TEXT);
        btn.setTextSize(16);
        btn.setGravity(Gravity.CENTER);
        int size = dpToPx(context, 32);
        btn.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_SPINNER_BG);
        bg.setCornerRadius(dpToPx(context, 4));
        btn.setBackground(bg);
        return btn;
    }

    private static int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
