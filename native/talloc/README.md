# talloc

`talloc.c` and `talloc.h` from [talloc 2.5.0](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz)
(sha256 `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007`), unmodified.
Licensed under the GNU LGPL v3 or later — see [LICENSE](LICENSE).

`replace.h` is HandTop's own minimal replacement for Samba's `lib/replace`,
so the library builds with plain NDK clang (no waf). It is linked statically into proot.
