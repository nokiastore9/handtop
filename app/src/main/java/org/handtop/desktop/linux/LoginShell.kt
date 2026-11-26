package org.handtop.desktop.linux

import java.io.File

/** The shell HandTop's Linux terminal runs: bash where the distribution has it, else sh. */
object LoginShell {
    fun path(rootfs: File): String = if (File(rootfs, "bin/bash").exists()) "/bin/bash" else "/bin/sh"

    /** [path] started as a login shell, so it reads the profile. */
    fun command(rootfs: File): List<String> {
        val shell = path(rootfs)
        return listOf(shell, if (shell == "/bin/bash") "--login" else "-l")
    }
}
