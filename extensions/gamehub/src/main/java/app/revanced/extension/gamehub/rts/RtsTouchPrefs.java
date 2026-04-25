package app.revanced.extension.gamehub.rts;

import android.content.SharedPreferences;

import app.revanced.extension.gamehub.util.GHLog;
import com.blankj.utilcode.util.Utils;

import java.util.LinkedHashMap;

@SuppressWarnings("unused")
public class RtsTouchPrefs {

    private static final String PREFS_NAME = "rts_touch_prefs";
    private static final String PREFS_NAME_PROFILE_PREFIX = "rts_touch_prefs_";

    private static final String KEY_ENABLED = "rts_touch_controls_enabled";

    // Current game profile ID — set by RtsTouchHelper.initOverlay(), null = global fallback
    private static volatile String currentProfileId;

    // Per-gesture enabled keys
    private static final String KEY_GESTURE_ENABLED_PREFIX = "rts_gesture_enabled_";

    // Per-gesture action keys (for configurable gestures)
    private static final String KEY_GESTURE_ACTION_PREFIX = "rts_gesture_action_";

    // ── Gesture definitions ──────────────────────────────────────────────

    /** Gesture info: label (display name) and description (what it does, null for configurable). */
    public static final class GestureDef {
        public final String key;
        public final String label;
        public final String description; // null = has action spinner instead

        GestureDef(String key, String label, String description) {
            this.key = key;
            this.label = label;
            this.description = description;
        }
    }

    /** All gestures in display order. Key = pref key, value = definition. */
    public static final LinkedHashMap<String, GestureDef> GESTURES = new LinkedHashMap<>();

    // Gesture key constants (used by RtsTouchOverlayView for pref lookups)
    public static final String GESTURE_TAP = "TAP";
    public static final String GESTURE_LONG_PRESS = "LONG_PRESS";
    public static final String GESTURE_DOUBLE_TAP = "DOUBLE_TAP";
    public static final String GESTURE_DRAG = "DRAG";
    public static final String GESTURE_PINCH = "PINCH";
    public static final String GESTURE_TWO_FINGER_DRAG = "TWO_FINGER_DRAG";

    static {
        GESTURES.put(GESTURE_TAP, new GestureDef(GESTURE_TAP, "Tap", "Left Click"));
        GESTURES.put(GESTURE_LONG_PRESS, new GestureDef(GESTURE_LONG_PRESS, "Long Press", "Right Click"));
        GESTURES.put(GESTURE_DOUBLE_TAP, new GestureDef(GESTURE_DOUBLE_TAP, "Double Tap", "Double Click"));
        GESTURES.put(GESTURE_DRAG, new GestureDef(GESTURE_DRAG, "Drag", "Mouse Drag"));
        GESTURES.put(GESTURE_PINCH, new GestureDef(GESTURE_PINCH, "Pinch", null));
        GESTURES.put(GESTURE_TWO_FINGER_DRAG, new GestureDef(GESTURE_TWO_FINGER_DRAG, "Two-Finger Drag", null));
    }

    // ── Action options ───────────────────────────────────────────────────

    // Pinch actions
    public static final int PINCH_ACTION_SCROLL = 0;
    public static final int PINCH_ACTION_PLUS_MINUS = 1;
    public static final int PINCH_ACTION_PAGE_UP_DOWN = 2;
    public static final String[] PINCH_ACTION_LABELS = {"Scroll Wheel", "+/- Keys", "Page Up/Down"};

    // Two-finger drag actions
    public static final int TWO_FINGER_ACTION_MIDDLE_MOUSE = 0;
    public static final int TWO_FINGER_ACTION_WASD = 1;
    public static final int TWO_FINGER_ACTION_ARROWS = 2;
    public static final String[] TWO_FINGER_ACTION_LABELS = {"Middle Mouse", "WASD Keys", "Arrow Keys"};

    // ── Timing/threshold settings ────────────────────────────────────────

    private static final String KEY_TIMING_PREFIX = "rts_timing_";

    public static final String TIMING_LONG_PRESS_MS = "long_press_ms";
    public static final String TIMING_DOUBLE_TAP_MS = "double_tap_ms";
    public static final String TIMING_DRAG_THRESHOLD = "drag_threshold";
    public static final String TIMING_ZOOM_STEP = "zoom_step";

    public static final int DEFAULT_LONG_PRESS_MS = 300;
    public static final int DEFAULT_DOUBLE_TAP_MS = 250;
    public static final int DEFAULT_DRAG_THRESHOLD = 10;
    public static final int DEFAULT_ZOOM_STEP = 5;

    public static final String[] ALL_TIMINGS = {
        TIMING_LONG_PRESS_MS, TIMING_DOUBLE_TAP_MS, TIMING_DRAG_THRESHOLD, TIMING_ZOOM_STEP
    };

    // ── Profile scoping ──────────────────────────────────────────────────

    /**
     * Sets the current game profile ID. Called from RtsTouchHelper.initOverlay().
     * All settings are scoped to this profile.
     */
    public static void setProfileId(String profileId) {
        currentProfileId = profileId;
        GHLog.RTS.d("RTS prefs profile: " + (profileId != null ? profileId : "global"));
    }

    /** Profile-scoped prefs — all settings are per game. */
    private static SharedPreferences getPrefs() {
        String id = currentProfileId;
        String name = (id != null) ? PREFS_NAME_PROFILE_PREFIX + id : PREFS_NAME;
        return Utils.a().getSharedPreferences(name, android.content.Context.MODE_PRIVATE);
    }

    // ── Accessors ────────────────────────────────────────────────────────

    public static boolean isEnabled() {
        return getPrefs().getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        GHLog.RTS.d("RTS touch controls " + (enabled ? "enabled" : "disabled") + " (profile=" + currentProfileId + ")");
    }

    public static boolean isGestureEnabled(String gesture) {
        return getPrefs().getBoolean(KEY_GESTURE_ENABLED_PREFIX + gesture, true);
    }

    public static void setGestureEnabled(String gesture, boolean enabled) {
        getPrefs()
                .edit()
                .putBoolean(KEY_GESTURE_ENABLED_PREFIX + gesture, enabled)
                .apply();
    }

    public static int getGestureAction(String gesture) {
        return getPrefs().getInt(KEY_GESTURE_ACTION_PREFIX + gesture, 0);
    }

    public static void setGestureAction(String gesture, int action) {
        getPrefs().edit().putInt(KEY_GESTURE_ACTION_PREFIX + gesture, action).apply();
    }

    public static int getTimingValue(String key, int defaultValue) {
        return getPrefs().getInt(KEY_TIMING_PREFIX + key, defaultValue);
    }

    public static void setTimingValue(String key, int value) {
        getPrefs().edit().putInt(KEY_TIMING_PREFIX + key, value).apply();
    }

    public static void resetToDefaults() {
        SharedPreferences.Editor editor = getPrefs().edit();
        for (String gesture : GESTURES.keySet()) {
            editor.remove(KEY_GESTURE_ENABLED_PREFIX + gesture);
            editor.remove(KEY_GESTURE_ACTION_PREFIX + gesture);
        }
        for (String timing : ALL_TIMINGS) {
            editor.remove(KEY_TIMING_PREFIX + timing);
        }
        editor.apply();
        GHLog.RTS.d("RTS touch preferences reset to defaults");
    }
}
