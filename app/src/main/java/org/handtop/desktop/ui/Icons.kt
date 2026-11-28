package org.handtop.desktop.ui

import android.widget.ImageView
import androidx.annotation.DrawableRes
import org.handtop.desktop.R

/**
 * The classic theme's icons: for each of HandTop's icons `ic_x` a colour one, `classic_ic_x`
 * (drawn by tools/icons/make_classic_icons.py). Found by name once, from the resource table.
 */
object ClassicIcons {

    private val map: Map<Int, Int> by lazy {
        val ids = R.drawable::class.java.fields.associate { it.name to it.getInt(null) }
        ids.filterKeys { it.startsWith("ic_") }
            .mapNotNull { (name, id) -> ids["classic_$name"]?.let { id to it } }
            .toMap()
    }

    /** The classic counterpart of [res], or [res] itself when there is none. */
    fun forBase(@DrawableRes res: Int): Int = map[res] ?: res
}

/** HandTop's icons that have colours of their own: a theme's [ThemeColors.iconTint] leaves them alone. */
internal val COLOURED_ICONS = setOf(
    R.drawable.ic_files, R.drawable.ic_linux,
    R.drawable.weather_clear, R.drawable.weather_clear_night, R.drawable.weather_partly, R.drawable.weather_partly_night,
    R.drawable.weather_cloudy, R.drawable.weather_fog, R.drawable.weather_drizzle, R.drawable.weather_rain,
    R.drawable.weather_snow, R.drawable.weather_thunder,
)

/**
 * One of HandTop's icons in the current theme: the white icon tinted [tint] (or the theme's
 * [ThemeColors.iconTint], or as it is) in the modern look, the colour icon untinted in the
 * classic one. An icon [onWallpaper] (the desktop's) stays white, whatever the theme.
 */
fun ImageView.setThemedIcon(@DrawableRes res: Int, tint: Int? = null, onWallpaper: Boolean = false) {
    imageTintList = null
    if (Themes.current.tintIcons) {
        setImageResource(res)
        val themeTint = Themes.current.colors.iconTint.takeIf { it != 0 && !onWallpaper && res !in COLOURED_ICONS }
        val color = tint ?: themeTint
        if (color != null) setColorFilter(color) else clearColorFilter()
    } else {
        setImageResource(ClassicIcons.forBase(res))
        clearColorFilter()
    }
}

/** The white icon [res] in [color], whatever the theme: title buttons and taskbar icons of themes that colour them. */
fun ImageView.setGlyph(@DrawableRes res: Int, color: Int) {
    imageTintList = null
    setImageResource(res)
    setColorFilter(color)
}

/**
 * An icon on the taskbar: in the theme's [ThemeColors.taskbarIcon] when it has one (a colour
 * icon would not show on its bar), otherwise as [setThemedIcon] with [usual].
 */
fun ImageView.setBarIcon(@DrawableRes res: Int, usual: Int? = null) {
    val color = Themes.current.colors.taskbarIcon
    if (color != 0) setGlyph(res, color) else setThemedIcon(res, usual)
}

/**
 * The tint of icon [res] on the taskbar: the theme's [ThemeColors.taskbarIcon] when it has one,
 * except for the coloured icons, which keep their colours; otherwise [usual].
 */
fun barIconTint(colors: ThemeColors, @DrawableRes res: Int, usual: Int?): Int? = when {
    colors.taskbarIcon == 0 -> usual
    res in COLOURED_ICONS -> null
    else -> colors.taskbarIcon
}
