package org.handtop.desktop.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import org.handtop.desktop.R
import org.handtop.desktop.shell.showCalendarPopup
import org.handtop.desktop.ui.choices
import org.handtop.desktop.ui.dialogBuilder
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.showDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The clock's text, apart from Android. */
object ClockText {
    /** The time pattern: 24- or 12-hour, with or without seconds. */
    fun timePattern(is24: Boolean, seconds: Boolean): String = when {
        is24 && seconds -> "HH:mm:ss"
        is24 -> "HH:mm"
        seconds -> "h:mm:ss a"
        else -> "h:mm a"
    }

    /** 24-hour from the widget's setting ("24", "12"), or as Android ([system24]) without one. */
    fun is24(setting: String?, system24: Boolean): Boolean = when (setting) {
        "24" -> true
        "12" -> false
        else -> system24
    }

    /** How long until the next minute (or second) starts. */
    fun delayToNextTick(nowMs: Long, seconds: Boolean): Long {
        val step = if (seconds) 1_000L else 60_000L
        return step - nowMs % step
    }
}

/** A large clock with the date under it; a tap shows the calendar. */
@SuppressLint("ViewConstructor")
class ClockWidget(context: Context, host: WidgetHost) : WidgetView(context, WidgetKind.CLOCK, host) {

    private val time = label(34f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val date = label(13f, dim = true)
    private var active = false
    private val tick = object : Runnable {
        override fun run() {
            show()
            if (active) postDelayed(this, ClockText.delayToNextTick(System.currentTimeMillis(), seconds()))
        }
    }

    init {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(time)
            addView(date)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        show()
    }

    private fun settings() = Widgets.load(context).firstOrNull { it.kind == kind }?.settings.orEmpty()

    private fun seconds() = settings()["seconds"] == "true"

    private fun show() {
        val s = settings()
        val locale = Locale.getDefault()
        val now = Date()
        val is24 = ClockText.is24(s["format"], DateFormat.is24HourFormat(context))
        time.text = SimpleDateFormat(ClockText.timePattern(is24, s["seconds"] == "true"), locale).format(now)
        date.text = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(now)
    }

    override fun onActive(active: Boolean) {
        this.active = active
        removeCallbacks(tick)
        if (active) tick.run()
    }

    override fun onTap() {
        showCalendarPopup(this, nearBottomRight = false)
    }

    override val hasSettings = true

    override fun showSettings() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), 0)
        }
        fillSettings(column)
        showDialog(context.dialogBuilder().setTitle(R.string.widget_clock).setView(column).setPositiveButton(android.R.string.ok, null))
    }

    /** The two choices (format, seconds), built again after each pick to show it. */
    private fun fillSettings(column: LinearLayout) {
        column.removeAllViews()
        val s = settings()
        val formats = listOf(null, "24", "12")
        column.addView(context.choices(
            listOf(context.getString(R.string.widget_clock_system), context.getString(R.string.widget_clock_24), context.getString(R.string.widget_clock_12)),
            null,
            formats.indexOf(s["format"]).coerceAtLeast(0),
        ) {
            Widgets.setSetting(context, kind, "format", formats[it])
            show()
            fillSettings(column)
        })
        column.addView(context.choices(
            listOf(context.getString(R.string.widget_clock_no_seconds), context.getString(R.string.widget_clock_seconds)),
            null,
            if (s["seconds"] == "true") 1 else 0,
        ) {
            Widgets.setSetting(context, kind, "seconds", if (it == 1) "true" else null)
            onActive(active)
            fillSettings(column)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8) })
    }
}
