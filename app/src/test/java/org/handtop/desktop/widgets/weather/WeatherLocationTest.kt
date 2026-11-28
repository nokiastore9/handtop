package org.handtop.desktop.widgets.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherLocationTest {

    @Test
    fun networkFirst() = assertEquals("network", WeatherLocation.provider(setOf("network", "gps", "fused"), sdk = 34))

    @Test
    fun withoutNetworkLocationNewAndroidUsesFusedThenGps() {
        // Phones without Google services may have no network provider; coarse access may use these since Android 12.
        assertEquals("fused", WeatherLocation.provider(setOf("gps", "fused"), sdk = 31))
        assertEquals("gps", WeatherLocation.provider(setOf("gps"), sdk = 31))
    }

    @Test
    fun olderAndroidOnlyNetwork() {
        assertEquals(null, WeatherLocation.provider(setOf("gps"), sdk = 29))
        assertEquals(null, WeatherLocation.provider(emptySet(), sdk = 34))
    }
}
