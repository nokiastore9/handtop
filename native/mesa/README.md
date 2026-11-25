# Mesa for Adreno (Turnip + Zink over KGSL)

GPU acceleration for HandTop's Linux programs on Snapdragon phones:

- **Turnip** is Mesa's Vulkan driver for Adreno. Here it talks to **KGSL**, the GPU driver of
  Android kernels (`/dev/kgsl-3d0`, open to every app, no root needed).
- **Zink** provides OpenGL 4.6 and OpenGL ES 3.2 on top of Vulkan.

Mesa supports this pairing without DRM (`pseudo-drm`). The distributions' Mesa is built
without KGSL, so HandTop builds its own, once per C library:

| Package | Built on | Runs on | OpenGL registered through |
|---|---|---|---|
| `glibc` | Debian 13 (the oldest glibc of HandTop's distributions) | Debian 13, Ubuntu 26.04, Fedora 45 | glvnd, next to the distribution's Mesa |
| `musl` | Alpine 3.24 | Alpine | first directory of the musl loader's path (Alpine has no glvnd) |

Both packages go into the APK, and HandTop installs the fitting one by itself (see below).

## How HandTop uses it

1. **Bundling.** The `bundleMesaGlibc` and `bundleMesaMusl` Gradle tasks put the packages into
   the APK's assets. Each takes its package from `native/mesa/prebuilt/` (not in git), or else
   from the project's release `mesa-<version>`, and checks the SHA-256 pinned in
   `app/build.gradle.kts`.
2. **Installing.** On a phone with Adreno (the KGSL device is open to apps), `GpuDrivers`
   unpacks the package for the installed distribution into `/opt/handtop-mesa`. It installs
   any of the distribution's libraries it needs that are missing (apt, dnf or apk). This
   happens right after Linux is installed, or at the next Linux session.
3. **Running.** While acceleration is on, HandTop:
   - registers the package with the Vulkan loader (`/usr/share/vulkan/icd.d/`);
   - glibc: registers it with glvnd (`/usr/share/glvnd/egl_vendor.d/10_handtop-mesa.json`,
     `libGLX_handtop.so.0`);
   - musl: puts `/opt/handtop-mesa/lib` first in `/etc/ld-musl-aarch64.path`;
   - gives every Linux program the variables from `GpuDrivers.environment`.

   Switched off in the settings, all of that is removed and llvmpipe draws again.

`handtop-gpu` runs a single program with the package even when acceleration is off.

## Build

Inside HandTop's Debian 13 or Alpine (arm64), as root, or in a Debian 13 / Alpine arm64
container. The distribution decides the package:

```sh
sh build-mesa.sh
```

What the script does:

1. Installs about 1 GB of build tools: with apt on Debian; with apk on Alpine, as the virtual
   package `.handtop-mesa-build`, which `apk del .handtop-mesa-build` removes again.
2. Fetches the pinned Mesa release from archive.mesa3d.org and checks its SHA-256.
3. Applies `patches/`.
4. Builds Mesa, installs it into `/opt/handtop-mesa`, and packs that directory as
   `handtop-mesa-<version>-<revision>-<glibc|musl>-arm64.tar.gz` (about 10 MB), with a
   `.sha256`.

A new package for the APK means:

1. Put it into `native/mesa/prebuilt/`.
2. Update the version and the SHA-256 in `app/build.gradle.kts`.

The GitHub Actions workflow `.github/workflows/mesa.yml` builds both packages on an arm64
runner, in Debian 13 and Alpine containers. For a tag `mesa-<version>-<revision>`, it
publishes them as a release.

The build takes about 10 minutes on a Snapdragon 8 Gen 3 (7 minutes on Alpine). It resumes after an interruption;
delete `mesa-<version>/` to start over.

## Patches

- `0001-egl-keep-zink-without-drm.patch`: with Zink requested and no DRM device, EGL used to
  drop Zink for its software path. Zink then still drew on the GPU, but every frame was read
  back through drisw, and on Wayland the windows stayed empty. With the patch, EGL keeps
  Zink on the software platform path, and Zink presents through Vulkan (kopper), as it does
  on X11. It also keeps Zink when Mesa tried it by itself, so programs started without
  HandTop's variables still draw on the GPU. Candidate for upstream.
- `0002-turnip-kgsl-present-through-shared-memory.patch`: on KGSL, Turnip now presents
  through shared memory (wl_shm, MIT-SHM) by itself, as a CPU device does. A Linux
  compositor or X server cannot take KGSL memory as a dma-buf, so without the patch, windows
  only worked with `MESA_VK_WSI_DEBUG=sw`. With the patch, a terminal opened before the
  drivers were installed, or a program started with a cleaned environment, still gets GPU
  windows. Candidate for upstream.

## Running a program by hand

```sh
/opt/handtop-mesa/bin/handtop-gpu glmark2-es2-wayland
/opt/handtop-mesa/bin/handtop-gpu vkcube
```

`handtop-gpu` sets the environment and runs the program; without arguments it prints that
environment. Frames reach HandTop's windows through shared memory for now
(`MESA_VK_WSI_DEBUG=sw`), which costs copies. Taking GPU buffers directly is the next step.

## Measured

Galaxy S24 Ultra (Adreno 750), Mesa 26.3.1, in windows on the phone's screen. The phone
was warm after an hour of tests, so expect some spread.

| test | llvmpipe (CPU) | Adreno 750 |
|---|---|---|
| glmark2 clear, 800×600 | 922 fps | 293 fps |
| glmark2 build, 800×600 | 393 fps | 266 fps |
| glmark2 refract, 800×600 | 16 fps | 160 fps |
| glmark2 terrain, 800×600 | 7 fps | 117 fps |
| vkmark, 800×600 | — | score 428 |
| SuperTuxKart 1.4, Black Forest, 8 karts, 2560×1440 px | ~0.5 fps | 48.5 fps |
| glxgears (X11) | — | 557 fps |

Light scenes are limited by the copies above (and lose to llvmpipe for now). Heavy scenes
run 10–100× faster.
