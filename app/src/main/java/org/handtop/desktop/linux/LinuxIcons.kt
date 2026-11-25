package org.handtop.desktop.linux

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * The icons of Linux programs, as the programs ship them: found by name (Icon= of a .desktop
 * file) in every icon theme and size the distribution has, the user's own icons and
 * Flatpak's, or /usr/share/pixmaps; and drawn from PNG, SVG (most GNOME programs have no
 * other) or XPM (older X11 programs such as xterm).
 *
 * An index of every program icon is built once per menu scan ([refresh]), so looking up a
 * hundred programs does not walk the themes a hundred times.
 */
object LinuxIcons {

    private const val TAG = "LinuxIcons"
    private val THEME_ROOTS = listOf(
        "usr/share/icons", "usr/local/share/icons", "root/.local/share/icons", "root/.icons",
        "var/lib/flatpak/exports/share/icons",
    )
    private const val PIXMAPS = "usr/share/pixmaps"
    private val FORMATS = setOf("png", "svg", "svgz", "xpm")

    private class Candidate(val file: File, val score: Int)

    @Volatile
    private var index: Map<String, Candidate>? = null

    /** Reads the icon themes again (programs may have been installed). Off the main thread. */
    fun refresh(rootfs: File) {
        index = build(rootfs)
    }

    /** The best file for icon [icon] (a name, or an absolute path inside Linux), or null. */
    fun find(rootfs: File, icon: String): File? {
        if (icon.startsWith("/")) {
            val file = LinuxApps.inRoot(rootfs, File(rootfs, icon.trimStart('/')))
            return file.takeIf { it.isFile && it.extension.lowercase(Locale.ROOT) in FORMATS }
        }
        val name = if (icon.substringAfterLast('.', "").lowercase(Locale.ROOT) in FORMATS) icon.substringBeforeLast('.') else icon
        val known = index ?: build(rootfs).also { index = it }
        return known[name]?.let { LinuxApps.inRoot(rootfs, it.file) }
    }

    /** The icon in [file] drawn at about [sizePx] square, or null when it cannot be read. */
    fun decode(file: File, sizePx: Int): Bitmap? = try {
        when (file.extension.lowercase(Locale.ROOT)) {
            "svg" -> file.inputStream().use { svg(it, sizePx) }
            "svgz" -> GZIPInputStream(file.inputStream()).use { svg(it, sizePx) }
            "xpm" -> Xpm.decode(file.readText())?.let { Bitmap.createScaledBitmap(it, sizePx, sizePx, true) }
            else -> png(file, sizePx)
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot draw $file", e)
        null
    }

    // ---- finding ----

    /**
     * Every program icon, by name, with the best file for it: the program's own (the hicolor
     * theme, where programs install theirs) before a theme's look-alike, then a scalable one
     * before the largest picture; never a one-colour "symbolic" icon if there is another.
     */
    private fun build(rootfs: File): Map<String, Candidate> {
        val best = HashMap<String, Candidate>()
        fun offer(file: File, sizeScore: Int, hicolor: Boolean) {
            val ext = file.extension.lowercase(Locale.ROOT)
            if (ext !in FORMATS) return
            val name = file.name.substringBeforeLast('.')
            val symbolic = name.endsWith("-symbolic") || file.parentFile?.parentFile?.name == "symbolic"
            val score = (if (hicolor) 100_000 else 0) + (if (symbolic) 0 else 10_000) + sizeScore
            val current = best[name]
            if (current == null || score > current.score) best[name] = Candidate(file, score)
        }
        for (root in THEME_ROOTS) {
            val themes = File(rootfs, root).listFiles() ?: continue
            for (theme in themes) {
                val hicolor = theme.name == "hicolor"
                // Two layouts: <theme>/<size>/apps (freedesktop) and <theme>/apps/<size> (Breeze).
                for (sizeDir in theme.listFiles() ?: continue) {
                    File(sizeDir, "apps").listFiles()?.forEach { offer(it, sizeScore(sizeDir.name), hicolor) }
                }
                File(theme, "apps").listFiles()?.forEach { sizeDir ->
                    sizeDir.listFiles()?.forEach { offer(it, sizeScore(sizeDir.name), hicolor) }
                }
            }
        }
        // Older programs: /usr/share/pixmaps, sometimes with the size in the name (name_48x48.xpm).
        File(rootfs, PIXMAPS).listFiles()?.forEach { file ->
            val base = file.name.substringBeforeLast('.')
            val sized = Regex("^(.+)_(\\d+)x\\d+$").find(base)
            val name = sized?.groupValues?.get(1) ?: base
            val size = sized?.groupValues?.get(2)?.toIntOrNull() ?: if (file.extension.startsWith("svg")) 9_000 else 48
            val ext = file.extension.lowercase(Locale.ROOT)
            if (ext !in FORMATS) return@forEach
            // Below the themes: a theme's icon of the same name is the newer one.
            val score = 50_000 + 10_000 + size
            val current = best[name]
            if (current == null || score > current.score) best[name] = Candidate(file, score)
        }
        return best
    }

    /** How good a size directory is: scalable first, then the larger the better. */
    private fun sizeScore(dir: String): Int = when {
        dir.startsWith("scalable") -> 9_000
        else -> Regex("^(\\d+)").find(dir)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtMost(1024) ?: 1
    }

    // ---- drawing ----

    private fun png(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun svg(input: InputStream, sizePx: Int): Bitmap {
        val svg = SVG.getFromInputStream(input)
        // Scaled to the icon's size: an SVG's own width and height would otherwise win (a
        // 16-pixel icon would be drawn 16 pixels large), and without a view box there is
        // nothing to scale from.
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap), RenderOptions().viewPort(0f, 0f, sizePx.toFloat(), sizePx.toFloat()))
        return bitmap
    }

    /** X PixMap: C source with the picture as strings (a header, a colour table, the rows). */
    private object Xpm {
        fun decode(text: String): Bitmap? {
            val strings = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text).map { it.groupValues[1] }.toList()
            val header = strings.firstOrNull()?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() } ?: return null
            if (header.size < 4) return null
            val (width, height, colors, cpp) = header
            if (width <= 0 || height <= 0 || cpp <= 0 || strings.size < 1 + colors + height) return null
            val table = HashMap<String, Int>()
            for (i in 1..colors) {
                val line = strings[i]
                if (line.length < cpp) continue
                table[line.substring(0, cpp)] = color(line.substring(cpp))
            }
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val row = strings[1 + colors + y]
                for (x in 0 until width) {
                    val at = x * cpp
                    if (at + cpp > row.length) break
                    pixels[y * width + x] = table[row.substring(at, at + cpp)] ?: Color.TRANSPARENT
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        /** The colour of a table entry: its "c" (colour) key, the others as fall-backs. */
        private fun color(spec: String): Int {
            val tokens = spec.trim().split(Regex("\\s+"))
            val keys = setOf("c", "m", "g", "g4", "s")
            val values = HashMap<String, String>()
            var i = 0
            while (i < tokens.size) {
                val key = tokens[i]
                if (key in keys) {
                    val value = StringBuilder()
                    i++
                    while (i < tokens.size && tokens[i] !in keys) value.append(tokens[i++])
                    values[key] = value.toString()
                } else {
                    i++
                }
            }
            val value = values["c"] ?: values["g"] ?: values["g4"] ?: values["m"] ?: return Color.GRAY
            return parse(value)
        }

        private fun parse(value: String): Int {
            val v = value.lowercase(Locale.ROOT)
            if (v == "none") return Color.TRANSPARENT
            if (v.startsWith("#")) {
                val hex = v.substring(1)
                return when (hex.length) {
                    3 -> Color.rgb(hex.substring(0, 1).toInt(16) * 17, hex.substring(1, 2).toInt(16) * 17, hex.substring(2, 3).toInt(16) * 17)
                    6 -> Color.rgb(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
                    12 -> Color.rgb(hex.substring(0, 2).toInt(16), hex.substring(4, 6).toInt(16), hex.substring(8, 10).toInt(16))
                    else -> Color.GRAY
                }
            }
            Regex("^gr[ae]y(\\d+)$").find(v)?.let {
                val level = (it.groupValues[1].toInt().coerceIn(0, 100) * 255) / 100
                return Color.rgb(level, level, level)
            }
            return NAMES[v] ?: Color.GRAY
        }

        private val NAMES = mapOf(
            "black" to Color.BLACK, "white" to Color.WHITE, "red" to Color.RED, "green" to Color.rgb(0, 128, 0),
            "blue" to Color.BLUE, "yellow" to Color.YELLOW, "cyan" to Color.CYAN, "magenta" to Color.MAGENTA,
            "gray" to Color.rgb(190, 190, 190), "grey" to Color.rgb(190, 190, 190),
            "darkgray" to Color.rgb(169, 169, 169), "darkgrey" to Color.rgb(169, 169, 169),
            "lightgray" to Color.rgb(211, 211, 211), "lightgrey" to Color.rgb(211, 211, 211),
            "orange" to Color.rgb(255, 165, 0), "brown" to Color.rgb(165, 42, 42), "navy" to Color.rgb(0, 0, 128),
        )
    }
}
