package org.handtop.desktop.wm

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowSizingTest {
    @Test
    fun aProgramsSizeIsLimitedByTheDesktopNotByTheKeyboard() {
        // Desktop 2000 x 1300 px, the keyboard hides 800 of it: a GTK window that cannot get
        // smaller than 1100 px keeps 1100. Limited to the 500 px left above the keyboard, it
        // stayed that short after the keyboard went away.
        val (w, h) = WindowSizing.fitContent(1500, 1100, areaWidth = 2000, areaHeight = 500, keyboardCover = 800).toList()
        assertEquals(1500, w)
        assertEquals(1100, h)
    }

    @Test
    fun aProgramsSizeStillFitsTheDesktop() {
        val (w, h) = WindowSizing.fitContent(2600, 1500, areaWidth = 2000, areaHeight = 1300, keyboardCover = 0).toList()
        assertEquals(2000, w)
        assertEquals(1300, h)
        // Before the desktop's first layout (size 0) nothing limits it.
        val (w0, h0) = WindowSizing.fitContent(2600, 1500, areaWidth = 0, areaHeight = 0, keyboardCover = 0).toList()
        assertEquals(2600, w0)
        assertEquals(1500, h0)
    }

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
