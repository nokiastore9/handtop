package org.handtop.desktop.ui

import org.handtop.desktop.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicIconsTest {

    @Test
    fun everyIconHasAClassicCounterpart() {
        val names = R.drawable::class.java.fields.map { it.name }.toSet()
        val exempt = setOf("ic_launcher_background", "ic_launcher_foreground", "ic_handtop")
        val missing = names.filter { it.startsWith("ic_") && it !in exempt && "classic_$it" !in names }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun forBaseMapsAndFallsBack() {
        assertEquals(R.drawable.classic_ic_folder, ClassicIcons.forBase(R.drawable.ic_folder))
        assertEquals(R.drawable.ic_handtop, ClassicIcons.forBase(R.drawable.ic_handtop))
    }
}
