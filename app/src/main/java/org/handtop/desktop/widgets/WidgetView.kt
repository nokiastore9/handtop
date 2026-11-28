package org.handtop.desktop.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.wm.WindowContent

/** What a widget needs from the desktop around it. */
interface WidgetHost {
    /** The Linux System window (install, status). */
    fun openLinuxSystem()

    /** Opens [content] in a window of the desktop. */
    fun openWindow(content: WindowContent, title: String, icon: Int)

    /** Asks for the approximate location (once, Android's dialog); [onResult] tells whether it is allowed. */
    fun requestLocation(onResult: (Boolean) -> Unit)
}

/**
 * A widget on the desktop: a card of the theme with its content. It refreshes only while
 * [onActive] says the desktop is on screen, and never draws anything per frame itself.
 */
@SuppressLint("ViewConstructor")
abstract class WidgetView(context: Context, val kind: WidgetKind, protected val host: WidgetHost) : FrameLayout(context) {

    protected val colors = Themes.current.widgetColors

    init {
        background = Themes.current.widgetBackground(context)
        // The glossy card's strip takes the top: content starts under it.
        setPadding(dpi(12), dpi(10), dpi(12), dpi(8))
        if (Themes.current.shadows) elevation = dp(4f)
        isClickable = true
        isLongClickable = true
    }

    /** The desktop came on screen ([active]) or went away: start or stop refreshing. */
    open fun onActive(active: Boolean) {}

    /** The widget has settings ([showSettings]). */
    open val hasSettings: Boolean = false

    open fun showSettings() {}

    /** A tap on the widget. */
    abstract fun onTap()

    /** A line of text in the widget's colours. */
    protected fun label(sizeSp: Float, dim: Boolean = false) = TextView(context).apply {
        textSize = sizeSp
        setTextColor(if (dim) colors.textDim else colors.text)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
}

/** The view for [kind]. */
fun widgetView(context: Context, kind: WidgetKind, host: WidgetHost): WidgetView = when (kind) {
    WidgetKind.CLOCK -> ClockWidget(context, host)
    WidgetKind.LINUX -> LinuxWidget(context, host)
    else -> PlaceholderWidget(context, kind, host)
}

/** A widget not built yet: its name, nothing else. */
@SuppressLint("ViewConstructor")
private class PlaceholderWidget(context: Context, kind: WidgetKind, host: WidgetHost) : WidgetView(context, kind, host) {
    init {
        addView(label(14f).apply {
            setText(if (kind == WidgetKind.WEATHER) R.string.widget_weather else R.string.widget_linux)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    override fun onTap() {}
}
