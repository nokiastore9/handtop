package org.handtop.desktop.linux

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxSetup.Status
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.styleProgress
import org.handtop.desktop.ui.styleButton
import org.handtop.desktop.ui.bevel
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.Bevel
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.roundedRect

/**
 * The progress of [LinuxSetup]: one line per step (waiting, working, done or failed), a bar,
 * and the package manager's latest line, so it is clear that something is being installed and
 * what. Shows the outcome when done, with a retry after a failure. Hidden before any run.
 */
@SuppressLint("ViewConstructor")
class SetupProgressView(context: Context) : LinearLayout(context) {

    private val title = TextView(context)
    private val text = TextView(context)
    private val rows = LinearLayout(context)
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val detail = TextView(context)
    private val error = TextView(context)
    private val retry = Button(context)
    private val listener: (LinuxSetup.State) -> Unit = { render() }
    /** The steps the rows show: rebuilt only when they change, so the spinner keeps turning. */
    private var shownSteps: List<Pair<LinuxSetup.Step, Status>>? = null

    init {
        orientation = VERTICAL
        setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
        // Classic: a white panel with a thin sunken edge instead of a rounded tint.
        background = if (Themes.current.bevelled) context.bevel(Bevel.SHALLOW, Palette.FIELD) else roundedRect(Palette.CARD, dp(10f))

        title.textSize = 16f
        title.setTextColor(Palette.TEXT)
        addView(title)
        text.setText(R.string.setup_text)
        text.textSize = 13f
        text.setTextColor(Palette.TEXT_DIM)
        text.setLineSpacing(0f, 1.15f)
        text.setPadding(0, dpi(4), 0, dpi(6))
        addView(text)
        rows.orientation = VERTICAL
        addView(rows)
        bar.max = 1000
        bar.styleProgress()
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dpi(8)).apply { topMargin = dpi(8) })
        detail.textSize = 12f
        detail.typeface = Typeface.MONOSPACE
        detail.setTextColor(Palette.TEXT_DIM)
        detail.isSingleLine = true
        detail.ellipsize = TextUtils.TruncateAt.END
        detail.setPadding(0, dpi(6), 0, 0)
        addView(detail)
        error.textSize = 13f
        error.setTextColor(Palette.WARNING)
        error.setPadding(0, dpi(8), 0, dpi(4))
        addView(error)
        retry.isAllCaps = false
        retry.styleButton()
        retry.setText(R.string.setup_retry)
        retry.setOnClickListener { LinuxSetup.start(context) }
        addView(retry, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
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
        visibility = if (state.steps.isEmpty()) View.GONE else View.VISIBLE
        if (state.steps.isEmpty()) return

        title.setText(if (state.finished) R.string.setup_ready else R.string.setup_title)
        text.visibility = if (state.running) View.VISIBLE else View.GONE

        if (state.steps != shownSteps) {
            shownSteps = state.steps
            rows.removeAllViews()
            for ((step, status) in state.steps) rows.addView(row(LinuxSetup.title(context, step), status))
        }

        bar.visibility = if (state.running) View.VISIBLE else View.GONE
        bar.progress = (state.progress * 1000).toInt()
        detail.text = state.detail
        detail.visibility = if (state.running && state.detail.isNotEmpty()) View.VISIBLE else View.GONE

        error.visibility = if (state.failed) View.VISIBLE else View.GONE
        error.text = context.getString(R.string.setup_failed, state.error ?: "?", LinuxSetup.LOG)
        retry.visibility = error.visibility
        retry.isEnabled = !LinuxInstaller.isRunning
    }

    /** A step: its state on the left (a spinner while it runs), its name on the right. */
    private fun row(name: String, status: Status): View {
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(3), 0, dpi(3))
        }
        val mark = FrameLayout(context)
        if (status == Status.RUNNING) {
            mark.addView(ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT)
            }, FrameLayout.LayoutParams(dpi(16), dpi(16), Gravity.CENTER))
        } else {
            mark.addView(TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                when (status) {
                    Status.DONE -> { text = "✓"; setTextColor(Palette.BATTERY_CHARGING) }
                    Status.FAILED -> { text = "✗"; setTextColor(Palette.WARNING) }
                    else -> { text = "○"; setTextColor(Palette.TEXT_DIM) }
                }
            }, FrameLayout.LayoutParams(dpi(22), dpi(22), Gravity.CENTER))
        }
        line.addView(mark, LayoutParams(dpi(24), dpi(24)))
        line.addView(TextView(context).apply {
            text = name
            textSize = 14f
            setTextColor(if (status == Status.PENDING) Palette.TEXT_DIM else Palette.TEXT)
            setPadding(dpi(8), 0, 0, 0)
        })
        return line
    }

}
