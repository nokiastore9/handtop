# Mesa for Adreno (Turnip + Zink over KGSL)

GPU acceleration for HandTop's Linux programs on Snapdragon phones:

- **Turnip** is Mesa's Vulkan driver for Adreno. Here it talks to **KGSL**, the GPU driver of
  Android kernels (`/dev/kgsl-3d0`, open to every app, no root needed).
- **Zink** provides OpenGL 4.6 and OpenGL ES 3.2 on top of Vulkan.

Mesa supports this pairing without DRM (`pseudo-drm`). Debian's Mesa is built without KGSL,
so HandTop builds its own. The package goes into the APK, and HandTop installs it next to
Debian's Mesa by itself (see below).

## How HandTop uses it

1. **Bundling.** The `bundleMesa` Gradle task puts the package into the APK's assets. It takes
   the package from `native/mesa/prebuilt/` (not in git), or else from the project's release
   `mesa-<version>`. It checks the SHA-256 pinned in `app/build.gradle.kts`.
2. **Installing.** On a phone with Adreno (the KGSL device is open to apps) running Debian 13,
   `GpuDrivers` unpacks it into `/opt/handtop-mesa`. It installs any of the Debian libraries
   it needs that are missing, with apt. This happens right after Linux is installed, or at
   the next Linux session.
3. **Running.** While acceleration is on, HandTop:
   - registers the package with glvnd (`/usr/share/glvnd/egl_vendor.d/10_handtop-mesa.json`,
     `libGLX_handtop.so.0`) and the Vulkan loader (`/usr/share/vulkan/icd.d/`);
   - gives every Linux program the variables from `GpuDrivers.environment`.

   Switched off in the settings, all of that is removed and llvmpipe draws again.

`handtop-gpu` runs a single program with the package even when acceleration is off.

## Build

Inside HandTop's Debian 13 (arm64), as root, or in any Debian 13 arm64 system or container:

```sh
sh build-mesa.sh
```

What the script does:

1. Installs about 1 GB of build tools with apt.
2. Fetches the pinned Mesa release from archive.mesa3d.org and checks its SHA-256.
3. Applies `patches/`.
4. Builds Mesa, installs it into `/opt/handtop-mesa`, and packs that directory as
   `handtop-mesa-<version>-<revision>-debian13-arm64.tar.gz` (about 10 MB), with a `.sha256`.

A new package for the APK means:

1. Put it into `native/mesa/prebuilt/`.
2. Update the version and the SHA-256 in `app/build.gradle.kts`.

The GitHub Actions workflow `.github/workflows/mesa.yml` builds the same package on an arm64
runner. For a tag `mesa-<version>-<revision>`, it publishes the package as a release.

The build takes about 10 minutes on a Snapdragon 8 Gen 3. It resumes after an interruption;
delete `mesa-<version>/` to start over.

## Patches

- `0001-egl-keep-zink-without-drm.patch`: with Zink requested and no DRM device, EGL used to
  drop Zink for its software path. Zink then still drew on the GPU, but every frame was read
  back through drisw, and on Wayland the windows stayed empty. With the patch, EGL keeps
  Zink on the software platform path, and Zink presents through Vulkan (kopper), as it does
  on X11. Candidate for upstream.

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
