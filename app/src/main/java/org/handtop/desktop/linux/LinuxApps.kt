package org.handtop.desktop.linux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import org.handtop.desktop.service.SessionService
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/** A graphical (or terminal) program from the distribution's menu (its .desktop file). */
data class LinuxApp(
    /** Desktop file id, e.g. "org.xfce.mousepad" — usually the Wayland app_id too. */
    val id: String,
    val name: String,
    val comment: String?,
    /** Exec line without field codes (%f, %U…). */
    val exec: String,
    val terminal: Boolean,
    val wmClass: String?,
    /** Lower-case text the search box matches against. */
    val searchText: String,
    /** A PNG icon inside the root filesystem, if the app has one. */
    val iconFile: File?,
)

/**
 * The Linux apps menu: reads .desktop files from the installed distribution and starts
 * programs without a terminal. Started programs keep HandTop's foreground service alive,
 * like terminals do.
 */
object LinuxApps {

    private const val TAG = "LinuxApps"
    private val MENU_DIRS = listOf("usr/share/applications", "usr/local/share/applications", "root/.local/share/applications")
    private val ICON_SIZES = listOf("256x256", "128x128", "96x96", "64x64", "48x48", "32x32")

    private val main = Handler(Looper.getMainLooper())
    private val icons = LruCache<String, Bitmap>(64)

    /** Programs started from the menu that are still running. */
    val running = HashSet<Process>()

    /** Last scan; used to find the icon of a window by its app id. */
    @Volatile
    private var known: List<LinuxApp> = emptyList()

    /** The menu entries, sorted by name. Reads files: call it off the main thread. */
    fun scan(context: Context): List<LinuxApp> {
        val rootfs = LinuxEnv.rootfs(context)
        val byId = LinkedHashMap<String, LinuxApp>()
        for (dir in MENU_DIRS) {
            val files = File(rootfs, dir).listFiles { f -> f.name.endsWith(".desktop") } ?: continue
            for (file in files) parse(rootfs, file)?.let { byId[it.id] = it } // later dirs override
        }
        return byId.values.sortedBy { it.name.lowercase(Locale.getDefault()) }.also { known = it }
    }

    /** The menu entry of a Wayland window, by its app id (desktop file id or WM class). */
    fun forAppId(context: Context, appId: String): LinuxApp? {
        if (appId.isEmpty()) return null
        val rootfs = LinuxEnv.rootfs(context)
        for (dir in MENU_DIRS.asReversed()) {
            val file = File(rootfs, "$dir/$appId.desktop")
            if (file.exists() || Files.isSymbolicLink(file.toPath())) parse(rootfs, file)?.let { return it }
        }
        return known.firstOrNull {
            it.wmClass.equals(appId, ignoreCase = true) || it.id.substringAfterLast('.').equals(appId, ignoreCase = true)
        }
    }

    /** The app's icon at about [sizePx], or null (no PNG icon). */
    fun icon(app: LinuxApp, sizePx: Int): Bitmap? {
        val file = app.iconFile ?: return null
        val key = "${file.path}@$sizePx"
        icons.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        icons.put(key, bitmap)
        return bitmap
    }

    /** Starts a graphical program in the background (no terminal window). */
    fun launch(context: Context, app: LinuxApp) {
        LinuxEnv.startServices(context)
        val spec = LinuxEnv.commandSpec(context, "exec ${app.exec}")
        val log = File(LinuxEnv.rootfs(context), "tmp/handtop-apps.log")
        val builder = ProcessBuilder(listOf(spec.executable) + spec.args.drop(1))
            .directory(File(spec.cwd))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        builder.environment().apply {
            clear()
            putAll(spec.env)
        }
        val process = try {
            log.appendText("--- ${app.name}: ${app.exec}\n")
            builder.start()
        } catch (e: IOException) {
            Log.w(TAG, "cannot start ${app.id}", e)
            return
        }
        val appContext = context.applicationContext
        running += process
        SessionService.update(appContext)
        Thread({
            process.waitFor()
            main.post {
                running -= process
                SessionService.update(appContext)
            }
        }, "linux-app-${app.id}").start()
    }

    // ---- .desktop files ----

    private fun parse(rootfs: File, file: File): LinuxApp? {
        val lines = try {
            inRoot(rootfs, file).readLines()
        } catch (_: IOException) {
            return null
        }
        val keys = HashMap<String, String>()
        var inEntry = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEntry = line == "[Desktop Entry]"
                continue
            }
            if (!inEntry || line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq > 0) keys[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        if (keys["Type"] != "Application") return null
        if (keys["NoDisplay"] == "true" || keys["Hidden"] == "true") return null
        if (keys.containsKey("OnlyShowIn")) return null // made for one desktop environment's session
        val exec = keys["Exec"]?.let(::stripFieldCodes)?.takeIf { it.isNotBlank() } ?: return null

        val locale = Locale.getDefault()
        fun localized(key: String) =
            keys["$key[${locale.language}_${locale.country}]"] ?: keys["$key[${locale.language}]"] ?: keys[key]
        val name = localized("Name")?.let(::unescape) ?: return null
        val comment = localized("Comment")?.let(::unescape)
        val search = listOfNotNull(name, keys["Name"], localized("GenericName"), comment, localized("Keywords"), exec)
            .joinToString(" ").lowercase(locale)
        return LinuxApp(
            id = file.name.removeSuffix(".desktop"),
            name = name,
            comment = comment,
            exec = exec,
            terminal = keys["Terminal"] == "true",
            wmClass = keys["StartupWMClass"],
            searchText = search,
            iconFile = keys["Icon"]?.let { findIcon(rootfs, it) },
        )
    }

    /** Removes %f, %U and other field codes; "%%" is a literal percent sign. */
    private fun stripFieldCodes(exec: String): String =
        exec.replace(Regex("%[a-zA-Z]"), "").replace("%%", "%").replace(Regex("\\s+"), " ").trim()

    private fun unescape(value: String) =
        value.replace("\\s", " ").replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\")

    private fun findIcon(rootfs: File, icon: String): File? {
        if (icon.startsWith("/")) {
            val file = inRoot(rootfs, File(rootfs, icon.trimStart('/')))
            return file.takeIf { it.isFile && it.name.endsWith(".png") }
        }
        val name = icon.removeSuffix(".png")
        val candidates = ICON_SIZES.map { "usr/share/icons/hicolor/$it/apps/$name.png" } +
            "usr/share/pixmaps/$name.png"
        for (path in candidates) {
            val file = inRoot(rootfs, File(rootfs, path))
            if (file.isFile) return file
        }
        return null
    }

    /**
     * Follows symbolic links the way they resolve inside Linux: an absolute target means a
     * path in the root filesystem, not on Android.
     */
    private fun inRoot(rootfs: File, file: File): File {
        var current = file
        repeat(8) {
            val path = current.toPath()
            if (!Files.isSymbolicLink(path)) return current
            val target = try {
                Files.readSymbolicLink(path).toString()
            } catch (_: IOException) {
                return current
            }
            current = if (target.startsWith("/")) File(rootfs, target.trimStart('/'))
            else File(current.parentFile, target)
        }
        return current
    }
}
