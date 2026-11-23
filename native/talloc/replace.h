/*
 * HandTop: minimal stand-in for Samba's lib/replace, just enough to compile
 * talloc.c with the Android NDK without talloc's waf build system.
 * Everything here exists in bionic for API 26+ except memset_explicit().
 */
#ifndef HANDTOP_TALLOC_REPLACE_H
#define HANDTOP_TALLOC_REPLACE_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/types.h>

#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 5
#define TALLOC_BUILD_VERSION_RELEASE 0

#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_GETAUXVAL 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_VA_COPY 1
#define HAVE_INTPTR_T 1

#ifndef discard_const
#define discard_const(ptr) ((void *)((uintptr_t)(ptr)))
#endif
#ifndef discard_const_p
#define discard_const_p(type, ptr) ((type *)discard_const(ptr))
#endif
#ifndef likely
#define likely(x) __builtin_expect(!!(x), 1)
#endif
#ifndef unlikely
#define unlikely(x) __builtin_expect(!!(x), 0)
#endif
#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif

/* C23 memset_explicit() is missing from bionic. talloc uses it to zero the
 * grown part of a realloc'ed block; a volatile loop can't be optimised away. */
static inline void *handtop_memset_explicit(void *s, int c, size_t n)
{
	volatile unsigned char *p = (volatile unsigned char *)s;
	while (n--)
		*p++ = (unsigned char)c;
	return s;
}
#define memset_explicit handtop_memset_explicit

#endif /* HANDTOP_TALLOC_REPLACE_H */
