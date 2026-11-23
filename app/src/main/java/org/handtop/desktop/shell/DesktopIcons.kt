package org.handtop.desktop.shell

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.selectableBackground

/** Column of shortcuts on the wallpaper, below all windows. */
class DesktopIcons(context: Context) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
    }

    fun add(@DrawableRes icon: Int, @StringRes label: Int, onOpen: () -> Unit) {
        val item = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(6), dpi(8), dpi(6), dpi(8))
            background = context.selectableBackground()
            setOnClickListener { onOpen() }
        }
        item.addView(ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(dpi(40), dpi(40)))
        item.addView(TextView(context).apply {
            setText(label)
            setTextColor(Palette.TEXT)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
            setPadding(0, dpi(4), 0, 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(item, LayoutParams(dpi(92), LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(4) })
    }
}
