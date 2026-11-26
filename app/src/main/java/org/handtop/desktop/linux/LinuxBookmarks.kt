package org.handtop.desktop.linux

import android.content.Context
import android.os.Environment
import android.util.Log
import org.handtop.desktop.R
import org.handtop.desktop.files.FilePlaces
import java.io.File
import java.io.IOException

/**
 * The phone's folders in Linux file managers, once "All files access" is granted, for /sdcard,
 * Downloads, Camera…:
 * - GTK bookmarks (Thunar, Nautilus, PCManFM, the GTK file dialog of every GTK program);
 * - KDE places (Dolphin and the file dialogs of KDE programs), see [KdePlaces].
 *
 * Each one is added once: HandTop remembers what it added, so a bookmark the user removed
 * does not come back, and the user's own bookmarks are never touched.
 */
object LinuxBookmarks {

    private const val TAG = "HandTopFiles"
    private const val BOOKMARKS = "root/.config/gtk-3.0/bookmarks"
    private const val ADDED = "etc/handtop/bookmarks-added"
    private const val KDE_PLACES = "root/.local/share/user-places.xbel"
    private const val KDE_ADDED = "etc/handtop/kde-places-added"

    /** A phone folder as Linux sees it, with its name and KDE icon. */
    private class Folder(val uri: String, val label: String, val kdeIcon: String)

    fun update(context: Context) {
        if (!LinuxEnv.isInstalled(context) || !LinuxEnv.hasStorageAccess()) return
        val rootfs = LinuxEnv.rootfs(context)
        val phone = FilePlaces.phoneRoot()
        val wanted = listOf(
            Triple("", R.string.files_phone, "smartphone"),
            Triple(Environment.DIRECTORY_DOWNLOADS, R.string.files_downloads, "folder-download"),
            Triple(Environment.DIRECTORY_DCIM, R.string.files_camera, "camera-photo"),
            Triple(Environment.DIRECTORY_DOCUMENTS, R.string.files_documents, "folder-documents"),
            Triple(Environment.DIRECTORY_PICTURES, R.string.files_pictures, "folder-pictures"),
            Triple(Environment.DIRECTORY_MUSIC, R.string.files_music, "folder-music"),
            Triple(Environment.DIRECTORY_MOVIES, R.string.files_movies, "folder-videos"),
        ).filter { (dir, _, _) -> dir.isEmpty() || File(phone, dir).isDirectory }
            .map { (dir, label, icon) ->
                Folder(if (dir.isEmpty()) "file:///sdcard" else "file:///sdcard/$dir", context.getString(label), icon)
            }
        updateGtk(rootfs, wanted)
        updateKde(rootfs, wanted)
    }

    private fun updateGtk(rootfs: File, wanted: List<Folder>) {
        try {
            val addedFile = File(rootfs, ADDED)
            val added = readSet(addedFile)
            val fresh = wanted.filter { it.uri !in added }
            if (fresh.isEmpty()) return
            val bookmarks = File(rootfs, BOOKMARKS)
            val present = if (bookmarks.exists()) bookmarks.readLines().map { it.substringBefore(' ') }.toSet() else emptySet()
            val lines = fresh.filter { it.uri !in present }.joinToString("") { "${it.uri} ${it.label}\n" }
            if (lines.isNotEmpty()) {
                bookmarks.parentFile?.mkdirs()
                val text = if (bookmarks.exists()) bookmarks.readText() else ""
                bookmarks.writeText(text + (if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else "") + lines)
            }
            writeSet(addedFile, added + fresh.map { it.uri })
            Log.i(TAG, "GTK bookmarks: added ${fresh.size}")
        } catch (e: IOException) {
            Log.w(TAG, "cannot write GTK bookmarks", e)
        }
    }

    /** Only into the file KDE made on its first start (until then, there is nothing to do). */
    private fun updateKde(rootfs: File, wanted: List<Folder>) {
        val places = File(rootfs, KDE_PLACES)
        if (!places.isFile) return
        try {
            val addedFile = File(rootfs, KDE_ADDED)
            val added = readSet(addedFile)
            val fresh = wanted.filter { it.uri !in added }
            if (fresh.isEmpty()) return
            val text = places.readText()
            if ("</xbel>" !in text) return // being written: next time
            val updated = KdePlaces.add(text, fresh.map { KdePlaces.Place(it.uri, it.label, it.kdeIcon) }, System.currentTimeMillis() / 1000)
            if (updated != null) {
                // Replaced in one step: KDE programs watching the file never read half of it.
                val temp = File(places.parentFile, places.name + ".handtop")
                temp.writeText(updated)
                if (!temp.renameTo(places)) {
                    temp.delete()
                    throw IOException("cannot replace $places")
                }
            }
            writeSet(addedFile, added + fresh.map { it.uri })
            Log.i(TAG, "KDE places: added ${fresh.size}")
        } catch (e: IOException) {
            Log.w(TAG, "cannot write KDE places", e)
        }
    }

    private fun readSet(file: File): Set<String> = if (file.exists()) file.readLines().filter { it.isNotEmpty() }.toSet() else emptySet()

    private fun writeSet(file: File, items: Set<String>) {
        file.parentFile?.mkdirs()
        file.writeText(items.joinToString("\n") + "\n")
    }
}
