package com.bigsinger.tvminesweeper.tv;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证遥控器事件配对和系统按键放行，不需要 Android KeyEvent 实例。 */
public class KeyHandlerTest {
    @Test public void menuDownOpensImmediatelyAndItsUpDoesNotToggleAgain() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.down(KeyEvent.KEYCODE_MENU, 0));
        assertEquals(1, fixture.menus);
        assertTrue(fixture.up(KeyEvent.KEYCODE_MENU));
        assertEquals(1, fixture.menus);
        fixture.down(KeyEvent.KEYCODE_MENU, 0);
        fixture.up(KeyEvent.KEYCODE_MENU);
        assertEquals(2, fixture.menus);
    }

    @Test public void menuOnlyUpOpensForRemoteThatOmitsDown() {
        Fixture fixture = new Fixture();
        fixture.up(KeyEvent.KEYCODE_MENU);
        fixture.up(KeyEvent.KEYCODE_MENU);
        assertEquals(2, fixture.menus);
    }

    @Test public void menuLongPressAndCanceledReleaseDoNotToggleRepeatedly() {
        Fixture fixture = new Fixture();
        fixture.down(KeyEvent.KEYCODE_MENU, 0);
        fixture.down(KeyEvent.KEYCODE_MENU, 1);
        fixture.down(KeyEvent.KEYCODE_MENU, 2);
        fixture.release(KeyEvent.KEYCODE_MENU, true);
        assertEquals(1, fixture.menus);
        fixture.up(KeyEvent.KEYCODE_MENU);
        assertEquals(2, fixture.menus);
    }

    @Test public void canceledUpWithoutDownNeverOpensMenu() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.release(KeyEvent.KEYCODE_MENU, true));
        assertEquals(0, fixture.menus);
    }

    @Test public void menuAliasesSharePairingRules() {
        int[] aliases = {KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU};
        for (int alias : aliases) {
            Fixture fixture = new Fixture();
            assertTrue(fixture.down(alias, 0));
            assertTrue(fixture.up(alias));
            assertEquals(1, fixture.menus);
            fixture.up(alias);
            assertEquals(2, fixture.menus);
        }
    }

    @Test public void missingReleaseCannotSwallowSubsequentUpOnlyPress() {
        Fixture fixture = new Fixture();
        fixture.down(KeyEvent.KEYCODE_MENU, 0);
        fixture.keys.dispatch(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, 0, false, 2L);
        assertEquals(2, fixture.menus);
    }

    @Test public void repeatedDeliveryAcrossWindowsKeepsOneActionPerPress() {
        Fixture fixture = new Fixture();
        fixture.keys.dispatch(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, 0, false, 500L);
        fixture.keys.dispatch(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, 0, false, 500L);
        fixture.keys.dispatch(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, 0, false, 500L);
        fixture.keys.dispatch(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, 0, false, 500L);
        assertEquals(1, fixture.menus);
        fixture.keys.dispatch(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, 0, false, 800L);
        assertEquals(2, fixture.menus);
    }

    @Test public void canceledDownAndItsReleaseDoNotOpenMenu() {
        Fixture fixture = new Fixture();
        fixture.keys.dispatch(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, 0, true, 500L);
        fixture.keys.dispatch(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP, 0, false, 500L);
        assertEquals(0, fixture.menus);
    }

    @Test public void knownMenuScanCodeRepairsOnlyUnknownKeyCode() {
        assertEquals(KeyEvent.KEYCODE_MENU, KeyHandler.resolveKeyCode(KeyEvent.KEYCODE_UNKNOWN, 139));
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, KeyHandler.resolveKeyCode(KeyEvent.KEYCODE_UNKNOWN, 140));
        assertEquals(KeyEvent.KEYCODE_HOME, KeyHandler.resolveKeyCode(KeyEvent.KEYCODE_HOME, 139));
        assertEquals(KeyEvent.KEYCODE_APP_SWITCH,
                KeyHandler.resolveKeyCode(KeyEvent.KEYCODE_APP_SWITCH, 139));
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP,
                KeyHandler.resolveKeyCode(KeyEvent.KEYCODE_VOLUME_UP, 139));
    }

    @Test public void volumeHomeAndAppSwitchRemainSystemKeys() {
        Fixture fixture = new Fixture();
        int[] systemKeys = {KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH};
        for (int key : systemKeys) {
            assertFalse(fixture.down(key, 0));
            assertFalse(fixture.up(key));
        }
        assertEquals(0, fixture.flags);
        assertEquals(0, fixture.menus);
        assertEquals(0, fixture.confirms);
    }

    @Test public void heldOkIsOnePressButSeparateDownsReachGestureDetector() {
        Fixture fixture = new Fixture();
        fixture.down(KeyEvent.KEYCODE_DPAD_CENTER, 0);
        fixture.down(KeyEvent.KEYCODE_DPAD_CENTER, 1);
        fixture.up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, fixture.confirms);
        fixture.down(KeyEvent.KEYCODE_DPAD_CENTER, 0);
        fixture.up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(2, fixture.confirms);
    }

    @Test public void directionRepeatsStillMoveAndGamepadXStillFlags() {
        Fixture fixture = new Fixture();
        fixture.down(KeyEvent.KEYCODE_DPAD_RIGHT, 0);
        fixture.down(KeyEvent.KEYCODE_DPAD_RIGHT, 1);
        fixture.up(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(2, fixture.column);
        fixture.down(KeyEvent.KEYCODE_BUTTON_X, 0);
        fixture.up(KeyEvent.KEYCODE_BUTTON_X);
        assertEquals(1, fixture.flags);
    }

    private static final class Fixture implements KeyHandler.Target {
        final KeyHandler keys = new KeyHandler(this);
        final Map<Integer, Long> downTimes = new HashMap<Integer, Long>();
        long nextDownTime = 1L;
        int menus;
        int confirms;
        int flags;
        int column;

        boolean down(int key, int repeats) {
            if (repeats == 0 || !downTimes.containsKey(key)) {
                downTimes.put(key, nextDownTime++);
            }
            return keys.dispatch(key, KeyEvent.ACTION_DOWN, repeats, false, downTimes.get(key));
        }

        boolean up(int key) {
            return release(key, false);
        }

        boolean release(int key, boolean canceled) {
            Long downTime = downTimes.remove(key);
            return keys.dispatch(key, KeyEvent.ACTION_UP, 0, canceled,
                    downTime == null ? nextDownTime++ : downTime);
        }

        @Override public void move(int rowDelta, int columnDelta) { column += columnDelta; }
        @Override public void confirm() { confirms++; }
        @Override public void flag() { flags++; }
        @Override public void menu() { menus++; }
        @Override public void back() { }
    }
}
