package org.handtop.desktop.ui

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
}
