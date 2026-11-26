package org.handtop.desktop.wayland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPolicyTest {

    @Test
    fun aTextFieldGettingTheFocusBringsTheKeyboard() =
        assertTrue(KeyboardPolicy.showsByItself(fieldFocused = true, hadField = false, hardwareKeyboard = false, onMenu = false))

    @Test
    fun notWithAHardwareKeyboard() =
        assertFalse(KeyboardPolicy.showsByItself(fieldFocused = true, hadField = false, hardwareKeyboard = true, onMenu = false))

    @Test
    fun aMenuNeverBringsTheKeyboard() =
        // GTK 3 enables its text view's input on the menu surface while a submenu opens.
        assertFalse(KeyboardPolicy.showsByItself(fieldFocused = true, hadField = false, hardwareKeyboard = false, onMenu = true))
}
