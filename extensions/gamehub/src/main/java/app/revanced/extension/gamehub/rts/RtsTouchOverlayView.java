package app.revanced.extension.gamehub.rts;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import app.revanced.extension.gamehub.util.GHLog;
import com.winemu.openapi.WinUIBridge;
import com.xj.pcvirtualbtn.inputcontrols.InputControlsView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Transparent overlay that translates touch gestures into mouse/keyboard events
 * for RTS/strategy PC games running in the Wine emulator.
 *
 * Coordinate mapping: touch coords -> X11View local coords -> game window coords
 * via WinUIBridge.k (X11Controller) -> X11Controller.a (X11View).
 */
@SuppressLint("ViewConstructor")
public class RtsTouchOverlayView extends View {

    // Fixed thresholds (not worth exposing to users)
    private static final float DOUBLE_TAP_SLOP = 50f;
    private static final float MOVE_THRESHOLD = 5f;
    private static final float TWO_FINGER_CLASSIFY_THRESHOLD = 25f;
    private static final float PAN_KEY_THRESHOLD = 50f;

    // Mouse button codes for WinUIBridge.f0()
    private static final int BTN_NONE = 0;
    private static final int BTN_LEFT = 1;
    private static final int BTN_MIDDLE = 2;
    private static final int BTN_RIGHT = 3;

    private final WinUIBridge winUIBridge;
    private final InputControlsView inputControlsView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // X11View access via reflection (retries until successful)
    private View x11View;
    private Method getScreenSizeMethod;

    // Button hit testing via reflection (InputControlsView.w(float, float) -> ControlElement)
    private Method hitTestMethod;
    private boolean hitTestResolved;
    private boolean forwardingToButtons;

    // Single-finger state
    private boolean tracking;
    private boolean dragging;
    private float startX, startY;
    private float lastTouchX, lastTouchY;
    private long downTime;

    // Double-tap state
    private long lastTapTime;
    private float lastTapX, lastTapY;
    private boolean doubleTapPending;

    // Two-finger state — gesture is undecided until classified
    private boolean twoFingerActive;
    private boolean twoFingerClassified;
    private boolean twoFingerPanning;
    private boolean pinching;
    private float initialPinchDist;
    private float lastPinchDist;
    private float accumulatedZoom;
    private float twoFingerStartX, twoFingerStartY;
    private float twoFingerLastX, twoFingerLastY;
    private boolean panLeft, panRight, panUp, panDown;
    // Track which keycodes are actively held for each direction (for correct release).
    // -1 = no key pressed yet; prevents releasing keycode 0 on uninitialized state.
    private int activePanLeftKey = -1, activePanRightKey = -1, activePanUpKey = -1, activePanDownKey = -1;

    // Long press
    private boolean isLongPressed;

    private final Runnable longPressRunnable = () -> {
        if (tracking && !dragging && !twoFingerActive) {
            isLongPressed = true;
            if (RtsTouchPrefs.isGestureEnabled(RtsTouchPrefs.GESTURE_LONG_PRESS)) {
                doRightClick();
                GHLog.RTS.d("Long press -> right click");
            }
        }
    };

    public RtsTouchOverlayView(Context context, WinUIBridge winUIBridge, InputControlsView inputControlsView) {
        super(context);
        this.winUIBridge = winUIBridge;
        this.inputControlsView = inputControlsView;
        setClickable(true);
    }

    // -- X11View resolution ------------------------------------------------

    /**
     * Resolves the X11View via WinUIBridge.k (X11Controller) -> X11Controller.a (X11View).
     * Retries on each call until successful.
     */
    private void resolveX11View() {
        if (x11View != null && getScreenSizeMethod != null) return;
        try {
            // WinUIBridge.k -> X11Controller
            Field ctrlField = winUIBridge.getClass().getDeclaredField("k");
            ctrlField.setAccessible(true);
            Object x11Controller = ctrlField.get(winUIBridge);
            if (x11Controller == null) {
                GHLog.RTS.w("X11Controller is null (will retry)");
                return;
            }

            // X11Controller.a -> X11View
            Field viewField = x11Controller.getClass().getDeclaredField("a");
            viewField.setAccessible(true);
            Object view = viewField.get(x11Controller);
            if (view instanceof View) {
                x11View = (View) view;
                getScreenSizeMethod = x11View.getClass().getMethod("getScreenSize");
                GHLog.RTS.d("X11View resolved: " + x11View.getClass().getSimpleName());
            } else {
                GHLog.RTS.w("X11View field is not a View: " + (view != null ? view.getClass() : "null"));
            }
        } catch (Exception e) {
            GHLog.RTS.w("Failed to resolve X11View (will retry)", e);
        }
    }

    /**
     * Maps Android touch coordinates to game window coordinates.
     * Returns null if mapping fails.
     */
    private float[] mapToGameCoords(float touchX, float touchY) {
        resolveX11View();
        if (x11View == null || getScreenSizeMethod == null) return null;

        try {
            Point screenSize = (Point) getScreenSizeMethod.invoke(x11View);
            if (screenSize == null || screenSize.x <= 0 || screenSize.y <= 0) return null;

            float viewX = x11View.getX();
            float viewY = x11View.getY();
            int viewW = x11View.getWidth();
            int viewH = x11View.getHeight();
            if (viewW <= 0 || viewH <= 0) return null;

            float relX = touchX - viewX;
            float relY = touchY - viewY;
            float scaleX = (float) screenSize.x / viewW;
            float scaleY = (float) screenSize.y / viewH;
            float gameX = Math.max(0, Math.min(screenSize.x, relX * scaleX));
            float gameY = Math.max(0, Math.min(screenSize.y, relY * scaleY));
            return new float[] {gameX, gameY};
        } catch (Exception e) {
            GHLog.RTS.w("mapToGameCoords failed", e);
            return null;
        }
    }

    // -- Button hit testing ------------------------------------------------

    /**
     * Tests if the given coordinates hit a control element on the InputControlsView.
     * Uses reflection: InputControlsView.w(float, float) returns a ControlElement
     * (non-null means a button was hit).
     */
    private boolean hitTestButtons(float x, float y) {
        if (!hitTestResolved) {
            hitTestResolved = true;
            try {
                hitTestMethod = inputControlsView.getClass().getMethod("w", float.class, float.class);
                GHLog.RTS.d("Button hit test method resolved");
            } catch (NoSuchMethodException e) {
                GHLog.RTS.d("InputControlsView hit test not found, button pass-through disabled");
            }
        }
        if (hitTestMethod == null) return false;
        try {
            Object result = hitTestMethod.invoke(inputControlsView, x, y);
            return result != null;
        } catch (Exception e) {
            return false;
        }
    }

    // -- Touch event handling ----------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!RtsTouchPrefs.isEnabled()) return false;

        int action = event.getActionMasked();

        // Button pass-through: if ACTION_DOWN hits a game button, forward the entire gesture
        if (action == MotionEvent.ACTION_DOWN) {
            forwardingToButtons = hitTestButtons(event.getX(), event.getY());
        }
        if (forwardingToButtons) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                forwardingToButtons = false;
            }
            return false;
        }

        int pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return handleDown(event);

            case MotionEvent.ACTION_POINTER_DOWN:
                return handlePointerDown(event);

            case MotionEvent.ACTION_MOVE:
                if (pointerCount >= 2) {
                    return handleTwoFingerMove(event);
                } else if (tracking) {
                    return handleSingleMove(event);
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                return handlePointerUp(event);

            case MotionEvent.ACTION_UP:
                return handleUp(event);

            case MotionEvent.ACTION_CANCEL:
                endAllGestures();
                return true;

            default:
                return true;
        }
    }

    private boolean handleDown(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        startX = x;
        startY = y;
        lastTouchX = x;
        lastTouchY = y;
        downTime = System.currentTimeMillis();
        tracking = true;
        dragging = false;
        isLongPressed = false;
        doubleTapPending = false;
        twoFingerPanning = false;
        pinching = false;
        accumulatedZoom = 0;

        // Warp cursor to touch position
        warpCursorTo(x, y);

        // Check double-tap: if this DOWN is within timeout and slop of the last tap
        long now = System.currentTimeMillis();
        if (now - lastTapTime < getDoubleTapTimeout()
                && RtsTouchPrefs.isGestureEnabled(RtsTouchPrefs.GESTURE_DOUBLE_TAP)) {
            float dx = x - lastTapX;
            float dy = y - lastTapY;
            if (Math.sqrt(dx * dx + dy * dy) < DOUBLE_TAP_SLOP) {
                // Second tap of a double-tap: fire one more click (first was on previous UP)
                doClick();
                doubleTapPending = true;
                lastTapTime = 0; // Prevent triple-tap
                GHLog.RTS.d("Double tap -> double click");
                return true;
            }
        }

        handler.postDelayed(longPressRunnable, getLongPressTimeout());
        return true;
    }

    private boolean handlePointerDown(MotionEvent event) {
        handler.removeCallbacks(longPressRunnable);

        if (dragging) {
            releaseLeftButton();
            dragging = false;
        }

        if (event.getPointerCount() >= 2) {
            tracking = false;
            float midX = (event.getX(0) + event.getX(1)) / 2f;
            float midY = (event.getY(0) + event.getY(1)) / 2f;
            initialPinchDist = getPinchDistance(event);
            lastPinchDist = initialPinchDist;
            twoFingerStartX = midX;
            twoFingerStartY = midY;
            twoFingerLastX = midX;
            twoFingerLastY = midY;
            accumulatedZoom = 0;

            // Mark two-finger active but don't classify yet — wait for movement
            twoFingerActive = true;
            twoFingerClassified = false;
            twoFingerPanning = false;
            pinching = false;
        }
        return true;
    }

    private boolean handleSingleMove(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float dx = x - lastTouchX;
        float dy = y - lastTouchY;
        float moveDist = (float) Math.sqrt(dx * dx + dy * dy);

        if (moveDist < MOVE_THRESHOLD) return true;

        float fromStart = (float) Math.sqrt((x - startX) * (x - startX) + (y - startY) * (y - startY));

        if (!dragging && fromStart > getDragThreshold()) {
            handler.removeCallbacks(longPressRunnable);
            if (isLongPressed) return true;
            dragging = true;
            if (RtsTouchPrefs.isGestureEnabled(RtsTouchPrefs.GESTURE_DRAG)) {
                pressLeftButton();
            }
        }

        // Always warp cursor to follow finger
        warpCursorTo(x, y);
        lastTouchX = x;
        lastTouchY = y;
        return true;
    }

    private boolean handleTwoFingerMove(MotionEvent event) {
        if (event.getPointerCount() < 2) return true;

        float midX = (event.getX(0) + event.getX(1)) / 2f;
        float midY = (event.getY(0) + event.getY(1)) / 2f;
        float currentDist = getPinchDistance(event);

        // -- Classify gesture on first significant movement --
        if (!twoFingerClassified) {
            float distChange = Math.abs(currentDist - initialPinchDist);
            float midDX = midX - twoFingerStartX;
            float midDY = midY - twoFingerStartY;
            float panChange = (float) Math.sqrt(midDX * midDX + midDY * midDY);

            if (distChange > TWO_FINGER_CLASSIFY_THRESHOLD || panChange > TWO_FINGER_CLASSIFY_THRESHOLD) {
                twoFingerClassified = true;
                if (distChange > panChange && RtsTouchPrefs.isGestureEnabled(RtsTouchPrefs.GESTURE_PINCH)) {
                    // Fingers spreading/closing more than translating -> pinch
                    pinching = true;
                    lastPinchDist = currentDist;
                    GHLog.RTS.d("Two-finger: classified as PINCH");
                } else if (RtsTouchPrefs.isGestureEnabled(RtsTouchPrefs.GESTURE_TWO_FINGER_DRAG)) {
                    // Fingers moving together -> pan
                    twoFingerPanning = true;
                    int tfAction = RtsTouchPrefs.getGestureAction(RtsTouchPrefs.GESTURE_TWO_FINGER_DRAG);
                    if (tfAction == RtsTouchPrefs.TWO_FINGER_ACTION_MIDDLE_MOUSE) {
                        pressMiddleButton();
                    }
                    GHLog.RTS.d("Two-finger: classified as PAN");
                } else if (RtsTouchPrefs.isGestureEnabled(RtsTouchPrefs.GESTURE_PINCH)) {
                    // Pan disabled but pinch enabled — fall back to pinch
                    pinching = true;
                    lastPinchDist = currentDist;
                }
            }
            twoFingerLastX = midX;
            twoFingerLastY = midY;
            return true;
        }

        // -- Execute classified gesture --

        if (pinching) {
            float distDelta = currentDist - lastPinchDist;
            accumulatedZoom += distDelta;
            int pinchAction = RtsTouchPrefs.getGestureAction(RtsTouchPrefs.GESTURE_PINCH);
            while (Math.abs(accumulatedZoom) >= getZoomStep()) {
                boolean zoomIn = accumulatedZoom > 0; // spread = zoom in (natural gesture)
                switch (pinchAction) {
                    case RtsTouchPrefs.PINCH_ACTION_SCROLL:
                        sendScrollWheel(zoomIn ? 1 : -1);
                        break;
                    case RtsTouchPrefs.PINCH_ACTION_PLUS_MINUS:
                        sendKey(zoomIn ? KeyEvent.KEYCODE_NUMPAD_ADD : KeyEvent.KEYCODE_MINUS);
                        break;
                    case RtsTouchPrefs.PINCH_ACTION_PAGE_UP_DOWN:
                        sendKey(zoomIn ? KeyEvent.KEYCODE_PAGE_UP : KeyEvent.KEYCODE_PAGE_DOWN);
                        break;
                }
                accumulatedZoom -= (accumulatedZoom > 0 ? getZoomStep() : -getZoomStep());
            }
            lastPinchDist = currentDist;
        } else if (twoFingerPanning) {
            float panDX = midX - twoFingerStartX;
            float panDY = midY - twoFingerStartY;
            int tfAction = RtsTouchPrefs.getGestureAction(RtsTouchPrefs.GESTURE_TWO_FINGER_DRAG);

            switch (tfAction) {
                case RtsTouchPrefs.TWO_FINGER_ACTION_MIDDLE_MOUSE: {
                    float moveDX = (midX - twoFingerLastX) * -0.25f;
                    float moveDY = (midY - twoFingerLastY) * -0.25f;
                    sendRelativeMove(moveDX, moveDY);
                    break;
                }
                case RtsTouchPrefs.TWO_FINGER_ACTION_WASD:
                    updatePanKeys(
                            panDX,
                            panDY,
                            KeyEvent.KEYCODE_W,
                            KeyEvent.KEYCODE_S,
                            KeyEvent.KEYCODE_A,
                            KeyEvent.KEYCODE_D);
                    break;
                case RtsTouchPrefs.TWO_FINGER_ACTION_ARROWS:
                    updatePanKeys(
                            panDX,
                            panDY,
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT);
                    break;
            }
        }

        twoFingerLastX = midX;
        twoFingerLastY = midY;
        return true;
    }

    private boolean handlePointerUp(MotionEvent event) {
        endTwoFingerPan();
        return true;
    }

    private boolean handleUp(MotionEvent event) {
        handler.removeCallbacks(longPressRunnable);

        if (twoFingerActive || twoFingerPanning || pinching) {
            endTwoFingerPan();
        } else if (dragging) {
            if (RtsTouchPrefs.isGestureEnabled(RtsTouchPrefs.GESTURE_DRAG)) {
                releaseLeftButton();
            }
        } else if (!isLongPressed && tracking) {
            long elapsed = System.currentTimeMillis() - downTime;
            if (elapsed < getLongPressTimeout()) {
                if (doubleTapPending) {
                    // Second tap UP — suppress click (2nd click was already fired in handleDown)
                    doubleTapPending = false;
                } else if (RtsTouchPrefs.isGestureEnabled(RtsTouchPrefs.GESTURE_TAP)) {
                    doClick();
                    GHLog.RTS.d("Tap -> left click");

                    // Record for double-tap detection on next DOWN
                    lastTapTime = System.currentTimeMillis();
                    lastTapX = event.getX();
                    lastTapY = event.getY();
                }
            }
        }

        tracking = false;
        dragging = false;
        isLongPressed = false;
        twoFingerActive = false;
        twoFingerClassified = false;
        twoFingerPanning = false;
        pinching = false;
        return true;
    }

    private void endAllGestures() {
        handler.removeCallbacks(longPressRunnable);
        if (dragging) releaseLeftButton();
        endTwoFingerPan();
        tracking = false;
        dragging = false;
        isLongPressed = false;
        doubleTapPending = false;
        twoFingerActive = false;
        twoFingerClassified = false;
        twoFingerPanning = false;
        pinching = false;
    }

    // -- Pan key management ------------------------------------------------

    private void updatePanKeys(float dx, float dy, int upKey, int downKey, int leftKey, int rightKey) {
        boolean wantLeft = dx < -PAN_KEY_THRESHOLD;
        boolean wantRight = dx > PAN_KEY_THRESHOLD;
        boolean wantUp = dy < -PAN_KEY_THRESHOLD;
        boolean wantDown = dy > PAN_KEY_THRESHOLD;

        if (wantLeft != panLeft) {
            sendKeyEvent(leftKey, wantLeft);
            panLeft = wantLeft;
            if (wantLeft) activePanLeftKey = leftKey;
        }
        if (wantRight != panRight) {
            sendKeyEvent(rightKey, wantRight);
            panRight = wantRight;
            if (wantRight) activePanRightKey = rightKey;
        }
        if (wantUp != panUp) {
            sendKeyEvent(upKey, wantUp);
            panUp = wantUp;
            if (wantUp) activePanUpKey = upKey;
        }
        if (wantDown != panDown) {
            sendKeyEvent(downKey, wantDown);
            panDown = wantDown;
            if (wantDown) activePanDownKey = downKey;
        }
    }

    private void endTwoFingerPan() {
        // Release middle button if it was held
        int tfAction = RtsTouchPrefs.getGestureAction(RtsTouchPrefs.GESTURE_TWO_FINGER_DRAG);
        if (tfAction == RtsTouchPrefs.TWO_FINGER_ACTION_MIDDLE_MOUSE && twoFingerPanning) {
            releaseMiddleButton();
        }
        // Release whichever keys were actually pressed (tracked by activePan*Key fields)
        if (panLeft && activePanLeftKey >= 0) {
            sendKeyEvent(activePanLeftKey, false);
            panLeft = false;
        }
        if (panRight && activePanRightKey >= 0) {
            sendKeyEvent(activePanRightKey, false);
            panRight = false;
        }
        if (panUp && activePanUpKey >= 0) {
            sendKeyEvent(activePanUpKey, false);
            panUp = false;
        }
        if (panDown && activePanDownKey >= 0) {
            sendKeyEvent(activePanDownKey, false);
            panDown = false;
        }
        twoFingerPanning = false;
        pinching = false;
    }

    // -- Mouse dispatch ----------------------------------------------------

    /**
     * Warps the cursor to the given touch position using absolute game coordinates.
     */
    private void warpCursorTo(float touchX, float touchY) {
        float[] game = mapToGameCoords(touchX, touchY);
        if (game != null) {
            try {
                winUIBridge.f0(game[0], game[1], BTN_NONE, false, false);
            } catch (Exception e) {
                GHLog.RTS.w("warpCursorTo failed", e);
            }
        }
    }

    private void doClick() {
        try {
            // Confirm cursor position, then click
            warpCursorTo(lastTouchX, lastTouchY);
            winUIBridge.f0(0, 0, BTN_LEFT, true, true);
            winUIBridge.f0(0, 0, BTN_LEFT, false, true);
        } catch (Exception e) {
            GHLog.RTS.w("doClick failed", e);
        }
    }

    private void doRightClick() {
        try {
            winUIBridge.f0(0, 0, BTN_RIGHT, true, true);
            winUIBridge.f0(0, 0, BTN_RIGHT, false, true);
        } catch (Exception e) {
            GHLog.RTS.w("doRightClick failed", e);
        }
    }

    private void pressLeftButton() {
        try {
            winUIBridge.f0(0, 0, BTN_LEFT, true, true);
        } catch (Exception e) {
            GHLog.RTS.w("pressLeftButton failed", e);
        }
    }

    private void releaseLeftButton() {
        try {
            winUIBridge.f0(0, 0, BTN_LEFT, false, true);
        } catch (Exception e) {
            GHLog.RTS.w("releaseLeftButton failed", e);
        }
    }

    private void pressMiddleButton() {
        try {
            winUIBridge.f0(0, 0, BTN_MIDDLE, true, true);
        } catch (Exception e) {
            GHLog.RTS.w("pressMiddleButton failed", e);
        }
    }

    private void releaseMiddleButton() {
        try {
            winUIBridge.f0(0, 0, BTN_MIDDLE, false, true);
        } catch (Exception e) {
            GHLog.RTS.w("releaseMiddleButton failed", e);
        }
    }

    private void sendRelativeMove(float dx, float dy) {
        try {
            winUIBridge.f0(dx, dy, BTN_NONE, false, true);
        } catch (Exception e) {
            GHLog.RTS.w("sendRelativeMove failed", e);
        }
    }

    private void sendScrollWheel(int direction) {
        try {
            // Scroll direction encoded in Y coordinate; always button 4, single event.
            // Matches gamehub-lite: c0(0, direction * -2.0f, 4, false, true)
            winUIBridge.f0(0, direction * -2.0f, 4, false, true);
        } catch (Exception e) {
            GHLog.RTS.w("sendScrollWheel failed", e);
        }
    }

    // -- Keyboard dispatch via WinUIBridge.d0 ------------------------------

    private void sendKey(int keyCode) {
        sendKeyEvent(keyCode, true);
        sendKeyEvent(keyCode, false);
    }

    private void sendKeyEvent(int keyCode, boolean pressed) {
        try {
            winUIBridge.d0(0, keyCode, pressed);
        } catch (Exception e) {
            GHLog.RTS.w("sendKeyEvent failed", e);
        }
    }

    // -- Configurable timing/thresholds (read from prefs) -----------------

    private long getLongPressTimeout() {
        return RtsTouchPrefs.getTimingValue(RtsTouchPrefs.TIMING_LONG_PRESS_MS, RtsTouchPrefs.DEFAULT_LONG_PRESS_MS);
    }

    private long getDoubleTapTimeout() {
        return RtsTouchPrefs.getTimingValue(RtsTouchPrefs.TIMING_DOUBLE_TAP_MS, RtsTouchPrefs.DEFAULT_DOUBLE_TAP_MS);
    }

    private float getDragThreshold() {
        return RtsTouchPrefs.getTimingValue(RtsTouchPrefs.TIMING_DRAG_THRESHOLD, RtsTouchPrefs.DEFAULT_DRAG_THRESHOLD);
    }

    private float getZoomStep() {
        return RtsTouchPrefs.getTimingValue(RtsTouchPrefs.TIMING_ZOOM_STEP, RtsTouchPrefs.DEFAULT_ZOOM_STEP);
    }

    // -- Utility -----------------------------------------------------------

    private static float getPinchDistance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
