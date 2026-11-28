package org.handtop.desktop.widgets

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxEnv
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.dpi
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** One look at this app's processes: HandTop and Linux under proot. */
class LinuxSampler(private val context: Context) {

    data class Sample(val ticks: Long, val rss: Long, val linuxRunning: Boolean, val atMs: Long)

    /**
     * Sums the CPU time and resident memory of every process whose executable can be read —
     * this app's own: HandTop and everything Linux runs. A process that ends meanwhile is
     * skipped.
     */
    fun sample(): Sample {
        var ticks = 0L
        var rss = 0L
        var linux = false
        for (dir in File("/proc").listFiles() ?: emptyArray()) {
            val pid = dir.name.toIntOrNull() ?: continue
            val exe = try {
                Os.readlink("/proc/$pid/exe")
            } catch (_: Exception) {
                continue // another app's process, or gone
            }
            if (LinuxEnv.isProotExe(context, exe)) linux = true
            val stat = try { File(dir, "stat").readText() } catch (_: Exception) { continue }
            val status = try { File(dir, "status").readText() } catch (_: Exception) { continue }
            ticks += LinuxLoad.statTicks(stat) ?: continue
            rss += LinuxLoad.rssBytes(status) ?: 0
        }
        return Sample(ticks, rss, linux, SystemClock.elapsedRealtime())
    }
}

/** Linux's processor and memory, every 2 seconds while the desktop is shown; a tap opens Linux System. */
@SuppressLint("ViewConstructor")
class LinuxWidget(context: Context, host: WidgetHost) : WidgetView(context, WidgetKind.LINUX, host) {

    private val sampler = LinuxSampler(context.applicationContext)
    private val cores = Runtime.getRuntime().availableProcessors()
    private val hz = try { Os.sysconf(OsConstants._SC_CLK_TCK).toInt() } catch (_: Exception) { 100 }
    private val totalMemory = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .let { am -> ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem }

    private val title = label(11f, dim = true)
    private val state = label(13f)
    private val cpuValue = label(11f)
    private val memoryValue = label(11f)
    private val cpuMeter = meter(colors.cpu)
    private val memoryMeter = meter(colors.memory)
    private val meters = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private var active = false
    private var last: LinuxSampler.Sample? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            EXECUTOR.execute {
                val sample = try { sampler.sample() } catch (_: Exception) { null }
                post {
                    if (sample != null) show(sample)
                    if (active) postDelayed(this, INTERVAL_MS)
                }
            }
        }
    }

    init {
        meters.addView(row(R.string.widget_linux_cpu, cpuValue))
        meters.addView(cpuMeter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(6)).apply { bottomMargin = dpi(4) })
        meters.addView(row(R.string.widget_linux_memory, memoryValue))
        meters.addView(memoryMeter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(6)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(state)
            addView(meters)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showState()
    }

    private fun row(name: Int, value: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(11f).apply { setText(name) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
    }

    private fun meter(fill: Int) = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressDrawable = Themes.current.widgetMeter(context, fill)
        max = 100
        isIndeterminate = false
    }

    /** Title and, without a running Linux, why there are no meters. */
    private fun showState(running: Boolean = false) {
        val distro = LinuxEnv.installedDistro(context)
        title.text = if (distro != null) "Linux · ${distro.title}" else "Linux"
        val message = when {
            !LinuxEnv.isInstalled(context) -> R.string.widget_linux_not_installed
            !running -> R.string.widget_linux_not_running
            else -> 0
        }
        state.visibility = if (message != 0) VISIBLE else GONE
        if (message != 0) state.setText(message)
        meters.visibility = if (message == 0) VISIBLE else GONE
    }

    private fun show(sample: LinuxSampler.Sample) {
        showState(sample.linuxRunning)
        val prev = last
        last = sample
        if (prev != null) {
            val cpu = LinuxLoad.cpuPercent(prev.ticks, sample.ticks, sample.atMs - prev.atMs, cores, hz)
            cpuValue.text = String.format(Locale.getDefault(), "%d %%", cpu)
            cpuMeter.progress = cpu
        }
        memoryValue.text = LinuxLoad.memoryText(sample.rss, totalMemory, Locale.getDefault(), context.getString(R.string.widget_gb))
        memoryMeter.progress = if (totalMemory > 0) (sample.rss * 100 / totalMemory).toInt() else 0
    }

    override fun onActive(active: Boolean) {
        this.active = active
        removeCallbacks(tick)
        if (active) {
            last = null
            tick.run()
        }
    }

    override fun onTap() = host.openLinuxSystem()

    private companion object {
        const val INTERVAL_MS = 2_000L
        val EXECUTOR = Executors.newSingleThreadExecutor { Thread(it, "linux-widget").apply { isDaemon = true } }
    }
}
