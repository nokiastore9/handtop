#!/bin/sh
# Builds Mesa for HandTop's Linux (Debian 13, arm64) with GPU acceleration on Adreno:
#   - Turnip, the Vulkan driver for Adreno, on KGSL (the GPU driver of Android kernels);
#   - Zink, OpenGL / OpenGL ES on top of Vulkan.
# Mesa supports this pairing without DRM ("pseudo-drm"), which Android does not offer to apps.
#
# Runs inside the distribution (as root, the default in HandTop) or in any Debian 13 arm64
# system or container. Installs into $PREFIX, leaving Debian's own Mesa (llvmpipe) untouched,
# and packs $PREFIX into $OUT/handtop-mesa-<version>-debian13-arm64.tar.xz for releases.
# Programs get the GPU through handtop-gpu (see that file).
set -eu

MESA_VERSION=26.3.1
MESA_SHA256=4e4e438eac7b840a9383988cbb7c08dad8041da544b9319fc26d319fa7348961
PREFIX=${PREFIX:-/opt/handtop-mesa}
WORK=${WORK:-/root/mesa-build}
OUT=${OUT:-$WORK}
JOBS=${JOBS:-$(nproc)}
HERE=$(cd "$(dirname "$0")" && pwd)

step() { echo "=== $(date '+%H:%M:%S') $*"; }

step "build tools"
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
[ -d "mesa-$MESA_VERSION" ] || tar -xJf "$TARBALL"
cd "mesa-$MESA_VERSION"

# HandTop's changes to Mesa (patches/, each explains itself), applied once.
if [ ! -f .handtop-patched ]; then
    for p in "$HERE"/patches/*.patch; do
        echo "applying $(basename "$p")"
        patch -p1 --forward --batch < "$p"
    done
    touch .handtop-patched
fi

# Mesa needs wayland-protocols newer than Debian 13's: it comes from Mesa's own wrap. Its XML
# uses the "frozen" attribute, unknown to Debian 13's wayland-scanner (1.23), which with
# --strict rejects the file; without it the scanner warns and generates the same headers.
meson subprojects download wayland-protocols
sed -i "/'--strict',/d" subprojects/wayland-protocols-*/include/wayland-protocols/meson.build
# libarchive and libxml2 only serve freedreno's debugging tools; without these wraps Mesa skips
# the tools instead of building both libraries into the package.
rm -f subprojects/libarchive.wrap subprojects/libxml2.wrap

step "configure"
[ -f build/build.ninja ] || meson setup build \
    --prefix="$PREFIX" --libdir=lib --buildtype=release -Db_ndebug=true \
    -Dplatforms=x11,wayland \
    -Dgallium-drivers=zink \
    -Dvulkan-drivers=freedreno \
    -Dfreedreno-kmds=kgsl \
    -Dglx=dri -Degl=enabled -Dgles1=disabled -Dgles2=enabled -Dglvnd=enabled \
    -Dllvm=disabled -Dvideo-codecs= -Dvulkan-layers= -Dtools= \
    -Dlibunwind=disabled -Dvalgrind=disabled -Dlmsensors=disabled

step "build ($JOBS jobs)"
ninja -C build -j "$JOBS"

step "install into $PREFIX"
rm -rf "$PREFIX"
ninja -C build install
install -Dm755 "$HERE/handtop-gpu" "$PREFIX/bin/handtop-gpu"
echo "$MESA_VERSION" > "$PREFIX/VERSION"

step "package"
PACKAGE="$OUT/handtop-mesa-$MESA_VERSION-debian13-arm64.tar.xz"
tar -C "$(dirname "$PREFIX")" -cJf "$PACKAGE" "$(basename "$PREFIX")"
ls -la "$PACKAGE"
step "done"
