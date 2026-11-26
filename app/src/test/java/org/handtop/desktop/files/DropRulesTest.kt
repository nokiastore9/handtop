package org.handtop.desktop.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DropRulesTest {
    private val home = File("/r/home")
    private val docs = File("/r/home/docs")
    private val photo = File("/r/home/photo.jpg")
    private val music = File("/r/music")

    @Test
    fun aFolderUnderTheFingerTakesTheFiles() {
        assertEquals(docs, DropRules.target(listOf(photo), folderUnder = docs, shownFolder = home))
    }

    @Test
    fun elsewhereTheShownFolderTakesThem() {
        assertEquals(music, DropRules.target(listOf(photo), folderUnder = null, shownFolder = music))
    }

    @Test
    fun filesAreNotDroppedWhereTheyAre() {
        assertNull(DropRules.target(listOf(photo, docs), folderUnder = null, shownFolder = home))
    }

    @Test
    fun aFolderIsNotDroppedIntoItselfOrItsInside() {
        assertNull(DropRules.target(listOf(docs), folderUnder = docs, shownFolder = home))
        assertNull(DropRules.target(listOf(docs), folderUnder = null, shownFolder = File("/r/home/docs/old")))
        // A folder with a name that only starts the same is not inside.
        assertEquals(File("/r/home/docs2"), DropRules.target(listOf(docs), folderUnder = File("/r/home/docs2"), shownFolder = home))
    }

    @Test
    fun aDraggedFolderUnderTheFingerFallsBackToTheShownFolder() {
        // Dragging docs and photo, finger over docs (one of them): the folder shown takes them.
        assertEquals(music, DropRules.target(listOf(docs, photo), folderUnder = docs, shownFolder = music))
    }
}
