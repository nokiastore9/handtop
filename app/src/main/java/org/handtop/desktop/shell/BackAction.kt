package org.handtop.desktop.shell

/** What the Back button does on the desktop: closes what is open, the innermost first. */
enum class BackAction {
    CLOSE_LAUNCHPAD,
    CLOSE_START_MENU,

    /** A Linux program's menu: closed like a tap outside it, the program stays on screen. */
    CLOSE_LINUX_MENUS,

    /** Back never quits the desktop by accident: it minimizes the active window. */
    MINIMIZE,
    NOTHING;

    companion object {
        fun of(launchpadOpen: Boolean, startMenuOpen: Boolean, linuxMenuOpen: Boolean, hasActiveWindow: Boolean) = when {
            launchpadOpen -> CLOSE_LAUNCHPAD
            startMenuOpen -> CLOSE_START_MENU
            linuxMenuOpen -> CLOSE_LINUX_MENUS
            hasActiveWindow -> MINIMIZE
            else -> NOTHING
        }
    }
}
