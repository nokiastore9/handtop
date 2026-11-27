package org.handtop.desktop.wm

import org.handtop.desktop.wm.WindowAction.CLOSE
import org.handtop.desktop.wm.WindowAction.MAXIMIZE
import org.handtop.desktop.wm.WindowAction.MINIMIZE
import org.handtop.desktop.wm.WindowAction.NORMAL_SIZE
import org.handtop.desktop.wm.WindowAction.RESTORE
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowActionsTest {

    @Test
    fun aWindowOnScreenCanBeMinimizedMaximizedAndClosed() =
        assertEquals(listOf(MINIMIZE, MAXIMIZE, CLOSE), windowActions(minimized = false, maximized = false, resizable = true))

    @Test
    fun aMinimizedWindowIsRestoredInstead() =
        assertEquals(listOf(RESTORE, MAXIMIZE, CLOSE), windowActions(minimized = true, maximized = false, resizable = true))

    @Test
    fun aMaximizedWindowGoesBackToItsNormalSize() =
        assertEquals(listOf(MINIMIZE, NORMAL_SIZE, CLOSE), windowActions(minimized = false, maximized = true, resizable = true))

    @Test
    fun aWindowOfFixedSizeIsNotMaximized() =
        // A dialog, or a Linux program with a fixed size: no size entry.
        assertEquals(listOf(MINIMIZE, CLOSE), windowActions(minimized = false, maximized = false, resizable = false))
}
