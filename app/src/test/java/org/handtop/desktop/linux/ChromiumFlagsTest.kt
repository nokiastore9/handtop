package org.handtop.desktop.linux

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChromiumFlagsTest {
    private val rootfs: File = Files.createTempDirectory("rootfs").toFile().apply { deleteOnExit() }

    private fun file(path: String) = File(rootfs, path).apply { parentFile!!.mkdirs(); writeText("") }

    /** VS Code's .deb: the program next to Chromium's sandbox helper. */
    private fun vsCode() {
        file("usr/share/code/code")
        file("usr/share/code/chrome-sandbox")
    }

    @Test
    fun electronProgramGetsNoSandbox() {
        vsCode()
        assertEquals("/usr/share/code/code --no-sandbox", ChromiumFlags.apply(rootfs, "/usr/share/code/code"))
        assertEquals(
            "/usr/share/code/code --no-sandbox --new-window '/root/a b.txt'",
            ChromiumFlags.apply(rootfs, "/usr/share/code/code --new-window '/root/a b.txt'"),
        )
    }

    @Test
    fun otherProgramsAreLeftAlone() {
        file("usr/bin/mousepad")
        assertEquals("/usr/bin/mousepad '/root/a.txt'", ChromiumFlags.apply(rootfs, "/usr/bin/mousepad '/root/a.txt'"))
        assertEquals("missing-program", ChromiumFlags.apply(rootfs, "missing-program"))
    }

    @Test
    fun flagIsNotAddedTwice() {
        vsCode()
        val command = "/usr/share/code/code --no-sandbox --new-window"
        assertEquals(command, ChromiumFlags.apply(rootfs, command))
    }

    @Test
    fun programIsFoundOnThePath() {
        file("usr/bin/electron-app")
        file("usr/bin/chrome-sandbox")
        assertEquals("electron-app --no-sandbox", ChromiumFlags.apply(rootfs, "electron-app"))
    }

    @Test
    fun linkIsFollowedInsideLinux() {
        assumeTrue("Linux links need a POSIX file system", File.separatorChar == '/')
        file("opt/Obsidian/obsidian")
        file("opt/Obsidian/chrome-sandbox")
        File(rootfs, "usr/bin").mkdirs()
        val made = runCatching {
            Files.createSymbolicLink(File(rootfs, "usr/bin/obsidian").toPath(), File("/opt/Obsidian/obsidian").toPath())
        }.isSuccess
        assumeTrue("this file system cannot make symbolic links", made)
        assertEquals("/usr/bin/obsidian --no-sandbox %U", ChromiumFlags.apply(rootfs, "/usr/bin/obsidian %U"))
    }
}
