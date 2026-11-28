package org.handtop.desktop.linux

import android.content.Context
import android.util.Log
import org.handtop.desktop.ui.ClassicTheme
import org.handtop.desktop.ui.ThemeColors
import org.handtop.desktop.ui.ThemeId
import org.handtop.desktop.ui.Themes
import java.io.File
import java.io.IOException

/**
 * HandTop's look inside Linux programs. With the classic theme, every program HandTop starts
 * (menu, terminals, Files) finds classic settings for its toolkit; with the modern one,
 * programs keep their own look. Programs take the look when they start.
 *
 * Nothing is installed in the distribution and no user file is changed: HandTop writes its
 * files under [DIR] and points the toolkits there with environment variables, so it works the
 * same on every distribution, with or without a desktop's packages:
 * - Qt and KDE programs: Qt's built-in "kde" platform theme reads `kdeglobals` from the XDG
 *   config dirs (widget style "Windows", Qt's own, and the palette); KDE Frameworks read the
 *   same file and the colour scheme it names from the XDG data dirs.
 * - GTK 3, GTK 4 and libadwaita: GTK_THEME names HandTop's theme (built from Adwaita's and
 *   libadwaita's own stylesheets by tools/linux-theme/build.py, in the app's assets); with it,
 *   libadwaita programs use the theme instead of their built-in stylesheet.
 * - GTK 2: an rc file (the default engine draws the bevels), before the user's own.
 *
 * A user's own settings still win: their files come first in every lookup, and a login profile
 * that exports these variables overrides HandTop's.
 */
object LinuxTheme {

    /** HandTop's classic files inside Linux. */
    const val DIR = "/usr/local/share/handtop/classic"

    /** The KDE colour scheme's id (its file name in color-schemes). */
    const val KDE_SCHEME = "HandTopClassic"

    /** The GTK theme's name (a folder in themes). */
    const val GTK_NAME = "HandTop-Classic"

    /** The GTK 2 rc file, relative to [DIR]. */
    const val GTK2_RC = "share/themes/$GTK_NAME/gtk-2.0/gtkrc"

    private const val TAG = "HandTopTheme"

    /** The app assets with the built GTK themes (gtk-3.0, gtk-4.0). */
    private const val ASSETS = "linux-theme/classic"

    /** Where distributions keep libgtk-4: Debian and Ubuntu, Alpine, Fedora. */
    private val GTK4_LIBRARY_DIRS = listOf("usr/lib/aarch64-linux-gnu", "usr/lib", "usr/lib64")

    /** The variables Linux programs get for [theme]. */
    fun environment(theme: ThemeId): List<String> = when (theme) {
        ThemeId.MODERN, ThemeId.SOFT -> emptyList()
        ThemeId.CLASSIC -> listOf(
            "QT_QPA_PLATFORMTHEME=kde",
            "KDE_SESSION_VERSION=6",
            "XDG_CONFIG_DIRS=$DIR/xdg:/etc/xdg",
            "XDG_DATA_DIRS=$DIR/share:/usr/local/share:/usr/share",
            "GTK2_RC_FILES=$DIR/$GTK2_RC:/root/.gtkrc-2.0",
            "GTK_THEME=$GTK_NAME",
            "GTK_OVERLAY_SCROLLING=0",
        )
    }

    /** The variables for the theme the user chose (read from the settings: also works without the desktop). */
    fun environment(context: Context): List<String> {
        val key = context.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getString(Themes.KEY, null)
        return environment(ThemeId.fromKey(key))
    }

    /**
     * Writes the classic files into [rootfs]: after a HandTop update, or when the GTK 4 in
     * Linux needs the other stylesheet (it may be installed or upgraded at any time). Cheap
     * otherwise: a stamp file says what was written.
     */
    fun install(context: Context, rootfs: File) {
        val dir = File(rootfs, DIR.removePrefix("/"))
        val gtk4 = gtk4Stylesheet(gtk4Minor(GTK4_LIBRARY_DIRS.flatMap { File(rootfs, it).list()?.toList().orEmpty() }))
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val stamp = "$updated $gtk4\n"
        val stampFile = File(dir, ".installed")
        try {
            if (stampFile.isFile && stampFile.readText() == stamp) return
            for ((path, text) in files(ClassicTheme.colors)) write(File(dir, path), text.toByteArray())
            copyAssets(context, ASSETS, File(dir, "share/themes/$GTK_NAME"), gtk4)
            stampFile.writeText(stamp)
        } catch (e: IOException) {
            Log.w(TAG, "cannot write the classic theme into $dir", e)
        }
    }

    /** The built GTK themes (tools/linux-theme/build.py): [asset] folder into [target]. */
    private fun copyAssets(context: Context, asset: String, target: File, gtk4: String) {
        val names = context.assets.list(asset).orEmpty()
        if (names.isEmpty()) {
            // A file. GTK 4 reads gtk.css: the stylesheet for this GTK, under that name.
            val name = asset.substringAfterLast('/')
            val inGtk4 = asset.contains("/gtk-4.0/") && !asset.contains("/assets/")
            if (inGtk4 && name != gtk4) return
            val out = if (inGtk4) File(target.parentFile, "gtk.css") else target
            write(out, context.assets.open(asset).use { it.readBytes() })
            return
        }
        for (name in names) copyAssets(context, "$asset/$name", File(target, name), gtk4)
    }

    private fun write(file: File, bytes: ByteArray) {
        if (file.isFile && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)) return
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    /** The minor version of the GTK 4 among library [names] (libgtk-4.so.1.MM00.p), or null. */
    fun gtk4Minor(names: List<String>): Int? = names.firstNotNullOfOrNull { name ->
        Regex("""libgtk-4\.so\.1\.(\d+)\.\d+""").matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.div(100)
    }

    /**
     * The GTK 4 stylesheet for GTK 4.[minor]: built from libadwaita 1.9 for GTK 4.20 and later,
     * from libadwaita 1.7 for older GTK 4, whose CSS parser does not read the newer one.
     */
    fun gtk4Stylesheet(minor: Int?): String = if (minor != null && minor < 20) "gtk-4.18.css" else "gtk.css"

    /** The classic files, by path relative to [DIR], in the colours of [c]. */
    fun files(c: ThemeColors): Map<String, String> = mapOf(
        "xdg/kdeglobals" to kdeglobals(c),
        "share/color-schemes/$KDE_SCHEME.colors" to kdeColorScheme(c),
        "share/themes/$GTK_NAME/index.theme" to INDEX_THEME,
        GTK2_RC to gtk2Rc(c),
    )

    // ---- Qt and KDE ----

    private fun kdeglobals(c: ThemeColors) = """
        |# Written by HandTop for its classic theme; changes are overwritten.
        |# Qt reads the top-level keys, KDE Frameworks [KDE] and [UiSettings].
        |[General]
        |ColorScheme=$KDE_SCHEME
        |widgetStyle=Windows
        |
        |[KDE]
        |widgetStyle=Windows
        |
        |[UiSettings]
        |ColorScheme=$KDE_SCHEME
        |
        |""".trimMargin() + kdeColors(c)

    private fun kdeColorScheme(c: ThemeColors) = """
        |[General]
        |ColorScheme=$KDE_SCHEME
        |Name=HandTop Classic
        |
        |""".trimMargin() + kdeColors(c) + """
        |
        |[WM]
        |activeBackground=${rgb(c.titleActive)}
        |activeForeground=${rgb(c.titleTextActive)}
        |inactiveBackground=${rgb(c.titleInactive)}
        |inactiveForeground=${rgb(c.titleTextInactive)}
        |""".trimMargin()

    /** The colour groups of a KDE scheme: gray surfaces, white views, blue selection. */
    private fun kdeColors(c: ThemeColors): String {
        val face = c.face
        val text = c.text
        val group = { name: String, background: Int, alternate: Int, foreground: Int, inactive: Int, accent: Int ->
            """
            |[Colors:$name]
            |BackgroundAlternate=${rgb(alternate)}
            |BackgroundNormal=${rgb(background)}
            |DecorationFocus=${rgb(accent)}
            |DecorationHover=${rgb(accent)}
            |ForegroundActive=${rgb(accent)}
            |ForegroundInactive=${rgb(inactive)}
            |ForegroundLink=${rgb(if (foreground == text) LINK else foreground)}
            |ForegroundNegative=${rgb(if (foreground == text) c.warning else foreground)}
            |ForegroundNeutral=${rgb(if (foreground == text) NEUTRAL else foreground)}
            |ForegroundNormal=${rgb(foreground)}
            |ForegroundPositive=${rgb(if (foreground == text) POSITIVE else foreground)}
            |ForegroundVisited=${rgb(if (foreground == text) VISITED else foreground)}
            |""".trimMargin()
        }
        return listOf(
            // Disabled text fades two thirds towards the background: gray, as in the shell.
            """
            |[ColorEffects:Disabled]
            |Color=${rgb(c.textDisabled)}
            |ColorAmount=0
            |ColorEffect=0
            |ContrastAmount=0.65
            |ContrastEffect=1
            |IntensityAmount=0
            |IntensityEffect=0
            |""".trimMargin(),
            // Inactive windows keep their colours, as in the shell.
            """
            |[ColorEffects:Inactive]
            |ChangeSelectionColor=false
            |Enable=false
            |""".trimMargin(),
            group("Window", face, face, text, c.textDisabled, c.accent),
            group("Button", face, face, text, c.textDisabled, c.accent),
            group("Header", face, face, text, c.textDisabled, c.accent),
            group("View", c.field, ALTERNATE_ROW, text, c.textDisabled, c.accent),
            group("Selection", c.selection, c.selection, c.selectionText, face, c.selectionText),
            group("Tooltip", TOOLTIP, TOOLTIP, text, c.textDisabled, c.accent),
            group("Complementary", c.selection, c.selection, c.selectionText, face, c.selectionText),
        ).joinToString("\n")
    }

    // ---- GTK ----

    private val INDEX_THEME = """
        |[Desktop Entry]
        |Type=X-GNOME-Metatheme
        |Name=$GTK_NAME
        |Comment=HandTop's classic theme, written by HandTop
        |Encoding=UTF-8
        |
        |[X-GNOME-Metatheme]
        |GtkTheme=$GTK_NAME
        |""".trimMargin() + "\n"

    /** GTK 2's default engine draws the classic bevels from bg's light and dark shades. */
    private fun gtk2Rc(c: ThemeColors) = """
        |# Written by HandTop for its classic theme; changes are overwritten.
        |gtk-color-scheme = "bg_color:${hex(c.face)}\nfg_color:${hex(c.text)}\nbase_color:${hex(c.field)}\ntext_color:${hex(c.text)}\nselected_bg_color:${hex(c.selection)}\nselected_fg_color:${hex(c.selectionText)}\ntooltip_bg_color:${hex(TOOLTIP)}\ntooltip_fg_color:${hex(c.text)}"
        |
        |style "handtop-classic" {
        |    xthickness = 2
        |    ythickness = 2
        |    bg[NORMAL] = "${hex(c.face)}"
        |    bg[PRELIGHT] = "${hex(c.face)}"
        |    bg[ACTIVE] = "${hex(c.face)}"
        |    bg[SELECTED] = "${hex(c.selection)}"
        |    bg[INSENSITIVE] = "${hex(c.face)}"
        |    fg[NORMAL] = "${hex(c.text)}"
        |    fg[PRELIGHT] = "${hex(c.text)}"
        |    fg[ACTIVE] = "${hex(c.text)}"
        |    fg[SELECTED] = "${hex(c.selectionText)}"
        |    fg[INSENSITIVE] = "${hex(c.textDisabled)}"
        |    base[NORMAL] = "${hex(c.field)}"
        |    base[PRELIGHT] = "${hex(c.field)}"
        |    base[ACTIVE] = "${hex(c.selection)}"
        |    base[SELECTED] = "${hex(c.selection)}"
        |    base[INSENSITIVE] = "${hex(c.face)}"
        |    text[NORMAL] = "${hex(c.text)}"
        |    text[PRELIGHT] = "${hex(c.text)}"
        |    text[ACTIVE] = "${hex(c.selectionText)}"
        |    text[SELECTED] = "${hex(c.selectionText)}"
        |    text[INSENSITIVE] = "${hex(c.textDisabled)}"
        |}
        |
        |style "handtop-classic-menu-item" {
        |    bg[PRELIGHT] = "${hex(c.selection)}"
        |    fg[PRELIGHT] = "${hex(c.selectionText)}"
        |    text[PRELIGHT] = "${hex(c.selectionText)}"
        |}
        |
        |style "handtop-classic-tooltip" {
        |    bg[NORMAL] = "${hex(TOOLTIP)}"
        |    fg[NORMAL] = "${hex(c.text)}"
        |}
        |
        |class "GtkWidget" style "handtop-classic"
        |widget_class "*<GtkMenuItem>*" style "handtop-classic-menu-item"
        |widget "gtk-tooltip*" style "handtop-classic-tooltip"
        |""".trimMargin()

    // ---- colours ----

    /** Tooltips: pale yellow, the classic tooltip colour. */
    private const val TOOLTIP = 0xFFFFFFE1.toInt()
    private const val ALTERNATE_ROW = 0xFFF0F0F0.toInt()
    private const val LINK = 0xFF0000FF.toInt()
    private const val VISITED = 0xFF800080.toInt()
    private const val NEUTRAL = 0xFF808000.toInt()
    private const val POSITIVE = 0xFF008000.toInt()

    private fun rgb(color: Int) = "${color shr 16 and 0xFF},${color shr 8 and 0xFF},${color and 0xFF}"

    private fun hex(color: Int) = "#%06x".format(color and 0xFFFFFF)
}
