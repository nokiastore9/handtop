package org.handtop.desktop.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import org.handtop.desktop.linux.LinuxEnv
import org.handtop.desktop.wayland.WaylandServer
import java.io.File
import java.io.IOException

/**
 * Linux sound → Android. PulseAudio runs inside the distribution with a pipe sink: it writes
 * the mixed output (PCM, 48 kHz, 16-bit, stereo) into a FIFO, and this bridge plays what it
 * reads through an AudioTrack. Programs using PulseAudio, or ALSA through the pulse plugin,
 * are heard on the phone (or DeX).
 */
object SoundBridge {

    private const val TAG = "HandTopSound"
    const val RATE = 48000
    private const val FRAME = 4 // 16-bit stereo
    private const val IDLE_MS = 1000
    private const val FIFO_NAME = "handtop-audio"
    private const val CONFIG = "etc/handtop/pulse.pa"
    private val ALSA_PLUGIN_DIRS = listOf("usr/lib/aarch64-linux-gnu/alsa-lib", "usr/lib64/alsa-lib", "usr/lib/alsa-lib")

    private var pulse: Process? = null
    private var reader: Thread? = null

    /** PulseAudio is installed in the distribution. */
    fun isAvailable(context: Context) = File(LinuxEnv.rootfs(context), "usr/bin/pulseaudio").exists()

    val isRunning get() = pulse?.isAlive == true

    /** Starts PulseAudio and the player once (no-op when running or not installed). */
    @Synchronized
    fun ensureRunning(context: Context) {
        if (isRunning || !isAvailable(context)) return
        val rootfs = LinuxEnv.rootfs(context)
        writeConfig(rootfs)
        val runtime = "/${WaylandServer.RUNTIME_DIR}"
        val spec = LinuxEnv.commandSpec(
            context,
            "rm -f $runtime/$FIFO_NAME; mkdir -p $runtime/pulse; exec pulseaudio --daemonize=no --exit-idle-time=-1 --disallow-exit " +
                "--disable-shm=yes --use-pid-file=no -n -F /$CONFIG",
        )
        val log = File(rootfs, "tmp/handtop-pulse.log")
        val process = try {
            ProcessBuilder(listOf(spec.executable) + spec.args.drop(1))
                .directory(File(spec.cwd))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log))
                .apply { environment().apply { clear(); putAll(spec.env) } }
                .start()
        } catch (e: IOException) {
            Log.w(TAG, "cannot start PulseAudio", e)
            return
        }
        pulse = process
        Log.i(TAG, "PulseAudio started")
        if (reader?.isAlive != true) {
            val fifo = File(rootfs, "${WaylandServer.RUNTIME_DIR}/$FIFO_NAME")
            reader = Thread({ playLoop(fifo) }, "handtop-sound").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun writeConfig(rootfs: File) {
        val runtime = "/${WaylandServer.RUNTIME_DIR}"
        File(rootfs, CONFIG).apply {
            parentFile?.mkdirs()
            writeText(
                """
                # Written by HandTop: Linux sound goes to Android through a FIFO.
                .fail
                load-module module-pipe-sink file=$runtime/$FIFO_NAME sink_name=handtop format=s16le rate=$RATE channels=2 sink_properties=device.description=HandTop
                set-default-sink handtop
                load-module module-native-protocol-unix auth-anonymous=1 socket=$runtime/pulse/native
                .nofail
                load-module module-stream-restore
                load-module module-default-device-restore
                # Silence is not written to the pipe: Android's audio output can sleep.
                load-module module-suspend-on-idle timeout=3
                """.trimIndent() + "\n",
            )
        }
        // ALSA programs (aplay, SDL with ALSA…) through PulseAudio, when the plugin is there
        // (Debian / Ubuntu, Fedora and Alpine keep it in different places).
        val plugin = ALSA_PLUGIN_DIRS.any { File(rootfs, "$it/libasound_module_pcm_pulse.so").exists() }
        val asound = File(rootfs, "etc/asound.conf")
        if (plugin && !asound.exists()) {
            asound.writeText("# Written by HandTop: ALSA plays through PulseAudio.\npcm.!default { type pulse }\nctl.!default { type pulse }\n")
        }
    }

    /**
     * Stops PulseAudio: before the distribution it runs from is replaced or removed. The
     * player keeps waiting for the FIFO, which the next PulseAudio creates again.
     */
    @Synchronized
    fun stop() {
        pulse?.destroy() // proot ignores it: LinuxEnv.stopServices kills the Linux processes
        pulse = null
    }

    /** Reads the FIFO and plays it; reopens it whenever PulseAudio restarts. */
    private fun playLoop(fifo: File) {
        val buffer = ByteArray(RATE / 50 * 4) // 20 ms
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(
                maxOf(
                    AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT),
                    RATE / 10 * 4, // 100 ms
                ),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        while (true) {
            if (!fifo.exists()) {
                Thread.sleep(500)
                continue
            }
            try {
                pump(fifo, buffer, track)
            } catch (e: ErrnoException) {
                Log.w(TAG, "sound pipe: ${e.message}")
            }
            track.pause()
            track.flush()
            Thread.sleep(500)
        }
    }

    /**
     * Plays the FIFO until PulseAudio goes away. The track is paused while nothing comes
     * (PulseAudio suspends an idle sink), so Android's audio output can sleep.
     */
    private fun pump(fifo: File, buffer: ByteArray, track: AudioTrack) {
        // Non-blocking: opening a FIFO for reading would otherwise wait for a writer forever.
        val fd = Os.open(fifo.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
        try {
            val inode = Os.fstat(fd).st_ino
            val poll = StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            }
            var playing = false
            var carry = 0 // bytes of an incomplete frame, kept for the next read
            while (true) {
                if (Os.poll(arrayOf(poll), if (playing) IDLE_MS else 1000) == 0) {
                    if (playing) {
                        track.pause()
                        playing = false
                        Log.i(TAG, "idle; ${track.playbackHeadPosition} frames played so far")
                    } else if (!fifo.exists() || Os.stat(fifo.path).st_ino != inode) {
                        return // PulseAudio restarted with a new pipe
                    }
                    continue
                }
                val n = try {
                    Os.read(fd, buffer, carry, buffer.size - carry)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN) continue else throw e
                }
                if (n == 0) return // no writer: PulseAudio is gone
                if (!playing) {
                    track.play()
                    playing = true
                    Log.i(TAG, "playing")
                }
                val total = carry + n
                val whole = total - total % FRAME
                if (whole > 0) track.write(buffer, 0, whole)
                carry = total - whole
                if (carry > 0) System.arraycopy(buffer, whole, buffer, 0, carry)
            }
        } finally {
            Os.close(fd)
        }
    }
}
