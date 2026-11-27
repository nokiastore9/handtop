package org.handtop.desktop.wayland

import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_HEIGHT
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_WIDTH
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_X
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_Y
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class X11SizingTest {

    @Test
    fun aProgramAsksForANewSize() = assertArrayEquals(
        intArrayOf(1000, 300),
        X11Sizing.requestedSize(CONFIG_WIDTH or CONFIG_HEIGHT, 1000, 300, 486, 274),
    )

    @Test
    fun askingForTheWidthKeepsTheHeight() = assertArrayEquals(
        intArrayOf(900, 274),
        X11Sizing.requestedSize(CONFIG_WIDTH, 900, 0, 486, 274),
    )

    @Test
    fun theSameSizeIsNoRequest() = assertNull(X11Sizing.requestedSize(CONFIG_WIDTH or CONFIG_HEIGHT, 486, 274, 486, 274))

    @Test
    fun aMoveIsNoResize() = assertNull(X11Sizing.requestedSize(CONFIG_X or CONFIG_Y, 0, 0, 486, 274))

    @Test
    fun aWindowWhollyOffTheScreenIsNotAMenu() {
        // GTK keeps tiny windows at (-100, -100) for drag and drop: no one sees or taps them.
        assertTrue(X11Sizing.offScreen(-100, -100, 2, 2, 1560, 650))
        assertTrue(X11Sizing.offScreen(1560, 10, 50, 50, 1560, 650)) // right of the screen
        assertTrue(X11Sizing.offScreen(10, 650, 50, 50, 1560, 650)) // below it
        // A menu partly on the screen is a menu.
        assertFalse(X11Sizing.offScreen(-20, 100, 200, 300, 1560, 650))
        assertFalse(X11Sizing.offScreen(1500, 600, 200, 300, 1560, 650))
    }
}
