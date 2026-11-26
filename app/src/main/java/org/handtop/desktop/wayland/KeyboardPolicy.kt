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
     */
    fun showsByItself(fieldFocused: Boolean, hadField: Boolean, hardwareKeyboard: Boolean, onMenu: Boolean): Boolean =
        fieldFocused && !hadField && !hardwareKeyboard && !onMenu
}
