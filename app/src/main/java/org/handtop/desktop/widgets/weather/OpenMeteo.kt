package org.handtop.desktop.widgets.weather

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToLong

/** The weather now, the next hours and the next days, as Open-Meteo gives them. */
data class Forecast(val temperature: Double, val code: Int, val isDay: Boolean, val hours: List<Hour>, val days: List<Day>)

/** One of the next hours: "15:00". */
data class Hour(val time: String, val temperature: Double, val code: Int)

/** One day: its ISO date, the day's weather and its highest and lowest temperature. */
data class Day(val date: String, val code: Int, val max: Double, val min: Double)

/** A place found by name, with its region (state, province) and country to tell namesakes apart. */
data class Place(val name: String, val region: String?, val country: String?, val latitude: Double, val longitude: Double) {
    /** "Paris, Texas, United States". */
    val label: String get() = listOfNotNull(name, region, country).joinToString(", ")
}

/**
 * Open-Meteo (open-meteo.com): free, no key, no account. Only coordinates rounded to a
 * hundredth of a degree (about a kilometre) leave the phone.
 */
object OpenMeteo {

    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val SEARCH = "https://geocoding-api.open-meteo.com/v1/search"

    /** How many of the next hours the widget shows. */
    const val HOURS = 3

    /** [c] to a hundredth of a degree. */
    fun round(c: Double): Double = (c * 100).roundToLong() / 100.0 + 0.0

    fun forecastUrl(lat: Double, lon: Double, fahrenheit: Boolean): String {
        val coords = String.format(Locale.ROOT, "latitude=%s&longitude=%s", round(lat).toString(), round(lon).toString())
        return "$FORECAST?$coords" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&forecast_days=7&timezone=auto" +
            (if (fahrenheit) "&temperature_unit=fahrenheit" else "")
    }

    /**
     * The forecast in [json]. "Now" is the answer's own current time (the place's local time),
     * so the next hours are right whatever the phone's time zone. Missing hours or days are
     * simply fewer.
     */
    fun parseForecast(json: String): Forecast {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val now = current.optString("time")
        val hours = ArrayList<Hour>()
        root.optJSONObject("hourly")?.let { h ->
            val times = h.optJSONArray("time")
            val temps = h.optJSONArray("temperature_2m")
            val codes = h.optJSONArray("weather_code")
            if (times != null && temps != null && codes != null) {
                for (i in 0 until times.length()) {
                    val time = times.getString(i)
                    if (time <= now || hours.size >= HOURS) continue
                    hours += Hour(time.substringAfter('T'), temps.getDouble(i), codes.getInt(i))
                }
            }
        }
        val days = ArrayList<Day>()
        root.optJSONObject("daily")?.let { d ->
            val times = d.optJSONArray("time")
            val codes = d.optJSONArray("weather_code")
            val max = d.optJSONArray("temperature_2m_max")
            val min = d.optJSONArray("temperature_2m_min")
            if (times != null && codes != null && max != null && min != null) {
                for (i in 0 until times.length()) days += Day(times.getString(i), codes.getInt(i), max.getDouble(i), min.getDouble(i))
            }
        }
        return Forecast(current.getDouble("temperature_2m"), current.getInt("weather_code"), current.optInt("is_day", 1) == 1, hours, days)
    }

    fun searchUrl(name: String, language: String): String =
        "$SEARCH?name=${URLEncoder.encode(name.trim(), "UTF-8").replace("+", "%20")}&count=10&language=$language&format=json"

    fun parsePlaces(json: String): List<Place> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val o = results.getJSONObject(i)
            Place(
                o.getString("name"),
                o.optString("admin1").ifEmpty { null },
                o.optString("country").ifEmpty { null },
                o.getDouble("latitude"),
                o.getDouble("longitude"),
            )
        }
    }
}
