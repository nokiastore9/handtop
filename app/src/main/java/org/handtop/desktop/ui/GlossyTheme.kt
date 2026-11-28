package org.handtop.desktop.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import org.handtop.desktop.R

/**
 * A glossy look: blue gradient title bars with rounded tops, a blue taskbar with a green Start
 * button, and bevelled controls in warm beige with blue selection. Linux programs get the
 * classic theme in the same colours. Every text on blue and green reads at 4.5 : 1 or better.
 */
object GlossyTheme : Theme(
    ThemeId.GLOSSY,
    ThemeColors(
        titleActive = 0xFF0A58CE.toInt(),
        titleActiveEnd = 0xFF0846B0.toInt(),
        titleInactive = 0xFF6F8FCC.toInt(),
        titleInactiveEnd = 0xFF5B7EC2.toInt(),
        titleTextActive = 0xFFFFFFFF.toInt(),
        titleTextInactive = 0xFFFFFFFF.toInt(),
        taskbar = 0xFF1F56C8.toInt(),
        taskbarItemActive = 0xFF1A47A8.toInt(),
        taskbarIndicator = 0xFFFFFFFF.toInt(),
        accent = 0xFF316AC5.toInt(),
        snapPreviewFill = 0x40316AC5,
        snapPreviewStroke = 0xFF0846B0.toInt(),
        snapPreviewHalo = 0xFFFFFFFF.toInt(),
        closeHover = 0xFFE0683F.toInt(),
        closePressed = 0xFFA8381A.toInt(),
        menuBg = 0xFFFFFFFF.toInt(),
        menuDivider = 0xFFACA899.toInt(),
        text = 0xFF000000.toInt(),
        textDim = 0xFF444444.toInt(),
        textSoft = 0xFF222222.toInt(),
        textDisabled = 0xFFACA899.toInt(),
        statusIconOff = 0x80FFFFFF.toInt(),
        chip = 0x1A316AC5,
        card = 0xFFFFFFFF.toInt(),
        batteryLow = 0xFFE5484D.toInt(),
        batteryCharging = 0xFF46C46E.toInt(),
        keyBarBg = 0xFFECE9D8.toInt(),
        keyOn = 0xFF316AC5.toInt(),
        windowBg = 0xFFECE9D8.toInt(),
        sidebarBg = 0xFFFFFFFF.toInt(),
        barBg = 0xFFECE9D8.toInt(),
        warning = 0xFFA00000.toInt(),
        selection = 0xFF316AC5.toInt(),
        selectionText = 0xFFFFFFFF.toInt(),
        fieldBg = 0xFFFFFFFF.toInt(),
        field = 0xFFFFFFFF.toInt(),
        desktopLabel = 0xFFFFFFFF.toInt(),
        face = 0xFFECE9D8.toInt(),
        light = 0xFFFFFFFF.toInt(),
        innerLight = 0xFFF5F4EA.toInt(),
        shadow = 0xFFACA899.toInt(),
        darkShadow = 0xFF716F64.toInt(),
        taskbarText = 0xFFFFFFFF.toInt(),
        taskbarTextDim = 0xFFFFFFFF.toInt(),
        taskbarIcon = 0xFFFFFFFF.toInt(),
        iconTint = 0,
        titleGlyph = 0xFFFFFFFF.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
    ),
) {
    /** The active title bar, top to bottom. */
    val TITLE_ACTIVE_STOPS = intArrayOf(0xFF2A72E0.toInt(), 0xFF0A58CE.toInt(), 0xFF0846B0.toInt())
    val TITLE_INACTIVE_STOPS = intArrayOf(0xFF6F8FCC.toInt(), 0xFF5B7EC2.toInt())
    private val FRAME_ACTIVE = 0xFF0A54CC.toInt()
    private val FRAME_INACTIVE = 0xFF6F8FCC.toInt()
    val TASKBAR_STOPS = intArrayOf(0xFF2563D6.toInt(), 0xFF1A49B8.toInt())
    private val TASKBAR_HIGHLIGHT = 0xFF5A91F0.toInt()
    val TASK_BUTTON = 0xFF2F6FE0.toInt()
    val TASK_BUTTON_FRONT = 0xFF1A47A8.toInt()
    val TASK_BUTTON_MINIMIZED = 0xFF4A7FDC.toInt()
    val START_STOPS = intArrayOf(0xFF2A822A.toInt(), 0xFF226B22.toInt())
    val TRAY_STOPS = intArrayOf(0xFF0B72C8.toInt(), 0xFF0A63B4.toInt())
    private val TRAY_EDGE = 0xFF08519A.toInt()
    private val TITLE_BUTTON = 0xFF2A6FE8.toInt()
    private val CLOSE_BUTTON = 0xFFC8441E.toInt()
    private val MENU_BORDER = 0xFF0A54CC.toInt()

    override val animations = true
    override val blur = false
    override val shadows = false
    override val tintIcons = false
    override val roundedCorners = false
    override val bevelled = true
    override val light = false
    override val titleCentered = false
    override val dialogStyle = R.style.HandTop_GlossyDialog
    override val calendarStyle = R.style.HandTop_GlossyCalendar

    /** A blue frame with rounded top corners; square when maximized or snapped. */
    override fun windowFrame(context: Context, active: Boolean, floating: Boolean): Drawable =
        GradientDrawable().apply {
            setColor(if (active) FRAME_ACTIVE else FRAME_INACTIVE)
            cornerRadii = topCorners(if (floating) context.dp(7f) else 0f)
        }

    /** The gradient, its top corners rounded to sit inside the frame's. */
    override fun titleBar(context: Context, active: Boolean, floating: Boolean): Drawable? =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (active) TITLE_ACTIVE_STOPS else TITLE_INACTIVE_STOPS).apply {
            cornerRadii = topCorners(if (floating) context.dp(4f) else 0f)
        }

    private fun topCorners(r: Float) = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)

    /** A blue (close: orange-red) rounded square with a thin white edge, in the middle of the button's slot. */
    override fun titleButton(context: Context, close: Boolean, checked: Boolean): Drawable {
        val base = if (close) CLOSE_BUTTON else TITLE_BUTTON
        fun square(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(3f)
            setStroke(context.dpi(1), Color.WHITE)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), square(mix(base, Color.BLACK, 0.25f)))
            addState(intArrayOf(android.R.attr.state_hovered), square(mix(base, Color.WHITE, 0.18f)))
            addState(intArrayOf(), square(if (checked) mix(base, Color.BLACK, 0.25f) else base))
        }
        return InsetDrawable(states, context.dpi(9), context.dpi(6), context.dpi(9), context.dpi(6))
    }

    override val titleBold = true

    /** White, square, with a thin blue edge. */
    override fun panel(context: Context, cornerDp: Float): Drawable =
        roundedRect(colors.menuBg, 0f, MENU_BORDER, context.dpi(1))

    override fun menuItemBackground(context: Context): Drawable = StateListDrawable().apply {
        val highlight = ColorDrawable(colors.selection)
        addState(intArrayOf(-android.R.attr.state_enabled), ColorDrawable(Color.TRANSPARENT))
        addState(intArrayOf(android.R.attr.state_pressed), highlight)
        addState(intArrayOf(android.R.attr.state_hovered), highlight)
        addState(intArrayOf(android.R.attr.state_focused), highlight)
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    override fun menuTextColors(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_hovered),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(),
        ),
        intArrayOf(colors.textDisabled, colors.selectionText, colors.selectionText, colors.selectionText, colors.text),
    )

    override val embossDisabled = false

    override fun divider(context: Context): Drawable = ColorDrawable(colors.menuDivider)
    override fun dividerHeight(context: Context): Int = context.dpi(1)

    override val startMenuBand = true

    /** The blue gradient with a light line along its top. */
    override fun taskbarBackground(context: Context): Drawable =
        LayerDrawable(arrayOf(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, TASKBAR_STOPS), ColorDrawable(TASKBAR_HIGHLIGHT))).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, context.dpi(1))
        }

    override fun taskButton(context: Context, front: Boolean, minimized: Boolean): Drawable {
        val fill = GradientDrawable().apply {
            setColor(
                when {
                    front -> TASK_BUTTON_FRONT
                    minimized -> TASK_BUTTON_MINIMIZED
                    else -> TASK_BUTTON
                },
            )
            cornerRadius = context.dp(3f)
        }
        return InsetDrawable(fill, context.dpi(1), context.dpi(5), context.dpi(1), context.dpi(5))
    }

    override val startLabel = true

    /** The green pill, rounded on its right; darker while the start menu is open. */
    override fun startButton(context: Context, open: Boolean): Drawable? {
        val stops = if (open) intArrayOf(mix(START_STOPS[0], Color.BLACK, 0.2f), mix(START_STOPS[1], Color.BLACK, 0.2f)) else START_STOPS
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, stops).apply {
            val r = context.dp(12f)
            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
    }

    /** The lighter blue gradient around the status icons, with a dark edge on its left. */
    override fun trayBackground(context: Context): Drawable? =
        LayerDrawable(arrayOf(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, TRAY_STOPS), ColorDrawable(TRAY_EDGE))).apply {
            setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            setLayerWidth(1, context.dpi(1))
        }

    override fun toolButton(context: Context): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), InsetDrawable(context.bevel(Bevel.PRESSED), context.dpi(2)))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    /** [a] moved [t] of the way to [b]. */
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = Math.round((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
