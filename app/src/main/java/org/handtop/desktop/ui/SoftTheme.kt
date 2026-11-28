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

/**
 * A light, soft look: light gray windows whose title bar is the window's own colour, the title
 * centered, round gray title buttons, rounded corners and shadows, and a dark taskbar. Flat
 * controls on Android's light base. Linux programs keep their own look.
 */
object SoftTheme : Theme(
    ThemeId.SOFT,
    ThemeColors(
        titleActive = 0xFFEBEBED.toInt(),
        titleActiveEnd = 0xFFEBEBED.toInt(),
        titleInactive = 0xFFF4F4F5.toInt(),
        titleInactiveEnd = 0xFFF4F4F5.toInt(),
        titleTextActive = 0xFF1F1F23.toInt(),
        titleTextInactive = 0xFF6E6E73.toInt(),
        taskbar = 0xF21C1C1F.toInt(),
        taskbarItemActive = 0x26FFFFFF,
        taskbarIndicator = 0xFF5A9BF0.toInt(),
        accent = 0xFF2A68C8.toInt(),
        snapPreviewFill = 0x402A68C8,
        snapPreviewStroke = 0xB02A68C8.toInt(),
        snapPreviewHalo = 0,
        closeHover = 0xFFD0D0D4.toInt(),
        closePressed = 0xFFC2C2C7.toInt(),
        menuBg = 0xFFFFFFFF.toInt(),
        menuDivider = 0x1A000000,
        text = 0xFF1F1F23.toInt(),
        textDim = 0xFF6A6A6F.toInt(),
        textSoft = 0xFF3A3A40.toInt(),
        textDisabled = 0xFFA1A1A6.toInt(),
        statusIconOff = 0x55F2F2F7,
        chip = 0x14000000,
        card = 0xFFF0F0F2.toInt(),
        batteryLow = 0xFFE5484D.toInt(),
        batteryCharging = 0xFF46C46E.toInt(),
        keyBarBg = 0xFFE4E4E7.toInt(),
        keyOn = 0xFF2560BA.toInt(),
        windowBg = 0xFFFAFAFB.toInt(),
        sidebarBg = 0xFFF0F0F2.toInt(),
        barBg = 0xFFEBEBED.toInt(),
        warning = 0xFFA35200.toInt(),
        selection = 0x332A68C8,
        selectionText = 0xFF1F1F23.toInt(),
        fieldBg = 0x14000000,
        field = 0xFFFFFFFF.toInt(),
        desktopLabel = 0xFFFFFFFF.toInt(),
        face = 0xFFEBEBED.toInt(),
        light = 0xFFFFFFFF.toInt(),
        innerLight = 0xFFF4F4F5.toInt(),
        shadow = 0x33000000,
        darkShadow = 0x55000000,
        taskbarText = 0xFFF2F2F7.toInt(),
        taskbarTextDim = 0xFFA1A1A6.toInt(),
        taskbarIcon = 0xFFF2F2F7.toInt(),
        iconTint = 0xFF3A3A40.toInt(),
        titleGlyph = 0,
        onAccent = 0xFFFFFFFF.toInt(),
        taskbarWarning = 0xFFFFB454.toInt(),
        noteText = 0xFF6A6A6F.toInt(),
    ),
) {
    override val animations = true
    override val blur = true
    override val shadows = true
    override val tintIcons = true
    override val roundedCorners = true
    override val bevelled = false
    override val light = true
    override val titleCentered = true

    /** The gray of a title button at rest. */
    private const val BUTTON = 0xFFDEDEE1.toInt()

    /** Hover and press over the dark taskbar and the wallpaper (the light base's ripple is dark). */
    const val FEEDBACK_HOVER = 0x1FFFFFFF
    const val FEEDBACK_PRESSED = 0x40FFFFFF

    override fun windowFrame(context: Context, active: Boolean, floating: Boolean): Drawable =
        GradientDrawable().apply {
            setColor(if (active) this@SoftTheme.colors.titleActive else this@SoftTheme.colors.titleInactive)
            cornerRadius = if (floating) context.dp(12f) else 0f
        }

    override fun titleBar(context: Context, active: Boolean, floating: Boolean): Drawable? = null

    /** A round button of 24 dp in the middle of the title button's area; close is no different. */
    override fun titleButton(context: Context, close: Boolean, checked: Boolean): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), circle(context, colors.closePressed))
        addState(intArrayOf(android.R.attr.state_hovered), circle(context, colors.closeHover))
        addState(intArrayOf(), circle(context, if (checked) colors.closePressed else BUTTON))
    }

    private fun circle(context: Context, color: Int): Drawable {
        val size = context.dpi(24)
        return LayerDrawable(arrayOf(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) })).apply {
            setLayerSize(0, size, size)
            setLayerGravity(0, Gravity.CENTER)
        }
    }

    override val titleBold = true

    override fun panel(context: Context, cornerDp: Float): Drawable = roundedRect(colors.menuBg, context.dp(cornerDp))

    override fun menuItemBackground(context: Context): Drawable =
        context.selectableBackground() ?: ColorDrawable(Color.TRANSPARENT)

    override fun menuTextColors(): ColorStateList = ColorStateList.valueOf(colors.text)

    override val embossDisabled = false

    override fun divider(context: Context): Drawable = ColorDrawable(colors.menuDivider)
    override fun dividerHeight(context: Context): Int = context.dpi(1)

    override val startMenuBand = false

    override fun taskbarBackground(context: Context): Drawable = ColorDrawable(colors.taskbar)

    /** The front window's button is a light pill; every button has the blue mark under it. */
    override fun taskButton(context: Context, front: Boolean, minimized: Boolean): Drawable {
        val pill = GradientDrawable().apply {
            setColor(if (front) this@SoftTheme.colors.taskbarItemActive else 0)
            cornerRadius = context.dp(8f)
        }
        val bar = GradientDrawable().apply {
            setColor(if (minimized) this@SoftTheme.colors.taskbarTextDim else this@SoftTheme.colors.taskbarIndicator)
            cornerRadius = context.dp(2f)
        }
        return LayerDrawable(arrayOf(pill, bar)).apply {
            setLayerInset(0, context.dpi(2), context.dpi(4), context.dpi(2), context.dpi(4))
            val inset = if (front) context.dpi(10) else context.dpi(16)
            setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, context.dpi(3))
            setLayerInsetLeft(1, inset)
            setLayerInsetRight(1, inset)
            setLayerInsetBottom(1, context.dpi(2))
        }
    }

    override val startLabel = false

    override fun startButton(context: Context, open: Boolean): Drawable? = null

    override fun trayBackground(context: Context): Drawable? = null

    override fun toolButton(context: Context): Drawable =
        context.selectableBackground() ?: ColorDrawable(Color.TRANSPARENT)

    override fun barButton(context: Context): Drawable =
        InsetDrawable(lightFeedback(context), context.dpi(2), context.dpi(4), context.dpi(2), context.dpi(4))

    override fun darkFeedback(context: Context): Drawable = lightFeedback(context)

    override val widgetColors = WidgetColors(
        text = 0xFF1F1F23.toInt(),
        textDim = 0xFF6A6A6F.toInt(),
        track = 0x14000000,
        cpu = 0xFF2A68C8.toInt(),
        memory = 0xFF2F8F4E.toInt(),
        under = intArrayOf(0xF5FAFAFB.toInt()),
    )

    override fun widgetBackground(context: Context): Drawable = roundedRect(0xF5FAFAFB.toInt(), context.dp(14f))

    private fun lightFeedback(context: Context) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), roundedRect(FEEDBACK_PRESSED, context.dp(8f)))
        addState(intArrayOf(android.R.attr.state_hovered), roundedRect(FEEDBACK_HOVER, context.dp(8f)))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
}
