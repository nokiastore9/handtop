package org.handtop.desktop.wayland

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInputTypeTest {

    private val text = InputType.TYPE_CLASS_TEXT

    @Test fun spellcheckAsksForAutocorrection() {
        val type = TextInput.inputTypeFor(purpose = 0, hint = 0x2)
        assertEquals(text or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, type)
    }

    @Test fun sensitiveDataGetsNoSuggestions() {
        val type = TextInput.inputTypeFor(purpose = 0, hint = 0x2 or 0x80)
        assertEquals(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, type)
    }

    @Test fun capitalizationAndMultilineStay() {
        val type = TextInput.inputTypeFor(purpose = 0, hint = 0x4 or 0x200)
        assertEquals(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, type)
    }

    @Test fun purposesKeepTheirKeyboards() {
        assertEquals(InputType.TYPE_CLASS_NUMBER, TextInput.inputTypeFor(purpose = 2, hint = 0x2))
        val terminal = TextInput.inputTypeFor(purpose = 13, hint = 0x2)
        assertTrue(terminal and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            TextInput.inputTypeFor(purpose = 0, hint = 0x40 or 0x2),
        )
    }
}
