package org.handtop.desktop.notify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.handtop.desktop.R
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.hideSystemBarsOfWindow
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.ui.selectableBackground

/**
 * Notification center: a panel above the right end of the taskbar with the phone's current
 * notifications. Tap a card to open it, × to dismiss it; "Clear all" dismisses what Android
 * lets the user dismiss. Without notification access it explains how to grant it.
 */
class NotificationCenter(private val activity: Activity) {

    private var popup: PopupWindow? = null
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val clearAll = TextView(activity)
    private val listener: () -> Unit = { rebuild() }

    val isShowing get() = popup?.isShowing == true

    fun toggle(anchor: View) {
        if (isShowing) popup?.dismiss() else show(anchor)
    }

    private fun show(anchor: View) {
        // The list and the button live on between openings: free them from the last panel.
        (list.parent as? ViewGroup)?.removeView(list)
        (clearAll.parent as? ViewGroup)?.removeView(clearAll)
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val root = anchor.rootView
        val fromBottom = root.height - loc[1] + activity.dpi(6)

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12), dpi(10), dpi(12), dpi(12))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(4), 0, 0, dpi(8))
        }
        header.addView(TextView(activity).apply {
            setText(R.string.notifications_title)
            textSize = 16f
            setTextColor(Palette.TEXT)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clearAll.apply {
            setText(R.string.notif_clear_all)
            textSize = 13f
            setTextColor(Palette.ACCENT)
            setPadding(dpi(10), dpi(6), dpi(10), dpi(6))
            background = activity.selectableBackground()
            setOnClickListener { Notifications.clearAll() }
        }
        header.addView(clearAll)
        panel.addView(header)

        // Never taller than the space above the taskbar (minus a small gap at each end), or
        // the window manager pushes the panel down over the taskbar.
        val width = activity.dpi(380).coerceAtMost(root.width - activity.dpi(12))
        header.measure(
            View.MeasureSpec.makeMeasureSpec(width - panel.paddingLeft - panel.paddingRight, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // In a DeX window the caption bar overlaps the top of our window (we only get an inset),
        // and a popup can't go under it.
        val topInset = ViewCompat.getRootWindowInsets(anchor)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
        val listMax = loc[1] - topInset - activity.dpi(12) - header.measuredHeight - panel.paddingTop - panel.paddingBottom
        val scroll = MaxHeightScrollView(activity, maxHeight = listMax).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val window = PopupWindow(panel, width, WRAP_CONTENT, true)
        window.setBackgroundDrawable(roundedRect(Palette.MENU_BG, activity.dp(14f)))
        window.elevation = activity.dp(20f)
        window.isOutsideTouchable = true
        window.setOnDismissListener {
            Notifications.removeListener(listener)
            if (popup === window) popup = null
        }
        popup = window
        Notifications.addListener(listener)
        rebuild()
        window.showAtLocation(anchor, Gravity.BOTTOM or Gravity.END, activity.dpi(6), fromBottom)
        panel.hideSystemBarsOfWindow()
    }

    private fun rebuild() {
        list.removeAllViews()
        if (!Notifications.isAccessGranted(activity)) {
            clearAll.visibility = View.GONE
            list.addView(accessCard())
            return
        }
        val items = Notifications.items
        clearAll.visibility = if (items.any { it.clearable }) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            list.addView(TextView(activity).apply {
                setText(R.string.notif_empty)
                textSize = 13f
                setTextColor(Palette.TEXT_DIM)
                gravity = Gravity.CENTER
                setPadding(0, dpi(18), 0, dpi(18))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        for (item in items) list.addView(card(item))
    }

    private fun card(item: Notifications.Item): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(10), dpi(10), dpi(4), dpi(10))
            background = roundedRect(Palette.CARD, dp(12f))
            foreground = activity.selectableBackground()
            setOnClickListener {
                popup?.dismiss()
                Notifications.open(activity, item)
            }
        }
        // The app's small icon in a circle of its accent colour, like Android's shade.
        card.addView(ImageView(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (item.color != 0) item.color or 0xFF000000.toInt() else Palette.ACCENT)
            }
            setPadding(dpi(6), dpi(6), dpi(6), dpi(6))
            item.smallIcon?.let { icon ->
                try {
                    setImageIcon(icon)
                } catch (_: RuntimeException) {
                    setImageResource(R.drawable.ic_bell)
                }
            } ?: setImageResource(R.drawable.ic_bell)
            setColorFilter(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(activity.dpi(32), activity.dpi(32)).apply { marginEnd = activity.dpi(10) })

        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val time = DateUtils.getRelativeTimeSpanString(
            item.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        texts.addView(line("${item.appName} · $time", 11f, Palette.TEXT_DIM, 1))
        item.title?.takeIf { it.isNotBlank() }?.let {
            texts.addView(line(it, 13f, Palette.TEXT, 1).apply { setTypeface(typeface, Typeface.BOLD) })
        }
        item.text?.takeIf { it.isNotBlank() }?.let { texts.addView(line(it, 13f, Palette.TEXT_SOFT, 3)) }
        card.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        if (item.clearable) {
            card.addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_win_close)
                contentDescription = activity.getString(R.string.notif_dismiss)
                tooltipText = contentDescription
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dpi(7), dpi(7), dpi(7), dpi(7))
                background = activity.selectableBackground(borderless = true)
                setOnClickListener { Notifications.dismiss(item) }
            }, LinearLayout.LayoutParams(activity.dpi(30), activity.dpi(30)))
        }
        card.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = activity.dpi(6) }
        return card
    }

    private fun accessCard(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(6), dpi(4), dpi(6), dpi(6))
        addView(line(activity.getString(R.string.notif_access_text), 13f, Palette.TEXT, 0))
        addView(line(activity.getString(R.string.notif_access_restricted), 12f, Palette.TEXT_DIM, 0).apply {
            setPadding(0, dpi(6), 0, dpi(6))
        })
        addView(Button(activity).apply {
            isAllCaps = false
            setText(R.string.notif_access_open)
            setOnClickListener {
                popup?.dismiss()
                openAccessSettings(activity)
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private fun line(text: CharSequence, sizeSp: Float, color: Int, maxLines: Int) = TextView(activity).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (maxLines > 0) {
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    companion object {
        fun openAccessSettings(context: Context) {
            try {
                context.startActivity(Notifications.accessSettingsIntent(context))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }
    }
}

/** A ScrollView that wraps its content up to [maxHeight] pixels. */
@SuppressLint("ViewConstructor")
private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight.coerceAtLeast(0), MeasureSpec.AT_MOST))
    }
}
