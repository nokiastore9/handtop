package org.handtop.desktop.ui

import org.handtop.desktop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {

    @Test
    fun fromKeyDefaultsToModern() {
        assertEquals(ThemeId.MODERN, ThemeId.fromKey(null))
        assertEquals(ThemeId.MODERN, ThemeId.fromKey("modern"))
        assertEquals(ThemeId.CLASSIC, ThemeId.fromKey("classic"))
        assertEquals(ThemeId.MODERN, ThemeId.fromKey("nonsense"))
    }

    @Test
    fun classicColoursAreTheSpecs() = with(ClassicTheme.colors) {
        assertEquals(0xFFC0C0C0.toInt(), face)
        assertEquals(0xFFFFFFFF.toInt(), light)
        assertEquals(0xFFDFDFDF.toInt(), innerLight)
        assertEquals(0xFF808080.toInt(), shadow)
        assertEquals(0xFF0A0A0A.toInt(), darkShadow)
        assertEquals(0xFF000080.toInt(), titleActive)
        assertEquals(0xFF1084D0.toInt(), titleActiveEnd)
        assertEquals(0xFF808080.toInt(), titleInactive)
        assertEquals(0xFFB4B4B4.toInt(), titleInactiveEnd)
        assertEquals(0xFFDFDFDF.toInt(), titleTextInactive)
        assertEquals(0xFFFFFFFF.toInt(), titleTextActive)
        assertEquals(0xFF000080.toInt(), selection)
        assertEquals(0xFFFFFFFF.toInt(), selectionText)
        assertEquals(0xFFFFFFFF.toInt(), field)
        assertEquals(0xFF000000.toInt(), text)
        assertEquals(0xFF808080.toInt(), textDisabled)
        assertEquals(0xFFFFFFFF.toInt(), desktopLabel)
    }

    @Test
    fun classicHasNoEffects() = with(ClassicTheme) {
        assertFalse(animations)
        assertFalse(blur)
        assertFalse(shadows)
        assertFalse(tintIcons)
        assertFalse(roundedCorners)
    }

    @Test
    fun modernKeepsTodaysColours() = with(ModernTheme.colors) {
        assertEquals(0xFFE6EAF2.toInt(), text)
        assertEquals(0xFF4F8CFF.toInt(), accent)
        assertEquals(0xF0101522.toInt(), taskbar)
        assertEquals(0xFF26324A.toInt(), titleActive)
        assertEquals(0xFF0F1420.toInt(), windowBg)
        assertEquals(0xFFFFFFFF.toInt(), desktopLabel)
    }

    /** Perceived brightness 0..1 of an ARGB colour (android.graphics.Color is a stub here). */
    private fun brightness(argb: Int): Double {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000.0 / 255.0
    }

    @Test
    fun classicSnapPreviewHasALightAndADarkLine() = with(ClassicTheme.colors) {
        // Most wallpapers are dark, turquoise is mid: one light and one dark line show on both.
        assertTrue(brightness(snapPreviewHalo) > 0.8)
        assertTrue(brightness(snapPreviewStroke) < 0.2)
        assertEquals(0xFF, snapPreviewHalo ushr 24)
    }

    @Test
    fun modernSnapPreviewHasNoHalo() = assertEquals(0, ModernTheme.colors.snapPreviewHalo)

    @Test
    fun modernHasItsEffects() = with(ModernTheme) {
        assertTrue(animations)
        assertTrue(blur)
        assertTrue(shadows)
        assertTrue(tintIcons)
        assertTrue(roundedCorners)
    }
    @Test
    fun taskbarTextIsTodaysTextInModernAndClassic() {
        for (t in listOf(ModernTheme, ClassicTheme)) with(t.colors) {
            assertEquals(text, taskbarText)
            assertEquals(textDim, taskbarTextDim)
            assertEquals(0, iconTint)
            assertEquals(0, titleGlyph)
        }
        assertEquals(ModernTheme.colors.text, ModernTheme.colors.onAccent)
    }

    @Test
    fun modernAndClassicKeepTheirBaseAndTitles() {
        for (t in listOf(ModernTheme, ClassicTheme)) {
            assertFalse(t.light)
            assertFalse(t.titleCentered)
        }
        assertEquals(0, ModernTheme.dialogStyle)
        assertEquals(0, ModernTheme.calendarStyle)
        assertEquals(R.style.HandTop_ClassicDialog, ClassicTheme.dialogStyle)
        assertEquals(R.style.HandTop_ClassicCalendar, ClassicTheme.calendarStyle)
    }

    @Test
    fun contrastOfBlackOnWhiteIs21() {
        assertEquals(21.0, contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, contrast(0xFF808080.toInt(), 0xFF808080.toInt()), 0.001)
        // Half-transparent black over white is mid gray.
        assertEquals(0xFF7F7F7F.toInt(), over(0x80000000.toInt(), 0xFFFFFFFF.toInt()))
    }

    // ---- readability (WCAG 2 contrast) ----

    /** Relative luminance of an opaque ARGB colour. */
    private fun luminance(argb: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    /** [top] (maybe translucent) composited over opaque [bottom]. */
    private fun over(top: Int, bottom: Int): Int {
        val a = (top ushr 24) / 255.0
        fun mix(shift: Int) = Math.round((top shr shift and 0xFF) * a + (bottom shr shift and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun contrast(fg: Int, bg: Int): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** [fg] reads on [bg] at [min] : 1 or better; a translucent [bg] over white and over black. */
    private fun assertReadable(what: String, fg: Int, bg: Int, min: Double = 4.5) {
        for (backdrop in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
            val under = over(bg, backdrop)
            val ratio = contrast(over(fg, under), under)
            assertTrue("$what: %.2f < $min on #%08X".format(ratio, under), ratio >= min)
        }
    }
}
