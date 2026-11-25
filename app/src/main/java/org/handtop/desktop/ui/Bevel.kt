package org.handtop.desktop.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

/** The edges of the classic 3D look. */
enum class Bevel {
    /** Buttons, window frames, panels, menus: light top-left, dark bottom-right, two lines. */
    RAISED,
    /** Fields and lists: the reverse of [RAISED]. */
    SUNKEN,
    /** One line each way: the clock box on the taskbar, status bars. */
    SHALLOW,
    /** A button held down. */
    PRESSED,
    /** A button that stays down (the active window's taskbar button): pressed, with a fine checker. */
    CHECKERED,
    /** A cut line: group frames and separators. */
    ETCHED,
}

/**
 * A rectangle with classic bevelled edges, filled with [fill] (nothing for null). Each of
 * the edge lines is [edge] pixels thick: one point, so the look stays the same at every
 * density and every interface scale.
 */
class BevelDrawable(private val kind: Bevel, private val fill: Int?, private val edge: Int) : Drawable() {

    private val c = ClassicTheme.colors
    private val paint = Paint()
    private val checker: Paint? = if (kind == Bevel.CHECKERED) checkerPaint() else null

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        when {
            checker != null -> canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), checker)
            fill != null -> fillRect(canvas, 0, 0, w, h, fill)
        }
        val e = edge
        when (kind) {
            Bevel.RAISED -> {
                frame(canvas, 0, w, h, c.light, c.darkShadow)
                frame(canvas, e, w, h, c.innerLight, c.shadow)
            }
            Bevel.SUNKEN -> {
                frame(canvas, 0, w, h, c.shadow, c.light)
                frame(canvas, e, w, h, c.darkShadow, c.innerLight)
            }
            Bevel.PRESSED, Bevel.CHECKERED -> {
                frame(canvas, 0, w, h, c.darkShadow, c.light)
                frame(canvas, e, w, h, c.shadow, c.innerLight)
            }
            Bevel.SHALLOW -> frame(canvas, 0, w, h, c.shadow, c.light)
            Bevel.ETCHED -> {
                // A shadow line with a light line just inside and below it.
                outline(canvas, e, 0, 0, w - e, h - e, c.light)
                outline(canvas, e, 0, 0, w - e, h - e, c.shadow, light = false)
            }
        }
        canvas.restore()
    }

    /** Top and left lines in [topLeft], bottom and right in [bottomRight], [inset] in. */
    private fun frame(canvas: Canvas, inset: Int, w: Int, h: Int, topLeft: Int, bottomRight: Int) {
        val e = edge
        val l = inset
        val t = inset
        val r = w - inset
        val bt = h - inset
        if (r - l < 2 * e || bt - t < 2 * e) return
        fillRect(canvas, l, t, r, t + e, topLeft)
        fillRect(canvas, l, t, l + e, bt, topLeft)
        // Drawn second, so the bottom-left and top-right corners belong to the dark side.
        fillRect(canvas, l, bt - e, r, bt, bottomRight)
        fillRect(canvas, r - e, t, r, bt, bottomRight)
    }

    /** The etched frame: a [light] outline one edge right and down of a shadow outline. */
    private fun outline(canvas: Canvas, e: Int, l: Int, t: Int, r: Int, b: Int, color: Int, light: Boolean = true) {
        val d = if (light) e else 0
        fillRect(canvas, l + d, t + d, r + d, t + d + e, color)
        fillRect(canvas, l + d, b + d - e, r + d, b + d, color)
        fillRect(canvas, l + d, t + d, l + d + e, b + d, color)
        fillRect(canvas, r + d - e, t + d, r + d, b + d, color)
    }

    private fun fillRect(canvas: Canvas, l: Int, t: Int, r: Int, b: Int, color: Int) {
        paint.color = color
        canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint)
    }

    private fun checkerPaint(): Paint {
        val cell = max(1, edge)
        val tile = Bitmap.createBitmap(2 * cell, 2 * cell, Bitmap.Config.ARGB_8888)
        Canvas(tile).apply {
            drawColor(c.face)
            val light = Paint().apply { color = c.light }
            drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), light)
            drawRect(cell.toFloat(), cell.toFloat(), 2f * cell, 2f * cell, light)
        }
        return Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    override fun getPadding(padding: Rect): Boolean {
        val p = when (kind) {
            Bevel.SHALLOW -> edge
            else -> 2 * edge
        }
        padding.set(p, p, p, p)
        return true
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (fill != null || checker != null) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
}

/** A [BevelDrawable] with edges of one point at this context's density. */
fun Context.bevel(kind: Bevel, fill: Int? = ClassicTheme.colors.face): BevelDrawable =
    BevelDrawable(kind, fill, max(1, dp(1f).roundToInt()))
