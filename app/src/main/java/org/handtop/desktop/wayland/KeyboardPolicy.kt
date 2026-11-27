package org.handtop.desktop.wayland

/**
 * When the on-screen keyboard comes up for a Linux program: only when the user touches one of
 * its text fields. A field that gets the focus by itself (a window opening with its search
 * field active, a program moving the focus) leaves the keyboard down; the taskbar's keyboard
 * button brings it up for any program.
 */
object KeyboardPolicy {

    /** How long after a finger tap the program's answer (focus, caret) still counts as the tap's. */
    const val TAP_ANSWER_MS = 600L

    /**
     * A finger tap in the active window that the program answered with a text field
     * ([fieldAnswered], see [fieldAnswered]) brings the keyboard up. Asked a moment after the
     * tap, so that a tap that opened a menu ([menuOpen]: the menu bar of a text editor) does
     * not flash the keyboard.
     */
    fun showsOnTap(fieldAnswered: Boolean, hardwareKeyboard: Boolean, activeWindow: Boolean, menuOpen: Boolean): Boolean =
        fieldAnswered && !hardwareKeyboard && activeWindow && !menuOpen

    /**
     * Whether the program answered a tap with a text field: the text input focused now
     * ([inputNow], null for none) took the focus since the tap ([inputAtTap]), or committed its
     * state since then ([commitsNow] against [commitsAtTap]): Qt and Firefox commit when the
     * caret moves, and GTK when the tap lands on the caret. GTK says nothing when a tap only
     * moves the caret, so a tap on the caret's line ([tappedCaretLine], see [onCaretLine]) of
     * the field that keeps the focus counts too. A tap elsewhere does none of these.
     */
    fun fieldAnswered(inputAtTap: Any?, commitsAtTap: Int, inputNow: Any?, commitsNow: Int, tappedCaretLine: Boolean): Boolean =
        inputNow != null &&
            (inputNow !== inputAtTap || commitsNow != commitsAtTap || tappedCaretLine)

    /**
     * Whether the field focused now ([inputNow]) is the window's own field coming back rather
     * than an answer to the tap: the tap activated the window ([tapActivated]), which had a
     * field focused when it lost the focus ([fieldWhenDeactivated]). HandTop gives the window
     * the keyboard focus on the press, and the program puts the focus back into that field
     * before it even sees the tap. Only the first field to come ([restored]): what follows is
     * the program answering the tap.
     */
    fun restoresField(tapActivated: Boolean, fieldWhenDeactivated: Boolean, restored: Boolean, inputNow: Any?): Boolean =
        tapActivated && fieldWhenDeactivated && !restored && inputNow != null

    /**
     * Whether surface row [y] is on the line of the caret the program last reported (its top
     * [caretY], [caretHeight] high): from half a caret above it to half a caret below.
     */
    fun onCaretLine(caretY: Int, caretHeight: Int, y: Double): Boolean =
        caretHeight > 0 && y >= caretY - caretHeight / 2.0 && y <= caretY + caretHeight * 1.5

    /**
     * Without a tap the keyboard comes up only for a field getting the focus back from a menu
     * ([returningFromMenu]) when it was up before the menu ([keyboardWasUp]): HandTop hid it
     * for the menu (GTK moves the focus to it), and it returns to where the user left it.
     */
    fun showsByItself(returningFromMenu: Boolean, keyboardWasUp: Boolean, hardwareKeyboard: Boolean): Boolean =
        returningFromMenu && keyboardWasUp && !hardwareKeyboard
}
