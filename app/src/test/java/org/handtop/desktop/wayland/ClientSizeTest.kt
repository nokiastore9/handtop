package org.handtop.desktop.wayland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSizeTest {
    @Test
    fun programThatKeepsItsOwnSizeIsFollowed() {
        // glmark2 draws 800 x 600 whatever it is asked: the window hugs it, no black margin.
        assertTrue(ClientSize.adopt(ackedLatest = true, handled = false, 1000, 700, 800, 600))
        // GTK below its minimum size draws bigger: the window grows instead of cutting it off.
        assertTrue(ClientSize.adopt(ackedLatest = true, handled = false, 200, 100, 360, 240))
    }

    @Test
    fun programThatFollowsChangesNothing() {
        assertFalse(ClientSize.adopt(ackedLatest = true, handled = false, 1000, 700, 1000, 700))
    }

    @Test
    fun answerToAnOlderSizeIsIgnored() {
        // While the user drags the frame, the program still answers earlier sizes.
        assertFalse(ClientSize.adopt(ackedLatest = false, handled = false, 1000, 700, 800, 600))
    }

    @Test
    fun eachSizeIsFollowedOnce() {
        // A window that cannot grow (the desktop is smaller) must not be resized at every frame.
        assertFalse(ClientSize.adopt(ackedLatest = true, handled = true, 1000, 700, 800, 600))
    }

    @Test
    fun noSizeAskedNoSizeToCompare() {
        // The first configure is 0 x 0: the program picks its size.
        assertFalse(ClientSize.adopt(ackedLatest = true, handled = false, 0, 0, 800, 600))
    }
}
