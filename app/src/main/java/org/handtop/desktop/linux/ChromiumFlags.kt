package org.handtop.desktop.linux

import java.io.File
import java.nio.file.Files

/**
 * Electron programs (VS Code, Obsidian…) and Chromium refuse to start as root without
 * --no-sandbox, and HandTop's Linux runs everything as (fake) root. Their sandbox could not
 * work under proot anyway; Linux as a whole stays inside HandTop's Android app sandbox.
 */
object ChromiumFlags {
    private const val NO_SANDBOX = "--no-sandbox"

    /** Where a program without a path is looked up, as PATH in [LinuxEnv]. */
    private val PATH = listOf("usr/local/sbin", "usr/local/bin", "usr/sbin", "usr/bin", "sbin", "bin")

    /** [command], a shell command line that starts with the program, with the flags it needs. */
    fun apply(rootfs: File, command: String): String {
        val program = command.substringBefore(' ')
        if (NO_SANDBOX in command.split(' ') || !isChromium(rootfs, program)) return command
        val rest = command.substringAfter(' ', "")
        return if (rest.isEmpty()) "$program $NO_SANDBOX" else "$program $NO_SANDBOX $rest"
    }

    /** Chromium and every Electron program ship its sandbox helper next to the executable. */
    private fun isChromium(rootfs: File, program: String): Boolean {
        val file = locate(rootfs, program) ?: return false
        return File(file.parentFile, "chrome-sandbox").exists()
    }

    private fun locate(rootfs: File, program: String): File? {
        if (program.isEmpty() || program.startsWith("'") || program.startsWith("\"")) return null
        val candidates = if (program.startsWith("/")) listOf(program.trimStart('/')) else PATH.map { "$it/$program" }
        val found = candidates.map { File(rootfs, it) }.firstOrNull { it.exists() || Files.isSymbolicLink(it.toPath()) }
        return found?.let { LinuxApps.inRoot(rootfs, it) }?.takeIf { it.isFile }
    }
}
