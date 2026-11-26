package org.handtop.desktop.wayland

import org.handtop.desktop.wayland.X11ScaleMode.SCALED
import org.handtop.desktop.wayland.X11ScaleMode.SHARP
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class X11ScalingTest {

    @Test
    fun sharpIsTheDefaultMode() {
        assertEquals(SHARP, X11ScaleMode.fromKey(null))
        assertEquals(SHARP, X11ScaleMode.fromKey("nonsense"))
        assertEquals(SCALED, X11ScaleMode.fromKey("scaled"))
    }

    @Test
    fun sharpShowsOneXPixelPerScreenPixel() = assertEquals(1, X11Scaling.pixelsPerUnit(SHARP, 2))

    @Test
    fun scaledStretchesByTheOutputScale() = assertEquals(2, X11Scaling.pixelsPerUnit(SCALED, 2))

    @Test
    fun sharpXScreenIsThePhysicalOne() =
        assertArrayEquals(intArrayOf(3120, 1440), X11Scaling.screenSize(SHARP, 3120, 1440, 2))

    @Test
    fun scaledXScreenIsTheLogicalOne() =
        assertArrayEquals(intArrayOf(1560, 720), X11Scaling.screenSize(SCALED, 3120, 1440, 2))

    @Test
    fun sharpTellsXlibProgramsTheirDpiAndCursorSize() {
        val r = X11Scaling.resources(SHARP, 3)
        assertTrue(r, "Xft.dpi:\t288\n" in r)
        assertTrue(r, "Xcursor.size:\t72\n" in r)
        assertTrue(r, "XTerm*faceName:\tMonospace\n" in r) // xterm's bitmap fonts do not scale
    }

    @Test
    fun scaledProgramsDrawAt96Dpi() {
        val r = X11Scaling.resources(SCALED, 3)
        assertTrue(r, "Xft.dpi:\t96\n" in r)
        assertTrue(r, "Xcursor.size:\t24\n" in r)
    }

    @Test
    fun sharpXsettingsGiveGtkItsScale() {
        val s = parse(X11Scaling.xsettings(SHARP, 2, serial = 5))
        assertEquals(5, s.serial)
        assertEquals(2, s.ints["Gdk/WindowScalingFactor"])
        assertEquals(96 * 1024, s.ints["Gdk/UnscaledDPI"])
        assertEquals(192 * 1024, s.ints["Xft/DPI"])
        assertEquals("hintslight", s.strings["Xft/HintStyle"])
    }

    @Test
    fun scaledXsettingsKeepOneToOne() {
        val s = parse(X11Scaling.xsettings(SCALED, 2, serial = 1))
        assertEquals(1, s.ints["Gdk/WindowScalingFactor"])
        assertEquals(96 * 1024, s.ints["Xft/DPI"])
    }

    private class Settings(val serial: Int, val ints: Map<String, Int>, val strings: Map<String, String>)

    /** Reads the XSETTINGS format (byte order, serial, settings; each padded to 4 bytes). */
    private fun parse(b: ByteArray): Settings {
        fun u8(i: Int) = b[i].toInt() and 0xff
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        fun pad(n: Int) = (4 - n % 4) % 4
        assertEquals("little-endian", 0, u8(0))
        val ints = HashMap<String, Int>()
        val strings = HashMap<String, String>()
        var at = 12
        repeat(u32(8)) {
            val type = u8(at)
            val nameLength = u16(at + 2)
            val name = String(b, at + 4, nameLength, Charsets.US_ASCII)
            at += 4 + nameLength + pad(nameLength) + 4 // + last-change serial
            when (type) {
                0 -> { ints[name] = u32(at); at += 4 }
                1 -> {
                    val length = u32(at)
                    strings[name] = String(b, at + 4, length, Charsets.UTF_8)
                    at += 4 + length + pad(length)
                }
                else -> error("type $type")
            }
        }
        assertEquals("no trailing bytes", b.size, at)
        return Settings(u32(4), ints, strings)
    }
}
