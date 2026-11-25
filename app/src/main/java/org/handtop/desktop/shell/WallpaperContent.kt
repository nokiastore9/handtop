package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.wm.WindowContent

/**
 * The wallpaper chooser: HandTop's eight pictures and the user's own image. A tap applies
 * the wallpaper at once; the chosen one is outlined.
 */
@SuppressLint("ViewConstructor")
class WallpaperContent(
    context: Context,
    /** Opens Android's image picker; the result comes back through [Wallpapers.setCustom]. */
    private val pickImage: () -> Unit,
) : ScrollView(context), WindowContent {

    override val view: View get() = this

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(18), dpi(14), dpi(18), dpi(18))
    }
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val listener: () -> Unit = { build() }
    private var columns = 0
    private val thumbWidth = dpi(128)
    private val thumbHeight = dpi(80)

    init {
        setBackgroundColor(0xFF0F1420.toInt())
        column.addView(grid)
        column.addView(Button(context).apply {
            isAllCaps = false
            setText(R.string.wallpaper_choose_image)
            setOnClickListener { pickImage() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(10)
        })
        column.addView(TextView(context).apply {
            setText(R.string.wallpaper_hint)
            textSize = 13f
            setTextColor(Palette.TEXT_DIM)
            setPadding(0, dpi(8), 0, 0)
        })
        addView(column)
        Wallpapers.addListener(listener)
    }

    override fun onClosed() {
        Wallpapers.removeListener(listener)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fit = ((w - column.paddingLeft - column.paddingRight) / (thumbWidth + dpi(12))).coerceAtLeast(1)
        if (fit != columns) {
            columns = fit
            post { build() }
        }
    }

    private fun build() {
        if (columns == 0) return
        grid.removeAllViews()
        val ids = Wallpapers.BUILT_IN + if (Wallpapers.hasCustom(context)) listOf(Wallpapers.CUSTOM) else emptyList()
        val current = Wallpapers.current(context)
        ids.chunked(columns).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { id -> row.addView(tile(id, id == current)) }
            grid.addView(row)
        }
    }

    private fun tile(id: String, chosen: Boolean): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(4), dpi(4), dpi(4), dpi(6))
            background = context.selectableBackground()
            contentDescription = Wallpapers.name(context, id)
            setOnClickListener { Wallpapers.select(context, id) }
        }
        val frame = FrameLayout(context).apply {
            // The chosen wallpaper gets a ring around it.
            background = if (chosen) roundedRect(0, dp(12f), Palette.ACCENT, dpi(3)) else null
            setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
        }
        frame.addView(View(context).apply {
            background = WallpaperDrawable(context, id)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(8f))
            }
        }, FrameLayout.LayoutParams(thumbWidth, thumbHeight))
        tile.addView(frame)
        tile.addView(TextView(context).apply {
            text = Wallpapers.name(context, id)
            textSize = 13f
            setTextColor(if (chosen) Palette.TEXT else Palette.TEXT_SOFT)
            setPadding(0, dpi(4), 0, 0)
        })
        return tile.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dpi(4)
                bottomMargin = dpi(4)
            }
        }
    }
}
