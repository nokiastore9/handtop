package org.handtop.desktop.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable

/** The themes HandTop can wear; [key] is how the choice is stored. */
enum class ThemeId(val key: String) {
    MODERN("modern"),
    CLASSIC("classic");

    companion object {
        /** The stored theme, Modern for nothing stored or anything unknown. */
        fun fromKey(key: String?): ThemeId = entries.firstOrNull { it.key == key } ?: MODERN
    }
}

/**
 * Every colour the shell draws with. The names are roles, not looks: [face], [light],
 * [shadow] and friends are the parts of a bevelled 3D edge, which only the classic theme
 * draws, but the modern theme has an answer for them too.
 */
class ThemeColors(
    val titleActive: Int,
    val titleActiveEnd: Int,
    val titleInactive: Int,
    val titleInactiveEnd: Int,
    val titleTextActive: Int,
    val titleTextInactive: Int,
    val taskbar: Int,
    val taskbarItemActive: Int,
    val taskbarIndicator: Int,
    val accent: Int,
    val snapPreviewFill: Int,
    val snapPreviewStroke: Int,
    val closeHover: Int,
    val closePressed: Int,
    val menuBg: Int,
    val menuDivider: Int,
    val text: Int,
    val textDim: Int,
    val textSoft: Int,
    val textDisabled: Int,
    val statusIconOff: Int,
    val chip: Int,
    val card: Int,
    val batteryLow: Int,
    val batteryCharging: Int,
    val keyBarBg: Int,
    val keyOn: Int,
    /** Background of a window's content (Settings, Files, About...). */
    val windowBg: Int,
    /** Side lists (Settings' categories, Files' places). */
    val sidebarBg: Int,
    /** Tool and status bars inside windows. */
    val barBg: Int,
    val warning: Int,
    /** The chosen row of a list. */
    val selection: Int,
    val selectionText: Int,
    /** Text fields (search, rename). */
    val fieldBg: Int,
    /** Lists and fields drawn as white sunken boxes in the classic theme. */
    val field: Int,
    /** Labels of the desktop's icons: they sit on the wallpaper, whatever the theme. */
    val desktopLabel: Int,
    val face: Int,
    val light: Int,
    val innerLight: Int,
    val shadow: Int,
    val darkShadow: Int,
)

/**
 * A look of the whole shell: its colours and what it allows itself (animations, blur,
 * shadows, rounded corners, tinting the monochrome icons).
 */
abstract class Theme(val id: ThemeId, val colors: ThemeColors) {
    abstract val animations: Boolean
    abstract val blur: Boolean
    abstract val shadows: Boolean
    abstract val tintIcons: Boolean
    abstract val roundedCorners: Boolean

    /** Controls are drawn as classic 3D: raised buttons, sunken lists and fields, etched groups. */
    abstract val bevelled: Boolean

    // ---- windows ----

    /** The frame around a window: [floating] is false when maximized or snapped to a side. */
    abstract fun windowFrame(context: Context, active: Boolean, floating: Boolean): Drawable

    /** The title bar's own background, or null to let the frame show through. */
    abstract fun titleBar(context: Context, active: Boolean): Drawable?

    /** A button of the title bar: minimize, maximize, close ([close]), or a toggle that is on ([checked]). */
    abstract fun titleButton(context: Context, close: Boolean, checked: Boolean = false): Drawable

    abstract val titleBold: Boolean

    // ---- menus and popups ----

    /** Background of a popup panel: menus, the start menu, the calendar, notifications. */
    abstract fun panel(context: Context, cornerDp: Float): Drawable

    /** Background of one menu line, showing when it is pressed or under the pointer. */
    abstract fun menuItemBackground(context: Context): Drawable

    /** Text of a menu line: normal, highlighted (with [menuItemBackground]) and disabled. */
    abstract fun menuTextColors(): ColorStateList

    /** Disabled text is engraved: a light copy one point down and right. */
    abstract val embossDisabled: Boolean

    /** The line between groups of menu items, and its height in pixels. */
    abstract fun divider(context: Context): Drawable
    abstract fun dividerHeight(context: Context): Int

    /** The start menu has a vertical band with HandTop's name along its left side. */
    abstract val startMenuBand: Boolean

    // ---- taskbar ----

    abstract fun taskbarBackground(context: Context): Drawable

    /** A window's button on the taskbar: the [front] window's stands out, a [minimized] one is quieter. */
    abstract fun taskButton(context: Context, front: Boolean, minimized: Boolean): Drawable

    /** The start button carries the word "Start" next to HandTop's mark. */
    abstract val startLabel: Boolean

    /** The start button: [open] while the start menu is showing. Null for a plain icon button. */
    abstract fun startButton(context: Context, open: Boolean): Drawable?

    /** Around the status icons and the clock, or null for none. */
    abstract fun trayBackground(context: Context): Drawable?

    /** A flat icon button (taskbar, toolbars): only its press shows. */
    abstract fun toolButton(context: Context): Drawable
}

/**
 * The theme in use. It is read once when the desktop is created and stays until the
 * desktop is created again: switching themes re-creates the activity, as a scale change does.
 */
object Themes {
    const val PREFS = "settings"
    const val KEY = "theme"

    @Volatile
    var current: Theme = ModernTheme
        private set

    fun load(context: Context) {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        current = of(ThemeId.fromKey(key))
    }

    fun choose(context: Context, id: ThemeId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, id.key).apply()
        current = of(id)
    }

    private fun of(id: ThemeId): Theme = when (id) {
        ThemeId.MODERN -> ModernTheme
        ThemeId.CLASSIC -> ClassicTheme
    }
}
