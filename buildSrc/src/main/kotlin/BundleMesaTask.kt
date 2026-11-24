import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Puts HandTop's Mesa package (GPU drivers for Adreno, built by native/mesa/build-mesa.sh) into
 * the APK's assets, so the app installs it into Linux without downloading anything.
 *
 * Output: `<outputDirectory>/handtop-mesa.tar` and `handtop-mesa.version`. The tar goes in
 * uncompressed: the APK compresses its assets anyway (and the Android Gradle plugin would
 * unpack a .gz asset by itself).
 *
 * Mesa for Debian arm64 cannot be built here, so the package comes ready-made: from
 * `<prebuiltDirectory>` (a build made with the script, on a phone or by GitHub Actions), or
 * else downloaded from the project's release for that version. Either way its SHA-256 must
 * be [sha256]: a different build means updating [version] and [sha256] together.
 */
abstract class BundleMesaTask : DefaultTask() {

    /** Mesa version and package revision, e.g. 26.3.1-1 (VERSION in build-mesa.sh). */
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    /** Where a package made locally is looked for first (not in git). */
    @get:Internal
    abstract val prebuiltDirectory: DirectoryProperty

    /** Release download, used when there is no local package. */
    @get:Input
    abstract val releaseUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val name = "handtop-mesa-${version.get()}-debian13-arm64.tar.gz"
        val prebuilt = prebuiltDirectory.get().asFile.resolve(name)
        if (!prebuilt.isFile) download(releaseUrl.get().replace("{version}", version.get()) + "/" + name, prebuilt)
        val actual = sha256(prebuilt)
        if (actual != sha256.get()) {
            throw GradleException(
                "$prebuilt has SHA-256 $actual, expected ${sha256.get()}. " +
                    "A new Mesa build needs its version and SHA-256 updated in app/build.gradle.kts.",
            )
        }
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        GZIPInputStream(prebuilt.inputStream().buffered()).use { input ->
            out.resolve("handtop-mesa.tar").outputStream().use { input.copyTo(it) }
        }
        out.resolve("handtop-mesa.version").writeText(version.get() + "\n")
    }

    private fun download(url: String, target: File) {
        logger.lifecycle("Mesa package not in ${target.parentFile}: downloading $url")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            if (connection.responseCode != 200) {
                throw GradleException(
                    "Mesa package ${target.name} not found (HTTP ${connection.responseCode} from $url). " +
                        "Build it with native/mesa/build-mesa.sh and put it into ${target.parentFile}.",
                )
            }
            target.parentFile.mkdirs()
            val partial = File(target.path + ".part")
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            if (!partial.renameTo(target)) throw GradleException("cannot move $partial to $target")
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
