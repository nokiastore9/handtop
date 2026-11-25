package org.handtop.desktop.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import org.handtop.desktop.R
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.dp

/**
 * The classic start menu's side band: a blue gradient, darker at the bottom, with
 * HandTop's name written up it from the bottom.
 */
class StartBand(context: Context) : View(context) {

    private val name = context.getString(R.string.app_name)
    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Themes.current.colors.selectionText
        typeface = Typeface.DEFAULT_BOLD
        textSize = dp(17f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val c = Themes.current.colors
        fill.shader = LinearGradient(0f, h.toFloat(), 0f, 0f, c.selection, c.titleActiveEnd, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        canvas.save()
        // Rotated a quarter turn counter-clockwise: the text reads upwards, starting near the bottom.
        canvas.rotate(-90f)
        val baseline = (width + text.textSize * 0.7f) / 2f
        canvas.drawText(name, -height + dp(10f), baseline, text)
        canvas.restore()
    }
}
