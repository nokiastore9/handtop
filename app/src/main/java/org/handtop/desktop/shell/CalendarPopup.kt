package org.handtop.desktop.shell

import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CalendarView
import android.widget.PopupWindow
import org.handtop.desktop.ui.Popups
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.applyTheme
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.hideSystemBarsOfWindow

/**
 * Android's calendar in a popup panel of the theme: above the taskbar at the right edge, like
 * a desktop tray calendar ([nearBottomRight]), or under [anchor] (a clock widget).
 */
fun showCalendarPopup(anchor: View, nearBottomRight: Boolean = true): PopupWindow {
    val context = anchor.context
    // Android's calendar takes its colours from the context's theme: dark text on the
    // classic gray panel, light text on the modern dark one.
    val style = Themes.current.calendarStyle
    val themed = if (style != 0) ContextThemeWrapper(context, style) else context
    val calendar = CalendarView(themed)
    val popup = PopupWindow(calendar, WRAP_CONTENT, WRAP_CONTENT, true)
    popup.applyTheme(context, Themes.current.panel(context, 14f), 20f)
    popup.isOutsideTouchable = true
    if (nearBottomRight) {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val fromBottom = anchor.rootView.height - loc[1] + context.dpi(6)
        popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.END, context.dpi(6), fromBottom)
    } else {
        popup.showAsDropDown(anchor, 0, context.dpi(6), Gravity.START)
    }
    Popups.track(popup)
    calendar.hideSystemBarsOfWindow()
    return popup
}
