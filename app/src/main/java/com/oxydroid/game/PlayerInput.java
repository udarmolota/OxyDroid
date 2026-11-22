package com.oxydroid.game;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.oxydroid.controls.InputSink;

import java.lang.reflect.Method;

/**
 * The on-screen controls' input, delivered to Unity's Android player as Android input events.
 *
 * Oxygen Not Included reads Unity's legacy Input Manager, which the Android player fills from the
 * events its view receives. So the overlay's virtual mouse and keyboard become what a USB mouse and
 * keyboard would send: mouse MotionEvents (hover moves, button presses with a button state, the
 * wheel) and KeyEvents, handed to {@code UnityPlayer.injectEvent} through the player activity's
 * {@code injectInputEvent}. The same approach as RimDroid's native engine.
 *
 * Positions arrive as fractions of the game rect and go out in the player view's own pixels: the
 * player scales them by its view size itself, whatever the render resolution.
 *
 * Gamepad input is not delivered: the game's only gamepad path is Steam Input.
 */
public final class PlayerInput implements InputSink.Backend {
    private static final String TAG = "OxyDroid/PlayerInput";

    private final Context activity;
    private final View area;                 // the overlay: same size as the player's view
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Method inject;             // NativeUnityActivity.injectInputEvent, by reflection:
                                             // the app module cannot depend on the unity module
    private float x, y;                      // the virtual pointer, view px
    private int buttonState;                 // MotionEvent.BUTTON_* held
    private int metaState;                   // KeyEvent.META_* held
    private long downTime;

    public PlayerInput(Context activity, View area) {
        this.activity = activity;
        this.area = area;
        Method m = null;
        try {
            m = activity.getClass().getMethod("injectInputEvent", InputEvent.class);
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "the player activity has no injectInputEvent: on-screen input is off", e);
        }
        inject = m;
    }

    private void send(InputEvent e) {
        if (inject == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {   // the player takes input on the UI thread
            ui.post(() -> send(e));
            return;
        }
        try {
            inject.invoke(activity, e);
        } catch (ReflectiveOperationException ex) {
            Log.w(TAG, "inject failed", ex);
        }
    }

    private MotionEvent mouse(int action, float vscroll) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = buttonState != 0 ? 1f : 0f;
        c.size = 1f;
        if (vscroll != 0) c.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        return MotionEvent.obtain(downTime, now, action, 1, new MotionEvent.PointerProperties[] { p },
                new MotionEvent.PointerCoords[] { c }, metaState, buttonState, 1f, 1f,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    private void moved() {
        send(mouse(buttonState != 0 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE, 0));
    }

    private static float clamp(float v, float max) {
        return v < 0 ? 0 : (v > max ? max : v);
    }

    @Override public void mousePosition(float fx, float fy) {
        x = clamp(fx * area.getWidth(), Math.max(0, area.getWidth() - 1));
        y = clamp(fy * area.getHeight(), Math.max(0, area.getHeight() - 1));
        moved();
    }

    @Override public void mouseDelta(float dx, float dy) {
        x = clamp(x + dx * area.getWidth(), Math.max(0, area.getWidth() - 1));
        y = clamp(y + dy * area.getHeight(), Math.max(0, area.getHeight() - 1));
        moved();
    }

    @Override public void mouseButton(int glfwButton, boolean pressed) {
        int bit;
        switch (glfwButton) {
            case 0: bit = MotionEvent.BUTTON_PRIMARY; break;
            case 1: bit = MotionEvent.BUTTON_SECONDARY; break;
            case 2: bit = MotionEvent.BUTTON_TERTIARY; break;
            default: return;
        }
        if (pressed) {
            if ((buttonState & bit) != 0) return;
            boolean first = buttonState == 0;
            // The game takes the pointer's position from the last move it saw: tell it where the
            // press lands before pressing, or a click after a jump lands where the pointer was.
            if (first) moved();
            buttonState |= bit;
            if (first) downTime = SystemClock.uptimeMillis();
            send(mouse(first ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_MOVE, 0));
        } else {
            if ((buttonState & bit) == 0) return;
            buttonState &= ~bit;
            send(mouse(buttonState == 0 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, 0));
        }
    }

    @Override public void mouseScroll(float notches) {
        if (notches == 0 || Float.isNaN(notches) || Float.isInfinite(notches)) return;
        send(mouse(MotionEvent.ACTION_SCROLL, notches));
    }

    @Override public void key(int glfwKey, boolean pressed) {
        int code = androidKeyCode(glfwKey);
        if (code == KeyEvent.KEYCODE_UNKNOWN) {
            Log.w(TAG, "no Android key for GLFW key " + glfwKey);
            return;
        }
        int meta = metaFor(code);
        if (meta != 0) metaState = pressed ? (metaState | meta) : (metaState & ~meta);
        long now = SystemClock.uptimeMillis();
        send(new KeyEvent(now, now, pressed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    /** Lets go of everything held (the game goes to the background, the overlay is hidden). */
    public void releaseAll() {
        if (buttonState != 0) {
            buttonState = 0;
            send(mouse(MotionEvent.ACTION_UP, 0));
        }
        metaState = 0;
    }

    @Override public void gamepadAxis(int axis, float value) {}
    @Override public void gamepadDpad(int mask) {}
    @Override public void gamepadButton(int button, boolean pressed) {}

    // Unknown, on purpose: "not locked" would be read as a menu and "locked" as mouse look, and the
    // mouse stick would stop moving the pointer. This game never holds the mouse.
    @Override public int mouseLocked() { return -1; }
    @Override public int cursorHidden() { return -1; }

    private static int metaFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_SHIFT_LEFT: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
            case KeyEvent.KEYCODE_CTRL_LEFT: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
            default: return 0;
        }
    }

    /** GLFW key number (the controls' bindings) to Android key code; the player maps those to Unity's KeyCode. */
    static int androidKeyCode(int glfw) {
        if (glfw >= 65 && glfw <= 90) return KeyEvent.KEYCODE_A + (glfw - 65);          // A..Z
        if (glfw >= 48 && glfw <= 57) return KeyEvent.KEYCODE_0 + (glfw - 48);          // 0..9
        if (glfw >= 290 && glfw <= 301) return KeyEvent.KEYCODE_F1 + (glfw - 290);      // F1..F12
        if (glfw >= 320 && glfw <= 329) return KeyEvent.KEYCODE_NUMPAD_0 + (glfw - 320); // keypad 0..9
        switch (glfw) {
            case 32: return KeyEvent.KEYCODE_SPACE;
            case 39: return KeyEvent.KEYCODE_APOSTROPHE;
            case 44: return KeyEvent.KEYCODE_COMMA;
            case 45: return KeyEvent.KEYCODE_MINUS;
            case 46: return KeyEvent.KEYCODE_PERIOD;
            case 47: return KeyEvent.KEYCODE_SLASH;
            case 59: return KeyEvent.KEYCODE_SEMICOLON;
            case 61: return KeyEvent.KEYCODE_EQUALS;
            case 91: return KeyEvent.KEYCODE_LEFT_BRACKET;
            case 92: return KeyEvent.KEYCODE_BACKSLASH;
            case 93: return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case 96: return KeyEvent.KEYCODE_GRAVE;
            case 256: return KeyEvent.KEYCODE_ESCAPE;
            case 257: return KeyEvent.KEYCODE_ENTER;
            case 258: return KeyEvent.KEYCODE_TAB;
            case 259: return KeyEvent.KEYCODE_DEL;
            case 260: return KeyEvent.KEYCODE_INSERT;
            case 261: return KeyEvent.KEYCODE_FORWARD_DEL;
            case 262: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 263: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 264: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 265: return KeyEvent.KEYCODE_DPAD_UP;
            case 266: return KeyEvent.KEYCODE_PAGE_UP;
            case 267: return KeyEvent.KEYCODE_PAGE_DOWN;
            case 268: return KeyEvent.KEYCODE_MOVE_HOME;
            case 269: return KeyEvent.KEYCODE_MOVE_END;
            case 330: return KeyEvent.KEYCODE_NUMPAD_DOT;
            case 331: return KeyEvent.KEYCODE_NUMPAD_DIVIDE;
            case 332: return KeyEvent.KEYCODE_NUMPAD_MULTIPLY;
            case 333: return KeyEvent.KEYCODE_NUMPAD_SUBTRACT;
            case 334: return KeyEvent.KEYCODE_NUMPAD_ADD;
            case 335: return KeyEvent.KEYCODE_NUMPAD_ENTER;
            case 340: return KeyEvent.KEYCODE_SHIFT_LEFT;
            case 341: return KeyEvent.KEYCODE_CTRL_LEFT;
            case 342: return KeyEvent.KEYCODE_ALT_LEFT;
            case 344: return KeyEvent.KEYCODE_SHIFT_RIGHT;
            case 345: return KeyEvent.KEYCODE_CTRL_RIGHT;
            case 346: return KeyEvent.KEYCODE_ALT_RIGHT;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }
}
