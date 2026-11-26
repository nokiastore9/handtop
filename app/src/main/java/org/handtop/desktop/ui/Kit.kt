package org.handtop.desktop.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The building blocks of HandTop's own windows (Settings, Files, the launchpad...), drawn by
 * the theme in use. The modern theme draws each the way the screens did before themes; the
 * classic one draws gray 3D controls: raised buttons, sunken white lists and fields, etched
 * group frames, radio buttons and check boxes.
 */

private val classic: Boolean get() = Themes.current.bevelled

/** The least height of anything tapped, classic controls included: they look classic, not small. */
private const val TOUCH_DP = 44

/** A section's title. */
fun Context.heading(text: CharSequence): TextView = TextView(this).apply {
    this.text = text
    textSize = 17f
    setTextColor(Palette.TEXT)
    setPadding(0, dpi(14), 0, dpi(4))
}

/** Explanatory text under a heading. */
fun Context.bodyText(text: CharSequence): TextView = TextView(this).apply {
    this.text = text
    textSize = 13f
    setTextColor(Palette.TEXT_DIM)
    setLineSpacing(0f, 1.15f)
    setPadding(0, 0, 0, dpi(4))
}

/** A push button with [text] (null to set it later). */
fun Context.pushButton(text: CharSequence? = null, onClick: ((View) -> Unit)? = null): Button = Button(this).apply {
    isAllCaps = false
    if (text != null) this.text = text
    if (onClick != null) setOnClickListener(onClick)
    styleButton()
}

/** Restyles an existing button for the theme (classic: raised gray, black text). */
fun Button.styleButton() {
    if (!classic) return
    val c = Themes.current.colors
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), context.bevel(Bevel.PRESSED))
        addState(intArrayOf(), context.bevel(Bevel.RAISED))
    }
    stateListAnimator = null
    isAllCaps = false
    setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(c.textDisabled, c.text)))
    setPadding(context.dpi(16), context.dpi(8), context.dpi(16), context.dpi(8))
    minHeight = context.dpi(TOUCH_DP)
    minimumHeight = context.dpi(TOUCH_DP)
}

/** A text field restyled for the theme (classic: sunken white, black text). */
fun EditText.styleField() {
    if (!classic) return
    val c = Themes.current.colors
    background = context.bevel(Bevel.SUNKEN, c.field)
    setTextColor(c.text)
    setHintTextColor(c.shadow)
    setPadding(context.dpi(8), context.dpi(6), context.dpi(8), context.dpi(6))
}

/** A progress bar in the theme's accent. */
fun ProgressBar.styleProgress() {
    progressTintList = ColorStateList.valueOf(Palette.ACCENT)
    indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT)
    if (classic) progressBackgroundTintList = ColorStateList.valueOf(Palette.FIELD)
}

/** A radio button restyled for the theme (classic: the sunken white circle with a dot). */
fun android.widget.RadioButton.styleRadio() {
    setTextColor(Palette.TEXT)
    buttonTintList = ColorStateList.valueOf(Palette.ACCENT)
    if (!classic) return
    buttonTintList = null
    buttonDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), RadioMark(context, true))
        addState(intArrayOf(), RadioMark(context, false))
    }
    setPadding(context.dpi(6), 0, 0, 0)
    minHeight = context.dpi(TOUCH_DP)
    minimumHeight = context.dpi(TOUCH_DP)
}

/** A key or a small toggle: classic draws it raised, pressed in while held. */
fun View.styleKey() {
    background = if (classic) StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), context.bevel(Bevel.PRESSED))
        addState(intArrayOf(), context.bevel(Bevel.RAISED))
    } else context.selectableBackground()
}

/** Background of a list (Settings' categories, Files' places): classic draws a sunken white box. */
fun View.styleListBox() {
    background = if (classic) context.bevel(Bevel.SUNKEN, Palette.FIELD) else android.graphics.drawable.ColorDrawable(Palette.SIDEBAR_BG)
}

/**
 * A section: in the modern look a column starting with [title] as a heading; in the classic
 * look an etched frame with the title cut into its top line.
 */
class GroupBox(context: Context, private val title: CharSequence?) : LinearLayout(context) {

    private val framed = classic
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }
    private val behind = Paint().apply { color = Palette.WINDOW_BG }

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (framed) topMargin = dpi(14) }
        if (framed) {
            setWillNotDraw(false)
            val line = caption.fontSpacing
            background = InsetDrawable(context.bevel(Bevel.ETCHED, null), 0, (line / 2).roundToInt(), 0, 0)
            setPadding(dpi(12), line.roundToInt() + dpi(4), dpi(12), dpi(10))
        } else if (title != null) {
            addView(context.heading(title))
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!framed || title == null) return
        val x = dpi(10).toFloat()
        val text = title.toString()
        val w = caption.measureText(text) + dpi(8)
        canvas.drawRect(x, 0f, x + w, caption.fontSpacing, behind)
        canvas.drawText(text, x + dpi(4), -caption.ascent(), caption)
    }
}

fun Context.groupBox(title: CharSequence?): GroupBox = GroupBox(this, title)

/**
 * One choice of several: modern chips (with [sublabels] under the labels), or classic radio
 * buttons. Picking calls [onPick] with the index; the caller rebuilds what depends on it.
 */
fun Context.choices(labels: List<CharSequence>, sublabels: List<CharSequence>?, selected: Int, onPick: (Int) -> Unit): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    labels.forEachIndexed { i, label ->
        val chosen = i == selected
        val sub = sublabels?.getOrNull(i)
        val item = if (classic) radioItem(label, sub, chosen) else chipItem(label, sub, chosen)
        item.isSelected = chosen
        item.setOnClickListener { if (!chosen) onPick(i) }
        row.addView(item, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dpi(6) })
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }
}

private fun Context.chipItem(label: CharSequence, sub: CharSequence?, chosen: Boolean) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    setPadding(dpi(if (sub != null) 12 else 14), dpi(if (sub != null) 6 else 8), dpi(if (sub != null) 12 else 14), dpi(if (sub != null) 6 else 8))
    background = roundedRect(if (chosen) Palette.ACCENT else Palette.CHIP, dp(10f))
    foreground = selectableBackground()
    addView(TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(Palette.TEXT)
        gravity = Gravity.CENTER
    })
    if (sub != null) addView(TextView(context).apply {
        text = sub
        textSize = 11f
        setTextColor(if (chosen) Palette.TEXT else Palette.TEXT_DIM)
        gravity = Gravity.CENTER
    })
}

private fun Context.radioItem(label: CharSequence, sub: CharSequence?, chosen: Boolean) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dpi(TOUCH_DP)
    setPadding(dpi(2), dpi(8), dpi(8), dpi(8))
    addView(ImageView(context).apply { setImageDrawable(RadioMark(this@radioItem, chosen)) }, LinearLayout.LayoutParams(dpi(16), dpi(16)))
    addView(TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(Palette.TEXT)
        setPadding(dpi(6), 0, 0, 0)
    })
    if (sub != null) addView(TextView(context).apply {
        text = sub
        textSize = 11f
        setTextColor(Themes.current.colors.shadow)
        setPadding(dpi(5), 0, 0, 0)
    })
}

/**
 * Something on or off. Modern: a status line ([statusOn] / [statusOff]) and a "Turn on/off"
 * button; classic: a check box with [checkLabel].
 */
class OnOff(
    context: Context,
    private val checkLabel: CharSequence,
    private val statusOn: CharSequence,
    private val statusOff: CharSequence,
    private val turnOn: CharSequence,
    private val turnOff: CharSequence,
    onToggle: () -> Unit,
) : LinearLayout(context) {

    private val status = TextView(context)
    private val button = context.pushButton { onToggle() }
    private val box = ImageView(context)

    init {
        if (classic) {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(6), 0, dpi(6))
            minimumHeight = dpi(TOUCH_DP)
            setOnClickListener { onToggle() }
            addView(box, LayoutParams(dpi(16), dpi(16)))
            addView(TextView(context).apply {
                text = checkLabel
                textSize = 14f
                setTextColor(Palette.TEXT)
                setPadding(dpi(8), 0, 0, 0)
            })
        } else {
            orientation = VERTICAL
            status.textSize = 14f
            status.setTextColor(Palette.TEXT)
            status.setPadding(0, dpi(2), 0, dpi(4))
            addView(status)
            addView(button, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    fun set(on: Boolean) {
        status.text = if (on) statusOn else statusOff
        button.text = if (on) turnOff else turnOn
        box.setImageDrawable(CheckMark(context, on))
    }
}

/** A row of a list with an icon and a label; [setChosen] marks the current one. */
class ListRow(context: Context, @DrawableRes icon: Int, text: CharSequence) : LinearLayout(context) {

    private val icon = icon
    private val iconView = ImageView(context)
    private val label = TextView(context).apply {
        this.text = text
        textSize = 15f
        isSingleLine = true
        setPadding(dpi(12), 0, 0, 0)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(12), 0, dpi(12), 0)
        if (!classic) foreground = context.selectableBackground()
        contentDescription = text
        addView(iconView, LayoutParams(dpi(20), dpi(20)))
        addView(label)
        setChosen(false)
    }

    fun setChosen(chosen: Boolean) {
        val c = Themes.current.colors
        if (classic) {
            background = if (chosen) android.graphics.drawable.ColorDrawable(c.selection) else null
            label.setTextColor(if (chosen) c.selectionText else c.text)
            iconView.setThemedIcon(icon)
        } else {
            background = if (chosen) roundedRect(Palette.CHIP, dp(8f)) else null
            iconView.setThemedIcon(icon, if (chosen) Palette.ACCENT else Palette.TEXT_SOFT)
            label.setTextColor(if (chosen) Palette.TEXT else Palette.TEXT_SOFT)
        }
    }
}

/** A classic radio button's circle: sunken, white, with a dot when chosen. */
private class RadioMark(context: Context, private val checked: Boolean) : Drawable() {
    private val e = max(1f, context.dp(1f).roundToInt().toFloat())
    private val size = context.dpi(16)
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    private val c = Themes.current.colors
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = size / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = e
        fun ring(radius: Float, topLeft: Int, bottomRight: Int) {
            val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            paint.color = topLeft
            canvas.drawArc(oval, 135f, 180f, false, paint)
            paint.color = bottomRight
            canvas.drawArc(oval, 315f, 180f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = c.field
        canvas.drawCircle(cx, cy, r - e, paint)
        paint.style = Paint.Style.STROKE
        ring(r - e / 2f, c.shadow, c.light)
        ring(r - 1.5f * e, c.darkShadow, c.innerLight)
        if (checked) {
            paint.style = Paint.Style.FILL
            paint.color = c.text
            canvas.drawCircle(cx, cy, r * 0.3f, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The mark of a file picked with a finger (or not yet): classic, a check box; modern, a ring
 * that fills with the accent colour and a tick.
 */
fun Context.selectionMark(checked: Boolean): Drawable =
    if (classic) CheckMark(this, checked) else SelectionRing(this, checked)

private class SelectionRing(context: Context, private val checked: Boolean) : Drawable() {
    private val colors = Themes.current.colors
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        color = colors.textDim
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = android.graphics.Color.WHITE
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = min(b.width(), b.height()) / 2f - ring.strokeWidth
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        if (!checked) {
            canvas.drawCircle(cx, cy, r, ring)
            return
        }
        canvas.drawCircle(cx, cy, r + ring.strokeWidth / 2, fill)
        val path = Path().apply {
            moveTo(cx - r * 0.45f, cy + r * 0.02f)
            lineTo(cx - r * 0.1f, cy + r * 0.38f)
            lineTo(cx + r * 0.5f, cy - r * 0.32f)
        }
        canvas.drawPath(path, tick)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** A classic check box: a sunken white square, ticked when on. */
private class CheckMark(context: Context, private val checked: Boolean) : Drawable() {
    private val box = context.bevel(Bevel.SUNKEN, Themes.current.colors.field)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Themes.current.colors.text
        style = Paint.Style.STROKE
        strokeWidth = max(2f, context.dp(2f))
        strokeCap = Paint.Cap.SQUARE
    }

    override fun draw(canvas: Canvas) {
        box.bounds = bounds
        box.draw(canvas)
        if (!checked) return
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val path = Path().apply {
            moveTo(b.left + w * 0.25f, b.top + h * 0.5f)
            lineTo(b.left + w * 0.43f, b.top + h * 0.68f)
            lineTo(b.left + w * 0.75f, b.top + h * 0.32f)
        }
        canvas.drawPath(path, tick)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
