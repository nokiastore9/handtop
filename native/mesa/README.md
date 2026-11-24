# Mesa for Adreno (Turnip + Zink over KGSL)

GPU acceleration for HandTop's Linux programs on Snapdragon phones:

- **Turnip** is Mesa's Vulkan driver for Adreno. Here it talks to **KGSL**, the GPU driver of
  Android kernels (`/dev/kgsl-3d0`, open to every app, no root needed).
- **Zink** provides OpenGL 4.6 and OpenGL ES 3.2 on top of Vulkan.

Mesa supports this pairing without DRM (`pseudo-drm`). Debian's Mesa is built without KGSL,
so HandTop builds its own and installs it next to Debian's, into `/opt/handtop-mesa`. Debian's
llvmpipe stays the default.

## Build

Inside HandTop's Debian 13 (arm64), as root, or in any Debian 13 arm64 system or container:

```sh
sh build-mesa.sh
```

What the script does:

1. Installs about 1 GB of build tools with apt.
2. Fetches the pinned Mesa release from archive.mesa3d.org and checks its SHA-256.
3. Builds Mesa, installs it into `/opt/handtop-mesa`, and packs that directory as
   `handtop-mesa-<version>-debian13-arm64.tar.xz` (about 7 MB).

The build takes about 10 minutes on a Snapdragon 8 Gen 3. It resumes after an interruption;
delete `mesa-<version>/` to start over.

## Use

```sh
/opt/handtop-mesa/bin/handtop-gpu glmark2-es2-wayland
/opt/handtop-mesa/bin/handtop-gpu vkcube
```

`handtop-gpu` sets the environment and runs the program; without arguments it prints that
environment. Frames reach HandTop's windows through shared memory for now
(`MESA_VK_WSI_DEBUG=sw`), which costs copies. Taking GPU buffers directly is the next step.

## Measured

Galaxy S24 Ultra (Adreno 750), Mesa 26.3.1, window 800×600 on the phone's screen, fps:

| test | llvmpipe (CPU) | Adreno 750 |
|---|---|---|
| glmark2 clear | 922 | 354 |
| glmark2 build | 393 | 337 |
| glmark2 refract | 16 | 186 |
| glmark2 terrain | 7 | 126 |
| glxgears (X11) | — | 508 |

Light scenes are limited by the copies above; heavy scenes run 10–18× faster.
