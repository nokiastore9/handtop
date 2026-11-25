package org.handtop.desktop.ui

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
}
