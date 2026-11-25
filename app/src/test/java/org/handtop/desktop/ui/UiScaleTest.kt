package org.handtop.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiScaleTest {

    @Test
    fun phoneScreenStartsAt70Percent() = assertEquals(70, UiScale.defaultPercent(desktop = false))

    @Test
    fun desktopDisplayStartsAtItsOwnDensity() = assertEquals(100, UiScale.defaultPercent(desktop = true))

    @Test
    fun everyDefaultIsOfferedInSettings() {
        assertTrue(UiScale.defaultPercent(desktop = false) in UiScale.CHOICES)
        assertTrue(UiScale.defaultPercent(desktop = true) in UiScale.CHOICES)
    }

    @Test
    fun thePhoneDefaultReallyScales() =
        // 70 % must change the density, not read as "no override" as 100 % does.
        assertEquals(336, UiScale.overrideDpi(480, UiScale.defaultPercent(desktop = false)))

    @Test
    fun fullScaleKeepsTheSystemDensity() = assertNull(UiScale.overrideDpi(480, 100))
}
