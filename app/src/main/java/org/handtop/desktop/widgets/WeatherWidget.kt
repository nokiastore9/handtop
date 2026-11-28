package org.handtop.desktop.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.util.LocalePreferences
import org.handtop.desktop.R
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.choices
import org.handtop.desktop.ui.dialogBuilder
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.ui.showDialog
import org.handtop.desktop.ui.styleField
import org.handtop.desktop.widgets.weather.Forecast
import org.handtop.desktop.widgets.weather.OpenMeteo
import org.handtop.desktop.widgets.weather.Place
import org.handtop.desktop.widgets.weather.WeatherContent
import org.handtop.desktop.widgets.weather.WeatherKind
import org.handtop.desktop.widgets.weather.WeatherLocation
import org.handtop.desktop.widgets.weather.WeatherState
import org.handtop.desktop.widgets.weather.WeatherStore
import org.handtop.desktop.widgets.weather.WeatherUnits
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The weather from Open-Meteo: now, the next hours, the place. By the phone's approximate
 * location, or a city the user picked; refreshed at most every 30 minutes while the desktop is
 * shown. A tap opens the 7-day forecast (or the settings when a city is needed).
 */
@SuppressLint("ViewConstructor")
class WeatherWidget(context: Context, host: WidgetHost) : WidgetView(context, WidgetKind.WEATHER, host) {

    private val icon = ImageView(context)
    private val temperature = label(30f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val word = label(13f)
    private val place = label(11f, dim = true)
    private val hours = label(12f)
    private val status = label(11f, dim = true)

    private var active = false
    private var shown: WeatherState? = null
    private var forecast: Forecast? = null
    private var fetchedMs = 0L
    private val every30 = Runnable { refresh() }

    init {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginEnd = dpi(10) })
                // The words take the rest of the width: "Partly cloudy" is not cut to the temperature's.
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(temperature)
                    addView(word)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(place)
            addView(hours.apply { setPadding(0, dpi(6), 0, 0) })
            addView(status)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render()
    }

    private fun settings() = Widgets.load(context).firstOrNull { it.kind == kind }?.settings.orEmpty()

    private fun byCity() = settings()["mode"] == "city"

    private fun fahrenheit() = WeatherUnits.fahrenheit(
        runCatching { LocalePreferences.getTemperatureUnit() }.getOrNull(),
        Locale.getDefault().country,
    )

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun onActive(active: Boolean) {
        this.active = active
        removeCallbacks(every30)
        if (active) refresh()
    }

    /** Finds where, then fetches when the cache is old or for another place, and shows it. */
    private fun refresh() {
        removeCallbacks(every30)
        if (active) postDelayed(every30, WeatherStore.REFRESH_MS)
        val s = settings()
        if (s["mode"] == "city") {
            val lat = s["lat"]?.toDoubleOrNull()
            val lon = s["lon"]?.toDoubleOrNull()
            if (lat == null || lon == null) return render(WeatherState.NeedsCity)
            fetchIfNeeded(lat, lon, s["city"])
        } else {
            if (!WeatherLocation.allowed(context)) return render(noLocation())
            WeatherLocation.locate(context) { location ->
                if (location == null) render(noLocation())
                else fetchIfNeeded(location.latitude, location.longitude, null)
            }
        }
    }

    private fun noLocation() = WeatherStore.withoutLocation(WeatherStore.cached(context) != null, online())

    private fun fetchIfNeeded(lat: Double, lon: Double, cityName: String?) {
        val f = fahrenheit()
        val cached = WeatherStore.cached(context)
        val samePlace = cached != null && OpenMeteo.samePlace(cached.lat, cached.lon, lat, lon) && cached.fahrenheit == f
        if (samePlace && !WeatherStore.needsRefresh(System.currentTimeMillis(), cached!!.fetchedMs)) return render()
        render() // what there is, while fetching
        val app = context.applicationContext
        EXECUTOR.execute {
            val json = WeatherStore.download(OpenMeteo.forecastUrl(lat, lon, f))
            val parsed = json?.let { runCatching { OpenMeteo.parseForecast(it) }.getOrNull() }
            post {
                if (json != null && parsed != null) {
                    WeatherStore.save(app, json, System.currentTimeMillis(), lat, lon, f)
                    if (cityName != null) WeatherStore.setPlace(app, cityName)
                    else WeatherLocation.placeName(app, lat, lon) { name -> WeatherStore.setPlace(app, name); render() }
                } else if (!samePlace && cached != null) {
                    // Another place, and no answer: the old place's weather would mislead.
                    WeatherStore.clear(app)
                }
                render()
            }
        }
    }

    /** Shows the cached forecast, or [forced] (why there is none). */
    private fun render(forced: WeatherState? = null) {
        val cached = WeatherStore.cached(context)
        forecast = cached?.let { runCatching { OpenMeteo.parseForecast(it.json) }.getOrNull() }
        fetchedMs = cached?.fetchedMs ?: 0
        val state = forced ?: WeatherStore.state(forecast, cached?.fetchedMs, online(), System.currentTimeMillis())
        shown = state
        when (state) {
            is WeatherState.Ready -> {
                val f = state.forecast
                val kind = WeatherKind.of(f.code)
                icon.setImageResource(kind.icon(f.isDay))
                icon.visibility = VISIBLE
                temperature.text = WeatherUnits.degrees(f.temperature)
                word.setText(kind.word)
                place.text = WeatherStore.place(context).orEmpty()
                place.visibility = if (place.text.isNullOrEmpty()) GONE else VISIBLE
                hours.text = f.hours.joinToString("  ·  ") { "${it.time.substringBefore(':').trimStart('0').ifEmpty { "0" }}  ${WeatherUnits.degrees(it.temperature)}" }
                hours.visibility = if (f.hours.isEmpty()) GONE else VISIBLE
                status.text = if (state.stale) context.getString(R.string.widget_weather_updated, DateFormat.getTimeFormat(context).format(state.fetchedMs)) else ""
                status.visibility = if (state.stale) VISIBLE else GONE
            }
            is WeatherState.NoDataYet -> showMessage(R.string.widget_weather_no_data, if (state.offline) R.string.widget_weather_offline else 0)
            WeatherState.NeedsCity -> showMessage(R.string.widget_weather_need_city, 0)
        }
    }

    private fun showMessage(main: Int, second: Int) {
        icon.setImageResource(WeatherKind.CLOUDY.icon(true))
        temperature.text = ""
        word.setText(main)
        place.visibility = GONE
        hours.visibility = GONE
        status.visibility = if (second != 0) VISIBLE else GONE
        if (second != 0) status.setText(second)
    }

    override fun onTap() {
        // "Choose a city" opens the settings, whatever led to it.
        if (shown == WeatherState.NeedsCity) return showSettings()
        val f = forecast
        if (f == null) return refresh()
        host.openWindow(WeatherContent(context, f, WeatherStore.place(context), fetchedMs), context.getString(R.string.weather_title), R.drawable.weather_partly)
    }

    // ---- settings: by location, or a city found by name ----

    override val hasSettings = true

    override fun showSettings() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), 0)
        }
        val dialog = showDialog(context.dialogBuilder().setTitle(R.string.widget_weather).setView(column).setNegativeButton(android.R.string.cancel, null))
        fillSettings(column) { dialog.dismiss() }
    }

    private fun fillSettings(column: LinearLayout, done: () -> Unit) {
        column.removeAllViews()
        val city = byCity()
        column.addView(context.choices(
            listOf(context.getString(R.string.widget_weather_by_location), context.getString(R.string.widget_weather_city)),
            null,
            if (city) 1 else 0,
        ) { pick ->
            if (pick == 0) {
                Widgets.setSetting(context, kind, "mode", null)
                WeatherStore.clear(context)
                host.requestLocation { refresh() }
                done()
            } else {
                Widgets.setSetting(context, kind, "mode", "city")
                fillSettings(column, done)
            }
        })
        if (!city) return
        val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val field = EditText(context).apply {
            setHint(R.string.widget_weather_search)
            setText(settings()["city"].orEmpty())
            setSingleLine()
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_DIM)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            styleField()
        }
        val search = Runnable { search(field.text.toString(), results, done) }
        field.setOnEditorActionListener { _, _, _ -> field.removeCallbacks(search); search.run(); true }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                field.removeCallbacks(search)
                field.postDelayed(search, 600)
            }
        })
        column.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10) })
        column.addView(results)
    }

    private fun search(name: String, results: LinearLayout, done: () -> Unit) {
        if (name.isBlank()) return results.removeAllViews()
        val language = Locale.getDefault().language
        EXECUTOR.execute {
            val places = WeatherStore.download(OpenMeteo.searchUrl(name, language))?.let { runCatching { OpenMeteo.parsePlaces(it) }.getOrNull() }
            results.post { showPlaces(places.orEmpty(), results, done) }
        }
    }

    private fun showPlaces(places: List<Place>, results: LinearLayout, done: () -> Unit) {
        results.removeAllViews()
        if (places.isEmpty()) {
            results.addView(TextView(context).apply {
                setText(R.string.widget_weather_nothing_found)
                setTextColor(Palette.TEXT_DIM)
                setPadding(0, dpi(8), 0, dpi(8))
            })
            return
        }
        for (p in places.take(6)) results.addView(TextView(context).apply {
            text = p.label
            textSize = 14f
            setTextColor(Palette.TEXT)
            setPadding(dpi(4), dpi(10), dpi(4), dpi(10))
            background = context.selectableBackground()
            setOnClickListener {
                Widgets.setSetting(context, kind, "city", p.name)
                Widgets.setSetting(context, kind, "lat", p.latitude.toString())
                Widgets.setSetting(context, kind, "lon", p.longitude.toString())
                WeatherStore.clear(context)
                refresh()
                done()
            }
        })
    }

    private companion object {
        val EXECUTOR = Executors.newSingleThreadExecutor { Thread(it, "weather").apply { isDaemon = true } }
    }
}
