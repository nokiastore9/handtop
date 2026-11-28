package org.handtop.desktop.widgets.weather

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** What the weather widget can show. */
sealed class WeatherState {
    /** A forecast fetched at [fetchedMs]; [stale] when it could not be refreshed (offline or old). */
    data class Ready(val forecast: Forecast, val fetchedMs: Long, val stale: Boolean) : WeatherState()

    /** Nothing fetched yet; [offline] says why. */
    data class NoDataYet(val offline: Boolean) : WeatherState()

    /** No location (no permission or no fix) and no city chosen. */
    data object NeedsCity : WeatherState()
}

/** Celsius or Fahrenheit, and how a temperature is written. */
object WeatherUnits {
    private val FAHRENHEIT_COUNTRIES = setOf("US", "BS", "BZ", "KY", "PW", "LR")

    /**
     * Fahrenheit when the phone's regional settings say so ([preference] from
     * `LocalePreferences.getTemperatureUnit()`: "fahrenhe" or "celsius"), or, without one, in
     * the countries that use it.
     */
    fun fahrenheit(preference: String?, country: String): Boolean = when (preference) {
        "fahrenhe" -> true
        "celsius" -> false
        else -> country.uppercase() in FAHRENHEIT_COUNTRIES
    }

    /** "+3°", "−2°", "0°". */
    fun degrees(t: Double): String {
        val n = t.roundToInt()
        return when {
            n > 0 -> "+$n°"
            n < 0 -> "−${-n}°"
            else -> "0°"
        }
    }
}

/**
 * The last forecast, kept in the app's preferences, and fetching a new one. Weather is
 * refreshed at most every 30 minutes, and only while the desktop is shown.
 */
object WeatherStore {

    const val REFRESH_MS = 30 * 60_000L
    private const val PREFS = "weather"

    fun needsRefresh(nowMs: Long, fetchedMs: Long?): Boolean = fetchedMs == null || nowMs - fetchedMs >= REFRESH_MS

    fun state(cached: Forecast?, fetchedMs: Long?, online: Boolean, nowMs: Long): WeatherState =
        if (cached == null || fetchedMs == null) WeatherState.NoDataYet(offline = !online)
        else WeatherState.Ready(cached, fetchedMs, stale = !online || needsRefresh(nowMs, fetchedMs))

    /** The cached answer, its fetch time and the coordinates it was for. */
    class Cached(val json: String, val fetchedMs: Long, val lat: Double, val lon: Double, val fahrenheit: Boolean)

    fun cached(context: Context): Cached? {
        val p = prefs(context)
        val json = p.getString("json", null) ?: return null
        return Cached(
            json,
            p.getLong("fetched", 0),
            p.getFloat("lat", 0f).toDouble(),
            p.getFloat("lon", 0f).toDouble(),
            p.getBoolean("fahrenheit", false),
        )
    }

    fun save(context: Context, json: String, fetchedMs: Long, lat: Double, lon: Double, fahrenheit: Boolean) {
        prefs(context).edit()
            .putString("json", json).putLong("fetched", fetchedMs)
            .putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).putBoolean("fahrenheit", fahrenheit)
            .apply()
    }

    /** The name of the place the cached forecast is for, when known. */
    fun place(context: Context): String? = prefs(context).getString("place", null)

    fun setPlace(context: Context, name: String?) = prefs(context).edit().putString("place", name).apply()

    /** Forgets the cached forecast (the place changed). */
    fun clear(context: Context) = prefs(context).edit().clear().apply()

    /** GETs [url] (off the main thread); null on any failure. */
    fun download(url: String): String? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", "HandTop")
        try {
            if (connection.responseCode == 200) connection.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
