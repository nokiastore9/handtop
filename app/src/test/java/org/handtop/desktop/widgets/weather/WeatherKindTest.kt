package org.handtop.desktop.widgets.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WeatherKindTest {

    @Test
    fun wmoCodes() {
        val expected = mapOf(
            0 to WeatherKind.CLEAR, 1 to WeatherKind.CLEAR, 2 to WeatherKind.PARTLY, 3 to WeatherKind.CLOUDY,
            45 to WeatherKind.FOG, 48 to WeatherKind.FOG, 51 to WeatherKind.DRIZZLE, 53 to WeatherKind.DRIZZLE,
            57 to WeatherKind.DRIZZLE, 61 to WeatherKind.RAIN, 63 to WeatherKind.RAIN, 67 to WeatherKind.RAIN,
            80 to WeatherKind.RAIN, 81 to WeatherKind.RAIN, 71 to WeatherKind.SNOW, 73 to WeatherKind.SNOW,
            77 to WeatherKind.SNOW, 85 to WeatherKind.SNOW, 86 to WeatherKind.SNOW, 95 to WeatherKind.THUNDER,
            96 to WeatherKind.THUNDER, 99 to WeatherKind.THUNDER, 150 to WeatherKind.CLOUDY,
        )
        for ((code, kind) in expected) assertEquals("code $code", kind, WeatherKind.of(code))
    }

    @Test
    fun everyKindHasDayAndNightIconsAndAWord() {
        for (kind in WeatherKind.entries) {
            assertNotEquals(0, kind.icon(day = true))
            assertNotEquals(0, kind.icon(day = false))
            assertNotEquals(0, kind.word)
        }
    }
}
