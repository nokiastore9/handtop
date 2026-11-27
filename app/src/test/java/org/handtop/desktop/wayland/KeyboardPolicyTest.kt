package org.handtop.desktop.wayland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPolicyTest {
    private val field = Any()
    private val otherField = Any()

    @Test
    fun aFieldThatGetsTheFocusByItselfLeavesTheKeyboardDown() {
        // A window that opens with its search field active, or a program moving the focus:
        // the user did not touch a field, so the keyboard stays down.
        assertFalse(KeyboardPolicy.showsByItself(returningFromMenu = false, keyboardWasUp = false, hardwareKeyboard = false))
        assertFalse(KeyboardPolicy.showsByItself(returningFromMenu = false, keyboardWasUp = true, hardwareKeyboard = false))
    }

    @Test
    fun afterAMenuTheKeyboardComesBackOnlyIfItWasUp() {
        // The menu took the field's focus and HandTop hid the keyboard for it: it comes back.
        assertTrue(KeyboardPolicy.showsByItself(returningFromMenu = true, keyboardWasUp = true, hardwareKeyboard = false))
        // The user had put the keyboard away before opening the menu: it stays away.
        assertFalse(KeyboardPolicy.showsByItself(returningFromMenu = true, keyboardWasUp = false, hardwareKeyboard = false))
    }

    @Test
    fun aTapThatTheProgramAnswersWithItsFieldBringsTheKeyboard() =
        assertTrue(KeyboardPolicy.showsOnTap(fieldAnswered = true, hardwareKeyboard = false, activeWindow = true, menuOpen = false))

    @Test
    fun aTapElsewhereWhileAFieldIsFocusedDoesNot() =
        // A tap on a button of a window whose text field has the focus: no keyboard.
        assertFalse(KeyboardPolicy.showsOnTap(fieldAnswered = false, hardwareKeyboard = false, activeWindow = true, menuOpen = false))

    @Test
    fun aTapThatOpenedAMenuDoesNot() =
        // A tap on the menu bar while the text view has the focus: the menu, not the keyboard.
        assertFalse(KeyboardPolicy.showsOnTap(fieldAnswered = true, hardwareKeyboard = false, activeWindow = true, menuOpen = true))

    @Test
    fun notWithAHardwareKeyboard() {
        assertFalse(KeyboardPolicy.showsOnTap(fieldAnswered = true, hardwareKeyboard = true, activeWindow = true, menuOpen = false))
        assertFalse(KeyboardPolicy.showsByItself(returningFromMenu = true, keyboardWasUp = true, hardwareKeyboard = true))
    }

    @Test
    fun theCaretsLineIsTheBandAroundTheCaretRectangle() {
        // Caret at y 100, 20 high: the line is 90 to 130 (half a caret above, half below).
        assertTrue(KeyboardPolicy.onCaretLine(caretY = 100, caretHeight = 20, y = 92.0))
        assertTrue(KeyboardPolicy.onCaretLine(caretY = 100, caretHeight = 20, y = 128.0))
        assertFalse(KeyboardPolicy.onCaretLine(caretY = 100, caretHeight = 20, y = 85.0))
        assertFalse(KeyboardPolicy.onCaretLine(caretY = 100, caretHeight = 20, y = 140.0))
        // No caret rectangle yet (height 0): nothing is on its line.
        assertFalse(KeyboardPolicy.onCaretLine(caretY = 0, caretHeight = 0, y = 0.0))
    }

    @Test
    fun theFieldAnswersATapByTakingTheFocusOrMovingItsCaret() {
        // No field had the focus; the tapped one took it.
        assertTrue(KeyboardPolicy.fieldAnswered(inputAtTap = null, commitsAtTap = 0, inputNow = field, commitsNow = 1, tappedCaretLine = false))
        // Another field had it; the tapped one took it.
        assertTrue(KeyboardPolicy.fieldAnswered(inputAtTap = otherField, commitsAtTap = 4, inputNow = field, commitsNow = 1, tappedCaretLine = false))
        // The focused field: its caret moved to the tap (programs commit their state on that).
        assertTrue(KeyboardPolicy.fieldAnswered(inputAtTap = field, commitsAtTap = 4, inputNow = field, commitsNow = 5, tappedCaretLine = false))
        // Nothing changed: the tap was somewhere else.
        assertFalse(KeyboardPolicy.fieldAnswered(inputAtTap = field, commitsAtTap = 4, inputNow = field, commitsNow = 4, tappedCaretLine = false))
        // GTK says nothing when a tap only moves the caret: a tap on the caret's line of the
        // field that keeps the focus is a tap into it.
        assertTrue(KeyboardPolicy.fieldAnswered(inputAtTap = field, commitsAtTap = 4, inputNow = field, commitsNow = 4, tappedCaretLine = true))
        // The field lost the focus (a button took it), even on the caret's line.
        assertFalse(KeyboardPolicy.fieldAnswered(inputAtTap = field, commitsAtTap = 4, inputNow = null, commitsNow = 0, tappedCaretLine = true))
    }
}
