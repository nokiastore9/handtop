package org.handtop.desktop.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTextTest {

    @Test
    fun patterns() {
        assertEquals("HH:mm", ClockText.timePattern(is24 = true, seconds = false))
        assertEquals("HH:mm:ss", ClockText.timePattern(is24 = true, seconds = true))
        assertEquals("h:mm a", ClockText.timePattern(is24 = false, seconds = false))
        assertEquals("h:mm:ss a", ClockText.timePattern(is24 = false, seconds = true))
    }

    @Test
    fun formatFollowsAndroidUnlessSet() {
        assertTrue(ClockText.is24(null, system24 = true))
        assertFalse(ClockText.is24(null, system24 = false))
        assertFalse(ClockText.is24("12", system24 = true))
        assertTrue(ClockText.is24("24", system24 = false))
    }

    @Test
    fun nextTickIsTheNextMinuteOrSecond() {
        assertEquals(30_000L, ClockText.delayToNextTick(nowMs = 90_000L, seconds = false))
        assertEquals(60_000L, ClockText.delayToNextTick(nowMs = 120_000L, seconds = false))
        assertEquals(250L, ClockText.delayToNextTick(nowMs = 5_750L, seconds = true))
    }
}
