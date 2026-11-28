package org.handtop.desktop.widgets.weather

import android.content.Context
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.wm.WindowContent
import java.text.SimpleDateFormat
import java.util.Locale

/** The weather window: the place, the next 7 days, when it was updated, and the source. */
class WeatherContent(context: Context, forecast: Forecast, place: String?, fetchedMs: Long) : WindowContent {

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dpi(16), context.dpi(12), context.dpi(16), context.dpi(12))
    }
    override val view: View = ScrollView(context).apply {
        setBackgroundColor(Palette.WINDOW_BG)
        addView(column)
    }

    init {
        val locale = Locale.getDefault()
        column.addView(text(context, place ?: context.getString(R.string.weather_title), 18f, Palette.TEXT))
        val time = DateFormat.getTimeFormat(context).format(fetchedMs)
        column.addView(text(context, context.getString(R.string.widget_weather_updated, time), 12f, Palette.TEXT_DIM))
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val label = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
        for (day in forecast.days) {
            val kind = WeatherKind.of(day.code)
            column.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dpi(8), 0, 0)
                val date = runCatching { iso.parse(day.date)?.let { label.format(it) } }.getOrNull() ?: day.date
                addView(text(context, date, 14f, Palette.TEXT), LinearLayout.LayoutParams(context.dpi(120), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(ImageView(context).apply { setImageResource(kind.icon(day = true)) }, LinearLayout.LayoutParams(context.dpi(28), context.dpi(28)))
                addView(text(context, context.getString(kind.word), 13f, Palette.TEXT_DIM).apply { setPadding(context.dpi(10), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(text(context, "${WeatherUnits.degrees(day.max)} / ${WeatherUnits.degrees(day.min)}", 14f, Palette.TEXT))
            })
        }
        column.addView(text(context, context.getString(R.string.widget_weather_source), 11f, Palette.TEXT_DIM).apply {
            setPadding(0, context.dpi(14), 0, 0)
        })
    }

    private fun text(context: Context, value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
    }
}
