package org.handtop.desktop.linux

import org.handtop.desktop.ui.ClassicTheme
import org.handtop.desktop.ui.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(listOf("${LinuxTheme.DIR}/xdg:/etc/xdg"), classic("XDG_CONFIG_DIRS"))
        assertEquals(listOf("${LinuxTheme.DIR}/share:/usr/local/share:/usr/share"), classic("XDG_DATA_DIRS"))
    }

    @Test
    fun gtk2ReadsHandTopsRcFileThenTheUsersOwn() {
        val files = classic("GTK2_RC_FILES").single().split(':')
        assertEquals(listOf("${LinuxTheme.DIR}/${LinuxTheme.GTK2_RC}", "/root/.gtkrc-2.0"), files)
    }

    @Test
    fun kdeglobalsSetsTheStyleAndTheColourSchemeWhereEachReaderLooks() {
        val text = LinuxTheme.files(ClassicTheme.colors).getValue("xdg/kdeglobals")
        // Qt reads top-level keys ([General] in an INI file), KDE Frameworks [KDE] and
        // [UiSettings]: without the last, KDE programs paint themselves Breeze outside Plasma.
        assertEquals("Windows", ini(text, "General", "widgetStyle"))
        assertEquals("Windows", ini(text, "KDE", "widgetStyle"))
        assertEquals(LinuxTheme.KDE_SCHEME, ini(text, "UiSettings", "ColorScheme"))
        assertEquals(LinuxTheme.KDE_SCHEME, ini(text, "General", "ColorScheme"))
    }

    @Test
    fun theKdeColoursAreHandTopsClassicPalette() {
        val files = LinuxTheme.files(ClassicTheme.colors)
        val scheme = files.getValue("share/color-schemes/${LinuxTheme.KDE_SCHEME}.colors")
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
        val rc = LinuxTheme.files(ClassicTheme.colors).getValue(LinuxTheme.GTK2_RC)
        assertTrue(rc.contains("bg[NORMAL] = \"#c0c0c0\""))
        assertTrue(rc.contains("bg[SELECTED] = \"#000080\""))
        assertTrue(rc.contains("base[NORMAL] = \"#ffffff\""))
        assertTrue(rc.contains("fg[INSENSITIVE] = \"#808080\""))
        assertTrue(rc.contains("class \"GtkWidget\" style \"handtop-classic\""))
    }
}
