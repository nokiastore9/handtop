package org.handtop.desktop.linux

import android.content.Context
import android.graphics.Bitmap
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
    /** The program's icon inside the root filesystem (PNG, SVG or XPM), if it has one. */
    val iconFile: File?,
    /** The Exec line as written, with %f / %F / %u / %U where files go. */
    val execTemplate: String = exec,
    /** MIME types the program opens (MimeType=). */
    val mimeTypes: List<String> = emptyList(),
    /** Categories= (Viewer, Player, TextEditor, WebBrowser…). */
    val categories: List<String> = emptyList(),
)

/**
 * The Linux apps menu: reads .desktop files from the installed distribution and starts
 * programs without a terminal. Started programs keep HandTop's foreground service alive,
 * like terminals do.
 */
object LinuxApps {

    private const val TAG = "LinuxApps"
    private val MENU_DIRS = listOf("usr/share/applications", "usr/local/share/applications", "root/.local/share/applications")
    private const val X11_WAIT_MS = 8_000L

    /**
     * GTK programs that work properly only on X11 here: Parole puts its video into an X11
     * window (GstVideoOverlay); as a Wayland window it would show the video in a second window.
     */
    private val PREFER_X11 = setOf("parole")
    private const val BUS_WAIT_MS = 3_000L

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
        LinuxIcons.refresh(rootfs) // programs may have brought icons since the last scan
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
        // E.g. GTK started from a terminal says "mousepad", the menu entry is org.xfce.mousepad.
        fun match(apps: List<LinuxApp>) = apps.firstOrNull {
            it.wmClass.equals(appId, ignoreCase = true) ||
                it.id.substringAfterLast('.').equals(appId, ignoreCase = true) ||
                it.exec.substringBefore(' ').substringAfterLast('/').equals(appId, ignoreCase = true)
        }
        return match(known) ?: match(scan(context)) // not known yet, or installed since the last scan
    }

    /** The program's own icon at about [sizePx], or null (it has none, or it cannot be read). */
    fun icon(app: LinuxApp, sizePx: Int): Bitmap? {
        val file = app.iconFile ?: return null
        val key = "${file.path}@$sizePx"
        icons.get(key)?.let { return it }
        val bitmap = LinuxIcons.decode(file, sizePx) ?: return null
        icons.put(key, bitmap)
        return bitmap
    }

    /**
     * Programs that open [mime] (from their MimeType=), the best first: the default chosen in
     * Linux (mimeapps.list), then programs made for that kind of file (an image viewer before a
     * web browser), then programs that take any file of the family.
     */
    fun appsFor(context: Context, mime: String): List<LinuxApp> {
        val apps = known.ifEmpty { scan(context) }
        val major = mime.substringBefore('/') + "/*"
        val defaults = defaultsFor(LinuxEnv.rootfs(context), mime)
        val exact = apps.filter { mime in it.mimeTypes }
        val wide = apps.filter { it !in exact && (major in it.mimeTypes || (mime.startsWith("text/") && "text/plain" in it.mimeTypes)) }
        fun rank(app: LinuxApp): Int {
            val chosen = defaults.indexOf(app.id)
            if (chosen >= 0) return 100 - chosen
            return fit(app.categories, mime)
        }
        return exact.sortedByDescending(::rank) + wide.sortedByDescending(::rank)
    }

    /** How well a program's Categories fit a kind of file. */
    private fun fit(categories: List<String>, mime: String): Int {
        var score = 0
        val wanted = when {
            mime == "inode/directory" -> listOf("FileManager")
            mime.startsWith("image/") -> listOf("Viewer", "Graphics", "2DGraphics", "Photography")
            mime.startsWith("video/") -> listOf("Video", "Player", "AudioVideo")
            mime.startsWith("audio/") -> listOf("Audio", "Player", "AudioVideo", "Music")
            mime.startsWith("text/") -> listOf("TextEditor", "Development")
            else -> emptyList()
        }
        if (categories.any { it in wanted }) score += 10
        val html = mime == "text/html" || mime == "application/xhtml+xml"
        if ("WebBrowser" in categories && !html) score -= 10
        return score
    }

    /** Desktop file ids the user / distribution chose for [mime] (freedesktop mimeapps.list). */
    private fun defaultsFor(rootfs: File, mime: String): List<String> {
        val files = listOf("root/.config/mimeapps.list", "etc/xdg/mimeapps.list", "usr/share/applications/mimeapps.list")
        val ids = ArrayList<String>()
        for (path in files) {
            val lines = try {
                File(rootfs, path).takeIf { it.isFile }?.readLines() ?: continue
            } catch (_: IOException) {
                continue
            }
            var section = ""
            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("[")) section = line
                else if (section == "[Default Applications]" && line.startsWith("$mime=")) {
                    ids += list(line.substringAfter('=')).map { it.removeSuffix(".desktop") }
                }
            }
        }
        return ids
    }

    /** The command that opens [paths] (paths inside Linux) with [app], per its Exec field codes. */
    fun commandFor(app: LinuxApp, paths: List<String>): String {
        val all = paths.joinToString(" ") { quote(it) }
        var used = false
        val command = app.execTemplate.replace(Regex("%[a-zA-Z%]")) { code ->
            when (code.value) {
                "%f", "%u" -> { used = true; paths.firstOrNull()?.let(::quote) ?: "" }
                "%F", "%U" -> { used = true; all }
                "%%" -> "%"
                else -> "" // %i, %c, %k: icon, name, desktop file
            }
        }.replace(Regex("\\s+"), " ").trim()
        return if (!used && paths.isNotEmpty()) "$command $all" else command
    }

    /** Single-quoted for sh: a quote inside becomes '\'' (close, escaped quote, reopen). */
    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    /** Starts a graphical program in the background (no terminal window), optionally with files. */
    fun launch(context: Context, app: LinuxApp, paths: List<String> = emptyList()) {
        LinuxEnv.startServices(context)
        val program = app.exec.substringBefore(' ').substringAfterLast('/')
        val x11 = program in PREFER_X11 && X11Display.isInstalled(context)
        val rootfs = LinuxEnv.rootfs(context)
        val command = ChromiumFlags.apply(rootfs, commandFor(app, paths))
        val spec = LinuxEnv.commandSpec(context, (if (x11) "export GDK_BACKEND=x11; " else "") + "exec $command")
        val log = File(rootfs, "tmp/handtop-apps.log")
        val builder = ProcessBuilder(listOf(spec.executable) + spec.args.drop(1))
            .directory(File(spec.cwd))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        builder.environment().apply {
            clear()
            putAll(spec.env)
        }
        val appContext = context.applicationContext
        val waitX11 = X11Display.isInstalled(appContext)
        Thread({
            // The session bus and X11 may have been started just now.
            if (SessionBus.isAvailable(appContext)) SessionBus.awaitReady(appContext, BUS_WAIT_MS)
            if (waitX11) X11Display.awaitReady(appContext, X11_WAIT_MS)
            val process = try {
                log.appendText("--- ${app.name}: ${app.exec}\n")
                builder.start()
            } catch (e: IOException) {
                Log.w(TAG, "cannot start ${app.id}", e)
                return@Thread
            }
            main.post {
                running += process
                SessionService.update(appContext)
            }
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
            iconFile = keys["Icon"]?.let { LinuxIcons.find(rootfs, it) },
            execTemplate = keys["Exec"] ?: exec,
            mimeTypes = list(keys["MimeType"]),
            categories = list(keys["Categories"]),
        )
    }

    private fun list(value: String?) = value?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Removes %f, %U and other field codes; "%%" is a literal percent sign. */
    private fun stripFieldCodes(exec: String): String =
        exec.replace(Regex("%[a-zA-Z]"), "").replace("%%", "%").replace(Regex("\\s+"), " ").trim()

    private fun unescape(value: String) =
        value.replace("\\s", " ").replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\")

    /**
     * Follows symbolic links the way they resolve inside Linux: an absolute target means a
     * path in the root filesystem, not on Android.
     */
    internal fun inRoot(rootfs: File, file: File): File {
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
