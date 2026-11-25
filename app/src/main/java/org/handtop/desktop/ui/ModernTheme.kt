package org.handtop.desktop.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.graphics.drawable.StateListDrawable

/** HandTop's own dark look: translucent panels, rounded corners, shadows, blur, motion. */
object ModernTheme : Theme(
    ThemeId.MODERN,
    ThemeColors(
        titleActive = 0xFF26324A.toInt(),
        titleActiveEnd = 0xFF26324A.toInt(),
        titleInactive = 0xFF181E2B.toInt(),
        titleInactiveEnd = 0xFF181E2B.toInt(),
        titleTextActive = 0xFFFFFFFF.toInt(),
        titleTextInactive = 0xFF8A94A8.toInt(),
        taskbar = 0xF0101522.toInt(),
        taskbarItemActive = 0x30FFFFFF,
        taskbarIndicator = 0xFF4F8CFF.toInt(),
        accent = 0xFF4F8CFF.toInt(),
        snapPreviewFill = 0x404F8CFF,
        snapPreviewStroke = 0xB04F8CFF.toInt(),
        snapPreviewHalo = 0,
        closeHover = 0xFFE5484D.toInt(),
        closePressed = 0xFFB3261E.toInt(),
        menuBg = 0xF51A2233.toInt(),
        menuDivider = 0x22FFFFFF,
        text = 0xFFE6EAF2.toInt(),
        textDim = 0xFF8A94A8.toInt(),
        textSoft = 0xFFC3CAD8.toInt(),
        textDisabled = 0xFF8A94A8.toInt(),
        statusIconOff = 0x55E6EAF2,
        chip = 0x22FFFFFF,
        card = 0x14FFFFFF,
        batteryLow = 0xFFE5484D.toInt(),
        batteryCharging = 0xFF46C46E.toInt(),
        keyBarBg = 0xFF161B26.toInt(),
        keyOn = 0xFF4F8CFF.toInt(),
        windowBg = 0xFF0F1420.toInt(),
        sidebarBg = 0xFF141A28.toInt(),
        barBg = 0xFF161D2C.toInt(),
        warning = 0xFFFFB454.toInt(),
        selection = 0x334F8CFF,
        selectionText = 0xFFE6EAF2.toInt(),
        fieldBg = 0x26FFFFFF,
        field = 0xFF0F1420.toInt(),
        desktopLabel = 0xFFFFFFFF.toInt(),
        face = 0xFF26324A.toInt(),
        light = 0x33FFFFFF,
        innerLight = 0x1AFFFFFF,
        shadow = 0x66000000,
        darkShadow = 0x99000000.toInt(),
    ),
) {
    override val animations = true
    override val blur = true
    override val shadows = true
    override val tintIcons = true
    override val roundedCorners = true
    override val bevelled = false

    override fun windowFrame(context: Context, active: Boolean, floating: Boolean): Drawable =
        GradientDrawable().apply {
            setColor(if (active) this@ModernTheme.colors.titleActive else this@ModernTheme.colors.titleInactive)
            cornerRadius = if (floating) context.dp(10f) else 0f
        }

    override fun titleBar(context: Context, active: Boolean): Drawable? = null

    override fun titleButton(context: Context, close: Boolean, checked: Boolean): Drawable = when {
        close -> StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(colors.closePressed))
            addState(intArrayOf(android.R.attr.state_hovered), ColorDrawable(colors.closeHover))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        checked -> ColorDrawable(colors.chip)
        else -> context.selectableBackground() ?: ColorDrawable(Color.TRANSPARENT)
    }

    override val titleBold = false

    override fun panel(context: Context, cornerDp: Float): Drawable = roundedRect(colors.menuBg, context.dp(cornerDp))

    override fun menuItemBackground(context: Context): Drawable =
        context.selectableBackground() ?: ColorDrawable(Color.TRANSPARENT)

    override fun menuTextColors(): ColorStateList = ColorStateList.valueOf(colors.text)

    override val embossDisabled = false

    override fun divider(context: Context): Drawable = ColorDrawable(colors.menuDivider)
    override fun dividerHeight(context: Context): Int = context.dpi(1)

    override val startMenuBand = false

    override fun taskbarBackground(context: Context): Drawable = ColorDrawable(colors.taskbar)

    /** Highlighted background plus a coloured underline for the front window. */
    override fun taskButton(context: Context, front: Boolean, minimized: Boolean): Drawable {
        val fill = ColorDrawable(if (front) colors.taskbarItemActive else 0)
        val bar = GradientDrawable().apply {
            setColor(if (minimized) this@ModernTheme.colors.textDim else this@ModernTheme.colors.taskbarIndicator)
            cornerRadius = context.dp(2f)
        }
        return LayerDrawable(arrayOf(fill, bar)).apply {
            val inset = if (front) context.dpi(6) else context.dpi(14)
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
}
