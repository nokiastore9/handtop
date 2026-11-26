package org.handtop.desktop.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinuxPathsTest {
    private val rootfs = File("/data/app/files/linux/rootfs")
    private val phone = File("/storage/emulated/0")

    private fun android(path: String, access: Boolean = true) = LinuxPaths.toAndroid(path, rootfs, phone, access)?.path?.replace('\\', '/')

    @Test
    fun linuxFilesAreInsideTheRootFilesystem() {
        assertEquals("/data/app/files/linux/rootfs/root/a.pdf", android("/root/a.pdf"))
        assertEquals("/data/app/files/linux/rootfs", android("/"))
    }

    @Test
    fun sdcardIsThePhone() {
        assertEquals("/storage/emulated/0/Download/x.pdf", android("/sdcard/Download/x.pdf"))
        assertEquals("/storage/emulated/0", android("/sdcard"))
        assertEquals("/storage/ABCD-1234/song.mp3", android("/storage/ABCD-1234/song.mp3"))
    }

    @Test
    fun withoutStorageAccessThePhoneIsOutOfReach() {
        assertNull(android("/sdcard/Download/x.pdf", access = false))
        assertNull(android("/storage/ABCD-1234/song.mp3", access = false))
    }

    @Test
    fun dotsAreResolvedAndCannotLeaveLinux() {
        assertEquals("/data/app/files/linux/rootfs/etc/hosts", android("/root/../etc/./hosts"))
        assertEquals("/data/app/files/linux/rootfs/etc/hosts", android("/../../../etc/hosts"))
        assertEquals("/storage/emulated/0/a", android("/sdcard/x/../a"))
    }

    @Test
    fun whatProgramsAskToOpen() {
        assertEquals(LinuxPaths.Target.Path("/root/a b.pdf"), LinuxPaths.target("/root/a b.pdf"))
        assertEquals(LinuxPaths.Target.Path("/root/a b.pdf"), LinuxPaths.target("file:///root/a%20b.pdf"))
        assertEquals(LinuxPaths.Target.Link("https://example.com/?q=1"), LinuxPaths.target("https://example.com/?q=1"))
        assertEquals(LinuxPaths.Target.Link("mailto:someone@example.com"), LinuxPaths.target("mailto:someone@example.com"))
        assertNull(LinuxPaths.target("notes.txt"))
        assertNull(LinuxPaths.target(""))
        assertNull(LinuxPaths.target("file://other-host/x"))
    }

    @Test
    fun relativePathsAreNotGuessed() {
        assertNull(android("notes.txt"))
    }
}
