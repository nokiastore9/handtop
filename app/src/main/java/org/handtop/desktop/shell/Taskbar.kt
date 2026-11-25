package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.wm.DesktopArea
import org.handtop.desktop.wm.DesktopWindow

/**
 * Bottom panel: start button, the Linux programs, one button per open window, the Linux
 * setup while it runs, keyboard button and status / clock.
 */
@SuppressLint("ViewConstructor")
class Taskbar(
    context: Context,
    private val area: DesktopArea,
    private val onStart: (View) -> Unit,
    private val onKeyboard: () -> Unit,
    /** Opens the Linux System window (from the Linux setup's item). */
    private val onSetup: () -> Unit,
    /** Opens or closes the Linux programs (the launchpad). */
    private val onApps: () -> Unit,
    /** A window's button was used (the launchpad gets out of the way). */
    private val onWindowButton: () -> Unit,
) : LinearLayout(context), DesktopArea.Listener {

    val startButton: ImageButton
    private val strip = LinearLayout(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Palette.TASKBAR)
        elevation = dp(12f)

        startButton = iconButton(R.drawable.ic_handtop, R.string.start_menu) { onStart(it) }
        startButton.setColorFilter(Palette.ACCENT)
        addView(startButton, LayoutParams(dpi(52), LayoutParams.MATCH_PARENT))
        // The Linux programs, as GNOME's "Show Applications".
        addView(iconButton(R.drawable.ic_apps, R.string.app_linux_apps) { onApps() }.apply {
            setColorFilter(Palette.TEXT_SOFT)
        }, LayoutParams(dpi(44), LayoutParams.MATCH_PARENT))

        strip.orientation = HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        addView(SetupIndicator(context) { onSetup() }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        addView(iconButton(R.drawable.ic_keyboard, R.string.action_keyboard) { onKeyboard() },
            LayoutParams(dpi(48), LayoutParams.MATCH_PARENT))

        // HandTop runs full screen: network, battery and clock live here instead of Android's status bar.
        addView(StatusArea(context), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    override fun onWindowsChanged() {
        strip.removeAllViews()
        for (w in area.windows) strip.addView(windowButton(w))
    }

    private fun windowButton(w: DesktopWindow): View {
        val isFront = w === area.activeWindow && !w.minimized
        val item = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(10), 0, dpi(12), 0)
            background = itemBackground(isFront, w.minimized)
            foreground = context.selectableBackground()
            tooltipText = w.title
            setOnClickListener {
                onWindowButton()
                if (w === area.activeWindow && !w.minimized) area.minimize(w) else area.activate(w)
            }
        }
        val icon = ImageView(context).apply {
            val bitmap = w.iconBitmap
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(w.iconRes)
            alpha = if (w.minimized) 0.6f else 1f
        }
        item.addView(icon, LayoutParams(dpi(20), dpi(20)))
        val label = TextView(context).apply {
            text = w.title
            setTextColor(if (w.minimized) Palette.TEXT_DIM else Palette.TEXT)
            textSize = 13f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dpi(160)
            setPadding(dpi(8), 0, 0, 0)
        }
        item.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        item.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            marginStart = dpi(2)
        }
        return item
    }

    /** Highlighted background plus a coloured underline for the front window. */
    private fun itemBackground(front: Boolean, minimized: Boolean): android.graphics.drawable.Drawable {
        val fill = ColorDrawable(if (front) Palette.TASKBAR_ITEM_ACTIVE else 0)
        val bar = GradientDrawable().apply {
            setColor(if (minimized) Palette.TEXT_DIM else Palette.TASKBAR_INDICATOR)
            cornerRadius = dp(2f)
        }
        return LayerDrawable(arrayOf(fill, bar)).apply {
            val h = dpi(3)
            val inset = if (front) dpi(6) else dpi(14)
            setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, h)
            setLayerInsetLeft(1, inset)
            setLayerInsetRight(1, inset)
            setLayerInsetBottom(1, dpi(2))
        }
    }

    private fun iconButton(icon: Int, desc: Int, onClick: (View) -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        contentDescription = context.getString(desc)
        tooltipText = contentDescription
        scaleType = ImageView.ScaleType.CENTER
        background = context.selectableBackground()
        isFocusable = false
        setOnClickListener(onClick)
    }
}
