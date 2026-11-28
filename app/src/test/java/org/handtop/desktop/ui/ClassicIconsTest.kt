package org.handtop.desktop.ui

import org.handtop.desktop.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ClassicIconsTest {

    @Test
    fun everyIconHasAClassicCounterpart() {
        val names = R.drawable::class.java.fields.map { it.name }.toSet()
        val exempt = setOf("ic_launcher_background", "ic_launcher_foreground", "ic_handtop")
        val missing = names.filter { it.startsWith("ic_") && it !in exempt && "classic_$it" !in names }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun colouredIconsAreKnown() {
        // A light theme tints the white icons dark; the coloured ones must keep their colours.
        val white = Regex("(?i)#(FF)?FFFFFF|@android:color/white|#0+")
        val coloured = File("src/main/res/drawable").listFiles()!!
            .filter { it.name.startsWith("ic_") && it.name.endsWith(".xml") }
            .filter { f -> Regex("(?:fill|stroke)Color=\"([^\"]+)\"").findAll(f.readText()).any { !white.matches(it.groupValues[1]) } }
            .map { it.name.removeSuffix(".xml") }.toSet()
        val known = COLOURED_ICONS.map { id -> R.drawable::class.java.fields.first { it.getInt(null) == id }.name }.toSet()
        assertEquals(coloured, known)
    }

    @Test
    fun forBaseMapsAndFallsBack() {
        assertEquals(R.drawable.classic_ic_folder, ClassicIcons.forBase(R.drawable.ic_folder))
        assertEquals(R.drawable.ic_handtop, ClassicIcons.forBase(R.drawable.ic_handtop))
    }
}
