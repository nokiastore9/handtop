package org.handtop.desktop.linux

import android.content.Context
import android.os.Environment
import android.util.Log
import org.handtop.desktop.R
import org.handtop.desktop.files.FilePlaces
import java.io.File
import java.io.IOException

/**
 * The phone's folders in Linux file managers: GTK bookmarks (Thunar, Nautilus, PCManFM, the GTK
 * file dialog of every GTK program) for /sdcard, Downloads, Camera… once "All files access"
 * is granted.
 *
 * Each bookmark is added once: HandTop remembers what it added, so a bookmark the user removed
 * does not come back, and the user's own bookmarks are never touched.
 */
object LinuxBookmarks {

    private const val TAG = "HandTopFiles"
    private const val BOOKMARKS = "root/.config/gtk-3.0/bookmarks"
    private const val ADDED = "etc/handtop/bookmarks-added"

    fun update(context: Context) {
        if (!LinuxEnv.isInstalled(context) || !LinuxEnv.hasStorageAccess()) return
        val rootfs = LinuxEnv.rootfs(context)
        val phone = FilePlaces.phoneRoot()
        val wanted = listOf(
            "" to R.string.files_phone,
            Environment.DIRECTORY_DOWNLOADS to R.string.files_downloads,
            Environment.DIRECTORY_DCIM to R.string.files_camera,
            Environment.DIRECTORY_DOCUMENTS to R.string.files_documents,
            Environment.DIRECTORY_PICTURES to R.string.files_pictures,
            Environment.DIRECTORY_MUSIC to R.string.files_music,
            Environment.DIRECTORY_MOVIES to R.string.files_movies,
        ).filter { (dir, _) -> dir.isEmpty() || File(phone, dir).isDirectory }
            .map { (dir, label) -> (if (dir.isEmpty()) "file:///sdcard" else "file:///sdcard/$dir") to context.getString(label) }
        try {
            val addedFile = File(rootfs, ADDED)
            val added = if (addedFile.exists()) addedFile.readLines().toMutableSet() else mutableSetOf()
            val fresh = wanted.filter { (uri, _) -> uri !in added }
            if (fresh.isEmpty()) return
            val bookmarks = File(rootfs, BOOKMARKS)
            val present = if (bookmarks.exists()) bookmarks.readLines().map { it.substringBefore(' ') }.toSet() else emptySet()
            val lines = fresh.filter { (uri, _) -> uri !in present }.joinToString("") { (uri, label) -> "$uri $label\n" }
            if (lines.isNotEmpty()) {
                bookmarks.parentFile?.mkdirs()
                val text = if (bookmarks.exists()) bookmarks.readText() else ""
                bookmarks.writeText(text + (if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else "") + lines)
            }
            addedFile.parentFile?.mkdirs()
            addedFile.writeText((added + fresh.map { it.first }).joinToString("\n") + "\n")
            Log.i(TAG, "GTK bookmarks: added ${fresh.size}")
        } catch (e: IOException) {
            Log.w(TAG, "cannot write GTK bookmarks", e)
        }
    }
}
