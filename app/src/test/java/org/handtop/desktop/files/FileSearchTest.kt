package org.handtop.desktop.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FileSearchTest {
    private val root: File = Files.createTempDirectory("search").toFile().apply { deleteOnExit() }

    private fun file(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("") }

    private fun names(found: List<File>) = found.map { it.relativeTo(root).invariantSeparatorsPath }

    @Test
    fun everyWordMatchesIgnoringCase() {
        assertTrue(FileSearch.matches("Report_2024.pdf", "rep 2024"))
        assertTrue(FileSearch.matches("Report_2024.pdf", "  PDF  "))
        assertFalse(FileSearch.matches("Report.pdf", "rep 2023"))
        assertFalse(FileSearch.matches("Report.pdf", "   "))
    }

    @Test
    fun findsInSubfoldersNearerFirst() {
        file("x/y/report.txt")
        file("report.md")
        file("x/report.odt")
        file("notes.txt")
        assertEquals(listOf("report.md", "x/report.odt", "x/y/report.txt"), names(FileSearch.search(root, "report")))
    }

    @Test
    fun foldersAreFoundToo() {
        File(root, "Reports").mkdirs()
        assertEquals(listOf("Reports"), names(FileSearch.search(root, "report")))
    }

    @Test
    fun hiddenFilesOnlyWhenShown() {
        file(".cache/report.txt")
        file(".report")
        file("report")
        assertEquals(listOf("report"), names(FileSearch.search(root, "report")))
        assertEquals(setOf("report", ".report", ".cache/report.txt"), names(FileSearch.search(root, "report", hidden = true)).toSet())
    }

    @Test
    fun stopsAtTheLimit() {
        for (i in 1..30) file("dir$i/file$i.txt")
        assertEquals(10, FileSearch.search(root, "file", limit = 10).size)
    }

    @Test
    fun stopsWhenCancelled() {
        file("a/report.txt")
        assertTrue(FileSearch.search(root, "report", cancelled = { true }).isEmpty())
    }

    @Test
    fun linkedFoldersAreNotEntered() {
        assumeTrue("needs POSIX links", File.separatorChar == '/')
        file("real/report.txt")
        val made = runCatching { Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath()) }.isSuccess
        assumeTrue(made)
        assertEquals(listOf("real/report.txt"), names(FileSearch.search(root, "report")))
    }
}
