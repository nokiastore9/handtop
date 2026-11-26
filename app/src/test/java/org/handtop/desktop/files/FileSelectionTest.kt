package org.handtop.desktop.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSelectionTest {
    private val files = listOf("a", "b", "c", "d", "e")
    private val selection = FileSelection<String>()

    @Test
    fun mouseClickSelectsOnlyThatFile() {
        selection.click(files, 1, add = false, range = false)
        selection.click(files, 3, add = false, range = false)
        assertEquals(setOf("d"), selection.items)
    }

    @Test
    fun ctrlClickAddsAndRemoves() {
        selection.click(files, 0, add = false, range = false)
        selection.click(files, 2, add = true, range = false)
        assertEquals(setOf("a", "c"), selection.items)
        selection.click(files, 0, add = true, range = false)
        assertEquals(setOf("c"), selection.items)
    }

    @Test
    fun shiftClickSelectsARange() {
        selection.click(files, 1, add = false, range = false)
        selection.click(files, 3, add = false, range = true)
        assertEquals(setOf("b", "c", "d"), selection.items)
    }

    @Test
    fun fingerTapOpensOutsideTheSelectionMode() {
        assertFalse(selection.tap(files, 2))
        assertTrue(selection.items.isEmpty())
        assertFalse(selection.touchMode)
    }

    @Test
    fun startingTheModeThenTapsPickFiles() {
        selection.startTouch(files, 1)
        assertTrue(selection.touchMode)
        assertTrue(selection.tap(files, 3))
        assertTrue(selection.tap(files, 4))
        assertEquals(setOf("b", "d", "e"), selection.items)
        assertTrue(selection.tap(files, 3)) // a second tap takes it back
        assertEquals(setOf("b", "e"), selection.items)
    }

    @Test
    fun theModeEndsWithTheLastFile() {
        selection.startTouch(files, 1)
        selection.tap(files, 1)
        assertTrue(selection.items.isEmpty())
        assertFalse(selection.touchMode)
        assertFalse(selection.tap(files, 2)) // opens again
    }

    @Test
    fun startingTheModeKeepsWhatTheMouseSelected() {
        selection.click(files, 0, add = false, range = false)
        selection.startTouch(files, 2)
        assertEquals(setOf("a", "c"), selection.items)
    }

    @Test
    fun aMouseClickLeavesTheMode() {
        selection.startTouch(files, 1)
        selection.click(files, 3, add = false, range = false)
        assertFalse(selection.touchMode)
        assertEquals(setOf("d"), selection.items)
    }

    @Test
    fun clearLeavesTheMode() {
        selection.startTouch(files, 1)
        selection.clear()
        assertTrue(selection.items.isEmpty())
        assertFalse(selection.touchMode)
    }

    @Test
    fun selectAllKeepsTheMode() {
        selection.startTouch(files, 1)
        selection.selectAll(files)
        assertEquals(files.toSet(), selection.items)
        assertTrue(selection.touchMode)
    }

    @Test
    fun highlightMarksAFolderWithoutStartingARange() {
        // Going up shows the folder the user came from; Shift+click then does not select a range.
        selection.highlight("c")
        assertEquals(setOf("c"), selection.items)
        selection.click(files, 4, add = false, range = true)
        assertEquals(setOf("e"), selection.items)
    }

    @Test
    fun filesThatAreGoneAfterAReloadAreDropped() {
        selection.startTouch(files, 1)
        selection.tap(files, 2)
        selection.retain(listOf("a", "c", "e"))
        assertEquals(setOf("c"), selection.items)
        selection.retain(listOf("a"))
        assertFalse(selection.touchMode)
    }
}
