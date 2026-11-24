package org.handtop.desktop.files

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.webkit.MimeTypeMap
import androidx.annotation.DrawableRes
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxEnv
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/** A place in the file manager's side bar. */
class Place(val label: String, @param:DrawableRes val icon: Int, val dir: File)

/**
 * Where files are: the phone's shared storage, SD cards / USB drives and the Linux root
 * filesystem, and the paths Linux programs see for them (/sdcard, /storage, /).
 */
object FilePlaces {

    fun phoneRoot(): File = Environment.getExternalStorageDirectory()

    fun places(context: Context): List<Place> {
        val list = ArrayList<Place>()
        val phone = phoneRoot()
        list += Place(context.getString(R.string.files_phone), R.drawable.ic_phone, phone)
        val folders = listOf(
            Triple(Environment.DIRECTORY_DOWNLOADS, R.string.files_downloads, R.drawable.ic_download),
            Triple(Environment.DIRECTORY_DCIM, R.string.files_camera, R.drawable.ic_camera),
            Triple(Environment.DIRECTORY_DOCUMENTS, R.string.files_documents, R.drawable.ic_file_text),
            Triple(Environment.DIRECTORY_PICTURES, R.string.files_pictures, R.drawable.ic_file_image),
            Triple(Environment.DIRECTORY_MUSIC, R.string.files_music, R.drawable.ic_file_audio),
            Triple(Environment.DIRECTORY_MOVIES, R.string.files_movies, R.drawable.ic_file_video),
        )
        for ((name, label, icon) in folders) {
            val dir = File(phone, name)
            if (dir.isDirectory) list += Place(context.getString(label), icon, dir)
        }
        for ((dir, label) in removableVolumes(context)) list += Place(label, R.drawable.ic_sd_card, dir)
        if (LinuxEnv.isInstalled(context)) {
            val rootfs = LinuxEnv.rootfs(context)
            list += Place(context.getString(R.string.files_linux_home), R.drawable.ic_home, File(rootfs, "root"))
            list += Place(context.getString(R.string.files_linux_root), R.drawable.ic_linux, rootfs)
        }
        return list
    }

    /** SD cards and USB drives that are mounted, with their names. */
    private fun removableVolumes(context: Context): List<Pair<File, String>> {
        val storage = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return storage.storageVolumes.mapNotNull { volume ->
            if (volume.isPrimary || volume.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
            val dir = directoryOf(volume) ?: return@mapNotNull null
            dir to volume.getDescription(context)
        }
    }

    private fun directoryOf(volume: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory
        else volume.uuid?.let { File("/storage/$it") }?.takeIf { it.isDirectory }

    /** The path a Linux program sees for [file], or null if Linux cannot see it. */
    fun linuxPath(context: Context, file: File): String? {
        val path = file.absolutePath
        val rootfs = LinuxEnv.rootfs(context).absolutePath
        if (path == rootfs) return "/"
        if (path.startsWith("$rootfs/")) return path.removePrefix(rootfs)
        if (!LinuxEnv.hasStorageAccess()) return null
        val phone = phoneRoot().absolutePath
        if (path == phone || path.startsWith("$phone/")) return "/sdcard" + path.removePrefix(phone)
        if (path.startsWith("/storage/")) return path
        return null
    }

    fun isInLinux(context: Context, file: File): Boolean {
        val rootfs = LinuxEnv.rootfs(context).absolutePath
        return file.absolutePath == rootfs || file.absolutePath.startsWith("$rootfs/")
    }

    /** Needs "All files access" (phone storage, SD cards) rather than being HandTop's own files. */
    fun needsStorageAccess(context: Context, dir: File) =
        !isInLinux(context, dir) && dir.absolutePath.startsWith("/storage/")

    /**
     * Follows symbolic links inside the Linux root filesystem the way they resolve in Linux: an
     * absolute target is a path in the root filesystem, not on Android.
     */
    fun resolve(context: Context, file: File): File {
        if (!isInLinux(context, file)) return file
        val rootfs = LinuxEnv.rootfs(context)
        var current = file
        repeat(16) {
            val path = current.toPath()
            if (!Files.isSymbolicLink(path)) return current
            val target = try {
                Files.readSymbolicLink(path).toString()
            } catch (_: IOException) {
                return current
            }
            current = if (target.startsWith("/")) File(rootfs, target.trimStart('/')) else File(current.parentFile, target)
        }
        return current
    }

    /** Roots that "up" does not go above: the phone, each volume, Linux. */
    private fun roots(context: Context): List<Pair<File, String>> {
        val list = ArrayList<Pair<File, String>>()
        list += phoneRoot() to context.getString(R.string.files_phone)
        list += removableVolumes(context)
        if (LinuxEnv.isInstalled(context)) list += LinuxEnv.rootfs(context) to "Linux"
        return list
    }

    private fun rootOf(context: Context, dir: File): Pair<File, String>? =
        roots(context).filter { (root, _) -> dir.absolutePath == root.absolutePath || dir.absolutePath.startsWith(root.absolutePath + "/") }
            .maxByOrNull { it.first.absolutePath.length }

    fun parentOf(context: Context, dir: File): File? {
        val root = rootOf(context, dir)?.first
        if (root != null && root.absolutePath == dir.absolutePath) return null
        return dir.parentFile
    }

    /** The path as clickable segments: the root's name, then each folder. */
    fun crumbs(context: Context, dir: File): List<Pair<String, File>> {
        val (root, label) = rootOf(context, dir) ?: (File("/") to "/")
        val list = arrayListOf(label to root)
        val rest = dir.absolutePath.removePrefix(root.absolutePath).trim('/')
        var current = root
        if (rest.isNotEmpty()) for (part in rest.split('/')) {
            current = File(current, part)
            list += part to current
        }
        return list
    }
}

/** What a file is: MIME type and icon. */
object FileKinds {

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "log", "conf", "cfg", "ini", "toml", "yaml", "yml", "json", "xml", "csv", "sh", "bash",
        "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "rs", "go", "js", "ts", "css", "html", "gradle", "properties",
    )

    fun mimeOf(file: File, isDir: Boolean): String {
        if (isDir) return "inode/directory"
        val ext = file.extension.lowercase(Locale.ROOT)
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (ext) {
            in TEXT_EXTENSIONS -> "text/plain"
            "mkv" -> "video/x-matroska"
            "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "deb" -> "application/vnd.debian.binary-package"
            "xz", "zst", "bz2", "7z", "rar" -> "application/x-archive"
            else -> "application/octet-stream"
        }
    }

    @DrawableRes
    fun iconOf(mime: String): Int = when {
        mime == "inode/directory" -> R.drawable.ic_folder
        mime.startsWith("image/") -> R.drawable.ic_file_image
        mime.startsWith("video/") -> R.drawable.ic_file_video
        mime.startsWith("audio/") -> R.drawable.ic_file_audio
        mime == "application/vnd.android.package-archive" -> R.drawable.ic_file_apk
        mime.startsWith("text/") || mime.endsWith("/json") || mime.endsWith("/xml") || mime.endsWith("javascript") -> R.drawable.ic_file_text
        mime.contains("zip") || mime.contains("tar") || mime.contains("compressed") || mime.contains("archive") ||
            mime.contains("gzip") || mime.contains("debian") -> R.drawable.ic_file_archive
        else -> R.drawable.ic_file
    }

    /** A tint per kind, so lists are easy to scan. */
    fun colorOf(mime: String): Int = when (iconOf(mime)) {
        R.drawable.ic_folder -> 0xFFFFC857.toInt()
        R.drawable.ic_file_image -> 0xFF5FD38D.toInt()
        R.drawable.ic_file_video -> 0xFFFF7A7A.toInt()
        R.drawable.ic_file_audio -> 0xFFC79BFF.toInt()
        R.drawable.ic_file_text -> 0xFF7DB8FF.toInt()
        R.drawable.ic_file_archive -> 0xFFE0A86B.toInt()
        R.drawable.ic_file_apk -> 0xFF9CD67A.toInt()
        else -> 0xFFAEB6C6.toInt()
    }
}

/** File operations for the file manager. Blocking: run them off the main thread. */
object FileOps {

    /** [name] in [dir], or "name (2)", "name (3)"… if taken. */
    fun freeName(dir: File, name: String): File {
        var target = File(dir, name)
        if (!exists(target)) return target
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while (exists(target)) target = File(dir, "$base ($n)$ext").also { n++ }
        return target
    }

    private fun exists(file: File) = file.exists() || Files.isSymbolicLink(file.toPath())

    /** Copies [source] (a file, link or folder) into [dir]; returns the copy. */
    fun copy(source: File, dir: File, progress: (String) -> Unit): File {
        if (isInside(dir, source)) throw IOException("cannot copy a folder into itself")
        val target = freeName(dir, source.name)
        copyTree(source, target, progress)
        return target
    }

    /** Moves [source] into [dir] (renames on the same storage, else copies and deletes). */
    fun move(source: File, dir: File, progress: (String) -> Unit): File {
        if (isInside(dir, source)) throw IOException("cannot move a folder into itself")
        if (source.parentFile?.absolutePath == dir.absolutePath) return source
        val target = freeName(dir, source.name)
        if (source.renameTo(target)) return target
        copyTree(source, target, progress)
        delete(source)
        return target
    }

    private fun copyTree(source: File, target: File, progress: (String) -> Unit) {
        val path = source.toPath()
        when {
            Files.isSymbolicLink(path) -> try {
                Files.createSymbolicLink(target.toPath(), Files.readSymbolicLink(path))
            } catch (_: Exception) {
                // Shared storage has no links: copy what the link points to, if anything.
                if (source.exists()) copyTree(source.canonicalFile, target, progress)
            }
            source.isDirectory -> {
                if (!target.mkdirs() && !target.isDirectory) throw IOException("cannot create ${target.name}")
                for (child in source.listFiles().orEmpty()) copyTree(child, File(target, child.name), progress)
            }
            else -> {
                progress(source.name)
                Files.copy(path, target.toPath(), LinkOption.NOFOLLOW_LINKS)
                // Attributes where the storage keeps them (shared storage has no modes).
                target.setLastModified(source.lastModified())
                if (source.canExecute()) target.setExecutable(true, false)
            }
        }
    }

    /** Deletes [file]; a link is removed without touching what it points to. */
    fun delete(file: File) {
        val path = file.toPath()
        if (!Files.isSymbolicLink(path) && file.isDirectory) {
            for (child in file.listFiles().orEmpty()) delete(child)
        }
        Files.deleteIfExists(path)
    }

    private fun isInside(dir: File, folder: File): Boolean {
        val d = dir.absolutePath + "/"
        return folder.isDirectory && !Files.isSymbolicLink(folder.toPath()) && d.startsWith(folder.absolutePath + "/")
    }
}
