package dev.freedesk

import android.view.KeyEvent.*

/**
 * Thai Kedmanee layout, by physical (US QWERTY) key. Value = (normal, shifted).
 * Used by the experimental "TH" mode, which pastes the character into the guest app
 * instead of relying on a hardware-keyboard layout being applied to the virtual display.
 */
object Kedmanee {
    val map: Map<Int, Pair<String, String>> = mapOf(
        KEYCODE_GRAVE to ("_" to "%"),
        KEYCODE_1 to ("ๅ" to "+"),
        KEYCODE_2 to ("/" to "๑"),
        KEYCODE_3 to ("-" to "๒"),
        KEYCODE_4 to ("ภ" to "๓"),
        KEYCODE_5 to ("ถ" to "๔"),
        KEYCODE_6 to ("ุ" to "ู"),
        KEYCODE_7 to ("ึ" to "฿"),
        KEYCODE_8 to ("ค" to "๕"),
        KEYCODE_9 to ("ต" to "๖"),
        KEYCODE_0 to ("จ" to "๗"),
        KEYCODE_MINUS to ("ข" to "๘"),
        KEYCODE_EQUALS to ("ช" to "๙"),

        KEYCODE_Q to ("ๆ" to "๐"),
        KEYCODE_W to ("ไ" to "\""),
        KEYCODE_E to ("ำ" to "ฎ"),
        KEYCODE_R to ("พ" to "ฑ"),
        KEYCODE_T to ("ะ" to "ธ"),
        KEYCODE_Y to ("ั" to "ํ"),
        KEYCODE_U to ("ี" to "๊"),
        KEYCODE_I to ("ร" to "ณ"),
        KEYCODE_O to ("น" to "ฯ"),
        KEYCODE_P to ("ย" to "ญ"),
        KEYCODE_LEFT_BRACKET to ("บ" to "ฐ"),
        KEYCODE_RIGHT_BRACKET to ("ล" to ","),
        KEYCODE_BACKSLASH to ("ฃ" to "ฅ"),

        KEYCODE_A to ("ฟ" to "ฤ"),
        KEYCODE_S to ("ห" to "ฆ"),
        KEYCODE_D to ("ก" to "ฏ"),
        KEYCODE_F to ("ด" to "โ"),
        KEYCODE_G to ("เ" to "ฌ"),
        KEYCODE_H to ("้" to "็"),
        KEYCODE_J to ("่" to "๋"),
        KEYCODE_K to ("า" to "ษ"),
        KEYCODE_L to ("ส" to "ศ"),
        KEYCODE_SEMICOLON to ("ว" to "ซ"),
        KEYCODE_APOSTROPHE to ("ง" to "."),

        KEYCODE_Z to ("ผ" to "("),
        KEYCODE_X to ("ป" to ")"),
        KEYCODE_C to ("แ" to "ฉ"),
        KEYCODE_V to ("อ" to "ฮ"),
        KEYCODE_B to ("ิ" to "ฺ"),
        KEYCODE_N to ("ื" to "์"),
        KEYCODE_M to ("ท" to "?"),
        KEYCODE_COMMA to ("ม" to "ฒ"),
        KEYCODE_PERIOD to ("ใ" to "ฬ"),
        KEYCODE_SLASH to ("ฝ" to "ฦ"),
    )
}
