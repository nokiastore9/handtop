package org.handtop.desktop.linux

import org.handtop.desktop.ui.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LinuxThemeTest {
    private fun classic(name: String) =
        LinuxTheme.environment(ThemeId.CLASSIC).filter { it.startsWith("$name=") }.map { it.substringAfter('=') }

    /** The value of [key] in [group] of an INI text. */
    private fun ini(text: String, group: String, key: String): String? {
        var current = ""
        for (line in text.lines()) {
            if (line.startsWith("[")) current = line.trim('[', ']')
            else if (current == group && line.startsWith("$key=")) return line.substringAfter('=')
        }
        return null
    }

    @Test
    fun theModernThemeLeavesProgramsAlone() {
        assertEquals(emptyList<String>(), LinuxTheme.environment(ThemeId.MODERN))
    }

    @Test
    fun qtAndKdeProgramsReadHandTopsKdeSettings() {
        // Qt's own "kde" platform theme reads kdeglobals from the XDG config dirs, but only when
        // KDE_SESSION_VERSION says KDE 5 or later; KDE programs take colour schemes from the
        // XDG data dirs. HandTop's folders come first, the system's stay behind them.
        assertEquals(listOf("kde"), classic("QT_QPA_PLATFORMTHEME"))
        assertTrue(classic("KDE_SESSION_VERSION").single().toInt() >= 5)
        assertEquals(listOf("${LinuxTheme.CLASSIC.dir}/xdg:/etc/xdg"), classic("XDG_CONFIG_DIRS"))
        assertEquals(listOf("${LinuxTheme.CLASSIC.dir}/share:/usr/local/share:/usr/share"), classic("XDG_DATA_DIRS"))
    }

    @Test
    fun gtk2ReadsHandTopsRcFileThenTheUsersOwn() {
        val files = classic("GTK2_RC_FILES").single().split(':')
        assertEquals(listOf("${LinuxTheme.CLASSIC.dir}/${LinuxTheme.CLASSIC.gtk2Rc}", "/root/.gtkrc-2.0"), files)
    }

    @Test
    fun gtk3And4ProgramsUseHandTopsThemeWithClassicScrollBars() {
        // GTK_THEME is the one setting every GTK 3/4 program reads first, on Wayland and X11
        // alike (on Wayland GSettings would win over settings.ini); it also makes libadwaita
        // programs take the theme instead of their own stylesheet.
        assertEquals(listOf(LinuxTheme.CLASSIC.gtkName), classic("GTK_THEME"))
        // Scroll bars stay visible, with arrow buttons, instead of overlay indicators.
        assertEquals(listOf("0"), classic("GTK_OVERLAY_SCROLLING"))
    }

    @Test
    fun theGtk4VersionComesFromTheLibrarysFileName() {
        assertEquals(18, LinuxTheme.gtk4Minor(listOf("libgtk-3.so.0.2400.32", "libgtk-4.so.1.1800.6")))
        assertEquals(22, LinuxTheme.gtk4Minor(listOf("libgtk-4.so.1", "libgtk-4.so.1.2200.4")))
        assertEquals(null, LinuxTheme.gtk4Minor(listOf("libgtk-3.so.0.2400.32", "libgtk-4.so.1")))
    }

    @Test
    fun olderGtk4GetsTheStylesheetItsCssParserReads() {
        // The libadwaita 1.9 stylesheet needs GTK 4.20; Debian 13 has GTK 4.18.
        assertEquals("gtk-4.18.css", LinuxTheme.gtk4Stylesheet(18))
        assertEquals("gtk.css", LinuxTheme.gtk4Stylesheet(20))
        assertEquals("gtk.css", LinuxTheme.gtk4Stylesheet(22))
        // No GTK 4 yet: the newest; it is checked again whenever a program starts.
        assertEquals("gtk.css", LinuxTheme.gtk4Stylesheet(null))
    }

    @Test
    fun kdeglobalsSetsTheStyleAndTheColourSchemeWhereEachReaderLooks() {
        val text = LinuxTheme.files(LinuxTheme.CLASSIC).getValue("xdg/kdeglobals")
        // Qt reads top-level keys ([General] in an INI file), KDE Frameworks [KDE] and
        // [UiSettings]: without the last, KDE programs paint themselves Breeze outside Plasma.
        assertEquals("Windows", ini(text, "General", "widgetStyle"))
        assertEquals("Windows", ini(text, "KDE", "widgetStyle"))
        assertEquals(LinuxTheme.CLASSIC.kdeScheme, ini(text, "UiSettings", "ColorScheme"))
        assertEquals(LinuxTheme.CLASSIC.kdeScheme, ini(text, "General", "ColorScheme"))
    }

    @Test
    fun theKdeColoursAreHandTopsClassicPalette() {
        val files = LinuxTheme.files(LinuxTheme.CLASSIC)
        val scheme = files.getValue("share/color-schemes/${LinuxTheme.CLASSIC.kdeScheme}.colors")
        for (text in listOf(scheme, files.getValue("xdg/kdeglobals"))) {
            assertEquals("192,192,192", ini(text, "Colors:Window", "BackgroundNormal"))
            assertEquals("192,192,192", ini(text, "Colors:Button", "BackgroundNormal"))
            assertEquals("0,0,0", ini(text, "Colors:Button", "ForegroundNormal"))
            assertEquals("255,255,255", ini(text, "Colors:View", "BackgroundNormal"))
            assertEquals("0,0,128", ini(text, "Colors:Selection", "BackgroundNormal"))
            assertEquals("255,255,255", ini(text, "Colors:Selection", "ForegroundNormal"))
            assertEquals("128,128,128", ini(text, "Colors:View", "ForegroundInactive"))
        }
        assertEquals("HandTop Classic", ini(scheme, "General", "Name"))
    }

    @Test
    fun gtk2DrawsGrayBevelsAndBlueSelections() {
        val rc = LinuxTheme.files(LinuxTheme.CLASSIC).getValue(LinuxTheme.CLASSIC.gtk2Rc)
        assertTrue(rc.contains("bg[NORMAL] = \"#c0c0c0\""))
        assertTrue(rc.contains("bg[SELECTED] = \"#000080\""))
        assertTrue(rc.contains("base[NORMAL] = \"#ffffff\""))
        assertTrue(rc.contains("fg[INSENSITIVE] = \"#808080\""))
        assertTrue(rc.contains("class \"GtkWidget\" style \"handtop-classic\""))
    }

    @Test
    fun classicKeepsTodaysNames() {
        assertEquals("/usr/local/share/handtop/classic", LinuxTheme.CLASSIC.dir)
        assertEquals("HandTop-Classic", LinuxTheme.CLASSIC.gtkName)
        assertEquals("HandTopClassic", LinuxTheme.CLASSIC.kdeScheme)
        assertEquals("share/themes/HandTop-Classic/gtk-2.0/gtkrc", LinuxTheme.CLASSIC.gtk2Rc)
    }

    @Test
    fun softLeavesProgramsAlone() = assertEquals(emptyList<String>(), LinuxTheme.environment(ThemeId.SOFT))

    @Test
    fun glossyIsTheClassicSetInItsOwnFolder() {
        val expected = LinuxTheme.environment(ThemeId.CLASSIC).map {
            it.replace(LinuxTheme.CLASSIC.dir, LinuxTheme.GLOSSY.dir).replace(LinuxTheme.CLASSIC.gtkName, LinuxTheme.GLOSSY.gtkName)
        }
        assertEquals(expected, LinuxTheme.environment(ThemeId.GLOSSY))
        assertEquals("/usr/local/share/handtop/glossy", LinuxTheme.GLOSSY.dir)
        assertEquals("HandTop-Glossy", LinuxTheme.GLOSSY.gtkName)
    }

    @Test
    fun glossyFilesCarryGlossyColours() {
        val files = LinuxTheme.files(LinuxTheme.GLOSSY)
        val scheme = files.getValue("share/color-schemes/HandTopGlossy.colors")
        assertTrue(scheme.contains("236,233,216")) // face #ECE9D8
        assertTrue(scheme.contains("49,106,197")) // selection #316AC5
        assertTrue(files.getValue(LinuxTheme.GLOSSY.gtk2Rc).contains("#ece9d8"))
        assertEquals("HandTopGlossy", ini(files.getValue("xdg/kdeglobals"), "General", "ColorScheme"))
        assertTrue(files.containsKey("share/themes/HandTop-Glossy/index.theme"))
    }

    @Test
    fun theBuiltGlossyGtkThemesAreBeige() {
        for (path in listOf("gtk-3.0/gtk.css", "gtk-4.0/gtk.css", "gtk-4.0/gtk-4.18.css")) {
            val css = File("src/main/assets/linux-theme/glossy/$path").readText()
            assertTrue(path, css.contains("#ece9d8"))
            assertTrue(path, css.contains("#316ac5"))
        }
    }

    @Test
    fun installWritesEachVariantIntoItsOwnFolder() =
        assertEquals(listOf("classic", "glossy"), LinuxTheme.installed.map { it.name })
}
