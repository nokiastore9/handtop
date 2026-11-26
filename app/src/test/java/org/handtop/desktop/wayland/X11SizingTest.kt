package org.handtop.desktop.wayland

import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_HEIGHT
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_WIDTH
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_X
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_Y
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
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
}
