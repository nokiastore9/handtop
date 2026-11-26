package org.handtop.desktop.linux

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginShellTest {
    private fun rootfs(vararg files: String): File {
        val root = Files.createTempDirectory("rootfs").toFile()
        root.deleteOnExit()
        for (path in files) File(root, path).apply { parentFile!!.mkdirs(); writeText("") }
        return root
    }

    @Test
    fun bashWhenTheDistributionHasIt() {
        assertEquals("/bin/bash", LoginShell.path(rootfs("bin/bash", "bin/sh")))
    }

    @Test
    fun shOtherwise() {
        // Alpine: busybox sh only.
        assertEquals("/bin/sh", LoginShell.path(rootfs("bin/sh")))
    }

    @Test
    fun theTerminalStartsItAsALoginShell() {
        assertEquals(listOf("/bin/bash", "--login"), LoginShell.command(rootfs("bin/bash")))
        assertEquals(listOf("/bin/sh", "-l"), LoginShell.command(rootfs("bin/sh")))
    }
}
