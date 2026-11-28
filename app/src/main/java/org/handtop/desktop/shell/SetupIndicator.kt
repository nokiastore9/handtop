package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxSetup
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.selectableBackground

/**
 * Taskbar item while HandTop sets Linux up ([LinuxSetup]): a spinner and "Setting up Linux
 * 2/4", or a warning after a failure. Visible whatever window is in front; a tap opens the
 * Linux System window with the details. Hidden otherwise.
 */
@SuppressLint("ViewConstructor")
class SetupIndicator(context: Context, onClick: () -> Unit) : LinearLayout(context) {

    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val label = TextView(context)
    private val listener: (LinuxSetup.State) -> Unit = { render() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(10), 0, dpi(10), 0)
        background = Themes.current.darkFeedback(context)
        isFocusable = false
        setOnClickListener { onClick() }
        spinner.indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT)
        addView(spinner, LayoutParams(dpi(16), dpi(16)))
        label.textSize = 13f
        label.isSingleLine = true
        label.setPadding(dpi(8), 0, 0, 0)
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LinuxSetup.addListener(listener)
        render()
    }

    override fun onDetachedFromWindow() {
        LinuxSetup.removeListener(listener)
        super.onDetachedFromWindow()
    }

    private fun render() {
        val state = LinuxSetup.state
        when {
            state.running -> {
                visibility = View.VISIBLE
                spinner.visibility = View.VISIBLE
                label.setTextColor(Palette.TASKBAR_TEXT)
                label.text = context.getString(R.string.setup_taskbar, state.position, state.steps.size)
            }
            state.failed -> {
                visibility = View.VISIBLE
                spinner.visibility = View.GONE
                label.setTextColor(Palette.TASKBAR_WARNING)
                label.text = "⚠ " + context.getString(R.string.setup_taskbar_failed)
            }
            else -> visibility = View.GONE
        }
        tooltipText = label.text
    }

}
