package org.handtop.desktop.widgets.weather

import org.handtop.desktop.R

/** The kinds of weather HandTop draws, from the WMO codes Open-Meteo gives. */
enum class WeatherKind(private val dayIcon: Int, private val nightIcon: Int, val word: Int) {
    CLEAR(R.drawable.weather_clear, R.drawable.weather_clear_night, R.string.weather_clear),
    PARTLY(R.drawable.weather_partly, R.drawable.weather_partly_night, R.string.weather_partly),
    CLOUDY(R.drawable.weather_cloudy, R.drawable.weather_cloudy, R.string.weather_cloudy),
    FOG(R.drawable.weather_fog, R.drawable.weather_fog, R.string.weather_fog),
    DRIZZLE(R.drawable.weather_drizzle, R.drawable.weather_drizzle, R.string.weather_drizzle),
    RAIN(R.drawable.weather_rain, R.drawable.weather_rain, R.string.weather_rain),
    SNOW(R.drawable.weather_snow, R.drawable.weather_snow, R.string.weather_snow),
    THUNDER(R.drawable.weather_thunder, R.drawable.weather_thunder, R.string.weather_thunder);

    fun icon(day: Boolean): Int = if (day) dayIcon else nightIcon

    companion object {
        /** The kind of a WMO weather code; an unknown code is drawn as clouds. */
        fun of(code: Int): WeatherKind = when (code) {
            0, 1 -> CLEAR
            2 -> PARTLY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDER
            else -> CLOUDY
        }
    }
}
