package org.handtop.desktop.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.view.Display
import android.view.WindowManager
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Interface scale chosen in Settings, kept separately for the phone's own screen and for a
 * DeX / external display. It is applied as an override of the activity's density, so every
 * dp and sp of the shell follows (windows, taskbar, terminal text); Linux programs get the
 * nearest integer Wayland scale.
 */
object UiScale {

    /** Percent of the system density offered in Settings. */
    val CHOICES = intArrayOf(50, 60, 70, 80, 90, 100, 115, 130)

    /** The system density itself: no override. */
    private const val SYSTEM = 100

    /**
     * The scale a display starts at until one is chosen in Settings: 70 % on the phone's own
     * screen, where the system density leaves little room for windows; DeX or a monitor
     * already has a desktop density.
     */
    fun defaultPercent(desktop: Boolean): Int = if (desktop) SYSTEM else 70

    private const val PREFS = "ui"
    private const val KEY_PHONE = "scale_phone"
    private const val KEY_DESKTOP = "scale_desktop"

    /** DeX, a monitor or "DeX for PC": anything but the phone's built-in screen. */
    fun isDesktop(context: Context): Boolean {
        val config = context.resources.configuration
        if (config.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_DESK) return true
        val display = displayOf(context)
        return display != null && display.displayId != Display.DEFAULT_DISPLAY
    }

    private fun displayOf(context: Context): Display? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display
        else @Suppress("DEPRECATION") context.getSystemService(WindowManager::class.java)?.defaultDisplay
    } catch (_: RuntimeException) {
        null // not a visual context
    }

    fun percent(context: Context, desktop: Boolean): Int =
        prefs(context).getInt(if (desktop) KEY_DESKTOP else KEY_PHONE, defaultPercent(desktop))

    fun setPercent(context: Context, desktop: Boolean, percent: Int) {
        prefs(context).edit().putInt(if (desktop) KEY_DESKTOP else KEY_PHONE, percent).apply()
    }

    /**
     * The density delta for an activity whose base context is [base], or null at 100 %.
     * Only densityDpi is set, so orientation, size, font scale etc. keep following the system.
     */
    fun override(base: Context, desktop: Boolean): Configuration? {
        val dpi = overrideDpi(systemDpi(base), percent(base, desktop)) ?: return null
        return Configuration().apply { // a blank delta: everything "undefined"
            densityDpi = dpi
        }
    }

    /** The density to force for [percent] of [systemDpi], or null at the system's own (100 %). */
    fun overrideDpi(systemDpi: Int, percent: Int): Int? =
        if (percent == SYSTEM) null else scaledDpi(systemDpi, percent)

    fun scaledDpi(systemDpi: Int, percent: Int) = (systemDpi * percent / 100f).roundToInt().coerceAtLeast(72)

    /**
     * The system's own density for the display [context] is on. Read through a display context
     * of the application: an activity's base resources share its token and, after recreate(),
     * were seen to report the overridden density.
     */
    fun systemDpi(context: Context): Int {
        val display = displayOf(context) ?: return Resources.getSystem().configuration.densityDpi
        return (context.applicationContext ?: context).createDisplayContext(display)
            .resources.configuration.densityDpi
    }

    /** Integer Wayland output scale for a density: 1x up to ~1.75, 2x up to ~2.75, else 3x. */
    fun waylandScale(density: Float): Int = floor(density + 0.25f).toInt().coerceIn(1, 3)

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
