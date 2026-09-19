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

    /** Binds a remote action target. */
    public KeyHandler(Target target) {
        this.target = target;
    }

    /** Consumes both halves of recognized keys so Android cannot also adjust volume. */
    public boolean dispatch(KeyEvent event) {
        int key = event.getKeyCode();
        if (!isHandled(key)) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.isCanceled()) {
            return true;
        }
        boolean direction = key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!direction && event.getRepeatCount() > 0) {
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: target.move(-1, 0); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: target.move(1, 0); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: target.move(0, -1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: target.move(0, 1); break;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_BUTTON_X: target.flag(); break;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_BUTTON_START: target.menu(); break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B: target.back(); break;
            default: target.confirm(); break;
        }
        return true;
    }

    private static boolean isHandled(int key) {
        return (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_CENTER)
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER
                || key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_BUTTON_X
                || key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN
                || key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_SETTINGS
                || key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_BACK
                || key == KeyEvent.KEYCODE_ESCAPE || key == KeyEvent.KEYCODE_BUTTON_B;
    }
}
