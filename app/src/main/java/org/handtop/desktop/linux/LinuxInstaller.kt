package org.handtop.desktop.linux

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import org.handtop.desktop.service.SessionService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.GZIPInputStream

/**
 * Downloads an official Linux image from Docker Hub, verifies it and unpacks it into
 * [LinuxEnv.rootfs]; also removes it. Runs on its own thread; the UI observes [state].
 */
object LinuxInstaller {

    enum class Phase { IDLE, RESOLVING, DOWNLOADING, EXTRACTING, CONFIGURING, REMOVING, DONE, FAILED }

    data class State(
        val phase: Phase,
        val distro: Distro? = null,
        val done: Long = 0,
        val total: Long = 0,
        val error: String? = null,
    )

    @Volatile
    var state = State(Phase.IDLE)
        private set

    val isRunning: Boolean
        get() = state.phase in setOf(Phase.RESOLVING, Phase.DOWNLOADING, Phase.EXTRACTING, Phase.CONFIGURING, Phase.REMOVING)

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    /** Installs [distro], replacing whatever is installed. Linux terminals must be closed. */
    fun install(context: Context, distro: Distro) = runJob(context, State(Phase.RESOLVING, distro)) { app ->
        download(app, distro)
        State(Phase.DONE, distro)
    }

    /** Deletes the root filesystem. Linux terminals must be closed. */
    fun uninstall(context: Context) = runJob(context, State(Phase.REMOVING)) { app ->
        FileTrees.delete(LinuxEnv.rootfs(app))
        State(Phase.IDLE)
    }

    private fun runJob(context: Context, first: State, job: (Context) -> State) {
        if (isRunning) return
        val app = context.applicationContext
        publish(first, app)
        Thread({
            val result = try {
                job(app)
            } catch (e: Exception) {
                Log.e(TAG, "Linux job failed", e)
                State(Phase.FAILED, first.distro, error = e.message ?: e.javaClass.simpleName)
            }
            publish(result, app)
        }, "LinuxInstaller").start()
    }

    private fun publish(newState: State, app: Context) {
        main.post {
            val phaseChanged = newState.phase != state.phase
            state = newState
            listeners.forEach { it(newState) }
            if (phaseChanged) SessionService.update(app)
        }
    }

    /** Downloads, verifies, unpacks and configures [distro] into the root filesystem. */
    private fun download(app: Context, distro: Distro) {
        val registry = DockerRegistry(distro.repository)
        val layers = registry.layers(distro.tag)
        val total = layers.sumOf { it.size }

        // 1. Download every layer to the cache, checking sha256 digests on the way.
        val archives = ArrayList<File>()
        var downloaded = 0L
        layers.forEachIndexed { index, layer ->
            val archive = File(app.cacheDir, "rootfs-layer-$index.tar.gz")
            archives += archive
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = registry.openBlob(layer.digest)
            try {
                connection.inputStream.use { input ->
                    archive.outputStream().use { out ->
                        val buffer = ByteArray(128 * 1024)
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            downloaded += n
                            if (downloaded - lastReport > 256 * 1024) {
                                lastReport = downloaded
                                publish(State(Phase.DOWNLOADING, distro, downloaded, total), app)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val actual = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != layer.digest) {
                archives.forEach { it.delete() }
                throw IOException("Checksum mismatch: expected ${layer.digest}, got $actual")
            }
        }

        // 2. Unpack the layers, bottom to top, next to the current system: it is only
        //    replaced once the new one is complete, so a failed install loses nothing.
        val rootfs = File(LinuxEnv.rootfs(app).path + ".new")
        FileTrees.delete(rootfs)
        rootfs.mkdirs()
        val archiveTotal = archives.sumOf { it.length() }
        var unpackedBefore = 0L
        for (archive in archives) {
            CountingInputStream(BufferedInputStream(FileInputStream(archive), 256 * 1024)).use { counted ->
                var lastReport = 0L
                TarExtractor(rootfs) {
                    if (counted.count - lastReport > 256 * 1024) {
                        lastReport = counted.count
                        publish(State(Phase.EXTRACTING, distro, unpackedBefore + counted.count, archiveTotal), app)
                    }
                }.extract(GZIPInputStream(counted, 64 * 1024))
            }
            unpackedBefore += archive.length()
            archive.delete()
        }

        // 3. Make it usable on Android, then swap it in.
        publish(State(Phase.CONFIGURING, distro), app)
        configure(rootfs)
        val current = LinuxEnv.rootfs(app)
        FileTrees.delete(current)
        if (!rootfs.renameTo(current)) throw IOException("Cannot move $rootfs to $current")
        LinuxEnv.writeMarker(app, distro, layers.map { it.digest })
    }

    /** Fixes a container image needs to run under proot on Android. */
    private fun configure(rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val host = LinuxEnv.HOSTNAME
        // Containers get these from the runtime; replace possible symlinks with real files.
        writeFile(File(etc, "resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        writeFile(
            File(etc, "hosts"),
            "127.0.0.1   localhost.localdomain localhost\n" +
                "127.0.1.1   $host\n" +
                "::1         localhost.localdomain localhost ip6-localhost ip6-loopback\n",
        )
        writeFile(File(etc, "hostname"), "$host\n")
        // World-writable sticky /tmp, also bound as /dev/shm.
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        Os.chmod(tmp.path, 0x3FF) // 01777
    }

    private fun writeFile(file: File, text: String) {
        Files.deleteIfExists(file.toPath()) // does not follow symlinks
        file.writeText(text)
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    private const val TAG = "LinuxInstaller"
}
