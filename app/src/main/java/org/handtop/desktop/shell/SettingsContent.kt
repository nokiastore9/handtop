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
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.audio.SoundBridge
import org.handtop.desktop.linux.GpuDrivers
import org.handtop.desktop.linux.LinuxEnv
import org.handtop.desktop.linux.LinuxInstaller
import org.handtop.desktop.linux.X11Display
import org.handtop.desktop.notify.NotificationCenter
import org.handtop.desktop.notify.Notifications
import org.handtop.desktop.term.TermSessions
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.UiScale
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.hideSystemBars
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.ui.selectableBackground
import kotlin.math.roundToInt
import org.handtop.desktop.wm.WindowContent

/** HandTop settings: interface scale, phone storage for Linux, Android's process limit, Linux. */
@SuppressLint("ViewConstructor")
class SettingsContent(
    context: Context,
    private val openLinuxSetup: () -> Unit,
    /** Saves a new interface scale (percent) and rebuilds the desktop with it. */
    private val setScale: (Int) -> Unit,
    /** Runs a command in a new Linux terminal window (title, command). */
    private val runInTerminal: (String, String) -> Unit,
) : ScrollView(context), WindowContent {

    override val view: View get() = this

    private val storageStatus = TextView(context)
    private val storageButton = Button(context)
    private val processStatus = TextView(context)
    private val linuxStatus = TextView(context)
    private val removeButton = Button(context)
    private val notifyStatus = TextView(context)
    private val soundStatus = TextView(context)
    private val soundButton = Button(context)
    private val x11Status = TextView(context)
    private val x11Button = Button(context)
    private val notifyHint = TextView(context)
    private val notifyButton = Button(context)
    private val gpuStatus = TextView(context)
    private val gpuButton = Button(context)
    private val listener: (LinuxInstaller.State) -> Unit = { refresh() }
    private val gpuListener: (GpuDrivers.State) -> Unit = { refreshGpu() }

    // Interface scale of the screen this window is on (declared before init, which uses them).
    private val onDesktop = UiScale.isDesktop(context)
    private val currentScale = UiScale.percent(context, onDesktop)
    private val systemDpi = UiScale.systemDpi(context)

    init {
        setBackgroundColor(0xFF0F1420.toInt())
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22), dpi(14), dpi(22), dpi(20))
        }

        // Interface scale: first, it is what a phone screen needs most.
        column.addView(heading(R.string.settings_scale_title))
        column.addView(body(R.string.settings_scale_text))
        column.addView(scaleStatus().styled())
        column.addView(scaleChoices(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dpi(6) })

        // Notifications on the taskbar (HandTop hides Android's status bar).
        column.addView(heading(R.string.notifications_title))
        column.addView(body(R.string.settings_notif_text))
        column.addView(notifyStatus.styled())
        notifyHint.apply {
            setText(R.string.notif_access_restricted)
            textSize = 12f
            setTextColor(Palette.TEXT_DIM)
            setPadding(0, 0, 0, dpi(4))
        }
        column.addView(notifyHint)
        notifyButton.isAllCaps = false
        notifyButton.setText(R.string.notif_access_open)
        notifyButton.setOnClickListener { NotificationCenter.openAccessSettings(context) }
        column.addView(notifyButton, wrap())

        // Sound from Linux programs.
        column.addView(heading(R.string.settings_sound_title))
        column.addView(body(R.string.settings_sound_text))
        column.addView(soundStatus.styled())
        soundButton.isAllCaps = false
        soundButton.setText(R.string.settings_sound_install)
        soundButton.setOnClickListener {
            val distro = LinuxEnv.installedDistro(context)?.id
            runInTerminal(context.getString(R.string.settings_sound_title), SoundBridge.installCommand(distro))
        }
        column.addView(soundButton, wrap())

        // X11 programs through Xwayland.
        column.addView(heading(R.string.settings_x11_title))
        column.addView(body(R.string.settings_x11_text))
        column.addView(x11Status.styled())
        x11Button.isAllCaps = false
        x11Button.setText(R.string.settings_x11_install)
        x11Button.setOnClickListener {
            val distro = LinuxEnv.installedDistro(context)?.id
            runInTerminal(context.getString(R.string.settings_x11_title), X11Display.installCommand(distro))
        }
        column.addView(x11Button, wrap())

        // The GPU for Linux programs (Adreno).
        column.addView(heading(R.string.settings_gpu_title))
        column.addView(body(R.string.settings_gpu_text))
        column.addView(gpuStatus.styled())
        gpuButton.isAllCaps = false
        gpuButton.setOnClickListener { gpuButtonClicked() }
        column.addView(gpuButton, wrap())

        // Phone storage.
        column.addView(heading(R.string.settings_storage_title))
        column.addView(body(R.string.settings_storage_text))
        column.addView(storageStatus.styled())
        storageButton.isAllCaps = false
        storageButton.setText(R.string.settings_storage_grant)
        storageButton.setOnClickListener { LinuxEnv.requestStorageAccess(context) }
        column.addView(storageButton, wrap())

        // Android's phantom process killer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            column.addView(heading(R.string.settings_processes_title))
            column.addView(body(R.string.settings_processes_text))
            column.addView(processStatus.styled())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                column.addView(body(R.string.settings_processes_how_14))
                column.addView(Button(context).apply {
                    isAllCaps = false
                    setText(R.string.settings_processes_open)
                    setOnClickListener { openDeveloperOptions() }
                }, wrap())
            } else {
                column.addView(body(R.string.settings_processes_how_12))
                column.addView(TextView(context).apply {
                    text = ADB_COMMAND
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Palette.TEXT)
                    setTextIsSelectable(true)
                    setPadding(0, dpi(4), 0, dpi(4))
                })
            }
        }

        // The Linux system.
        column.addView(heading(R.string.settings_linux_title))
        column.addView(linuxStatus.styled())
        column.addView(Button(context).apply {
            isAllCaps = false
            setText(R.string.settings_linux_manage)
            setOnClickListener { openLinuxSetup() }
        }, wrap())
        removeButton.isAllCaps = false
        removeButton.setText(R.string.settings_linux_remove)
        removeButton.setOnClickListener { confirmRemove() }
        column.addView(removeButton, wrap())

        addView(column)
        LinuxInstaller.addListener(listener)
        GpuDrivers.addListener(gpuListener)
        refresh()
    }

    /** Called when the window is activated and when HandTop comes back from Settings. */
    override fun onActivated() = refresh()

    override fun onClosed() {
        LinuxInstaller.removeListener(listener)
        GpuDrivers.removeListener(gpuListener)
    }

    fun refresh() {
        val linux = LinuxEnv.isInstalled(context)
        val sound = linux && SoundBridge.isAvailable(context)
        if (sound) SoundBridge.ensureRunning(context)
        soundStatus.setText(
            when {
                !linux -> R.string.apps_not_installed
                !sound -> R.string.settings_sound_missing
                SoundBridge.isRunning -> R.string.settings_sound_on
                else -> R.string.settings_sound_off
            },
        )
        soundButton.visibility = if (linux && !sound) View.VISIBLE else View.GONE
        val x11 = linux && X11Display.isInstalled(context)
        if (x11) X11Display.ensureRunning(context)
        x11Status.setText(
            when {
                !linux -> R.string.apps_not_installed
                !x11 -> R.string.settings_x11_missing
                X11Display.isReady(context) -> R.string.settings_x11_on
                else -> R.string.settings_x11_off
            },
        )
        x11Button.visibility = if (linux && !x11) View.VISIBLE else View.GONE
        refreshGpu()
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
            LinuxInstaller.isRunning -> context.getString(R.string.settings_linux_busy)
            distro == null -> context.getString(R.string.settings_linux_none)
            else -> context.getString(R.string.linux_installed, distro.title)
        }
        removeButton.visibility = if (distro != null) View.VISIBLE else View.GONE
        removeButton.isEnabled = distro != null && openTerminals == 0 && !LinuxInstaller.isRunning
    }

    /** What the GPU section says and offers: install progress, on/off, or why it does not apply. */
    private fun refreshGpu() {
        val linux = LinuxEnv.isInstalled(context)
        val state = GpuDrivers.state
        var button: Int? = null
        gpuStatus.text = when {
            GpuDrivers.version(context) == null -> context.getString(R.string.settings_gpu_not_in_build)
            !GpuDrivers.hasAdreno() -> context.getString(R.string.settings_gpu_no_adreno)
            !linux -> context.getString(R.string.apps_not_installed)
            !GpuDrivers.fitsDistro(context) -> context.getString(R.string.settings_gpu_distro)
            state.phase == GpuDrivers.Phase.UNPACKING -> context.getString(R.string.settings_gpu_unpacking)
            state.phase == GpuDrivers.Phase.LIBRARIES -> context.getString(R.string.settings_gpu_libraries)
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
            state.phase == GpuDrivers.Phase.FAILED -> {
                button = R.string.settings_gpu_retry
                context.getString(R.string.settings_gpu_failed, state.error ?: "")
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
            else -> GpuDrivers.install(context)
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
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_linux_remove)
            .setMessage(context.getString(R.string.settings_linux_remove_confirm, distro.title))
            .setPositiveButton(R.string.settings_linux_remove) { _, _ -> LinuxInstaller.uninstall(context) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply { window?.hideSystemBars() } // stay full screen while the dialog is open
            .show()
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

    /** One chip per choice: the percent and the screen size it gives, in points. */
    private fun scaleChoices(): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val metrics = context.resources.displayMetrics
        for (percent in UiScale.CHOICES) {
            val points = 160f / UiScale.scaledDpi(systemDpi, percent)
            val selected = percent == currentScale
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dpi(12), dpi(6), dpi(12), dpi(6))
                background = roundedRect(if (selected) Palette.ACCENT else Palette.CHIP, dp(10f))
                foreground = context.selectableBackground()
                isSelected = selected
                setOnClickListener { if (!selected) setScale(percent) }
            }
            chip.addView(TextView(context).apply {
                text = context.getString(R.string.status_percent, percent)
                textSize = 15f
                setTextColor(Palette.TEXT)
                gravity = Gravity.CENTER
            })
            chip.addView(TextView(context).apply {
                text = context.getString(
                    R.string.settings_scale_size,
                    (metrics.widthPixels * points).roundToInt(),
                    (metrics.heightPixels * points).roundToInt(),
                )
                textSize = 11f
                setTextColor(if (selected) Palette.TEXT else Palette.TEXT_DIM)
                gravity = Gravity.CENTER
            })
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dpi(6) })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun heading(text: Int) = TextView(context).apply {
        setText(text)
        textSize = 17f
        setTextColor(Palette.TEXT)
        setPadding(0, dpi(14), 0, dpi(4))
    }

    private fun body(text: Int) = TextView(context).apply {
        setText(text)
        textSize = 13f
        setTextColor(Palette.TEXT_DIM)
        setLineSpacing(0f, 1.15f)
        setPadding(0, 0, 0, dpi(4))
    }

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
        /** Set to "false" by Developer options → "Disable child process restrictions" (Android 14+). */
        const val PHANTOM_SETTING = "settings_enable_monitor_phantom_procs"
        const val ADB_COMMAND = "adb shell device_config put activity_manager max_phantom_processes 2147483647"
    }
}
