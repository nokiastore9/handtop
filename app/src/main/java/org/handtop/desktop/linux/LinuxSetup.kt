package org.handtop.desktop.linux

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.handtop.desktop.R
import org.handtop.desktop.audio.SoundBridge
import org.handtop.desktop.service.SessionService
import org.handtop.desktop.term.TermSessions
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Setting up a distribution for HandTop: what Linux programs need on a phone beyond the
 * official image, installed without the user asking. It runs right after Linux is installed
 * and, for anything still missing (a failed attempt, a HandTop update that adds a step), at
 * the start of a Linux session.
 *
 * Each [Step] is skipped when already done:
 *  - GPU drivers (Adreno only, see [GpuDrivers]): HandTop's Mesa from the APK, plus the
 *    distribution's libraries it links to;
 *  - X11 programs: Xwayland;
 *  - sound: PulseAudio and its ALSA plugin;
 *  - keys of third-party repositories: gpg, which their install scripts (VS Code, Chrome…)
 *    use to store the repository's key.
 *
 * Packages come from the distribution's package manager, run without a terminal. Its output
 * goes to [LOG]; the latest line and a guess of how far it has got go to [state], which the
 * Linux System window, the taskbar and the notification show. A terminal opened meanwhile
 * says that the setup is still running ([NOTE], read by HandTop's login profile).
 */
object LinuxSetup {

    enum class Step { GPU, X11, SOUND, KEYS }

    enum class Status { PENDING, RUNNING, DONE, FAILED }

    data class State(
        /** The steps of the current (or last) run, in order; empty before any run. */
        val steps: List<Pair<Step, Status>> = emptyList(),
        val running: Boolean = false,
        /** 0..1 over all steps. */
        val progress: Float = 0f,
        /** What is happening now: the package manager's latest line. */
        val detail: String = "",
        /** Why the first failed step failed. */
        val error: String? = null,
    ) {
        val failed get() = !running && steps.any { it.second == Status.FAILED }
        val finished get() = !running && steps.isNotEmpty() && !failed

        /** 1-based number of the step being worked on, for "2/4". */
        val position get() = steps.indexOfFirst { it.second == Status.RUNNING }.let { if (it < 0) steps.size else it + 1 }
    }

    /** Package manager output, in the distribution. */
    const val LOG = "/tmp/handtop-setup.log"
    /** Exists while the setup runs; holds the line new terminals print. */
    private const val NOTE = "tmp/handtop-setup-running"

    @Volatile
    var state = State()
        private set

    private val busy = AtomicBoolean(false)

    /** A run is going on (true from the moment [start] returns). */
    val isRunning get() = busy.get()

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun title(context: Context, step: Step): String = when (step) {
        Step.GPU -> context.getString(R.string.setup_step_gpu, GpuDrivers.gpuName())
        Step.X11 -> context.getString(R.string.setup_step_x11)
        Step.SOUND -> context.getString(R.string.setup_step_sound)
        Step.KEYS -> context.getString(R.string.setup_step_keys)
    }

    /** The step will be (or is being) worked on by the current run. */
    fun isPending(step: Step): Boolean {
        val s = state
        return s.running && s.steps.any { it.first == step && it.second in setOf(Status.PENDING, Status.RUNNING) }
    }

    /** The step failed in the last run. */
    fun hasFailed(step: Step) = state.steps.any { it.first == step && it.second == Status.FAILED }

    /**
     * Starts a run when something is missing, from the start of a Linux session. Cheap when
     * everything is there. After a failed run it waits for [start] (a button) or the next
     * start of HandTop, so a phone without internet does not retry at every terminal.
     */
    fun startIfNeeded(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        File(LinuxEnv.rootfs(app), NOTE).delete() // left over by a HandTop that was killed mid-run
        if (state.failed || LinuxInstaller.isRunning || !LinuxEnv.isInstalled(app)) return
        if (missingSteps(app).isNotEmpty()) start(app)
    }

    /** Forgets the last run: its system is being replaced or removed. Main thread. */
    fun reset() {
        if (isRunning || state.steps.isEmpty()) return
        state = State()
        listeners.forEach { it(state) }
    }

    /** Runs the missing steps (again); no-op while a run or a Linux installation is going on. */
    fun start(context: Context) {
        val app = context.applicationContext
        val distro = LinuxEnv.installedDistro(app) ?: return
        if (!busy.compareAndSet(false, true)) return
        val steps = missingSteps(app)
        if (steps.isEmpty()) {
            busy.set(false)
            publish(State(), app)
            return
        }
        val rootfs = LinuxEnv.rootfs(app)
        try {
            File(rootfs, NOTE).writeText(app.getString(R.string.setup_terminal_note) + "\n")
        } catch (e: IOException) {
            Log.w(TAG, "cannot write the terminal note", e)
        }
        publish(State(steps.map { it to Status.PENDING }, running = true), app)
        Thread({ run(app, rootfs, distro, steps) }, "LinuxSetup").start()
    }

    private fun missingSteps(context: Context): List<Step> = Step.entries.filter { !isDone(context, it) }

    private fun isDone(context: Context, step: Step): Boolean {
        val rootfs = LinuxEnv.rootfs(context)
        return when (step) {
            Step.GPU -> !GpuDrivers.isWanted(context) || GpuDrivers.isReady(context)
            Step.X11 -> X11Display.isInstalled(context)
            Step.SOUND -> SoundBridge.isAvailable(context)
            Step.KEYS -> File(rootfs, "usr/bin/gpg").exists()
        }
    }

    // ---- the run, on its own thread ----

    private fun run(app: Context, rootfs: File, distro: Distro, steps: List<Step>) {
        val manager = PackageManager.of(distro)
        val statuses = steps.map { it to Status.PENDING }.toMutableList()
        var error: String? = null
        var refreshed = false
        val log = File(rootfs, LOG.removePrefix("/"))
        log.delete()

        fun report(index: Int, fraction: Float, detail: String) {
            val progress = (index + fraction.coerceIn(0f, 1f)) / steps.size
            publish(State(statuses.toList(), running = true, progress = progress, detail = detail, error = error), app)
        }

        for ((index, step) in steps.withIndex()) {
            statuses[index] = step to Status.RUNNING
            report(index, 0f, "")
            val result = try {
                // Within a step: unpacking the GPU drivers takes the first part, packages the rest.
                var base = 0f
                if (step == Step.GPU) {
                    report(index, 0f, app.getString(R.string.setup_unpacking_gpu))
                    GpuDrivers.unpackIfNeeded(app)
                    base = 0.3f
                }
                val packages = packages(app, step, distro)
                if (packages.isNotEmpty()) {
                    if (!refreshed) {
                        refreshed = true
                        manager.refresh?.let { command -> runCommand(app, log, command) { _, line -> report(index, base, line) } }
                    }
                    val code = runCommand(app, log, manager.install(packages)) { fraction, line ->
                        report(index, base + (1 - base) * fraction, line)
                    }
                    if (code != 0) throw IOException(app.getString(R.string.setup_error_code, code))
                }
                if (!isDone(app, step)) throw IOException(app.getString(R.string.setup_error_missing, packages.joinToString(" ")))
                Status.DONE
            } catch (e: Exception) {
                Log.e(TAG, "setup step $step failed", e)
                if (error == null) error = e.message ?: e.javaClass.simpleName
                Status.FAILED
            }
            statuses[index] = step to result
        }

        File(rootfs, NOTE).delete()
        val final = State(statuses.toList(), running = false, progress = 1f, error = error)
        main.post {
            busy.set(false)
            state = final
            listeners.forEach { it(final) }
            SessionService.update(app)
            GpuDrivers.register(app)
            // Programs already open can use what is new (sound, X11) without restarting HandTop.
            if (TermSessions.linuxSessionCount() > 0 || LinuxApps.running.isNotEmpty()) LinuxEnv.startServices(app)
        }
    }

    private fun packages(context: Context, step: Step, distro: Distro): List<String> = when (step) {
        Step.GPU -> GpuDrivers.missingPackages(context)
        Step.X11 -> when (distro) {
            Distro.FEDORA -> listOf("xorg-x11-server-Xwayland")
            Distro.ALPINE -> listOf("xwayland", "xkeyboard-config") // Xwayland needs the layouts to start
            Distro.DEBIAN, Distro.UBUNTU -> listOf("xwayland")
        }
        Step.SOUND -> when (distro) {
            Distro.ALPINE -> listOf("pulseaudio", "pulseaudio-utils", "alsa-plugins-pulse", "alsa-utils")
            Distro.FEDORA -> listOf("pulseaudio", "pulseaudio-utils", "alsa-plugins-pulseaudio", "alsa-utils")
            Distro.DEBIAN, Distro.UBUNTU -> listOf("pulseaudio", "pulseaudio-utils", "libasound2-plugins", "alsa-utils")
        }
        Step.KEYS -> listOf(if (distro == Distro.FEDORA) "gnupg2" else "gpg")
    }

    /**
     * [command] in the distribution, without a terminal. Its output is appended to [log]; each
     * line goes to [onLine] with a guess of how far the command has got (0..1).
     */
    private fun runCommand(app: Context, log: File, command: String, onLine: (Float, String) -> Unit): Int {
        val spec = LinuxEnv.commandSpec(app, command)
        val process = ProcessBuilder(listOf(spec.executable) + spec.args.drop(1))
            .directory(File(spec.cwd))
            .redirectErrorStream(true)
            .apply { environment().apply { clear(); putAll(spec.env) } }
            .start()
        process.outputStream.close()
        val progress = OutputProgress()
        var lastReport = 0L
        FileWriter(log, true).buffered().use { out ->
            out.write("\n# $command\n")
            val line = StringBuilder()
            fun endLine() {
                val text = line.toString().trim()
                line.setLength(0)
                if (text.isEmpty()) return
                progress.line(text)
                // At most a few updates a second: the main thread has windows to draw.
                val now = SystemClock.uptimeMillis()
                if (now - lastReport >= 150) {
                    lastReport = now
                    onLine(progress.fraction, text)
                }
            }
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val c = reader.read()
                    if (c < 0) break
                    out.write(c)
                    if (c == '\n'.code || c == '\r'.code) endLine() else line.append(c.toChar())
                }
                endLine()
            }
        }
        return process.waitFor()
    }

    /** How a distribution installs packages. */
    private class PackageManager(
        /** Run once before the first installation of a run: a fresh image has no package lists. */
        val refresh: String?,
        val install: (List<String>) -> String,
    ) {
        companion object {
            private val APT = PackageManager(
                // A broken third-party repository must not stop the setup: the distribution's
                // own lists are refreshed all the same.
                refresh = "apt-get update -q || true",
                install = { packages ->
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends " +
                        "-o DPkg::Lock::Timeout=300 " + packages.joinToString(" ")
                },
            )
            private val DNF = PackageManager(null) { packages ->
                "dnf install -y --setopt=install_weak_deps=False " + packages.joinToString(" ")
            }
            private val APK = PackageManager(null) { packages -> "apk add " + packages.joinToString(" ") }

            fun of(distro: Distro) = when (distro) {
                Distro.DEBIAN, Distro.UBUNTU -> APT
                Distro.FEDORA -> DNF
                Distro.ALPINE -> APK
            }
        }
    }

    /**
     * Guesses how far a package manager has got from its output: apt says how many packages it
     * installs, then fetches, unpacks and sets up each; dnf and apk number their lines ("[3/32]",
     * "(3/17)"), dnf once for the downloads and once for the installation.
     */
    private class OutputProgress {
        var fraction = 0f
            private set
        private var aptTotal = 0
        private var aptEvents = 0
        private var downloads = false

        fun line(text: String) {
            APT_TOTAL.find(text)?.let { aptTotal = it.groupValues[1].toInt() }
            if (aptTotal > 0 && APT_EVENTS.any { text.startsWith(it) }) {
                aptEvents++
                advance(aptEvents / (3f * aptTotal))
            }
            COUNTER.find(text)?.let { match ->
                val total = match.groupValues[2].toFloat()
                if (total <= 0) return@let
                val part = match.groupValues[1].toFloat() / total
                if (INSTALLING.containsMatchIn(text)) {
                    advance(if (downloads) 0.5f + part / 2 else part)
                } else {
                    downloads = true
                    advance(part / 2)
                }
            }
        }

        private fun advance(value: Float) {
            fraction = maxOf(fraction, value.coerceAtMost(1f))
        }

        companion object {
            val APT_TOTAL = Regex("(\\d+) newly installed")
            val APT_EVENTS = listOf("Get:", "Unpacking ", "Setting up ")
            val COUNTER = Regex("[\\[(]\\s*(\\d+)/(\\d+)\\s*[\\])]")
            val INSTALLING = Regex("Installing|Upgrading|Reinstalling")
        }
    }

    private fun publish(newState: State, app: Context) {
        main.post {
            val runningChanged = newState.running != state.running || newState.position != state.position
            state = newState
            listeners.forEach { it(newState) }
            if (runningChanged) SessionService.update(app)
        }
    }

    private const val TAG = "HandTopSetup"
}
