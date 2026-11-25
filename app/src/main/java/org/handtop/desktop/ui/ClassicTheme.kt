package org.handtop.desktop.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable

/**
 * The classic look: gray bevelled 3D surfaces, a blue title on the active window, black
 * text, white sunken lists. Nothing translucent, no shadows, no blur, no animations.
 * The palette is the classic one; the artwork that goes with it is HandTop's own.
 */
object ClassicTheme : Theme(
    ThemeId.CLASSIC,
    ThemeColors(
        titleActive = 0xFF000080.toInt(),
        titleActiveEnd = 0xFF1084D0.toInt(),
        titleInactive = 0xFF808080.toInt(),
        titleInactiveEnd = 0xFFB4B4B4.toInt(),
        titleTextActive = 0xFFFFFFFF.toInt(),
        titleTextInactive = 0xFFDFDFDF.toInt(),
        taskbar = FACE,
        taskbarItemActive = FACE,
        taskbarIndicator = 0xFF000080.toInt(),
        accent = 0xFF000080.toInt(),
        snapPreviewFill = 0,
        snapPreviewStroke = 0xFF000000.toInt(),
        // "Dangerous" items (remove, close) are not coloured in the classic look; gray would
        // read as disabled, so they keep the normal text colour.
        closeHover = 0xFF000000.toInt(),
        closePressed = 0xFF000000.toInt(),
        menuBg = FACE,
        menuDivider = 0xFF808080.toInt(),
        text = 0xFF000000.toInt(),
        textDim = 0xFF000000.toInt(),
        textSoft = 0xFF000000.toInt(),
        textDisabled = 0xFF808080.toInt(),
        statusIconOff = 0xFF808080.toInt(),
        chip = FACE,
        card = 0xFFFFFFFF.toInt(),
        batteryLow = 0xFFE5484D.toInt(),
        batteryCharging = 0xFF46C46E.toInt(),
        keyBarBg = FACE,
        keyOn = 0xFF000080.toInt(),
        windowBg = FACE,
        sidebarBg = 0xFFFFFFFF.toInt(),
        barBg = FACE,
        warning = 0xFFFFB454.toInt(),
        selection = 0xFF000080.toInt(),
        selectionText = 0xFFFFFFFF.toInt(),
        fieldBg = 0xFFFFFFFF.toInt(),
        field = 0xFFFFFFFF.toInt(),
        desktopLabel = 0xFFFFFFFF.toInt(),
        face = FACE,
        light = 0xFFFFFFFF.toInt(),
        innerLight = 0xFFDFDFDF.toInt(),
        shadow = 0xFF808080.toInt(),
        darkShadow = 0xFF0A0A0A.toInt(),
    ),
) {
    override val animations = false
    override val blur = false
    override val shadows = false
    override val tintIcons = false
    override val roundedCorners = false

    override fun windowFrame(context: Context, active: Boolean, floating: Boolean): Drawable =
        context.bevel(Bevel.RAISED)

    override fun titleBar(context: Context, active: Boolean): Drawable? = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        if (active) intArrayOf(colors.titleActive, colors.titleActiveEnd)
        else intArrayOf(colors.titleInactive, colors.titleInactiveEnd),
    )

    /**
     * A raised gray square in the middle of the button's slot: the slot keeps its touch size,
     * the square has the classic proportions.
     */
    override fun titleButton(context: Context, close: Boolean, checked: Boolean): Drawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), context.bevel(Bevel.PRESSED))
            addState(intArrayOf(), context.bevel(if (checked) Bevel.PRESSED else Bevel.RAISED))
        }
        return InsetDrawable(states, context.dpi(8), context.dpi(6), context.dpi(8), context.dpi(6))
    }

    override val titleBold = true

    override fun panel(context: Context, cornerDp: Float): Drawable = context.bevel(Bevel.RAISED)

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

    override val embossDisabled = true

    override fun divider(context: Context): Drawable = context.bevel(Bevel.ETCHED, null)
    override fun dividerHeight(context: Context): Int = 2 * kotlin.math.max(1, kotlin.math.round(context.dp(1f)).toInt())

    override val startMenuBand = true
}

private const val FACE = 0xFFC0C0C0.toInt()
