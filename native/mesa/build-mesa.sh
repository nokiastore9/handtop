#!/bin/sh
# Builds Mesa for HandTop's Linux (arm64) with GPU acceleration on Adreno:
#   - Turnip, the Vulkan driver for Adreno, on KGSL (the GPU driver of Android kernels);
#   - Zink, OpenGL / OpenGL ES on top of Vulkan.
# Mesa supports this pairing without DRM ("pseudo-drm"), which Android does not offer to apps.
#
# Two builds, one per C library, cover HandTop's distributions:
#   - glibc: built on Debian 13, whose glibc is the oldest of them; runs on Debian, Ubuntu and
#     Fedora. OpenGL through glvnd, next to the distribution's own Mesa.
#   - musl: built on Alpine, which has no glvnd; HandTop puts this build first in the musl
#     loader's search path instead.
#
# Runs inside the distribution (as root, the default in HandTop) or in a Debian 13 / Alpine
# arm64 container. Installs into $PREFIX, leaving the distribution's own Mesa untouched, and
# packs $PREFIX into $OUT/handtop-mesa-<version>-<glibc|musl>-arm64.tar.gz, the package the
# APK carries (see GpuDrivers.kt for how HandTop switches it on).
set -eu

MESA_VERSION=26.3.1
MESA_SHA256=4e4e438eac7b840a9383988cbb7c08dad8041da544b9319fc26d319fa7348961
# Bump when the package changes without a new Mesa (patches, options, extra files).
PACKAGE_REVISION=2
VERSION=$MESA_VERSION-$PACKAGE_REVISION
PREFIX=${PREFIX:-/opt/handtop-mesa}
WORK=${WORK:-/root/mesa-build}
OUT=${OUT:-$WORK}
JOBS=${JOBS:-$(nproc)}
HERE=$(cd "$(dirname "$0")" && pwd)

step() { echo; echo "=== $(date '+%H:%M:%S') $*"; }

step "build tools"
if [ -f /etc/alpine-release ]; then
    LIBC=musl
    GLVND=disabled
    # One virtual package: "apk del .handtop-mesa-build" removes exactly these afterwards.
    apk add --virtual .handtop-mesa-build \
        build-base meson samurai pkgconf bison flex glslang patch \
        py3-mako py3-yaml py3-packaging \
        libdrm-dev expat-dev zlib-dev zstd-dev \
        wayland-dev wayland-protocols \
        libx11-dev libxext-dev libxfixes-dev libxrandr-dev libxxf86vm-dev libxshmfence-dev \
        libxcb-dev xcb-util-keysyms-dev linux-headers \
        curl xz
else
    LIBC=glibc
    GLVND=enabled
    export DEBIAN_FRONTEND=noninteractive
    # A broken third-party repository (say, one whose key is missing) must not stop the build:
    # Debian's own lists are refreshed all the same.
    apt-get update -q || echo "warning: apt-get update reported errors; continuing with Debian's lists"
    apt-get install -y -q --no-install-recommends \
        build-essential meson ninja-build pkg-config bison flex glslang-tools patch \
        python3-mako python3-yaml python3-packaging \
        libdrm-dev libexpat1-dev zlib1g-dev libzstd-dev libglvnd-dev \
        libwayland-dev libwayland-bin libwayland-egl-backend-dev wayland-protocols \
        libx11-dev libx11-xcb-dev libxext-dev libxfixes-dev libxrandr-dev libxxf86vm-dev \
        libxshmfence-dev libxcb1-dev libxcb-glx0-dev libxcb-shm0-dev libxcb-dri3-dev \
        libxcb-present-dev libxcb-sync-dev libxcb-randr0-dev libxcb-xfixes0-dev libxcb-keysyms1-dev \
        curl ca-certificates xz-utils
fi

step "source: Mesa $MESA_VERSION"
mkdir -p "$WORK"
cd "$WORK"
TARBALL=mesa-$MESA_VERSION.tar.xz
if [ ! -f "$TARBALL" ]; then
    curl -fL -o "$TARBALL.part" "https://archive.mesa3d.org/$TARBALL"
    mv "$TARBALL.part" "$TARBALL"
fi
echo "$MESA_SHA256  $TARBALL" | sha256sum -c -
# An interrupted build continues where it stopped; delete mesa-$MESA_VERSION to start over.
# A source tree with other patches than patches/ has now (each explains itself) starts over.
PATCHES_ID=$(cat "$HERE"/patches/*.patch | sha256sum | cut -c1-16)
if [ -d "mesa-$MESA_VERSION" ] && [ "$(cat "mesa-$MESA_VERSION/.handtop-patched" 2>/dev/null)" != "$PATCHES_ID" ]; then
    echo "patches changed: starting from a clean source tree"
    rm -rf "mesa-$MESA_VERSION"
fi
if [ ! -d "mesa-$MESA_VERSION" ]; then
    tar -xJf "$TARBALL"
    for p in "$HERE"/patches/*.patch; do
        echo "applying $(basename "$p")"
        patch -d "mesa-$MESA_VERSION" -p1 --forward --batch < "$p"
    done
    echo "$PATCHES_ID" > "mesa-$MESA_VERSION/.handtop-patched"
fi
cd "mesa-$MESA_VERSION"

# Mesa needs wayland-protocols newer than Debian 13's: it comes from Mesa's own wrap. Its XML
# uses the "frozen" attribute, unknown to Debian 13's wayland-scanner (1.23), which with
# --strict rejects the file; without it the scanner warns and generates the same headers.
meson subprojects download wayland-protocols
sed -i "/'--strict',/d" subprojects/wayland-protocols-*/include/wayland-protocols/meson.build
# libarchive and libxml2 only serve freedreno's debugging tools; without these wraps Mesa skips
# the tools instead of building both libraries into the package.
rm -f subprojects/libarchive.wrap subprojects/libxml2.wrap

step "configure"
# The libraries find each other through their run path, not LD_LIBRARY_PATH: programs load
# them by absolute path (glvnd, the Vulkan loader), and Debian's Mesa stays usable next to them.
set -- \
    -Dprefix="$PREFIX" -Dlibdir=lib -Dbuildtype=release -Db_ndebug=true \
    -Dc_link_args="-Wl,-rpath,$PREFIX/lib" -Dcpp_link_args="-Wl,-rpath,$PREFIX/lib" \
    -Dplatforms=x11,wayland \
    -Dgallium-drivers=zink \
    -Dvulkan-drivers=freedreno \
    -Dfreedreno-kmds=kgsl \
    -Dglx=dri -Degl=enabled -Dgles1=disabled -Dgles2=enabled -Dglvnd=$GLVND \
    -Dllvm=disabled -Dvideo-codecs= -Dvulkan-layers= -Dtools= \
    -Dlibunwind=disabled -Dvalgrind=disabled -Dlmsensors=disabled
if [ -f build/build.ninja ]; then
    # A resumed build still gets changed options ("setup --reconfigure" ignores some).
    meson configure build "$@"
else
    meson setup build "$@"
fi

step "build ($JOBS jobs)"
ninja -C build -j "$JOBS"

step "install into $PREFIX"
rm -rf "$PREFIX"
ninja -C build install
install -Dm755 "$HERE/handtop-gpu" "$PREFIX/bin/handtop-gpu"
# glvnd's vendor file, by absolute path: HandTop puts it first in /usr/share/glvnd/egl_vendor.d.
[ "$GLVND" = enabled ] && cat > "$PREFIX/share/glvnd/egl_vendor.d/10_handtop-mesa.json" <<EOF
{
    "file_format_version" : "1.0.0",
    "ICD" : {
        "library_path" : "$PREFIX/lib/libEGL_mesa.so.0"
    }
}
EOF
echo "$VERSION" > "$PREFIX/VERSION"
# Check that the run path made it through meson's install.
EGL=$PREFIX/lib/libEGL_mesa.so.0
[ "$GLVND" = enabled ] || EGL=$PREFIX/lib/libEGL.so.1
objdump -p "$EGL" | grep -q "RUNPATH.*$PREFIX/lib" ||
    { echo "error: $EGL has no run path $PREFIX/lib"; exit 1; }

step "package"
PACKAGE="$OUT/handtop-mesa-$VERSION-$LIBC-arm64.tar.gz"
mkdir -p "$OUT"
tar -C "$(dirname "$PREFIX")" -czf "$PACKAGE" "$(basename "$PREFIX")"
(cd "$OUT" && sha256sum "$(basename "$PACKAGE")" | tee "$(basename "$PACKAGE").sha256")
ls -la "$PACKAGE"
step "done"
