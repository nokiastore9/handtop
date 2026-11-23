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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.GZIPInputStream

/**
 * Downloads the official Debian image from Docker Hub, verifies it and unpacks it into
 * [LinuxEnv.rootfs]. Runs on its own thread; the UI observes [state].
 */
object LinuxInstaller {

    enum class Phase { IDLE, RESOLVING, DOWNLOADING, EXTRACTING, CONFIGURING, DONE, FAILED }

    data class State(
        val phase: Phase,
        val done: Long = 0,
        val total: Long = 0,
        val error: String? = null,
    )

    @Volatile
    var state = State(Phase.IDLE)
        private set

    val isRunning: Boolean
        get() = state.phase in setOf(Phase.RESOLVING, Phase.DOWNLOADING, Phase.EXTRACTING, Phase.CONFIGURING)

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun start(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        publish(State(Phase.RESOLVING), app)
        Thread({
            try {
                install(app)
                publish(State(Phase.DONE), app)
            } catch (e: Exception) {
                Log.e(TAG, "Linux install failed", e)
                publish(State(Phase.FAILED, error = e.message ?: e.javaClass.simpleName), app)
            }
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

    private fun install(app: Context) {
        val registry = DockerRegistry(LinuxEnv.IMAGE_REPOSITORY)
        val layers = registry.layers(LinuxEnv.IMAGE_TAG)
        if (layers.size != 1) throw IOException("Expected a single-layer image, got ${layers.size} layers")
        val layer = layers.single()

        // 1. Download to the cache, checking the sha256 digest on the way.
        val archive = File(app.cacheDir, "rootfs-layer.tar.gz")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = registry.openBlob(layer.digest)
        try {
            connection.inputStream.use { input ->
                archive.outputStream().use { out ->
                    val buffer = ByteArray(128 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            lastReport = done
                            publish(State(Phase.DOWNLOADING, done, layer.size), app)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val actual = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != layer.digest) {
            archive.delete()
            throw IOException("Checksum mismatch: expected ${layer.digest}, got $actual")
        }

        // 2. Unpack into a fresh root filesystem.
        val rootfs = LinuxEnv.rootfs(app)
        deleteTree(rootfs)
        rootfs.mkdirs()
        val total = archive.length()
        CountingInputStream(BufferedInputStream(FileInputStream(archive), 256 * 1024)).use { counted ->
            var lastReport = 0L
            TarExtractor(rootfs) {
                if (counted.count - lastReport > 256 * 1024) {
                    lastReport = counted.count
                    publish(State(Phase.EXTRACTING, counted.count, total), app)
                }
            }.extract(GZIPInputStream(counted, 64 * 1024))
        }
        archive.delete()

        // 3. Make it usable on Android.
        publish(State(Phase.CONFIGURING), app)
        configure(rootfs)
        LinuxEnv.marker(app).writeText("${LinuxEnv.IMAGE_REPOSITORY}:${LinuxEnv.IMAGE_TAG}\n${layer.digest}\n")
    }

    /** Fixes a container image needs to run under proot on Android. */
    private fun configure(rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        // Containers get these from the runtime; replace possible symlinks with real files.
        writeFile(File(etc, "resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        writeFile(
            File(etc, "hosts"),
            "127.0.0.1   localhost.localdomain localhost\n" +
                "::1         localhost.localdomain localhost ip6-localhost ip6-loopback\n",
        )
        writeFile(File(etc, "hostname"), "handtop\n")
        // World-writable sticky /tmp, also bound as /dev/shm.
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        Os.chmod(tmp.path, 0x3FF) // 01777
    }

    private fun writeFile(file: File, text: String) {
        Files.deleteIfExists(file.toPath()) // does not follow symlinks
        file.writeText(text)
    }

    /** Recursive delete that never follows symbolic links out of the tree. */
    private fun deleteTree(dir: File) {
        if (!dir.exists() && !Files.isSymbolicLink(dir.toPath())) return
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                Files.delete(d)
                return FileVisitResult.CONTINUE
            }
        })
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
