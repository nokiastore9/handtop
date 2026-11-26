package org.handtop.desktop.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class BackActionTest {
    @Test
    fun openLinuxMenuClosesInsteadOfMinimizing() {
        assertEquals(
            BackAction.CLOSE_LINUX_MENUS,
            BackAction.of(launchpadOpen = false, startMenuOpen = false, linuxMenuOpen = true, hasActiveWindow = true),
        )
    }

    @Test
    fun withoutMenusTheActiveWindowIsMinimized() {
        assertEquals(
            BackAction.MINIMIZE,
            BackAction.of(launchpadOpen = false, startMenuOpen = false, linuxMenuOpen = false, hasActiveWindow = true),
        )
    }

    @Test
    fun handTopsOwnPanelsCloseFirst() {
        assertEquals(
            BackAction.CLOSE_LAUNCHPAD,
            BackAction.of(launchpadOpen = true, startMenuOpen = true, linuxMenuOpen = true, hasActiveWindow = true),
        )
        assertEquals(
            BackAction.CLOSE_START_MENU,
            BackAction.of(launchpadOpen = false, startMenuOpen = true, linuxMenuOpen = true, hasActiveWindow = true),
        )
    }

    @Test
    fun emptyDesktopDoesNothing() {
        assertEquals(
            BackAction.NOTHING,
            BackAction.of(launchpadOpen = false, startMenuOpen = false, linuxMenuOpen = false, hasActiveWindow = false),
        )
    }
}
