package org.handtop.desktop.wm

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowSizingTest {
    @Test
    fun phoneOnItsSideGetsTallWindows() {
        // S24 Ultra on its side at 70%: 1197 x 553 points, 48 for the taskbar.
        val (w, h) = WindowSizing.defaultSize(1197, 505, 1f).toList()
        assertEquals(718, w)
        assertEquals(505 - 48, h) // a strip of desktop stays free, not a third of the screen
    }

    @Test
    fun tallWindowsStopAt480Points() {
        // Pixels at density 2: the same rule in points.
        val (_, h) = WindowSizing.defaultSize(2394, 1100, 2f).toList()
        assertEquals(960, h)
    }

    @Test
    fun bigScreenKeepsItsProportions() {
        // DeX, 1600 x 900 points: 62% of the height as before.
        val (w, h) = WindowSizing.defaultSize(1600, 852, 1f).toList()
        assertEquals(960, w)
        assertEquals(528, h)
    }

    @Test
    fun tinyDesktopIsFilled() {
        val (w, h) = WindowSizing.defaultSize(300, 200, 1f).toList()
        assertEquals(300, w)
        assertEquals(200, h)
    }
}
