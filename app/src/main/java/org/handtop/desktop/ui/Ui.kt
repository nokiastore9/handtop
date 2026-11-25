package org.handtop.desktop.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

/**
 * Exclusive full screen: Android's status and navigation bars are hidden (HandTop's taskbar
 * shows the status instead); a swipe from the screen edge shows them for a moment.
 */
fun Window.hideSystemBars() {
    val controller = WindowCompat.getInsetsController(this, decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
}

/**
 * The same for the window this view is shown in, e.g. a popup: a focused popup window
 * would otherwise bring the system bars back while it is open.
 */
@Suppress("DEPRECATION")
fun View.hideSystemBarsOfWindow() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowInsetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    } else {
        systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}

fun roundedRect(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0) =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

/**
 * Colours of the desktop shell, from the theme in use ([Themes.current]). Read them while
 * building views: switching themes builds the desktop again.
 */
object Palette {
    val TITLE_ACTIVE: Int get() = Themes.current.colors.titleActive
    val TITLE_INACTIVE: Int get() = Themes.current.colors.titleInactive
    val TITLE_TEXT_ACTIVE: Int get() = Themes.current.colors.titleTextActive
    val TITLE_TEXT_INACTIVE: Int get() = Themes.current.colors.titleTextInactive
    val TASKBAR: Int get() = Themes.current.colors.taskbar
    val TASKBAR_ITEM_ACTIVE: Int get() = Themes.current.colors.taskbarItemActive
    val TASKBAR_INDICATOR: Int get() = Themes.current.colors.taskbarIndicator
    val ACCENT: Int get() = Themes.current.colors.accent
    val SNAP_PREVIEW_FILL: Int get() = Themes.current.colors.snapPreviewFill
    val SNAP_PREVIEW_STROKE: Int get() = Themes.current.colors.snapPreviewStroke
    val CLOSE_HOVER: Int get() = Themes.current.colors.closeHover
    val CLOSE_PRESSED: Int get() = Themes.current.colors.closePressed
    val MENU_BG: Int get() = Themes.current.colors.menuBg
    val MENU_DIVIDER: Int get() = Themes.current.colors.menuDivider
    val TEXT: Int get() = Themes.current.colors.text
    val TEXT_DIM: Int get() = Themes.current.colors.textDim
    val STATUS_ICON_OFF: Int get() = Themes.current.colors.statusIconOff
    val CHIP: Int get() = Themes.current.colors.chip
    val CARD: Int get() = Themes.current.colors.card
    val TEXT_SOFT: Int get() = Themes.current.colors.textSoft
    val BATTERY_LOW: Int get() = Themes.current.colors.batteryLow
    val BATTERY_CHARGING: Int get() = Themes.current.colors.batteryCharging
    val KEY_BAR_BG: Int get() = Themes.current.colors.keyBarBg
    val KEY_ON: Int get() = Themes.current.colors.keyOn
    val WINDOW_BG: Int get() = Themes.current.colors.windowBg
    val SIDEBAR_BG: Int get() = Themes.current.colors.sidebarBg
    val BAR_BG: Int get() = Themes.current.colors.barBg
    val WARNING: Int get() = Themes.current.colors.warning
    val SELECTION: Int get() = Themes.current.colors.selection
    val SELECTION_TEXT: Int get() = Themes.current.colors.selectionText
    val FIELD_BG: Int get() = Themes.current.colors.fieldBg
    val FIELD: Int get() = Themes.current.colors.field
    val DESKTOP_LABEL: Int get() = Themes.current.colors.desktopLabel
    val FACE: Int get() = Themes.current.colors.face
    val TEXT_DISABLED: Int get() = Themes.current.colors.textDisabled
}
