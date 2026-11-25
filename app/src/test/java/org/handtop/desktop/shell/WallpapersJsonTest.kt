package org.handtop.desktop.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WallpapersJsonTest {

    @Test
    fun turquoiseIsOnePlainLayer() {
        val json = File("src/main/assets/wallpapers.json").readText()
        val entry = Regex("""\{"id":"turquoise","layers":\[(.*?)\]\}""").find(json)?.groupValues?.get(1)
        assertNotNull(entry)
        assertTrue(entry!!.contains(""""fill":{"type":"solid","color":"#008080","opacity":1.0}"""))
        assertEquals(1, Regex("\"d\":").findAll(entry).count())
    }
}
