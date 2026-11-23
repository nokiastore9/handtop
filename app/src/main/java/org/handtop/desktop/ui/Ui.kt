package org.handtop.desktop.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import kotlin.math.roundToInt

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density
fun Context.dpi(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
fun View.dp(value: Float): Float = context.dp(value)
fun View.dpi(value: Int): Int = context.dpi(value)

/** The theme's ripple / hover background, used for every clickable item of the shell. */
fun Context.selectableBackground(borderless: Boolean = false): Drawable? {
    val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless
    else android.R.attr.selectableItemBackground
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) return null
    return getDrawable(tv.resourceId)
}

fun roundedRect(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0) =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

/** Colours of the desktop shell. Kept in one place so a theme engine can replace it later. */
object Palette {
    const val TITLE_ACTIVE = 0xFF26324A.toInt()
    const val TITLE_INACTIVE = 0xFF181E2B.toInt()
    const val TITLE_TEXT_ACTIVE = 0xFFFFFFFF.toInt()
    const val TITLE_TEXT_INACTIVE = 0xFF8A94A8.toInt()
    const val TASKBAR = 0xF0101522.toInt()
    const val TASKBAR_ITEM_ACTIVE = 0x30FFFFFF
    const val TASKBAR_INDICATOR = 0xFF4F8CFF.toInt()
    const val ACCENT = 0xFF4F8CFF.toInt()
    const val SNAP_PREVIEW_FILL = 0x404F8CFF
    const val SNAP_PREVIEW_STROKE = 0xB04F8CFF.toInt()
    const val CLOSE_HOVER = 0xFFE5484D.toInt()
    const val CLOSE_PRESSED = 0xFFB3261E.toInt()
    const val MENU_BG = 0xF51A2233.toInt()
    const val MENU_DIVIDER = 0x22FFFFFF
    const val TEXT = 0xFFE6EAF2.toInt()
    const val TEXT_DIM = 0xFF8A94A8.toInt()
    const val KEY_BAR_BG = 0xFF161B26.toInt()
    const val KEY_ON = 0xFF4F8CFF.toInt()
    val WALLPAPER = intArrayOf(0xFF0B1023.toInt(), 0xFF1B2A6B.toInt(), 0xFF3B1D5E.toInt())
}
