package org.handtop.desktop.linux

import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

object FileTrees {

    /**
     * Recursive delete that never follows symbolic links (a Linux root filesystem is full of
     * absolute links that would point into Android's own tree) and copes with directories
     * without permissions — proot creates its mount points with mode 000.
     */
    fun delete(target: File) {
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Os.chmod(target.path, 0x1C0) // 0700: we must be able to list and empty it
            } catch (_: ErrnoException) {
            }
            Files.newDirectoryStream(path).use { children -> children.forEach { delete(it.toFile()) } }
        }
        Files.delete(path)
    }
}
