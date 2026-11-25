package org.handtop.desktop.linux

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import org.handtop.desktop.service.SessionService
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * GPU acceleration for Linux programs on Snapdragon phones: HandTop's Mesa build (see
 * native/mesa). Turnip, the Vulkan driver for Adreno, talks to KGSL, the Android kernel's GPU
 * driver, and Zink provides OpenGL on top of Vulkan.
 *
 * How it is installed: the APK carries one package per C library (assets put there by the
 * bundleMesa build tasks, which check their SHA-256): glibc, built on Debian 13, whose glibc is
 * the oldest of the glibc distributions HandTop offers, so it also runs on Ubuntu and Fedora;
 * and musl, built on Alpine. The one that fits is unpacked into /opt/handtop-mesa of the
 * distribution; nothing is downloaded except, when missing, a few of the distribution's own
 * libraries ([Target]).
 *
 * How it is used: while acceleration is on, HandTop registers the package with the Vulkan
 * loader and, for OpenGL, with glvnd (glibc) or as the first directory of the musl loader's
 * search path (Alpine has no glvnd). Programs also get a few variables ([environment]).
 * Switched off, the distribution's own Mesa (llvmpipe, on the processor) draws as before.
 *
 * Where it applies: HandTop installs the package by itself where it can work, which means
 * Adreno (the KGSL device is open to apps) and a distribution with a [Target]. Other phones
 * keep software rendering.
 */
object GpuDrivers {

    private fun packageAsset(libc: String) = "handtop-mesa-$libc.tar"
    private fun versionAsset(libc: String) = "handtop-mesa-$libc.version"

    private const val PREFIX = "opt/handtop-mesa"
    private val KGSL = File("/dev/kgsl-3d0")
    private val GPU_MODEL = File("/sys/class/kgsl/kgsl-3d0/gpu_model")

    // What HandTop adds to the distribution while acceleration is on (paths in the rootfs).
    private const val EGL_VENDOR = "usr/share/glvnd/egl_vendor.d/10_handtop-mesa.json"
    private const val VULKAN_ICD = "usr/share/vulkan/icd.d/handtop-freedreno.aarch64.json"
    private const val GLX_VENDOR = "libGLX_handtop.so.0" // in the distribution's library directory
    /** The musl loader's search path; without the file, it is [MUSL_DEFAULT_PATH]. */
    private const val MUSL_PATH = "etc/ld-musl-aarch64.path"
    private const val MUSL_DEFAULT_PATH = "/lib\n/usr/local/lib\n/usr/lib\n"
    private const val MUSL_OURS = "/$PREFIX/lib"

    /**
     * What acceleration needs from a distribution: where its libraries live, the packages the
     * Mesa build links to (plus glvnd and the Vulkan loader that load it), each with a library
     * that shows the package is installed, and how to install them.
     */
    private class Target(
        /** Which package of the APK runs there: glibc or musl. */
        val libc: String,
        val libDir: String,
        val libraries: Map<String, String>,
        val install: (List<String>) -> String,
    ) {
        val glvnd get() = libc == "glibc"
    }

    private val DEBIAN_LIKE = Target(
        libc = "glibc",
        libDir = "usr/lib/aarch64-linux-gnu",
        libraries = linkedMapOf(
            "libvulkan1" to "libvulkan.so.1",
            "libegl1" to "libEGL.so.1",
            "libgl1" to "libGL.so.1",
            "libglx0" to "libGLX.so.0",
            "libgles2" to "libGLESv2.so.2",
            "libwayland-client0" to "libwayland-client.so.0",
            "libx11-6" to "libX11.so.6",
            "libx11-xcb1" to "libX11-xcb.so.1",
            "libxext6" to "libXext.so.6",
            "libxxf86vm1" to "libXxf86vm.so.1",
            "libxcb1" to "libxcb.so.1",
            "libxcb-dri3-0" to "libxcb-dri3.so.0",
            "libxcb-glx0" to "libxcb-glx.so.0",
            "libxcb-keysyms1" to "libxcb-keysyms.so.1",
            "libxcb-present0" to "libxcb-present.so.0",
            "libxcb-randr0" to "libxcb-randr.so.0",
            "libxcb-shm0" to "libxcb-shm.so.0",
            "libxcb-sync1" to "libxcb-sync.so.1",
            "libxcb-xfixes0" to "libxcb-xfixes.so.0",
            "libxshmfence1" to "libxshmfence.so.1",
            "libexpat1" to "libexpat.so.1",
            "zlib1g" to "libz.so.1",
            "libzstd1" to "libzstd.so.1",
        ),
        install = { packages ->
            "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update -q || true; " + // a broken third-party repository must not stop it
                "apt-get install -y -q --no-install-recommends -o DPkg::Lock::Timeout=300 " +
                packages.joinToString(" ")
        },
    )

    private val FEDORA = Target(
        libc = "glibc",
        libDir = "usr/lib64",
        libraries = linkedMapOf(
            "vulkan-loader" to "libvulkan.so.1",
            "libglvnd-egl" to "libEGL.so.1",
            "libglvnd-glx" to "libGLX.so.0",
            "libglvnd-opengl" to "libOpenGL.so.0",
            "libglvnd-gles" to "libGLESv2.so.2",
            "libwayland-client" to "libwayland-client.so.0",
            "libX11" to "libX11.so.6",
            "libX11-xcb" to "libX11-xcb.so.1",
            "libXext" to "libXext.so.6",
            "libXxf86vm" to "libXxf86vm.so.1",
            "libxcb" to "libxcb.so.1",
            "xcb-util-keysyms" to "libxcb-keysyms.so.1",
            "libxshmfence" to "libxshmfence.so.1",
            "expat" to "libexpat.so.1",
            "zlib-ng-compat" to "libz.so.1",
            "libzstd" to "libzstd.so.1",
        ),
        install = { packages -> "dnf install -y --setopt=install_weak_deps=False " + packages.joinToString(" ") },
    )

    private val ALPINE = Target(
        libc = "musl",
        libDir = "usr/lib",
        libraries = linkedMapOf(
            "vulkan-loader" to "libvulkan.so.1",
            "wayland-libs-client" to "libwayland-client.so.0",
            "libx11" to "libX11.so.6",
            "libxext" to "libXext.so.6",
            "libxxf86vm" to "libXxf86vm.so.1",
            "libxcb" to "libxcb.so.1",
            "xcb-util-keysyms" to "libxcb-keysyms.so.1",
            "libxshmfence" to "libxshmfence.so.1",
            "libexpat" to "libexpat.so.1",
            "zlib" to "libz.so.1",
            "zstd-libs" to "libzstd.so.1",
            "libstdc++" to "libstdc++.so.6",
            "libgcc" to "libgcc_s.so.1",
        ),
        install = { packages -> "apk add " + packages.joinToString(" ") },
    )

    private fun target(context: Context): Target? = when (LinuxEnv.installedDistro(context)) {
        Distro.DEBIAN, Distro.UBUNTU -> DEBIAN_LIKE
        Distro.FEDORA -> FEDORA
        Distro.ALPINE -> ALPINE
        null -> null
    }

    // ---- what the settings show ----

    enum class Phase { IDLE, UNPACKING, LIBRARIES, DONE, FAILED }

    data class State(val phase: Phase, val done: Long = 0, val total: Long = 0, val error: String? = null)

    @Volatile
    var state = State(Phase.IDLE)
        private set

    val isRunning get() = state.phase in setOf(Phase.UNPACKING, Phase.LIBRARIES)

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    private val bundledVersions = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Mesa version and package revision (e.g. 26.3.1-2) of the APK's package for the installed
     * distribution, or null (no fitting distribution, or an APK built without the package).
     */
    fun version(context: Context): String? = target(context)?.let { bundledVersion(context, it.libc) }

    private fun bundledVersion(context: Context, libc: String): String? = bundledVersions[libc] ?: try {
        context.assets.open(versionAsset(libc)).bufferedReader().use { it.readText().trim() }
            .also { bundledVersions[libc] = it }
    } catch (_: IOException) {
        null
    }

    /** This phone has an Adreno GPU that apps may use. */
    fun hasAdreno() = KGSL.canRead() && KGSL.canWrite()

    /** The package runs on the installed distribution. */
    fun fitsDistro(context: Context) = target(context) != null

    /** "Adreno 750", or just "Adreno" when the kernel does not say. */
    fun gpuName(): String {
        val model = try {
            GPU_MODEL.readText().trim()
        } catch (_: Exception) {
            ""
        }
        return Regex("Adreno\\D*(\\d+)").find(model)?.let { "Adreno ${it.groupValues[1]}" } ?: "Adreno"
    }

    fun isInstalled(context: Context) = version(context)?.let { installedVersion(LinuxEnv.rootfs(context)) == it } == true

    /** The user's choice; on unless switched off in the settings. */
    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        register(context)
        if (enabled) installIfNeeded(context)
    }

    /** Linux programs started now draw on the GPU. */
    fun isActive(context: Context): Boolean {
        val rootfs = LinuxEnv.rootfs(context)
        val version = version(context) ?: return false
        val target = target(context) ?: return false
        return hasAdreno() && isEnabled(context) &&
            installedVersion(rootfs) == version && missingPackages(rootfs, target).isEmpty()
    }

    /** Variables for every Linux program while acceleration is on. */
    fun environment(context: Context): List<String> = if (!isActive(context)) emptyList() else listOf(
        // OpenGL through Zink (Mesa would also pick it by itself; this makes it explicit).
        "MESA_LOADER_DRIVER_OVERRIDE=zink",
        // X11 programs: Xwayland under proot has no DRI3, and GLX then refuses Zink without this.
        "LIBGL_KOPPER_DRI2=1",
        "__GLX_VENDOR_LIBRARY_NAME=handtop",
        // HandTop takes frames through shared memory (no dmabuf yet); Vulkan presents that way.
        "MESA_VK_WSI_DEBUG=sw",
    )

    /**
     * Puts the package's registration into the distribution (or takes it out) to match
     * [isActive]. Called before every Linux program starts; cheap when nothing changes.
     */
    fun register(context: Context) {
        val rootfs = LinuxEnv.rootfs(context)
        if (!rootfs.isDirectory) return
        val active = isActive(context)
        val target = target(context) ?: DEBIAN_LIKE
        // The library directory of the installed distribution (the GLX vendor library goes there).
        val glx = File(rootfs, "${target.libDir}/$GLX_VENDOR")
        try {
            if (active) {
                copyIfDifferent(File(rootfs, "$PREFIX/share/vulkan/icd.d/freedreno_icd.aarch64.json"), File(rootfs, VULKAN_ICD))
            }
            if (active && !target.glvnd) {
                setMuslPath(rootfs, true)
            } else if (active) {
                copyIfDifferent(File(rootfs, "$PREFIX/share/glvnd/egl_vendor.d/10_handtop-mesa.json"), File(rootfs, EGL_VENDOR))
                val mesaGlx = "/$PREFIX/lib/libGLX_mesa.so.0"
                if (readLink(glx) != mesaGlx) {
                    glx.delete()
                    glx.parentFile?.mkdirs()
                    Os.symlink(mesaGlx, glx.path)
                }
            } else {
                File(rootfs, EGL_VENDOR).delete()
                File(rootfs, VULKAN_ICD).delete()
                glx.delete()
                setMuslPath(rootfs, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot register the GPU drivers", e)
        }
    }

    /**
     * Starts the installation when acceleration applies, is wanted and is not there yet: from
     * the start of a Linux session, so a fresh system gets it without the user doing anything.
     * A failure is not retried by itself until HandTop restarts (the settings offer a retry).
     */
    fun installIfNeeded(context: Context) {
        if (isRunning || state.phase == Phase.FAILED) return
        val version = version(context) ?: return
        val target = target(context) ?: return
        if (!hasAdreno() || !isEnabled(context)) return
        val rootfs = LinuxEnv.rootfs(context)
        if (installedVersion(rootfs) == version && missingPackages(rootfs, target).isEmpty()) return
        install(context)
    }

    /** Unpacks the package from the APK, then installs the libraries it needs. */
    fun install(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        publish(State(Phase.UNPACKING), app)
        Thread({
            val result = try {
                installNow(app)
                State(Phase.DONE)
            } catch (e: Exception) {
                Log.e(TAG, "GPU drivers not installed", e)
                State(Phase.FAILED, error = e.message ?: e.javaClass.simpleName)
            }
            publish(result, app)
            main.post { register(app) }
        }, "GpuDrivers").start()
    }

    private fun installNow(app: Context) {
        val rootfs = LinuxEnv.rootfs(app)
        val version = version(app) ?: throw IOException("this HandTop build has no GPU drivers")
        val target = target(app) ?: throw IOException("no GPU drivers for this distribution")
        if (installedVersion(rootfs) != version) unpack(app, rootfs, target.libc, version)
        val missing = missingPackages(rootfs, target)
        if (missing.isNotEmpty()) {
            publish(State(Phase.LIBRARIES), app)
            installPackages(app, rootfs, target.install(missing))
            val still = missingPackages(rootfs, target)
            if (still.isNotEmpty()) throw IOException("not installed: ${still.joinToString(" ")}")
        }
    }

    /** Unpacks next to the current version and swaps it in once complete. */
    private fun unpack(app: Context, rootfs: File, libc: String, version: String) {
        val staging = File(rootfs, "opt/.handtop-mesa.new")
        FileTrees.delete(staging)
        staging.mkdirs()
        try {
            BufferedInputStream(app.assets.open(packageAsset(libc)), 256 * 1024).use { input ->
                TarExtractor(staging) {}.extract(input)
            }
            val unpacked = File(staging, "handtop-mesa")
            if (installedVersion(staging, "handtop-mesa") != version) {
                throw IOException("the package is not Mesa $version")
            }
            val target = File(rootfs, PREFIX)
            FileTrees.delete(target)
            if (!unpacked.renameTo(target)) throw IOException("cannot move $unpacked to $target")
        } finally {
            FileTrees.delete(staging)
        }
    }

    /** The package manager in the distribution, without a terminal; its output goes to /tmp. */
    private fun installPackages(app: Context, rootfs: File, command: String) {
        val spec = LinuxEnv.commandSpec(app, command)
        val log = File(rootfs, "tmp/handtop-gpu-install.log")
        val process = ProcessBuilder(listOf(spec.executable) + spec.args.drop(1))
            .directory(File(spec.cwd))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log))
            .apply { environment().apply { clear(); putAll(spec.env) } }
            .start()
        val code = process.waitFor()
        if (code != 0) throw IOException("package installation failed ($code), see /tmp/handtop-gpu-install.log")
    }

    private fun missingPackages(rootfs: File, target: Target): List<String> =
        target.libraries.filter { (_, lib) ->
            !File(rootfs, "${target.libDir}/$lib").exists() && !File(rootfs, "lib/$lib").exists()
        }.keys.toList()

    /**
     * Puts HandTop's Mesa first in the musl loader's search path (or takes it out again), keeping
     * whatever else the file lists; a file that only held the defaults is removed.
     */
    private fun setMuslPath(rootfs: File, on: Boolean) {
        val file = File(rootfs, MUSL_PATH)
        val old = if (file.isFile) file.readText() else null
        val others = (old ?: MUSL_DEFAULT_PATH).lines().filter { it.isNotBlank() && it != MUSL_OURS }
            .joinToString("\n", postfix = "\n")
        when {
            on -> {
                val new = "$MUSL_OURS\n$others"
                if (new != old) file.writeText(new)
            }
            old == null -> Unit
            others == MUSL_DEFAULT_PATH -> file.delete()
            others != old -> file.writeText(others)
        }
    }

    private fun installedVersion(rootfs: File, dir: String = PREFIX): String? = try {
        File(rootfs, "$dir/VERSION").takeIf { it.isFile }?.readText()?.trim()
    } catch (_: IOException) {
        null
    }

    private fun copyIfDifferent(from: File, to: File) {
        val bytes = from.readBytes()
        if (to.isFile && to.readBytes().contentEquals(bytes)) return
        to.parentFile?.mkdirs()
        to.writeBytes(bytes)
    }

    private fun readLink(file: File): String? = try {
        Os.readlink(file.path)
    } catch (_: Exception) {
        null
    }

    private fun publish(newState: State, app: Context) {
        main.post {
            val phaseChanged = newState.phase != state.phase
            state = newState
            listeners.forEach { it(newState) }
            if (phaseChanged) SessionService.update(app)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "HandTopGpu"
    private const val PREFS = "gpu"
    private const val KEY_ENABLED = "enabled"
}
