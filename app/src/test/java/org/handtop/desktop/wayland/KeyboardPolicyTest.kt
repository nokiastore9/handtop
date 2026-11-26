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
    fun afterAMenuTheKeyboardComesBackOnlyIfItWasUp() {
        // The menu took the field's focus and HandTop hid the keyboard for it: it comes back.
        assertTrue(
            KeyboardPolicy.showsByItself(
                fieldFocused = true, hadField = false, hardwareKeyboard = false, onMenu = false,
                returningFromMenu = true, keyboardWasUp = true,
            ),
        )
        // The user had put the keyboard away before opening the menu: it stays away.
        assertFalse(
            KeyboardPolicy.showsByItself(
                fieldFocused = true, hadField = false, hardwareKeyboard = false, onMenu = false,
                returningFromMenu = true, keyboardWasUp = false,
            ),
        )
    }

    @Test
    fun aTapIntoTheFocusedFieldBringsTheKeyboard() =
        assertTrue(KeyboardPolicy.showsOnTap(fieldFocused = true, hardwareKeyboard = false, activeWindow = true, menuOpen = false))

    @Test
    fun aTapThatOpenedAMenuDoesNot() =
        // A tap on the menu bar while the text view has the focus: the menu, not the keyboard.
        assertFalse(KeyboardPolicy.showsOnTap(fieldFocused = true, hardwareKeyboard = false, activeWindow = true, menuOpen = true))

    @Test
    fun aMenuNeverBringsTheKeyboard() =
        // GTK 3 enables its text view's input on the menu surface while a submenu opens.
        assertFalse(KeyboardPolicy.showsByItself(fieldFocused = true, hadField = false, hardwareKeyboard = false, onMenu = true))
}
