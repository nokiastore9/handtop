package org.handtop.desktop.wayland

import android.util.SparseIntArray
import android.view.KeyEvent

/** Android key events → Linux evdev key codes and XKB modifier masks (for the "us" keymap). */
object Keycodes {

    private val map = SparseIntArray().apply {
        val letters = intArrayOf(30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44)
        letters.forEachIndexed { i, code -> put(KeyEvent.KEYCODE_A + i, code) }
        put(KeyEvent.KEYCODE_0, 11)
        for (i in 1..9) put(KeyEvent.KEYCODE_0 + i, 1 + i)
        put(KeyEvent.KEYCODE_ESCAPE, 1)
        put(KeyEvent.KEYCODE_MINUS, 12)
        put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_DEL, 14)
        put(KeyEvent.KEYCODE_TAB, 15)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 26)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_ENTER, 28)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 29)
        put(KeyEvent.KEYCODE_SEMICOLON, 39)
        put(KeyEvent.KEYCODE_APOSTROPHE, 40)
        put(KeyEvent.KEYCODE_GRAVE, 41)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 42)
        put(KeyEvent.KEYCODE_BACKSLASH, 43)
        put(KeyEvent.KEYCODE_COMMA, 51)
        put(KeyEvent.KEYCODE_PERIOD, 52)
        put(KeyEvent.KEYCODE_SLASH, 53)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54)
        put(KeyEvent.KEYCODE_ALT_LEFT, 56)
        put(KeyEvent.KEYCODE_SPACE, 57)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 58)
        for (i in 0 until 10) put(KeyEvent.KEYCODE_F1 + i, 59 + i)
        put(KeyEvent.KEYCODE_F11, 87)
        put(KeyEvent.KEYCODE_F12, 88)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 96)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 97)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 100)
        put(KeyEvent.KEYCODE_MOVE_HOME, 102)
        put(KeyEvent.KEYCODE_DPAD_UP, 103)
        put(KeyEvent.KEYCODE_PAGE_UP, 104)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 105)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 106)
        put(KeyEvent.KEYCODE_MOVE_END, 107)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 108)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 109)
        put(KeyEvent.KEYCODE_INSERT, 110)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 111)
        put(KeyEvent.KEYCODE_META_LEFT, 125)
        put(KeyEvent.KEYCODE_META_RIGHT, 126)
        put(KeyEvent.KEYCODE_MENU, 127)
    }

    /** Linux evdev code of [event] (XKB adds 8 itself), or 0. */
    fun evdev(event: KeyEvent): Int {
        // Hardware keyboards report the Linux scan code directly.
        if (event.scanCode in 1..767 && event.device?.isVirtual == false) return event.scanCode
        return map.get(event.keyCode, 0)
    }

    /** XKB "depressed" modifier mask: Shift, Lock, Control, Mod1 (Alt), Mod2 (Num), Mod4 (Super). */
    fun modifierMask(metaState: Int): Int {
        var mask = 0
        if (metaState and KeyEvent.META_SHIFT_ON != 0) mask = mask or 1
        if (metaState and KeyEvent.META_CAPS_LOCK_ON != 0) mask = mask or 2
        if (metaState and KeyEvent.META_CTRL_ON != 0) mask = mask or 4
        if (metaState and KeyEvent.META_ALT_ON != 0) mask = mask or 8
        if (metaState and KeyEvent.META_NUM_LOCK_ON != 0) mask = mask or 16
        if (metaState and KeyEvent.META_META_ON != 0) mask = mask or 64
        return mask
    }
}
