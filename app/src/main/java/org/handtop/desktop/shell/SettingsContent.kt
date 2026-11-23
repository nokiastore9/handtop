package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxEnv
import org.handtop.desktop.linux.LinuxInstaller
import org.handtop.desktop.term.TermSessions
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.wm.WindowContent

/** HandTop settings: phone storage for Linux, Android's process limit, the Linux system. */
@SuppressLint("ViewConstructor")
class SettingsContent(
    context: Context,
    private val openLinuxSetup: () -> Unit,
) : ScrollView(context), WindowContent {

    override val view: View get() = this

    private val storageStatus = TextView(context)
    private val storageButton = Button(context)
    private val processStatus = TextView(context)
    private val linuxStatus = TextView(context)
    private val removeButton = Button(context)
    private val listener: (LinuxInstaller.State) -> Unit = { refresh() }

    init {
        setBackgroundColor(0xFF0F1420.toInt())
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22), dpi(14), dpi(22), dpi(20))
        }

        // Phone storage.
        column.addView(heading(R.string.settings_storage_title))
        column.addView(body(R.string.settings_storage_text))
        column.addView(storageStatus.styled())
        storageButton.isAllCaps = false
        storageButton.setText(R.string.settings_storage_grant)
        storageButton.setOnClickListener { requestStorage() }
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
        refresh()
    }

    /** Called when the window is activated and when HandTop comes back from Settings. */
    override fun onActivated() = refresh()

    override fun onClosed() {
        LinuxInstaller.removeListener(listener)
    }

    fun refresh() {
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

    /** Whether Android limits child processes; null when it can't be read (Android 12–13, or denied). */
    private fun phantomMonitoring(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return try {
            Settings.Global.getString(context.contentResolver, PHANTOM_SETTING) != "false"
        } catch (_: SecurityException) {
            null
        }
    }

    private fun requestStorage() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
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
            .show()
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
