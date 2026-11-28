package org.handtop.desktop.widgets.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherStoreTest {

    private val now = 10_000_000_000L
    private val minute = 60_000L
    private val forecast = Forecast(3.0, 3, true, emptyList(), emptyList())

    @Test
    fun refreshEveryHalfHour() {
        assertTrue(WeatherStore.needsRefresh(now, null))
        assertFalse(WeatherStore.needsRefresh(now, now - 29 * minute))
        assertTrue(WeatherStore.needsRefresh(now, now - 30 * minute))
    }

    @Test
    fun weatherStateWithoutDataOrNetwork() {
        assertEquals(WeatherState.NoDataYet(offline = true), WeatherStore.state(null, null, online = false, nowMs = now))
        assertEquals(WeatherState.NoDataYet(offline = false), WeatherStore.state(null, null, online = true, nowMs = now))
    }

    @Test
    fun cachedDataIsStaleOfflineOrOld() {
        assertEquals(WeatherState.Ready(forecast, now - 5 * minute, stale = true), WeatherStore.state(forecast, now - 5 * minute, online = false, nowMs = now))
        assertEquals(WeatherState.Ready(forecast, now - 5 * minute, stale = false), WeatherStore.state(forecast, now - 5 * minute, online = true, nowMs = now))
        assertEquals(WeatherState.Ready(forecast, now - 90 * minute, stale = true), WeatherStore.state(forecast, now - 90 * minute, online = true, nowMs = now))
    }

    @Test
    fun withoutALocationTheWidgetSaysWhy() {
        // Something cached: show it. Nothing, online: ask for a city. Nothing, offline: the network is the problem.
        assertEquals(null, WeatherStore.withoutLocation(hasCache = true, online = false))
        assertEquals(WeatherState.NeedsCity, WeatherStore.withoutLocation(hasCache = false, online = true))
        assertEquals(WeatherState.NoDataYet(offline = true), WeatherStore.withoutLocation(hasCache = false, online = false))
    }

    @Test
    fun units() {
        assertTrue(WeatherUnits.fahrenheit(null, "US"))
        assertFalse(WeatherUnits.fahrenheit(null, "RU"))
        assertTrue(WeatherUnits.fahrenheit("fahrenhe", "RU"))
        assertFalse(WeatherUnits.fahrenheit("celsius", "US"))
    }

    @Test
    fun theWidgetsOwnUnitWins() {
        // "c" or "f" chosen in the widget's settings, whatever the phone says; none: as in Android.
        assertFalse(WeatherUnits.fahrenheit("c", "fahrenhe", "US"))
        assertTrue(WeatherUnits.fahrenheit("f", "celsius", "RU"))
        assertTrue(WeatherUnits.fahrenheit(null, null, "US"))
        assertFalse(WeatherUnits.fahrenheit(null, "celsius", "US"))
    }

    @Test
    fun temperatureText() {
        assertEquals("+3°", WeatherUnits.degrees(3.1))
        assertEquals("−2°", WeatherUnits.degrees(-2.4))
        assertEquals("0°", WeatherUnits.degrees(-0.3))
    }
}
