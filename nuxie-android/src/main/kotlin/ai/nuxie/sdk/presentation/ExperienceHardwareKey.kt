package ai.nuxie.sdk.presentation

import android.view.KeyEvent
import ai.nuxie.sdk.runtime.NuxieFocusInput

internal object ExperienceHardwareKey {
    fun code(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> keyCode - KeyEvent.KEYCODE_A + 65
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0 + 48
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> keyCode - KeyEvent.KEYCODE_F1 + 290
        in KeyEvent.KEYCODE_F13..KeyEvent.KEYCODE_F24 -> keyCode - KeyEvent.KEYCODE_F13 + 302
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0 + 320
        else -> special[keyCode]
    }

    fun modifiers(event: KeyEvent): Int =
        (if (event.isShiftPressed) 1 else 0) or
            (if (event.isCtrlPressed) 2 else 0) or
            (if (event.isAltPressed) 4 else 0) or
            (if (event.isMetaPressed) 8 else 0)

    fun input(event: KeyEvent): NuxieFocusInput? {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return null
        val code = code(event.keyCode) ?: return null
        val pressed = event.action == KeyEvent.ACTION_DOWN
        val modifiers = modifiers(event)
        if (code == 258) {
            if (!pressed) return null
            return if (modifiers and 1 == 0) NuxieFocusInput.Next else NuxieFocusInput.Previous
        }
        return NuxieFocusInput.Key(code, modifiers, pressed, pressed && event.repeatCount > 0)
    }

    private val special = mapOf(
        KeyEvent.KEYCODE_SPACE to 32,
        KeyEvent.KEYCODE_APOSTROPHE to 39,
        KeyEvent.KEYCODE_COMMA to 44,
        KeyEvent.KEYCODE_MINUS to 45,
        KeyEvent.KEYCODE_PERIOD to 46,
        KeyEvent.KEYCODE_SLASH to 47,
        KeyEvent.KEYCODE_SEMICOLON to 59,
        KeyEvent.KEYCODE_EQUALS to 61,
        KeyEvent.KEYCODE_LEFT_BRACKET to 91,
        KeyEvent.KEYCODE_BACKSLASH to 92,
        KeyEvent.KEYCODE_RIGHT_BRACKET to 93,
        KeyEvent.KEYCODE_GRAVE to 96,
        KeyEvent.KEYCODE_ESCAPE to 256,
        KeyEvent.KEYCODE_ENTER to 257,
        KeyEvent.KEYCODE_TAB to 258,
        KeyEvent.KEYCODE_DEL to 259,
        KeyEvent.KEYCODE_INSERT to 260,
        KeyEvent.KEYCODE_FORWARD_DEL to 261,
        KeyEvent.KEYCODE_DPAD_RIGHT to 262,
        KeyEvent.KEYCODE_DPAD_LEFT to 263,
        KeyEvent.KEYCODE_DPAD_DOWN to 264,
        KeyEvent.KEYCODE_DPAD_UP to 265,
        KeyEvent.KEYCODE_PAGE_UP to 266,
        KeyEvent.KEYCODE_PAGE_DOWN to 267,
        KeyEvent.KEYCODE_MOVE_HOME to 268,
        KeyEvent.KEYCODE_MOVE_END to 269,
        KeyEvent.KEYCODE_CAPS_LOCK to 280,
        KeyEvent.KEYCODE_SCROLL_LOCK to 281,
        KeyEvent.KEYCODE_NUM_LOCK to 282,
        KeyEvent.KEYCODE_SYSRQ to 283,
        KeyEvent.KEYCODE_BREAK to 284,
        KeyEvent.KEYCODE_NUMPAD_DOT to 330,
        KeyEvent.KEYCODE_NUMPAD_DIVIDE to 331,
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY to 332,
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT to 333,
        KeyEvent.KEYCODE_NUMPAD_ADD to 334,
        KeyEvent.KEYCODE_NUMPAD_ENTER to 335,
        KeyEvent.KEYCODE_NUMPAD_EQUALS to 336,
        KeyEvent.KEYCODE_SHIFT_LEFT to 340,
        KeyEvent.KEYCODE_CTRL_LEFT to 341,
        KeyEvent.KEYCODE_ALT_LEFT to 342,
        KeyEvent.KEYCODE_META_LEFT to 343,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 344,
        KeyEvent.KEYCODE_CTRL_RIGHT to 345,
        KeyEvent.KEYCODE_ALT_RIGHT to 346,
        KeyEvent.KEYCODE_META_RIGHT to 347,
        KeyEvent.KEYCODE_MENU to 348,
    )
}
