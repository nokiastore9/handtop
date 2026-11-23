package org.handtop.desktop.linux

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxInstaller.Phase
import org.handtop.desktop.term.TermSessions
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.wm.WindowContent
import org.handtop.desktop.wm.WindowHost

/** Window that installs (or replaces) the Linux environment and shows the progress. */
@SuppressLint("ViewConstructor")
class LinuxSetupContent(
    context: Context,
    private val openLinuxTerminal: () -> Unit,
) : ScrollView(context), WindowContent {

    override val view: View get() = this

    private var host: WindowHost? = null
    private val installedText = TextView(context)
    private val openButton = Button(context)
    private val chooserTitle = TextView(context)
    private val chooser = RadioGroup(context)
    private val warning = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val status = TextView(context)
    private val installButton = Button(context)
    private val listener: (LinuxInstaller.State) -> Unit = { render() }

    init {
        setBackgroundColor(0xFF0F1420.toInt())
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22), dpi(18), dpi(22), dpi(20))
        }
        column.addView(label(R.string.linux_setup_heading, 20f, Palette.TEXT))
        column.addView(label(R.string.linux_setup_intro, 14f, Palette.TEXT_DIM).apply {
            setPadding(0, dpi(10), 0, dpi(14))
        })

        installedText.setTextColor(Palette.TEXT)
        installedText.textSize = 15f
        column.addView(installedText)
        openButton.isAllCaps = false
        openButton.setText(R.string.linux_setup_open)
        openButton.setOnClickListener {
            host?.close()
            openLinuxTerminal()
        }
        column.addView(openButton, wrap())

        chooserTitle.setTextColor(Palette.TEXT)
        chooserTitle.textSize = 15f
        chooserTitle.setPadding(0, dpi(16), 0, dpi(4))
        column.addView(chooserTitle)
        for (distro in Distro.entries) {
            chooser.addView(RadioButton(context).apply {
                id = View.generateViewId()
                tag = distro
                text = context.getString(R.string.linux_distro_option, distro.title, distro.downloadMb)
                setTextColor(Palette.TEXT)
                buttonTintList = ColorStateList.valueOf(Palette.ACCENT)
            })
        }
        column.addView(chooser)
        warning.setTextColor(0xFFFFB454.toInt())
        warning.textSize = 13f
        warning.setPadding(0, dpi(6), 0, dpi(6))
        column.addView(warning)

        progress.max = 1000
        column.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(8)).apply {
            topMargin = dpi(8)
        })
        status.setTextColor(Palette.TEXT)
        status.textSize = 14f
        status.setPadding(0, dpi(10), 0, dpi(10))
        column.addView(status)
        installButton.isAllCaps = false
        installButton.setOnClickListener { LinuxInstaller.install(context, selectedDistro()) }
        column.addView(installButton, wrap())
        addView(column)

        val current = LinuxEnv.installedDistro(context) ?: Distro.DEFAULT
        chooser.check(chooser.findViewWithTag<RadioButton>(current).id)
        chooser.setOnCheckedChangeListener { _, _ -> render() }

        LinuxInstaller.addListener(listener)
        render()
    }

    override fun attach(host: WindowHost) {
        this.host = host
    }

    override fun onActivated() = render()

    override fun onClosed() {
        LinuxInstaller.removeListener(listener)
    }

    private fun selectedDistro(): Distro =
        findViewById<RadioButton>(chooser.checkedRadioButtonId)?.tag as? Distro ?: Distro.DEFAULT

    private fun render() {
        val state = LinuxInstaller.state
        val running = LinuxInstaller.isRunning
        val installed = LinuxEnv.installedDistro(context)
        val openTerminals = TermSessions.linuxSessionCount()

        installedText.visibility = if (installed != null && !running) View.VISIBLE else View.GONE
        installedText.text = installed?.let { context.getString(R.string.linux_installed, it.title) }
        openButton.visibility = installedText.visibility

        chooserTitle.setText(if (installed != null) R.string.linux_setup_change else R.string.linux_setup_choose)
        for (i in 0 until chooser.childCount) chooser.getChildAt(i).isEnabled = !running

        warning.text = when {
            running -> ""
            installed != null && openTerminals > 0 -> context.getString(R.string.linux_close_terminals, openTerminals)
            installed != null -> context.getString(R.string.linux_replace_warning, installed.title)
            else -> ""
        }
        warning.visibility = if (warning.text.isEmpty()) View.GONE else View.VISIBLE

        progress.visibility = if (running) View.VISIBLE else View.GONE
        progress.isIndeterminate = state.total <= 0
        if (state.total > 0) progress.progress = (state.done * 1000 / state.total).toInt()

        status.text = when (state.phase) {
            Phase.IDLE, Phase.DONE -> ""
            Phase.RESOLVING -> context.getString(R.string.linux_phase_resolving)
            Phase.DOWNLOADING -> context.getString(R.string.linux_phase_downloading, size(state.done), size(state.total))
            Phase.EXTRACTING -> context.getString(R.string.linux_phase_extracting, state.done * 100 / maxOf(1, state.total))
            Phase.CONFIGURING -> context.getString(R.string.linux_phase_configuring)
            Phase.REMOVING -> context.getString(R.string.linux_phase_removing)
            Phase.FAILED -> context.getString(R.string.linux_phase_failed, state.error ?: "?")
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE

        installButton.visibility = if (running) View.GONE else View.VISIBLE
        installButton.setText(
            when {
                state.phase == Phase.FAILED -> R.string.linux_setup_retry
                installed != null -> R.string.linux_setup_reinstall
                else -> R.string.linux_setup_install
            }
        )
        installButton.isEnabled = !(installed != null && openTerminals > 0)
    }

    private fun label(text: Int, sizeSp: Float, color: Int) = TextView(context).apply {
        setText(text)
        textSize = sizeSp
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
    }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
}
