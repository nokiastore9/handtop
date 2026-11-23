import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Builds proot (termux fork, `native/proot`) and its loader for Android arm64 with plain
 * NDK clang — no make, no waf — so the project builds the same on Windows, Linux and macOS.
 *
 * Output: `<outputDirectory>/arm64-v8a/libproot.so` and `libproot-loader.so`. Both are
 * executables named like libraries: Android extracts them to nativeLibraryDir, the only
 * place an app targeting API 29+ may execve() from.
 *
 * Mirrors `native/proot/src/GNUmakefile`: the object list and the loader constants are read
 * from the vendored sources, so updating proot needs no change here.
 */
@CacheableTask
abstract class BuildProotTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prootSourceDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tallocSourceDir: DirectoryProperty

    /** NDK root. Tracked through [ndkVersion], not its machine-specific path. */
    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val minSdk: Property<Int>

    /** Reported by `proot --version`. */
    @get:Input
    abstract val prootVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val ndk = ndkDirectory.get().asFile
        if (!ndk.isDirectory) {
            throw GradleException("NDK ${ndkVersion.get()} not found at $ndk — install it with the SDK manager.")
        }
        val tc = Toolchain(ndk, minSdk.get())
        val src = prootSourceDir.get().asFile
        val talloc = tallocSourceDir.get().asFile
        val work = temporaryDir.apply { deleteRecursively(); mkdirs() }
        val out = outputDirectory.get().asFile.resolve(ABI).apply { deleteRecursively(); mkdirs() }

        // Constants proot's makefile reads from arch.h for the target architecture.
        val arch = tc.macros(src.resolve("arch.h"), listOf("-DNO_LIBC_HEADER"))
        val loaderAddress = arch["LOADER_ADDRESS"] ?: throw GradleException("LOADER_ADDRESS missing in arch.h")
        val hasPokedataWorkaround = arch["HAS_POKEDATA_WORKAROUND"] == "true"

        val common = listOf("-D_FILE_OFFSET_BITS=64", "-D_GNU_SOURCE", "-I", src.path, "-O2", "-Wall", "-Wextra")

        // 1. The loader: static, freestanding, linked at a fixed address.
        val loaderObjects = listOf("loader/loader.c", "loader/assembly.S").map { rel ->
            work.resolve("loader/" + rel.substringAfterLast('/') + ".o").also {
                tc.compile(src.resolve(rel), it, common + listOf("-fPIC", "-ffreestanding"))
            }
        }
        val loader = out.resolve("libproot-loader.so")
        tc.link(
            loaderObjects, loader,
            listOf("-static", "-nostdlib", "-Wl,--build-id=none,-Ttext=$loaderAddress,--rosegment,-z,noexecstack"),
        )

        // 2. Generated sources: build.h, loader-info.c and a forced-include config header.
        val gen = work.resolve("gen").apply { mkdirs() }
        gen.resolve("build.h").writeText(
            """
            |#ifndef BUILD_H
            |#define BUILD_H
            |#undef VERSION
            |#define VERSION "${prootVersion.get()}"
            |#define HAVE_PROCESS_VM
            |#define HAVE_SECCOMP_FILTER
            |#endif
            |""".trimMargin()
        )
        // The loader is never extracted to app data (W^X): proot finds it via $PROOT_LOADER.
        val config = gen.resolve("handtop-config.h").apply {
            writeText("#define PROOT_UNBUNDLE_LOADER \"/proc/self/handtop-loader-not-set\"\n")
        }
        val extraSources = mutableListOf<File>()
        if (hasPokedataWorkaround) {
            val symbols = tc.symbols(loader)
            val start = symbols["_start"] ?: throw GradleException("_start not found in loader")
            val workaround = symbols["pokedata_workaround"]
                ?: throw GradleException("pokedata_workaround not found in loader")
            extraSources += gen.resolve("loader-info.c").apply {
                writeText("#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=${workaround - start};\n")
            }
        }

        // 3. proot + talloc, compiled in parallel.
        val prootFlags = common + listOf("-I", gen.path, "-I", talloc.path, "-include", config.path)
        val units = objectList(src.resolve("GNUmakefile")).map { src.resolve(it.removeSuffix(".o") + ".c") } +
            extraSources
        fun objectFor(c: File): File {
            val rel = if (c.startsWith(src)) c.relativeTo(src).path else "gen/" + c.name
            return work.resolve("obj/" + rel.removeSuffix(".c") + ".o")
        }
        val jobs = units.map { it to objectFor(it) } +
            (talloc.resolve("talloc.c") to work.resolve("obj/talloc.o"))
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtMost(16))
        try {
            jobs.map { (c, o) ->
                val flags = if (c.parentFile == talloc) listOf("-I", talloc.path, "-O2", "-fPIC", "-D_GNU_SOURCE") else prootFlags
                pool.submit(Callable { tc.compile(c, o, flags) })
            }.forEach { it.get() }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            pool.shutdown()
        }
        tc.link(jobs.map { it.second }, out.resolve("libproot.so"), listOf("-Wl,-z,noexecstack"))
    }

    /** Object files listed in the first `OBJECTS += \` block of proot's GNUmakefile. */
    private fun objectList(makefile: File): List<String> {
        val text = makefile.readText().replace("\r\n", "\n")
        val start = text.indexOf("OBJECTS += \\")
        if (start < 0) throw GradleException("OBJECTS block not found in $makefile")
        val block = text.substring(start, text.indexOf("\n\n", start))
        return Regex("""(\S+\.o)""").findAll(block).map { it.value }.toList()
    }

    private class Toolchain(ndk: File, private val api: Int) {
        private val windows = System.getProperty("os.name").startsWith("Windows")
        private val host = when {
            windows -> "windows-x86_64"
            System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        private val bin = ndk.resolve("toolchains/llvm/prebuilt/$host/bin")
        private val ext = if (windows) ".exe" else ""
        private val clang = bin.resolve("clang$ext").path
        private val readelf = bin.resolve("llvm-readelf$ext").path
        private val target = "--target=aarch64-linux-android$api"

        fun compile(source: File, output: File, flags: List<String>) {
            output.parentFile.mkdirs()
            run(listOf(clang, target) + flags + listOf("-c", source.path, "-o", output.path))
        }

        fun link(objects: List<File>, output: File, flags: List<String>) {
            // Response file: the object list can exceed the Windows command-line limit.
            val rsp = File(output.parentFile.parentFile, output.name + ".rsp")
            rsp.writeText(objects.joinToString("\n") { "\"" + it.path.replace('\\', '/') + "\"" })
            try {
                run(listOf(clang, target, "-o", output.path, "@" + rsp.path) + flags)
            } finally {
                rsp.delete()
            }
        }

        /** `#define NAME VALUE` pairs the preprocessor sees in [header]. */
        fun macros(header: File, flags: List<String>): Map<String, String> =
            run(listOf(clang, target, "-E", "-dM") + flags + header.path).lineSequence()
                .mapNotNull { Regex("""#define (\w+) (.+)""").matchEntire(it.trim()) }
                .associate { it.groupValues[1] to it.groupValues[2].trim() }

        /** Symbol name -> address, from `llvm-readelf -s`. */
        fun symbols(elf: File): Map<String, Long> =
            run(listOf(readelf, "-s", elf.path)).lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size >= 8 && it[0].matches(Regex("""\d+:""")) }
                .associate { it[7] to it[1].toLong(16) }

        private fun run(command: List<String>): String {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                throw GradleException("Command failed:\n${command.joinToString(" ")}\n$output")
            }
            return output
        }
    }

    private companion object {
        const val ABI = "arm64-v8a"
    }
}
