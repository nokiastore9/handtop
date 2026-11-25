package org.handtop.desktop.ui

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes

/** One line of a [showPopupMenu] menu; null in the list draws a divider. */
class MenuEntry(
    val label: CharSequence,
    @param:DrawableRes val icon: Int = 0,
    val enabled: Boolean = true,
    /** Icon tint; 0 keeps the icon's own colors. */
    val tint: Int = Palette.TEXT_SOFT,
    val action: () -> Unit,
)

/**
 * A context menu at a screen point (a right click or a long press), kept on screen: it opens
 * to the left / above the point when there is no room to the right / below.
 */
fun showPopupMenu(anchor: View, rawX: Float, rawY: Float, entries: List<MenuEntry?>): PopupWindow {
    val context = anchor.context
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dpi(6), 0, context.dpi(6))
    }
    val popup = PopupWindow(ScrollView(context).apply { addView(column) }, WRAP_CONTENT, WRAP_CONTENT, true)
    for (entry in entries) {
        if (entry == null) {
            column.addView(View(context).apply { setBackgroundColor(Palette.MENU_DIVIDER) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(1)).apply {
                    setMargins(context.dpi(8), context.dpi(4), context.dpi(8), context.dpi(4))
                })
            continue
        }
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dpi(12), 0, context.dpi(18), 0)
            minimumWidth = context.dpi(200)
            background = context.selectableBackground()
            isEnabled = entry.enabled
            alpha = if (entry.enabled) 1f else 0.4f
            if (entry.enabled) setOnClickListener { popup.dismiss(); entry.action() }
            addView(ImageView(context).apply {
                if (entry.icon != 0) setImageResource(entry.icon)
                if (entry.tint != 0) setColorFilter(entry.tint)
            }, LinearLayout.LayoutParams(context.dpi(18), context.dpi(18)))
            addView(TextView(context).apply {
                text = entry.label
                setTextColor(Palette.TEXT)
                textSize = 14f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(context.dpi(12), 0, 0, 0)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(38)))
    }
    popup.setBackgroundDrawable(roundedRect(Palette.MENU_BG, context.dp(10f)))
    popup.elevation = context.dp(16f)
    popup.isOutsideTouchable = true

    // Fit on screen: measure, then flip left / up as needed (window coordinates).
    val root = anchor.rootView
    val origin = IntArray(2).also { root.getLocationOnScreen(it) }
    column.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    // As wide as the longest line: a popup of WRAP_CONTENT would stretch to the screen.
    val w = minOf(column.measuredWidth, root.width)
    val h = minOf(column.measuredHeight, root.height)
    popup.width = w
    var x = rawX.toInt() - origin[0]
    var y = rawY.toInt() - origin[1]
    if (x + w > root.width) x = maxOf(0, x - w)
    if (y + h > root.height) y = maxOf(0, root.height - h)
    popup.height = h
    popup.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
    Popups.track(popup)
    column.hideSystemBarsOfWindow()
    return popup
}
