package org.handtop.desktop.widgets

import org.handtop.desktop.shell.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopGridTest {
    private val clock = PlacedWidget(WidgetKind.CLOCK, null)

    @Test
    fun noWidgetsKeepsTodaysIconOrder() {
        // 6 icons, none saved, grid 8 x 4: column-first, as DesktopIcons placed them before widgets.
        val l = DesktopGrid.layOut(8, 4, List(6) { null }, emptyList())
        assertEquals(listOf(Cell(0, 0), Cell(0, 1), Cell(0, 2), Cell(0, 3), Cell(1, 0), Cell(1, 1)), l.icons)
    }

    @Test
    fun savedIconsKeepTheirCells() {
        val l = DesktopGrid.layOut(8, 4, listOf(Cell(5, 2), null), emptyList())
        assertEquals(listOf(Cell(5, 2), Cell(0, 0)), l.icons)
    }

    @Test
    fun newWidgetGoesTopRight() =
        assertEquals(Cell(5, 0), DesktopGrid.layOut(8, 4, List(6) { null }, listOf(clock)).widgets[0])

    @Test
    fun savedIconsComeBeforeWidgetsAndUnsavedIconsFlowAround() {
        val w = PlacedWidget(WidgetKind.WEATHER, Cell(0, 0)) // 3 x 2 at the top left
        val l = DesktopGrid.layOut(8, 4, listOf(Cell(1, 0), null), listOf(w))
        assertEquals(Cell(1, 0), l.icons[0]) // the saved icon keeps its cell…
        assertNotEquals(Cell(0, 0), l.widgets[0]) // …so the widget moves
        assertTrue(l.icons[1] !in DesktopGrid.cellsOf(l.widgets[0], w.kind.size)) // the unsaved icon avoids it
        assertTrue(Cell(1, 0) !in DesktopGrid.cellsOf(l.widgets[0], w.kind.size))
    }

    @Test
    fun widgetKeepsItsCellWhenAnIconIsAdded() {
        val w = PlacedWidget(WidgetKind.CLOCK, Cell(0, 2))
        val before = DesktopGrid.layOut(8, 4, List(2) { null }, listOf(w)).widgets[0]
        val after = DesktopGrid.layOut(8, 4, List(3) { null }, listOf(w)).widgets[0]
        assertEquals(Cell(0, 2), before)
        assertEquals(before, after)
    }

    @Test
    fun iconsNeverLandUnderAWidget() {
        val w = PlacedWidget(WidgetKind.WEATHER, Cell(0, 0))
        val l = DesktopGrid.layOut(8, 4, List(10) { null }, listOf(w))
        val under = DesktopGrid.cellsOf(l.widgets[0], w.kind.size)
        assertTrue(l.icons.none { it in under })
        assertEquals(l.icons.size, l.icons.toSet().size)
    }

    @Test
    fun twoWidgetsDoNotOverlap() {
        val a = PlacedWidget(WidgetKind.CLOCK, Cell(2, 1))
        val b = PlacedWidget(WidgetKind.LINUX, Cell(3, 1)) // wants cells the clock has
        val l = DesktopGrid.layOut(8, 4, emptyList(), listOf(a, b))
        assertEquals(Cell(2, 1), l.widgets[0])
        assertTrue(DesktopGrid.cellsOf(l.widgets[0], a.kind.size).intersect(DesktopGrid.cellsOf(l.widgets[1], b.kind.size)).isEmpty())
    }

    @Test
    fun smallerGridMovesWidgetButKeepsSavedCell() {
        val w = PlacedWidget(WidgetKind.CLOCK, Cell(9, 0))
        val l = DesktopGrid.layOut(6, 4, emptyList(), listOf(w))
        assertTrue(DesktopGrid.fits(6, 4, w.kind.size, l.widgets[0], emptySet()))
        assertEquals(Cell(3, 0), l.widgets[0]) // the nearest place that fits
        assertEquals(Cell(9, 0), w.cell) // the input is not changed
    }

    @Test
    fun widgetWithNoRoomOverlapsTopLeft() =
        assertEquals(Cell(0, 0), DesktopGrid.layOut(3, 1, List(3) { Cell(it, 0) }, listOf(clock)).widgets[0])

    @Test
    fun aFullDesktopOfUnplacedIconsKeepsEveryIconOnScreen() {
        // A fresh desktop (or after "Arrange icons"): no icon has a saved cell.
        val small = DesktopGrid.layOut(3, 1, List(3) { null }, listOf(clock))
        assertTrue(small.icons.all { it.column < 3 && it.row < 1 })
        assertEquals(Cell(0, 0), small.widgets[0]) // over the icons, to be moved
        for (n in listOf(30, 31, 32)) {
            val l = DesktopGrid.layOut(8, 4, List(n) { null }, listOf(clock))
            assertTrue("$n icons", l.icons.all { it.column < 8 && it.row < 4 })
            assertEquals("$n icons", n, l.icons.toSet().size)
        }
    }

    @Test
    fun aWidgetWithRoomStillTakesIt() {
        // 29 unplaced icons + a 3-cell clock fill 32 cells exactly: no overlap needed.
        val l = DesktopGrid.layOut(8, 4, List(29) { null }, listOf(clock))
        assertTrue(l.icons.all { it.column < 8 })
        assertTrue(l.icons.none { it in DesktopGrid.cellsOf(l.widgets[0], clock.kind.size) })
    }

    @Test
    fun fitsRejectsTakenAndOffGrid() {
        assertFalse(DesktopGrid.fits(8, 4, CellSize(3, 1), Cell(6, 0), emptySet()))
        assertFalse(DesktopGrid.fits(8, 4, CellSize(3, 1), Cell(0, 0), setOf(Cell(2, 0))))
        assertFalse(DesktopGrid.fits(8, 4, CellSize(3, 2), Cell(0, 3), emptySet()))
        assertTrue(DesktopGrid.fits(8, 4, CellSize(3, 1), Cell(5, 3), emptySet()))
    }

    @Test
    fun firstFreeScansTopDownRightToLeft() {
        assertEquals(Cell(5, 0), DesktopGrid.firstFree(8, 4, CellSize(3, 1), emptySet()))
        assertEquals(Cell(4, 0), DesktopGrid.firstFree(8, 4, CellSize(3, 1), setOf(Cell(7, 0))))
        assertEquals(Cell(5, 1), DesktopGrid.firstFree(8, 4, CellSize(3, 1), (0..7).map { Cell(it, 0) }.toSet()))
        assertEquals(null, DesktopGrid.firstFree(2, 4, CellSize(3, 1), emptySet()))
    }
}
