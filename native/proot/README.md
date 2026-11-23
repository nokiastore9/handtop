# proot

Sources of [termux/proot](https://github.com/termux/proot) (commit `0e4287546a7df1a7f4395c75f7eca8b62d5f2cc9`),
the Android-maintained fork of PRoot. Licensed under the GNU GPL v2 or later — see [COPYING](COPYING).

Built by Gradle (see `buildSrc/.../BuildProotTask.kt`) into two executables shipped as
`lib*.so` so Android extracts them to the app's nativeLibraryDir, the only place an app
targeting API 29+ may `execve()` from:

- `libproot.so` — proot itself, with talloc linked statically;
- `libproot-loader.so` — the loader (`src/loader`), found through `PROOT_LOADER`
  (built with `PROOT_UNBUNDLE_LOADER`, so it is never written to app data).

Changes made by HandTop are marked with a comment at the top of the file:

- `src/extension/ashmem_memfd/ashmem_memfd.c`: added the missing `<string.h>` include
  (implicit declarations are errors with NDK r30 clang).
