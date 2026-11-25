package org.handtop.desktop.ui

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
}

private const val FACE = 0xFFC0C0C0.toInt()
