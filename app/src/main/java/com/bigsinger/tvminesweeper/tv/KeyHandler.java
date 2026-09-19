package com.bigsinger.tvminesweeper.tv;

import android.view.KeyEvent;

/** Single routing point for remote keys in both the board and modal dialogs. */
public final class KeyHandler {
    /** Screen-level actions; modal routing is provided by the activity. */
    public interface Target {
        void move(int rowDelta, int columnDelta);
        void confirm();
        void flag();
        void menu();
        void back();
    }

    private final Target target;
    private static final int[] MENU_KEYS = {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_TV_CONTENTS_MENU,
            KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU
    };
    private final long[] menuPressTimes = new long[MENU_KEYS.length];

    /** Binds a remote action target. */
    public KeyHandler(Target target) {
        this.target = target;
        for (int index = 0; index < menuPressTimes.length; index++) {
            menuPressTimes[index] = Long.MIN_VALUE;
        }
    }

    /** Routes remote controls while leaving all volume keys to Android. */
    public boolean dispatch(KeyEvent event) {
        return dispatch(resolveKeyCode(event.getKeyCode(), event.getScanCode()), event.getAction(),
                event.getRepeatCount(), event.isCanceled(), event.getDownTime());
    }

    static int resolveKeyCode(int keyCode, int scanCode) {
        // AOSP Generic.kl maps Linux scan code 139 to MENU. Only repair missing mappings;
        // never reinterpret valid HOME, APP_SWITCH, volume or vendor-mapped key codes.
        return keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode == 139
                ? KeyEvent.KEYCODE_MENU : keyCode;
    }

    // Primitive input keeps device-specific key routing testable without Android key mocks.
    boolean dispatch(int key, int action, int repeatCount, boolean canceled, long downTime) {
        int menuIndex = menuIndex(key);
        if (menuIndex >= 0) {
            if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
                boolean freshPress = menuPressTimes[menuIndex] != downTime;
                menuPressTimes[menuIndex] = downTime;
                if (freshPress && !canceled && repeatCount == 0) {
                    // downTime identifies the physical press across Activity/Dialog focus changes.
                    // A release without a matching down remains a valid MENU-only-up remote press.
                    target.menu();
                }
            }
            return true;
        }
        if (!isHandled(key)) {
            return false;
        }
        if (action != KeyEvent.ACTION_DOWN || canceled) {
            return true;
        }
        boolean direction = key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!direction && repeatCount > 0) {
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: target.move(-1, 0); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: target.move(1, 0); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: target.move(0, -1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: target.move(0, 1); break;
            case KeyEvent.KEYCODE_BUTTON_X: target.flag(); break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B: target.back(); break;
            default: target.confirm(); break;
        }
        return true;
    }

    private static int menuIndex(int key) {
        for (int index = 0; index < MENU_KEYS.length; index++) {
            if (MENU_KEYS[index] == key) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isHandled(int key) {
        return (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_CENTER)
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER
                || key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_BUTTON_X
                || key == KeyEvent.KEYCODE_BACK
                || key == KeyEvent.KEYCODE_ESCAPE || key == KeyEvent.KEYCODE_BUTTON_B;
    }
}
