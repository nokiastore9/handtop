package org.handtop.desktop.widgets

import org.handtop.desktop.shell.Cell
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetsJsonTest {

    @Test
    fun roundTrip() {
        val list = listOf(
            PlacedWidget(WidgetKind.CLOCK, Cell(5, 0), mapOf("seconds" to "true")),
            PlacedWidget(WidgetKind.WEATHER, null, mapOf("mode" to "city", "city" to "Paris")),
        )
        assertEquals(list, Widgets.fromJson(Widgets.toJson(list)))
    }

    @Test
    fun nothingStoredIsNoWidgets() {
        assertEquals(emptyList<PlacedWidget>(), Widgets.fromJson(null))
        assertEquals(emptyList<PlacedWidget>(), Widgets.fromJson("garbage"))
    }

    @Test
    fun unknownKindsAreDropped() {
        val json = """[{"kind":"radio","cell":[0,0]},{"kind":"linux","cell":[1,2]}]"""
        assertEquals(listOf(PlacedWidget(WidgetKind.LINUX, Cell(1, 2))), Widgets.fromJson(json))
    }

    @Test
    fun kindsAndSizes() {
        assertEquals(CellSize(3, 1), WidgetKind.CLOCK.size)
        assertEquals(CellSize(3, 2), WidgetKind.WEATHER.size)
        assertEquals(CellSize(3, 1), WidgetKind.LINUX.size)
        assertEquals(CellSize(3, 2), WidgetKind.PLAYER.size)
        assertEquals(WidgetKind.PLAYER, WidgetKind.fromKey("player"))
        assertEquals(WidgetKind.WEATHER, WidgetKind.fromKey("weather"))
        assertEquals(null, WidgetKind.fromKey("nope"))
    }
}
