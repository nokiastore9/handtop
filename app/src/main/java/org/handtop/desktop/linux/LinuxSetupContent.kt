package org.handtop.desktop.linux

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxInstaller.Phase
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.wm.WindowContent
import org.handtop.desktop.wm.WindowHost

/** Window that installs the Linux environment and shows the progress. */
@SuppressLint("ViewConstructor")
class LinuxSetupContent(
    context: Context,
    private val openLinuxTerminal: () -> Unit,
) : ScrollView(context), WindowContent {

    override val view: View get() = this

    private var host: WindowHost? = null
    private val status = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val button = Button(context)
    private val listener: (LinuxInstaller.State) -> Unit = { render(it) }

    init {
        setBackgroundColor(0xFF0F1420.toInt())
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22), dpi(18), dpi(22), dpi(20))
        }
        column.addView(TextView(context).apply {
            setText(R.string.linux_setup_heading)
            setTextColor(Palette.TEXT)
            textSize = 20f
        })
        column.addView(TextView(context).apply {
            setText(R.string.linux_setup_intro)
            setTextColor(Palette.TEXT_DIM)
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dpi(10), 0, dpi(16))
        })
        progress.max = 1000
        column.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(8)))
        status.setTextColor(Palette.TEXT)
        status.textSize = 14f
        status.setPadding(0, dpi(10), 0, dpi(16))
        column.addView(status)
        button.isAllCaps = false
        column.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START
        })
        addView(column)

        LinuxInstaller.addListener(listener)
        render(LinuxInstaller.state)
    }

    override fun attach(host: WindowHost) {
        this.host = host
    }

    override fun onClosed() {
        LinuxInstaller.removeListener(listener)
    }

    private fun render(state: LinuxInstaller.State) {
        val installed = LinuxEnv.isInstalled(context)
        val phase = if (state.phase == Phase.IDLE && installed) Phase.DONE else state.phase
        progress.visibility = if (LinuxInstaller.isRunning) View.VISIBLE else View.GONE
        progress.isIndeterminate = state.total <= 0
        if (state.total > 0) progress.progress = (state.done * 1000 / state.total).toInt()

        status.text = when (phase) {
            Phase.IDLE -> ""
            Phase.RESOLVING -> context.getString(R.string.linux_phase_resolving)
            Phase.DOWNLOADING -> context.getString(
                R.string.linux_phase_downloading, size(state.done), size(state.total),
            )
            Phase.EXTRACTING -> context.getString(R.string.linux_phase_extracting, state.done * 100 / maxOf(1, state.total))
            Phase.CONFIGURING -> context.getString(R.string.linux_phase_configuring)
            Phase.DONE -> context.getString(R.string.linux_phase_done)
            Phase.FAILED -> context.getString(R.string.linux_phase_failed, state.error ?: "?")
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE

        when (phase) {
            Phase.IDLE -> showButton(R.string.linux_setup_install) { LinuxInstaller.start(context) }
            Phase.FAILED -> showButton(R.string.linux_setup_retry) { LinuxInstaller.start(context) }
            Phase.DONE -> showButton(R.string.linux_setup_open) {
                host?.close()
                openLinuxTerminal()
            }
            else -> button.visibility = View.GONE
        }
    }

    private fun showButton(text: Int, onClick: () -> Unit) {
        button.setText(text)
        button.visibility = View.VISIBLE
        button.setOnClickListener { onClick() }
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
}
