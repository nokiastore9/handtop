package org.handtop.desktop.wayland

/** When HandTop brings the on-screen keyboard up by itself for a Linux program. */
object KeyboardPolicy {

    /**
     * A text field just got the focus ([fieldFocused], and none had it before): the keyboard
     * comes up by itself, unless a hardware keyboard is attached or the focus is on a menu
     * ([onMenu], a popup). Nobody types into a menu, but GTK 3 enables its text view's input on
     * the menu surface while a submenu opens; the keyboard would pop up at every submenu and
     * squeeze the menus. A text field in a popup (a GTK 4 popover) still gets typed into, after
     * the taskbar's keyboard button.
     *
     * A menu that took the focus from a text field hides the keyboard while it is open; when
     * the field gets the focus back ([returningFromMenu]) the keyboard returns only if it was up
     * before the menu ([keyboardWasUp]): a keyboard the user had put away stays away.
     */
    fun showsByItself(
        fieldFocused: Boolean,
        hadField: Boolean,
        hardwareKeyboard: Boolean,
        onMenu: Boolean,
        returningFromMenu: Boolean = false,
        keyboardWasUp: Boolean = false,
    ): Boolean =
        fieldFocused && !hadField && !hardwareKeyboard && !onMenu && (!returningFromMenu || keyboardWasUp)

    /**
     * A finger tap in the active window while the program has a text field focused brings the
     * keyboard back, like on Android; asked a moment after the tap, so that a tap that opened a
     * menu ([menuOpen]: the menu bar of a text editor) does not flash the keyboard.
     */
    fun showsOnTap(fieldFocused: Boolean, hardwareKeyboard: Boolean, activeWindow: Boolean, menuOpen: Boolean): Boolean =
        fieldFocused && !hardwareKeyboard && activeWindow && !menuOpen
}
