package org.handtop.desktop.wayland

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XdndTest {

    @Test
    fun theVersionIsTheLowerOfBothSidesFromThreeUp() {
        assertEquals(5, Xdnd.version(5))
        assertEquals(4, Xdnd.version(4))
        assertEquals(5, Xdnd.version(7)) // a newer target speaks ours
        assertEquals(0, Xdnd.version(2)) // too old: not a drop target for HandTop
        assertEquals(0, Xdnd.version(0)) // no XdndAware at all
    }

    @Test
    fun enterCarriesTheFirstThreeTypesAndSaysWhenThereAreMore() {
        assertArrayEquals(intArrayOf(0x200001, (5 shl 24), 10, 11, 0), Xdnd.enter(0x200001, 5, listOf(10, 11)))
        assertArrayEquals(intArrayOf(0x200001, (5 shl 24) or 1, 10, 11, 12), Xdnd.enter(0x200001, 5, listOf(10, 11, 12, 13)))
    }

    @Test
    fun positionPacksTheRootPointAndTheAction() =
        assertArrayEquals(intArrayOf(7, 0, (300 shl 16) or 40, 0, 99), Xdnd.position(7, 300, 40, 0, 99))

    @Test
    fun dropAndLeaveNameTheSource() {
        assertArrayEquals(intArrayOf(7, 0, 0, 0, 0), Xdnd.drop(7, 0))
        assertArrayEquals(intArrayOf(7, 0, 0, 0, 0), Xdnd.leave(7))
    }

    @Test
    fun theTargetTakesTheDropWhenItsStatusSaysSo() {
        assertTrue(Xdnd.accepts(1))
        assertTrue(Xdnd.accepts(3)) // also wants positions inside a rectangle
        assertFalse(Xdnd.accepts(2))
        assertFalse(Xdnd.accepts(0))
    }

    @Test
    fun x11TargetsMapToWaylandTypes() {
        assertEquals(UriList.MIME, Xdnd.mimeOf("text/uri-list"))
        assertEquals("text/plain;charset=utf-8", Xdnd.mimeOf("UTF8_STRING"))
        assertEquals("text/plain;charset=utf-8", Xdnd.mimeOf("text/plain;charset=utf-8"))
        assertEquals("text/plain", Xdnd.mimeOf("text/plain"))
        assertNull(Xdnd.mimeOf("image/png")) // only files and text cross over
    }

    @Test
    fun aDragIsOfferedToX11AsTheTargetsX11ProgramsAskFor() {
        assertEquals(
            listOf("text/uri-list", "UTF8_STRING", "text/plain;charset=utf-8"),
            Xdnd.targetsFor(listOf(UriList.MIME, "text/plain;charset=utf-8")),
        )
        assertEquals(listOf("text/plain"), Xdnd.targetsFor(listOf("text/plain", "image/png")))
        assertEquals(emptyList<String>(), Xdnd.targetsFor(listOf("application/x-whatever")))
    }

    @Test
    fun fromAnX11ProgramHandTopFetchesFilesAndText() {
        // TARGETS of the dragging program, by name; GTK also offers its own types.
        assertEquals(
            listOf("text/uri-list", "UTF8_STRING"),
            Xdnd.wanted(listOf("TARGETS", "x-special/gnome-icon-list", "text/uri-list", "UTF8_STRING", "STRING")),
        )
        assertEquals(listOf("text/plain;charset=utf-8"), Xdnd.wanted(listOf("text/plain;charset=utf-8", "text/plain")))
        assertEquals(listOf("text/plain"), Xdnd.wanted(listOf("text/plain")))
        assertEquals(emptyList<String>(), Xdnd.wanted(listOf("image/png")))
    }
}
