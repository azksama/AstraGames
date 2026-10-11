package fr.astragames.app.wolfnative

import android.view.KeyEvent
import fr.astragames.wolf.NativeKey

/** Wolf uses DirectInput/DxLib scan indices, rather than Android or Windows virtual-key numbers. */
internal object WolfNativeKeyboard {
    private val letters = intArrayOf(30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
        49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44)
    private val numbers = intArrayOf(11, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    private val numpad = intArrayOf(82, 79, 80, 81, 75, 76, 77, 71, 72, 73)

    fun scan(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> letters[code - KeyEvent.KEYCODE_A]
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> numbers[code - KeyEvent.KEYCODE_0]
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> numpad[code - KeyEvent.KEYCODE_NUMPAD_0]
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F10 -> 59 + code - KeyEvent.KEYCODE_F1
        KeyEvent.KEYCODE_F11 -> 87; KeyEvent.KEYCODE_F12 -> 88
        KeyEvent.KEYCODE_ESCAPE -> 1; KeyEvent.KEYCODE_DEL -> 14; KeyEvent.KEYCODE_TAB -> 15
        KeyEvent.KEYCODE_ENTER -> 28; KeyEvent.KEYCODE_NUMPAD_ENTER -> 156
        KeyEvent.KEYCODE_CTRL_LEFT -> 29; KeyEvent.KEYCODE_CTRL_RIGHT -> 157
        KeyEvent.KEYCODE_SHIFT_LEFT -> 42; KeyEvent.KEYCODE_SHIFT_RIGHT -> 54
        KeyEvent.KEYCODE_ALT_LEFT -> 56; KeyEvent.KEYCODE_ALT_RIGHT -> 184
        KeyEvent.KEYCODE_SPACE -> 57; KeyEvent.KEYCODE_CAPS_LOCK -> 58
        KeyEvent.KEYCODE_NUM_LOCK -> 69; KeyEvent.KEYCODE_SCROLL_LOCK -> 70
        KeyEvent.KEYCODE_DPAD_UP -> 200; KeyEvent.KEYCODE_DPAD_DOWN -> 208
        KeyEvent.KEYCODE_DPAD_LEFT -> 203; KeyEvent.KEYCODE_DPAD_RIGHT -> 205
        KeyEvent.KEYCODE_MOVE_HOME -> 199; KeyEvent.KEYCODE_MOVE_END -> 207
        KeyEvent.KEYCODE_PAGE_UP -> 201; KeyEvent.KEYCODE_PAGE_DOWN -> 209
        KeyEvent.KEYCODE_INSERT -> 210; KeyEvent.KEYCODE_FORWARD_DEL -> 211
        KeyEvent.KEYCODE_MINUS -> 12; KeyEvent.KEYCODE_EQUALS -> 13
        KeyEvent.KEYCODE_LEFT_BRACKET -> 26; KeyEvent.KEYCODE_RIGHT_BRACKET -> 27
        KeyEvent.KEYCODE_SEMICOLON -> 39; KeyEvent.KEYCODE_APOSTROPHE -> 40
        KeyEvent.KEYCODE_GRAVE -> 41; KeyEvent.KEYCODE_BACKSLASH -> 43
        KeyEvent.KEYCODE_COMMA -> 51; KeyEvent.KEYCODE_PERIOD -> 52; KeyEvent.KEYCODE_SLASH -> 53
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 181; KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 55
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 74; KeyEvent.KEYCODE_NUMPAD_ADD -> 78
        KeyEvent.KEYCODE_NUMPAD_DOT -> 83; KeyEvent.KEYCODE_NUMPAD_EQUALS -> 141
        KeyEvent.KEYCODE_SYSRQ -> 183; KeyEvent.KEYCODE_BREAK -> 197
        KeyEvent.KEYCODE_META_LEFT -> 219; KeyEvent.KEYCODE_META_RIGHT -> 220
        else -> null
    }

    fun scan(key: NativeKey): Int? = when (key) {
        NativeKey.UP -> 200; NativeKey.DOWN -> 208; NativeKey.LEFT -> 203; NativeKey.RIGHT -> 205
        NativeKey.ACCEPT -> 28; NativeKey.BACK -> 1; NativeKey.SPACE -> 57
        NativeKey.SHIFT -> 42; NativeKey.CONTROL -> 29
        NativeKey.Z -> 44; NativeKey.X -> 45; NativeKey.C -> 46; NativeKey.A -> 30
        NativeKey.S -> 31; NativeKey.D -> 32; NativeKey.W -> 17
        NativeKey.F1 -> 59; NativeKey.F5 -> 63; NativeKey.F12 -> 88
    }

    fun controllerButton(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> 301; KeyEvent.KEYCODE_BUTTON_B -> 302
        KeyEvent.KEYCODE_BUTTON_X -> 303; KeyEvent.KEYCODE_BUTTON_Y -> 304
        KeyEvent.KEYCODE_BUTTON_L1 -> 305; KeyEvent.KEYCODE_BUTTON_R1 -> 306
        KeyEvent.KEYCODE_BUTTON_L2 -> 307; KeyEvent.KEYCODE_BUTTON_R2 -> 308
        KeyEvent.KEYCODE_BUTTON_SELECT -> 309; KeyEvent.KEYCODE_BUTTON_START -> 310
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 311; KeyEvent.KEYCODE_BUTTON_THUMBR -> 312
        KeyEvent.KEYCODE_BUTTON_MODE -> 313
        in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_15 -> 301 + code - KeyEvent.KEYCODE_BUTTON_1
        else -> null
    }
}
