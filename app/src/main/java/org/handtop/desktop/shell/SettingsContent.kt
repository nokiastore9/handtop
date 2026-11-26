package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.audio.SoundBridge
import org.handtop.desktop.linux.GpuDrivers
import org.handtop.desktop.linux.LinuxEnv
import org.handtop.desktop.linux.LinuxInstaller
import org.handtop.desktop.linux.LinuxSetup
import org.handtop.desktop.linux.X11Display
import org.handtop.desktop.wayland.WaylandServer
import org.handtop.desktop.wayland.X11ScaleMode
import org.handtop.desktop.notify.NotificationCenter
import org.handtop.desktop.notify.Notifications
import org.handtop.desktop.term.TermSessions
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.setThemedIcon
import org.handtop.desktop.ui.styleListBox
import org.handtop.desktop.ui.showDialog
import org.handtop.desktop.ui.pushButton
import org.handtop.desktop.ui.groupBox
import org.handtop.desktop.ui.dialogBuilder
import org.handtop.desktop.ui.choices
import org.handtop.desktop.ui.bodyText
import org.handtop.desktop.ui.OnOff
import org.handtop.desktop.ui.ListRow
import org.handtop.desktop.ui.UiScale
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.hideSystemBars
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.ui.ThemeId
import org.handtop.desktop.ui.Themes
import kotlin.math.roundToInt
import org.handtop.desktop.wm.WindowContent

/** The groups of [SettingsContent], in the order the list shows them. */
enum class SettingsCategory(@param:DrawableRes val icon: Int, @param:StringRes val title: Int) {
    DISPLAY(R.drawable.ic_display, R.string.settings_cat_display),
    DESKTOP(R.drawable.ic_home, R.string.settings_cat_desktop),
    NOTIFICATIONS(R.drawable.ic_bell, R.string.settings_cat_notifications),
    LINUX(R.drawable.ic_linux, R.string.settings_cat_linux),
    GRAPHICS(R.drawable.ic_chip, R.string.settings_cat_graphics),
    ANDROID(R.drawable.ic_phone, R.string.settings_cat_android),
}

/**
 * HandTop settings, in categories ([SettingsCategory]). A wide window shows the categories on
 * the left and the chosen one on the right; a narrow one (a phone) shows the list, then one
 * category with a way back. The last category stays chosen, also after the interface scale
 * rebuilds the desktop.
 */
@SuppressLint("ViewConstructor")
class SettingsContent(
    context: Context,
    private val openLinuxSetup: () -> Unit,
    /** Saves a new interface scale (percent) and rebuilds the desktop with it. */
    private val setScale: (Int) -> Unit,
    private val openWallpapers: () -> Unit,
    /** Whether the screen works as a touchpad now, and switching it. */
    private val touchpadOn: () -> Boolean,
    private val toggleTouchpad: () -> Unit,
    /** Saves another theme and rebuilds the desktop with it. */
    private val chooseTheme: (ThemeId) -> Unit,
    /** Open on the last category's page even in a narrow window (back after a scale change). */
    private val startOnPage: Boolean = false,
) : LinearLayout(context), WindowContent {

    override val view: View get() = this

    private val storageStatus = TextView(context)
    private val storageButton = context.pushButton()
    private val processStatus = TextView(context)
    private val linuxStatus = TextView(context)
    private val removeButton = context.pushButton()
    private val notifyStatus = TextView(context)
    private val soundStatus = TextView(context)
    private val soundButton = context.pushButton()
    private val x11Status = TextView(context)
    private val x11ScaleRow = FrameLayout(context)
    private val x11Button = context.pushButton()
    private val notifyHint = TextView(context)
    private val notifyButton = context.pushButton()
    private val gpuStatus = TextView(context)
    private val wallpaperStatus = TextView(context)
    private val touchpad = OnOff(
        context,
        checkLabel = context.getString(R.string.settings_touchpad_check),
        statusOn = context.getString(R.string.settings_touchpad_on),
        statusOff = context.getString(R.string.settings_touchpad_off),
        turnOn = context.getString(R.string.settings_gpu_turn_on),
        turnOff = context.getString(R.string.settings_gpu_turn_off),
    ) {
        toggleTouchpad()
        refreshTouchpad()
    }
    private val restoreIconsButton = context.pushButton()
    private val desktopListener: () -> Unit = { refreshDesktop() }
    private val gpuButton = context.pushButton()
    private val listener: (LinuxInstaller.State) -> Unit = { refresh() }
    // Only when a step starts or ends: the setup reports progress several times a second.
    private var setupSteps: Any? = null
    private val setupListener: (LinuxSetup.State) -> Unit = { state ->
        val steps = state.running to state.steps
        if (steps != setupSteps) {
            setupSteps = steps
            refresh()
        }
    }

    // Interface scale of the screen this window is on (declared before init, which uses them).
    private val onDesktop = UiScale.isDesktop(context)
    private val currentScale = UiScale.percent(context, onDesktop)
    private val systemDpi = UiScale.systemDpi(context)

    // ---- categories ----

    private val pages = SettingsCategory.entries.associateWith { page(it) }
    private val categoryRows = HashMap<SettingsCategory, View>()
    private val sidebar = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dpi(8), dpi(10), dpi(8), dpi(10))
    }
    private val sidebarScroll = ScrollView(context).apply {
        styleListBox()
        addView(sidebar)
    }
    private val pageTitle = TextView(context)
    private val backBar = LinearLayout(context)
    private val pageScroll = ScrollView(context)
    private val right = LinearLayout(context).apply { orientation = VERTICAL }
    private var current = SettingsCategory.entries.getOrNull(prefs().getInt(KEY_CATEGORY, 0)) ?: SettingsCategory.DISPLAY
    /** The window is too narrow for the list and a page side by side. */
    private var narrow = false
    /** In a narrow window: a page is shown, not the list. */
    private var pageOpen = false
    private var measured = false

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Palette.WINDOW_BG)

        for (category in SettingsCategory.entries) {
            val row = categoryRow(category)
            categoryRows[category] = row
            sidebar.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dpi(44)).apply { bottomMargin = dpi(2) })
        }

        // A narrow window's way back to the list: an arrow and the category's name.
        backBar.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8), 0, dpi(14), 0)
            background = context.selectableBackground()
            contentDescription = context.getString(R.string.settings_all)
            setOnClickListener {
                pageOpen = false
                arrange()
            }
            addView(ImageView(context).apply {
                setThemedIcon(R.drawable.ic_arrow_back, Palette.TEXT)
            }, LayoutParams(dpi(36), dpi(24)))
            addView(pageTitle.apply {
                textSize = 17f
                setTextColor(Palette.TEXT)
                setPadding(dpi(6), 0, 0, 0)
            })
        }
        right.addView(backBar, LayoutParams(LayoutParams.MATCH_PARENT, dpi(48)))
        right.addView(pageScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        // Classic: the list is a sunken box with some gray around it.
        addView(sidebarScroll, LayoutParams(dpi(SIDEBAR_DP), LayoutParams.MATCH_PARENT).apply {
            if (Themes.current.bevelled) setMargins(dpi(6), dpi(6), 0, dpi(6))
        })
        addView(right, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        // Display: the interface scale, what a phone screen needs most.
        pages.getValue(SettingsCategory.DISPLAY).apply {
            addView(group(R.string.settings_scale_title).apply {
                addView(body(R.string.settings_scale_text))
                addView(scaleStatus().styled())
                addView(scaleChoices(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(6) })
            })
            // The screen as a touchpad (also: a long press on the taskbar's keyboard button).
            addView(group(R.string.settings_touchpad_title).apply {
                addView(body(R.string.settings_touchpad_text))
                addView(touchpad)
            })
        }

        // Desktop: wallpaper and HandTop's own shortcuts, and the theme.
        pages.getValue(SettingsCategory.DESKTOP).apply {
            addView(context.groupBox(null).apply {
                addView(body(R.string.settings_desktop_text))
                addView(wallpaperStatus.styled())
                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    addView(context.pushButton(context.getString(R.string.desktop_wallpaper)) { openWallpapers() })
                    addView(restoreIconsButton.apply {
                        setText(R.string.desktop_restore_icons)
                        setOnClickListener { Shortcuts.restoreBuiltIns(context) }
                    }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(6) })
                }, wrap())
            })
            // The look of the whole shell; switching rebuilds the desktop, as a scale change does.
            addView(group(R.string.settings_theme_title).apply {
                addView(themeChoices(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(6) })
            })
        }

        // Notifications on the taskbar (HandTop hides Android's status bar).
        pages.getValue(SettingsCategory.NOTIFICATIONS).apply {
            addView(context.groupBox(null).apply {
                addView(body(R.string.settings_notif_text))
                addView(notifyStatus.styled())
                notifyHint.apply {
                    setText(R.string.notif_access_restricted)
                    textSize = 12f
                    setTextColor(Palette.TEXT_DIM)
                    setPadding(0, 0, 0, dpi(4))
                }
                addView(notifyHint)
                notifyButton.setText(R.string.notif_access_open)
                notifyButton.setOnClickListener { NotificationCenter.openAccessSettings(context) }
                addView(notifyButton, wrap())
            })
        }

        // Linux: the system itself, and the phone's storage inside it.
        pages.getValue(SettingsCategory.LINUX).apply {
            addView(group(R.string.settings_linux_title).apply {
                addView(linuxStatus.styled())
                addView(context.pushButton(context.getString(R.string.settings_linux_manage)) { openLinuxSetup() }, wrap())
                removeButton.setText(R.string.settings_linux_remove)
                removeButton.setOnClickListener { confirmRemove() }
                addView(removeButton, wrap().apply { topMargin = dpi(4) })
            })
            addView(group(R.string.settings_storage_title).apply {
                addView(body(R.string.settings_storage_text))
                addView(storageStatus.styled())
                storageButton.setText(R.string.settings_storage_grant)
                storageButton.setOnClickListener { LinuxEnv.requestStorageAccess(context) }
                addView(storageButton, wrap())
            })
        }

        // Graphics and sound of Linux programs: the GPU (Adreno), X11 through Xwayland, sound.
        pages.getValue(SettingsCategory.GRAPHICS).apply {
            addView(group(R.string.settings_gpu_title).apply {
                addView(body(R.string.settings_gpu_text))
                addView(gpuStatus.styled())
                gpuButton.setOnClickListener { gpuButtonClicked() }
                addView(gpuButton, wrap())
            })
            addView(group(R.string.settings_x11_title).apply {
                addView(body(R.string.settings_x11_text))
                addView(x11Status.styled())
                x11Button.setText(R.string.settings_x11_install)
                x11Button.setOnClickListener { LinuxSetup.start(context) }
                addView(x11Button, wrap())
                addView(body(R.string.settings_x11_scale_text))
                addView(x11ScaleChoices(), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dpi(6) })
            })
            addView(group(R.string.settings_sound_title).apply {
                addView(body(R.string.settings_sound_text))
                addView(soundStatus.styled())
                soundButton.setText(R.string.settings_sound_install)
                soundButton.setOnClickListener { LinuxSetup.start(context) }
                addView(soundButton, wrap())
            })
        }

        // Android: its limit on background processes (Android 12+).
        pages.getValue(SettingsCategory.ANDROID).apply {
            addView(group(R.string.settings_processes_title).apply {
                addView(body(R.string.settings_processes_text))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    addView(processStatus.styled())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        addView(body(R.string.settings_processes_how_14))
                        addView(context.pushButton(context.getString(R.string.settings_processes_open)) { openDeveloperOptions() }, wrap())
                    } else {
                        addView(body(R.string.settings_processes_how_12))
                        addView(TextView(context).apply {
                            text = ADB_COMMAND
                            typeface = android.graphics.Typeface.MONOSPACE
                            setTextColor(Palette.TEXT)
                            setTextIsSelectable(true)
                            setPadding(0, dpi(4), 0, dpi(4))
                        })
                    }
                } else {
                    addView(body(R.string.settings_processes_none).apply { setTextColor(Palette.TEXT) })
                }
            })
        }

        show(current)
        LinuxInstaller.addListener(listener)
        LinuxSetup.addListener(setupListener)
        Wallpapers.addListener(desktopListener)
        Shortcuts.addListener(desktopListener)
        refresh()
    }

    /** Shows [category] (and, in a narrow window, its page instead of the list). */
    fun show(category: SettingsCategory) {
        current = category
        prefs().edit().putInt(KEY_CATEGORY, category.ordinal).apply()
        pageScroll.removeAllViews()
        pageScroll.addView(pages.getValue(category))
        pageScroll.scrollTo(0, 0)
        pageTitle.setText(category.title)
        categoryRows.forEach { (c, row) -> styleRow(row, c == category) }
        pageOpen = true
        arrange()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val now = w < dpi(NARROW_DP)
        // Opened in a narrow window: the list first, so the categories are seen.
        if (!measured && now && !startOnPage) pageOpen = false
        measured = true
        if (now != narrow || !pageOpen) {
            narrow = now
            post { arrange() }
        }
    }

    /** List and page side by side, or one of them, as the width allows. */
    private fun arrange() {
        val showList = !narrow || !pageOpen
        val showPage = !narrow || pageOpen
        sidebarScroll.visibility = if (showList) VISIBLE else GONE
        right.visibility = if (showPage) VISIBLE else GONE
        backBar.visibility = if (narrow) VISIBLE else GONE
        sidebarScroll.layoutParams = (sidebarScroll.layoutParams as LayoutParams).apply {
            width = if (narrow) 0 else dpi(SIDEBAR_DP)
            weight = if (narrow) 1f else 0f
        }
        // In the list of a narrow window nothing is "current": every row leads to a page.
        categoryRows.forEach { (c, row) -> styleRow(row, !narrow && c == current) }
        // A narrow window names the page in its bar already.
        pages.values.forEach { it.getChildAt(0).visibility = if (narrow) GONE else VISIBLE }
    }

    private fun categoryRow(category: SettingsCategory): View =
        ListRow(context, category.icon, context.getString(category.title)).apply { setOnClickListener { show(category) } }

    private fun styleRow(row: View, chosen: Boolean) = (row as ListRow).setChosen(chosen)

    /** A category's page: its title, then what the init puts in. */
    private fun page(category: SettingsCategory) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dpi(22), dpi(14), dpi(22), dpi(20))
        addView(TextView(context).apply {
            setText(category.title)
            textSize = 21f
            setTextColor(Palette.TEXT)
            setPadding(0, 0, 0, dpi(6))
        })
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called when the window is activated and when HandTop comes back from Settings. */
    override fun onActivated() = refresh()

    override fun onClosed() {
        LinuxInstaller.removeListener(listener)
        LinuxSetup.removeListener(setupListener)
        Wallpapers.removeListener(desktopListener)
        Shortcuts.removeListener(desktopListener)
    }

    fun refresh() {
        val linux = LinuxEnv.isInstalled(context)
        val sound = linux && SoundBridge.isAvailable(context)
        if (sound) SoundBridge.ensureRunning(context)
        soundStatus.setText(
            when {
                !linux -> R.string.apps_not_installed
                !sound && LinuxSetup.isPending(LinuxSetup.Step.SOUND) -> R.string.settings_setup_pending
                !sound -> R.string.settings_sound_missing
                SoundBridge.isRunning -> R.string.settings_sound_on
                else -> R.string.settings_sound_off
            },
        )
        soundButton.visibility = if (linux && !sound && !LinuxSetup.isRunning) View.VISIBLE else View.GONE
        val x11 = linux && X11Display.isInstalled(context)
        if (x11) X11Display.ensureRunning(context)
        x11Status.setText(
            when {
                !linux -> R.string.apps_not_installed
                !x11 && LinuxSetup.isPending(LinuxSetup.Step.X11) -> R.string.settings_setup_pending
                !x11 -> R.string.settings_x11_missing
                X11Display.isReady(context) -> R.string.settings_x11_on
                else -> R.string.settings_x11_off
            },
        )
        x11Button.visibility = if (linux && !x11 && !LinuxSetup.isRunning) View.VISIBLE else View.GONE
        refreshGpu()
        refreshDesktop()
        refreshTouchpad()
        val notifications = Notifications.isAccessGranted(context)
        notifyStatus.setText(if (notifications) R.string.notif_access_granted else R.string.notif_access_denied)
        notifyHint.visibility = if (notifications) View.GONE else View.VISIBLE
        notifyButton.visibility = if (notifications) View.GONE else View.VISIBLE
        val storage = LinuxEnv.hasStorageAccess()
        storageStatus.setText(if (storage) R.string.settings_storage_granted else R.string.settings_storage_denied)
        storageButton.visibility = if (storage) View.GONE else View.VISIBLE

        processStatus.setText(
            when (phantomMonitoring()) {
                true -> R.string.settings_processes_on
                false -> R.string.settings_processes_off
                null -> R.string.settings_processes_unknown
            }
        )

        val distro = LinuxEnv.installedDistro(context)
        val openTerminals = TermSessions.linuxSessionCount()
        linuxStatus.text = when {
            LinuxInstaller.isRunning || LinuxSetup.isRunning -> context.getString(R.string.settings_linux_busy)
            distro == null -> context.getString(R.string.settings_linux_none)
            else -> context.getString(R.string.linux_installed, distro.title)
        }
        removeButton.visibility = if (distro != null) View.VISIBLE else View.GONE
        removeButton.isEnabled = distro != null && openTerminals == 0 && !LinuxInstaller.isRunning && !LinuxSetup.isRunning
    }

    fun refreshTouchpad() {
        touchpad.set(touchpadOn())
    }

    private fun refreshDesktop() {
        wallpaperStatus.text = context.getString(R.string.settings_wallpaper_current, Wallpapers.name(context, Wallpapers.current(context)))
        restoreIconsButton.visibility = if (Shortcuts.missingBuiltIns(context).isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** What the GPU section says and offers: install progress, on/off, or why it does not apply. */
    private fun refreshGpu() {
        val linux = LinuxEnv.isInstalled(context)
        var button: Int? = null
        gpuStatus.text = when {
            !GpuDrivers.hasAdreno() -> context.getString(R.string.settings_gpu_no_adreno)
            !linux -> context.getString(R.string.apps_not_installed)
            !GpuDrivers.fitsDistro(context) -> context.getString(R.string.settings_gpu_distro)
            GpuDrivers.version(context) == null -> context.getString(R.string.settings_gpu_not_in_build)
            LinuxSetup.isPending(LinuxSetup.Step.GPU) -> context.getString(R.string.settings_setup_pending)
            !GpuDrivers.isEnabled(context) -> {
                button = R.string.settings_gpu_turn_on
                context.getString(R.string.settings_gpu_off)
            }
            GpuDrivers.isActive(context) -> {
                button = R.string.settings_gpu_turn_off
                context.getString(
                    R.string.settings_gpu_on,
                    GpuDrivers.gpuName(),
                    GpuDrivers.version(context)?.substringBefore('-') ?: "",
                )
            }
            LinuxSetup.hasFailed(LinuxSetup.Step.GPU) -> {
                button = R.string.settings_gpu_retry
                context.getString(R.string.settings_gpu_failed, LinuxSetup.state.error ?: "")
            }
            else -> {
                button = R.string.settings_gpu_install
                context.getString(R.string.settings_gpu_missing)
            }
        }
        gpuButton.visibility = if (button != null) View.VISIBLE else View.GONE
        button?.let { gpuButton.setText(it) }
    }

    private fun gpuButtonClicked() {
        when {
            !GpuDrivers.isEnabled(context) -> GpuDrivers.setEnabled(context, true)
            GpuDrivers.isActive(context) -> GpuDrivers.setEnabled(context, false)
            else -> LinuxSetup.start(context)
        }
        refreshGpu()
    }

    /** Whether Android limits child processes; null when it can't be read (Android 12–13, or denied). */
    private fun phantomMonitoring(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return try {
            Settings.Global.getString(context.contentResolver, PHANTOM_SETTING) != "false"
        } catch (_: SecurityException) {
            null
        }
    }

    private fun openDeveloperOptions() {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // Developer options are off: "About phone" is where they get enabled.
            context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }
    }

    private fun confirmRemove() {
        val distro = LinuxEnv.installedDistro(context) ?: return
        showDialog(
            context.dialogBuilder()
                .setTitle(R.string.settings_linux_remove)
                .setMessage(context.getString(R.string.settings_linux_remove_confirm, distro.title))
                .setPositiveButton(R.string.settings_linux_remove) { _, _ -> LinuxInstaller.uninstall(context) }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    // ---- interface scale ----

    private fun scaleStatus() = TextView(context).apply {
        val dpi = UiScale.scaledDpi(systemDpi, currentScale)
        text = context.getString(
            R.string.settings_scale_status,
            context.getString(if (onDesktop) R.string.settings_scale_desktop else R.string.settings_scale_phone),
            currentScale,
            UiScale.waylandScale(dpi / 160f),
        )
    }

    /** One choice per scale: the percent and the screen size it gives, in points. */
    private fun scaleChoices(): View {
        val metrics = context.resources.displayMetrics
        val choices = UiScale.CHOICES.toList()
        return context.choices(
            labels = choices.map { context.getString(R.string.status_percent, it) },
            sublabels = choices.map { percent ->
                val points = 160f / UiScale.scaledDpi(systemDpi, percent)
                context.getString(
                    R.string.settings_scale_size,
                    (metrics.widthPixels * points).roundToInt(),
                    (metrics.heightPixels * points).roundToInt(),
                )
            },
            selected = choices.indexOf(currentScale),
        ) { setScale(choices[it]) }
    }

    private fun themeChoices(): View {
        val themes = listOf(ThemeId.MODERN to R.string.theme_modern, ThemeId.CLASSIC to R.string.theme_classic)
        return context.choices(
            labels = themes.map { context.getString(it.second) },
            sublabels = null,
            selected = themes.indexOfFirst { it.first == Themes.current.id },
        ) { chooseTheme(themes[it].first) }
    }

    /** Sharp X11 programs (they scale themselves) or scaled by HandTop; see [X11ScaleMode]. */
    private fun x11ScaleChoices(): View {
        showX11Scale()
        return x11ScaleRow
    }

    private fun showX11Scale() {
        val modes = listOf(
            Triple(X11ScaleMode.SHARP, R.string.settings_x11_sharp, R.string.settings_x11_sharp_sub),
            Triple(X11ScaleMode.SCALED, R.string.settings_x11_scaled, R.string.settings_x11_scaled_sub),
        )
        x11ScaleRow.removeAllViews()
        x11ScaleRow.addView(context.choices(
            labels = modes.map { context.getString(it.second) },
            sublabels = modes.map { context.getString(it.third) },
            selected = modes.indexOfFirst { it.first == X11Display.mode(context) },
        ) { chooseX11Scale(modes[it].first) })
    }

    /** X11 restarts for the new mode; open X11 programs would close, so that is asked first. */
    private fun chooseX11Scale(mode: X11ScaleMode) {
        val open = WaylandServer.get(context).xwm?.toplevels()?.size ?: 0
        if (open == 0) {
            X11Display.setMode(context, mode)
            showX11Scale()
            return
        }
        showDialog(
            context.dialogBuilder()
                .setTitle(R.string.settings_x11_restart_title)
                .setMessage(context.resources.getQuantityString(R.plurals.settings_x11_restart_text, open, open))
                .setPositiveButton(R.string.settings_x11_restart) { _, _ ->
                    X11Display.setMode(context, mode)
                    showX11Scale()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> showX11Scale() },
        )
    }

    private fun group(title: Int) = context.groupBox(context.getString(title))

    private fun body(text: Int) = context.bodyText(context.getString(text))

    private fun TextView.styled() = apply {
        textSize = 14f
        setTextColor(Palette.TEXT)
        setPadding(0, dpi(2), 0, dpi(4))
    }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val PREFS = "settings"
        const val KEY_CATEGORY = "category"
        /** Narrower than this, the list and a page take turns. */
        const val NARROW_DP = 560
        const val SIDEBAR_DP = 210

        /** Set to "false" by Developer options → "Disable child process restrictions" (Android 14+). */
        const val PHANTOM_SETTING = "settings_enable_monitor_phantom_procs"
        const val ADB_COMMAND = "adb shell device_config put activity_manager max_phantom_processes 2147483647"
    }
}
