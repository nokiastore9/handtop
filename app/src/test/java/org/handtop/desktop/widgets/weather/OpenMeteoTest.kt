package org.handtop.desktop.widgets.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoTest {

    private val forecast = """
        {"current":{"time":"2026-11-28T14:00","temperature_2m":3.1,"weather_code":3,"is_day":1},
         "hourly":{"time":["2026-11-28T13:00","2026-11-28T14:00","2026-11-28T15:00","2026-11-28T16:00","2026-11-28T17:00","2026-11-28T18:00"],
                   "temperature_2m":[3.5,3.1,2.8,2.2,1.4,1.0],"weather_code":[3,3,3,61,61,61]},
         "daily":{"time":["2026-11-28","2026-11-29","2026-11-30","2026-12-01","2026-12-02","2026-12-03","2026-12-04"],
                  "weather_code":[3,61,71,0,2,45,95],
                  "temperature_2m_max":[4.0,3.0,1.0,-1.5,0.5,2.0,3.0],
                  "temperature_2m_min":[-1.0,0.0,-3.0,-6.0,-4.0,-1.0,0.0]}}
    """.trimIndent()

    @Test
    fun coordinatesAreRoundedToAHundredth() {
        assertEquals(55.76, OpenMeteo.round(55.75583), 1e-9)
        assertEquals(37.62, OpenMeteo.round(37.6173), 1e-9)
        assertEquals(0.0, OpenMeteo.round(-0.004), 1e-9)
    }

    @Test
    fun nearbyFixesAreTheSamePlace() {
        // A fix a few dozen metres away across a hundredth-of-a-degree line is still here.
        assertTrue(OpenMeteo.samePlace(55.7549, 37.6149, 55.7551, 37.6151))
        assertTrue(OpenMeteo.samePlace(55.75, 37.61, 55.79, 37.65))
        assertFalse(OpenMeteo.samePlace(55.75, 37.61, 55.85, 37.61))
        assertFalse(OpenMeteo.samePlace(55.75, 37.61, 48.85, 2.35))
    }

    @Test
    fun forecastUrlSendsOnlyRoundedCoordinates() {
        val url = OpenMeteo.forecastUrl(55.75583, 37.6173, fahrenheit = false)
        assertTrue(url, url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url, url.contains("latitude=55.76&longitude=37.62"))
        assertTrue(url, url.contains("timezone=auto"))
        assertFalse(url, url.contains("temperature_unit"))
        assertTrue(OpenMeteo.forecastUrl(1.0, 2.0, fahrenheit = true).contains("temperature_unit=fahrenheit"))
    }

    @Test
    fun parseForecast() {
        val f = OpenMeteo.parseForecast(forecast)
        assertEquals(3.1, f.temperature, 1e-9)
        assertEquals(3, f.code)
        assertTrue(f.isDay)
        assertEquals(listOf("15:00", "16:00", "17:00"), f.hours.map { it.time })
        assertEquals(listOf(2.8, 2.2, 1.4), f.hours.map { it.temperature })
        assertEquals(61, f.hours[1].code)
        assertEquals(7, f.days.size)
        assertEquals(Day("2026-11-28", 3, 4.0, -1.0), f.days[0])
    }

    @Test
    fun parseForecastToleratesMissingParts() {
        val json = """
            {"current":{"time":"2026-11-28T14:00","temperature_2m":-2.0,"weather_code":71,"is_day":0},
             "daily":{"time":["2026-11-28","2026-11-29","2026-11-30"],"weather_code":[71,71,3],
                      "temperature_2m_max":[0.0,1.0,2.0],"temperature_2m_min":[-5.0,-4.0,-3.0]}}
        """.trimIndent()
        val f = OpenMeteo.parseForecast(json)
        assertFalse(f.isDay)
        assertEquals(emptyList<Hour>(), f.hours)
        assertEquals(3, f.days.size)
    }

    @Test
    fun searchUrlAndPlaces() {
        val url = OpenMeteo.searchUrl("Нью Йорк", "ru")
        assertTrue(url, url.startsWith("https://geocoding-api.open-meteo.com/v1/search?name="))
        assertTrue(url, url.contains("language=ru"))
        assertFalse(url, url.contains(" "))
        val places = OpenMeteo.parsePlaces(
            """{"results":[{"name":"Paris","country":"France","admin1":"Île-de-France","latitude":48.85341,"longitude":2.3488},
                           {"name":"Paris","country":"United States","admin1":"Texas","latitude":33.66094,"longitude":-95.55551},
                           {"name":"Paris","latitude":1.0,"longitude":2.0}]}""",
        )
        assertEquals(3, places.size)
        assertEquals(Place("Paris", "Île-de-France", "France", 48.85341, 2.3488), places[0])
        // Several places of one name are told apart by their region.
        assertEquals("Paris, Texas, United States", places[1].label)
        assertEquals("Paris", places[2].label)
        assertEquals(emptyList<Place>(), OpenMeteo.parsePlaces("""{"generationtime_ms":0.1}"""))
    }
}
