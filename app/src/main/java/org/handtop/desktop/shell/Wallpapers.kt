package org.handtop.desktop.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.graphics.PathParser
import org.handtop.desktop.R
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Desktop wallpapers: eight built-in vector pictures (assets/wallpapers.json, made by
 * tools/wallpapers/make_wallpapers.py) or an image of the user's.
 *
 * Every wallpaper is drawn once into a bitmap of the screen's size, off the main thread, and
 * the desktop shows that bitmap: hundreds of vector layers redrawn in every frame would slow
 * down every window (a Linux game draws 60 frames a second). Pictures cover the screen and
 * are cropped to its middle, so one picture fits a phone, DeX and a tablet in either
 * orientation.
 */
object Wallpapers {

    const val DEFAULT = "twilight"
    const val CUSTOM = "custom"

    /** The built-in wallpapers, in the order the chooser shows them. */
    val BUILT_IN = listOf("twilight", "waves", "dunes", "aurora", "facets", "orbits", "ribbons", "bokeh", "turquoise")
    private val NAMES = mapOf(
        "twilight" to R.string.wallpaper_twilight,
        "waves" to R.string.wallpaper_waves,
        "dunes" to R.string.wallpaper_dunes,
        "aurora" to R.string.wallpaper_aurora,
        "facets" to R.string.wallpaper_facets,
        "orbits" to R.string.wallpaper_orbits,
        "ribbons" to R.string.wallpaper_ribbons,
        "bokeh" to R.string.wallpaper_bokeh,
        "turquoise" to R.string.wallpaper_turquoise,
        CUSTOM to R.string.wallpaper_custom,
    )

    /** Shown while a wallpaper is being drawn: the darkest colour of the default one. */
    const val PLACEHOLDER = 0xFF0B1023.toInt()

    private const val TAG = "HandTopWallpaper"
    private const val PREFS = "desktop"
    private const val KEY = "wallpaper"
    private const val IMAGE = "wallpaper.jpg"

    // One thread draws everything: the parsed paths and paints are not shared between threads.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "wallpapers").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var designs: Map<String, Design>? = null // on the worker thread only

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** The chosen wallpaper: a built-in id or [CUSTOM]. */
    fun current(context: Context): String {
        val id = prefs(context).getString(KEY, DEFAULT) ?: DEFAULT
        return when {
            id == CUSTOM && imageFile(context).isFile -> CUSTOM
            id in BUILT_IN -> id
            else -> DEFAULT
        }
    }

    fun name(context: Context, id: String): String = context.getString(NAMES[id] ?: R.string.wallpaper_twilight)

    fun hasCustom(context: Context) = imageFile(context).isFile

    fun select(context: Context, id: String) {
        prefs(context).edit().putString(KEY, id).apply()
        listeners.forEach { it() }
    }

    /**
     * Makes the user's image the wallpaper: decoded (turned upright, made no larger than the
     * screen needs) and kept in the app's files, since the picker's permission does not last.
     */
    fun setCustom(context: Context, uri: Uri, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        val metrics = app.resources.displayMetrics
        val longSide = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1920)
        worker.execute {
            val ok = try {
                val bitmap = decode(app, uri, longSide)
                val tmp = File(app.filesDir, "$IMAGE.new")
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                tmp.renameTo(imageFile(app))
            } catch (e: Exception) {
                Log.w(TAG, "cannot use $uri as the wallpaper", e)
                false
            }
            main.post {
                if (ok) select(app, CUSTOM)
                done(ok)
            }
        }
    }

    /** Draws wallpaper [id] at [width] x [height] off the main thread; [done] gets it on the main thread. */
    fun render(context: Context, id: String, width: Int, height: Int, done: (Bitmap?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val bitmap = try {
                if (width <= 0 || height <= 0) null
                else if (id == CUSTOM) renderImage(app, width, height)
                else renderDesign(app, id, width, height)
            } catch (e: Throwable) { // OutOfMemoryError included: the placeholder is fine
                Log.w(TAG, "cannot draw wallpaper $id", e)
                null
            }
            main.post { done(bitmap) }
        }
    }

    // ---- built-in pictures ----

    private class Layer(val path: Path, val paint: Paint)
    private class Design(val width: Float, val height: Float, val layers: List<Layer>)

    private fun renderDesign(context: Context, id: String, width: Int, height: Int): Bitmap? {
        val design = designs(context)[id] ?: designs(context)[DEFAULT] ?: return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = max(width / design.width, height / design.height) // cover
        canvas.translate((width - design.width * scale) / 2, (height - design.height * scale) / 2)
        canvas.scale(scale, scale)
        for (layer in design.layers) canvas.drawPath(layer.path, layer.paint)
        return bitmap
    }

    private fun designs(context: Context): Map<String, Design> = designs ?: parse(context).also { designs = it }

    private fun parse(context: Context): Map<String, Design> {
        val json = JSONObject(context.assets.open("wallpapers.json").bufferedReader().use { it.readText() })
        val width = json.getDouble("width").toFloat()
        val height = json.getDouble("height").toFloat()
        val result = HashMap<String, Design>()
        val list = json.getJSONArray("wallpapers")
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val layers = item.getJSONArray("layers")
            result[item.getString("id")] = Design(width, height, List(layers.length()) { layer(layers.getJSONObject(it)) })
        }
        return result
    }

    private fun layer(json: JSONObject): Layer {
        val path = PathParser.createPathFromPathData(json.getString("d"))
        if (json.optBoolean("evenOdd")) path.fillType = Path.FillType.EVEN_ODD
        val opacity = json.optDouble("opacity", 1.0).toFloat()
        val fill = json.getJSONObject("fill")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        when (fill.getString("type")) {
            "solid" -> paint.color = withAlpha(Color.parseColor(fill.getString("color")), fill.optDouble("opacity", 1.0).toFloat() * opacity)
            else -> {
                val stops = fill.getJSONArray("stops")
                val positions = FloatArray(stops.length()) { stops.getJSONArray(it).getDouble(0).toFloat() }
                val colors = IntArray(stops.length()) {
                    val stop = stops.getJSONArray(it)
                    withAlpha(Color.parseColor(stop.getString(1)), stop.getDouble(2).toFloat())
                }
                paint.shader = if (fill.getString("type") == "linear") {
                    LinearGradient(
                        fill.getDouble("x1").toFloat(), fill.getDouble("y1").toFloat(),
                        fill.getDouble("x2").toFloat(), fill.getDouble("y2").toFloat(),
                        colors, positions, Shader.TileMode.CLAMP,
                    )
                } else {
                    RadialGradient(
                        fill.getDouble("cx").toFloat(), fill.getDouble("cy").toFloat(), fill.getDouble("r").toFloat(),
                        colors, positions, Shader.TileMode.CLAMP,
                    )
                }
                paint.alpha = (opacity * 255).roundToInt()
                paint.isDither = true // smooth gradients over a whole screen
            }
        }
        return Layer(path, paint)
    }

    private fun withAlpha(color: Int, alpha: Float) =
        Color.argb((Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

    // ---- the user's image ----

    private fun renderImage(context: Context, width: Int, height: Int): Bitmap? {
        val file = imageFile(context)
        if (!file.isFile) return renderDesign(context, DEFAULT, width, height)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        // Keep enough pixels to cover the screen.
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        // An unreadable image (damaged, or removed meanwhile) leaves the default wallpaper, not a blank one.
        val source = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return renderDesign(context, DEFAULT, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val dst = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        Canvas(bitmap).drawBitmap(source, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return bitmap
    }

    /** The image at [uri], upright, its long side at most [longSide]. */
    private fun decode(context: Context, uri: Uri, longSide: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = info.size
                val ratio = longSide.toFloat() / max(size.width, size.height)
                if (ratio < 1f) decoder.setTargetSize((size.width * ratio).roundToInt(), (size.height * ratio).roundToInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // drawn and compressed in software
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longSide) sample *= 2
        return context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("not an image")
    }

    private fun imageFile(context: Context) = File(context.filesDir, IMAGE)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * A wallpaper as a drawable: the bitmap [Wallpapers] draws for the current size, the
 * placeholder colour until it is there. Follows the chosen wallpaper by itself when [follow].
 */
class WallpaperDrawable(
    private val context: Context,
    private var id: String,
    private val follow: Boolean = false,
) : Drawable() {

    private var bitmap: Bitmap? = null
    private var generation = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val listener: () -> Unit = { show(Wallpapers.current(context)) }

    init {
        if (follow) Wallpapers.addListener(listener)
    }

    /** Stops following the chosen wallpaper (the desktop is going away). */
    fun release() {
        Wallpapers.removeListener(listener)
    }

    /** Shows wallpaper [newId]; the current picture stays until the new one is drawn. */
    fun show(newId: String) {
        id = newId
        request()
    }

    override fun onBoundsChange(bounds: Rect) {
        val current = bitmap
        if (current == null || current.width != bounds.width() || current.height != bounds.height()) request()
    }

    private fun request() {
        val b = bounds
        if (b.isEmpty) return
        val mine = ++generation
        Wallpapers.render(context, id, b.width(), b.height()) { result ->
            if (mine != generation || result == null) return@render
            bitmap = result
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val current = bitmap
        if (current == null) {
            canvas.drawColor(Wallpapers.PLACEHOLDER)
            return
        }
        // While a new size is being drawn, the previous bitmap is stretched over it.
        canvas.drawBitmap(current, null, bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
