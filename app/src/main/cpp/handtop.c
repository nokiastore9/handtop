/*
 * HandTop native helpers for the Wayland compositor (the protocol itself is in Kotlin):
 * Unix sockets with file-descriptor passing, shared-memory mapping and pixel blits.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>
#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

#define MAX_FDS 28

#define JNI(name) Java_org_handtop_desktop_wayland_Native_##name

JNIEXPORT jint JNICALL JNI(listenUnix)(JNIEnv *env, jclass cls, jstring jpath)
{
	(void) cls;
	struct sockaddr_un addr;
	memset(&addr, 0, sizeof(addr));
	addr.sun_family = AF_UNIX;
	const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
	size_t len = strlen(path);
	if (len >= sizeof(addr.sun_path)) {
		(*env)->ReleaseStringUTFChars(env, jpath, path);
		return -ENAMETOOLONG;
	}
	memcpy(addr.sun_path, path, len);
	unlink(path);
	(*env)->ReleaseStringUTFChars(env, jpath, path);

	int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (fd < 0)
		return -errno;
	if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0 || listen(fd, 16) < 0) {
		int err = errno;
		close(fd);
		return -err;
	}
	return fd;
}

JNIEXPORT jint JNICALL JNI(accept)(JNIEnv *env, jclass cls, jint server)
{
	(void) env; (void) cls;
	int fd;
	do {
		fd = accept4(server, NULL, NULL, SOCK_CLOEXEC);
	} while (fd < 0 && errno == EINTR);
	return fd < 0 ? -errno : fd;
}

/* Reads bytes into buf[offset..offset+len); received fds go to fdsOut[1..], their count to fdsOut[0]. */
JNIEXPORT jint JNICALL JNI(recv)(JNIEnv *env, jclass cls, jint fd, jbyteArray jbuf, jint offset, jint len,
	jintArray jfds)
{
	(void) cls;
	jbyte buf[len];
	char control[CMSG_SPACE(sizeof(int) * MAX_FDS)];
	struct iovec iov = { .iov_base = buf, .iov_len = (size_t) len };
	struct msghdr msg;
	memset(&msg, 0, sizeof(msg));
	msg.msg_iov = &iov;
	msg.msg_iovlen = 1;
	msg.msg_control = control;
	msg.msg_controllen = sizeof(control);

	ssize_t n;
	do {
		n = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC);
	} while (n < 0 && errno == EINTR);
	if (n < 0)
		return -errno;

	jint fds[MAX_FDS + 1];
	int count = 0;
	for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c != NULL; c = CMSG_NXTHDR(&msg, c)) {
		if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS)
			continue;
		int n_fds = (int) ((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
		int *data = (int *) CMSG_DATA(c);
		for (int i = 0; i < n_fds && count < MAX_FDS; i++)
			fds[1 + count++] = data[i];
	}
	fds[0] = count;
	(*env)->SetIntArrayRegion(env, jfds, 0, count + 1, fds);
	if (n > 0)
		(*env)->SetByteArrayRegion(env, jbuf, offset, (jsize) n, buf);
	return (jint) n;
}

JNIEXPORT jint JNICALL JNI(send)(JNIEnv *env, jclass cls, jint fd, jbyteArray jbuf, jint offset, jint len,
	jintArray jfds)
{
	(void) cls;
	jbyte *bytes = (*env)->GetByteArrayElements(env, jbuf, NULL);
	struct iovec iov = { .iov_base = bytes + offset, .iov_len = (size_t) len };
	struct msghdr msg;
	memset(&msg, 0, sizeof(msg));
	msg.msg_iov = &iov;
	msg.msg_iovlen = 1;

	char control[CMSG_SPACE(sizeof(int) * MAX_FDS)];
	int n_fds = jfds != NULL ? (*env)->GetArrayLength(env, jfds) : 0;
	if (n_fds > MAX_FDS)
		n_fds = MAX_FDS;
	if (n_fds > 0) {
		memset(control, 0, sizeof(control));
		msg.msg_control = control;
		msg.msg_controllen = CMSG_SPACE(sizeof(int) * n_fds);
		struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
		c->cmsg_level = SOL_SOCKET;
		c->cmsg_type = SCM_RIGHTS;
		c->cmsg_len = CMSG_LEN(sizeof(int) * n_fds);
		(*env)->GetIntArrayRegion(env, jfds, 0, n_fds, (jint *) CMSG_DATA(c));
	}

	ssize_t n;
	do {
		n = sendmsg(fd, &msg, MSG_NOSIGNAL);
	} while (n < 0 && errno == EINTR);
	(*env)->ReleaseByteArrayElements(env, jbuf, bytes, JNI_ABORT);
	return n < 0 ? -errno : (jint) n;
}

JNIEXPORT void JNICALL JNI(close)(JNIEnv *env, jclass cls, jint fd)
{
	(void) env; (void) cls;
	close(fd);
}

JNIEXPORT void JNICALL JNI(shutdown)(JNIEnv *env, jclass cls, jint fd)
{
	(void) env; (void) cls;
	shutdown(fd, SHUT_RDWR);
}

/* Maps a client's shared-memory pool read-only. Returns the address, or 0. */
JNIEXPORT jlong JNICALL JNI(mmap)(JNIEnv *env, jclass cls, jint fd, jint size)
{
	(void) env; (void) cls;
	void *p = mmap(NULL, (size_t) size, PROT_READ, MAP_SHARED, fd, 0);
	return p == MAP_FAILED ? 0 : (jlong) (intptr_t) p;
}

JNIEXPORT void JNICALL JNI(munmap)(JNIEnv *env, jclass cls, jlong addr, jint size)
{
	(void) env; (void) cls;
	if (addr != 0)
		munmap((void *) (intptr_t) addr, (size_t) size);
}

/* A sealed-size memfd holding data (e.g. the XKB keymap sent to clients). */
JNIEXPORT jint JNICALL JNI(memfdWithData)(JNIEnv *env, jclass cls, jbyteArray jdata)
{
	(void) cls;
	int fd = (int) syscall(__NR_memfd_create, "handtop-keymap", MFD_CLOEXEC);
	if (fd < 0)
		return -errno;
	jsize len = (*env)->GetArrayLength(env, jdata);
	jbyte *data = (*env)->GetByteArrayElements(env, jdata, NULL);
	ssize_t done = 0;
	while (done < len) {
		ssize_t n = write(fd, data + done, (size_t) (len - done));
		if (n < 0 && errno == EINTR)
			continue;
		if (n <= 0)
			break;
		done += n;
	}
	(*env)->ReleaseByteArrayElements(env, jdata, data, JNI_ABORT);
	if (done != len) {
		close(fd);
		return -EIO;
	}
	return fd;
}

/* wl_shm pixels (0xAARRGGBB, bytes B,G,R,A) -> Android RGBA_8888 (bytes R,G,B,A). */
static inline void copy_row(uint32_t *d, const uint32_t *s, int n, uint32_t alpha)
{
	for (int x = 0; x < n; x++) {
		uint32_t p = s[x];
		d[x] = (p & 0xFF00FF00u) | ((p >> 16) & 0xFFu) | ((p & 0xFFu) << 16) | alpha;
	}
}

/*
 * Copies a wl_shm buffer (little-endian ARGB8888/XRGB8888, i.e. B,G,R,A bytes) into an
 * RGBA_8888 Android bitmap of the same size, swapping red and blue on the way.
 * Returns 0, or -1 if the buffer lies outside the pool.
 */
JNIEXPORT jint JNICALL JNI(blit)(JNIEnv *env, jclass cls, jlong poolAddr, jint poolSize, jint offset,
	jint width, jint height, jint stride, jboolean opaque, jobject bitmap)
{
	(void) cls;
	if (poolAddr == 0 || offset < 0 || stride < width * 4 ||
	    (int64_t) offset + (int64_t) stride * (height - 1) + (int64_t) width * 4 > poolSize)
		return -1;

	AndroidBitmapInfo info;
	void *pixels;
	if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 || (int) info.width != width ||
	    (int) info.height != height || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
		return -1;
	if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0)
		return -1;

	const uint8_t *src = (const uint8_t *) (intptr_t) poolAddr + offset;
	uint8_t *dst = pixels;
	uint32_t alpha = opaque ? 0xFF000000u : 0;
	for (int y = 0; y < height; y++)
		copy_row((uint32_t *) (dst + (size_t) y * info.stride),
			(const uint32_t *) (src + (size_t) y * stride), width, alpha);
	AndroidBitmap_unlockPixels(env, bitmap);
	return 0;
}

/* ---- hardware buffers: committed pixels the UI draws without uploading them ---- */

#define MAX_RECTS 64

/*
 * A GPU-sampled RGBA buffer the CPU writes, allocated for rare CPU writes (uncached,
 * write-combined memory).
 *
 * Persistent mode (Qualcomm): the buffer is locked once and written directly for its whole
 * life. Locking and unlocking per frame costs ~0.3 ms on Qualcomm's gralloc (it cleans the
 * CPU cache of the whole buffer on every unlock) whatever the damage; a store barrier after
 * each update makes the writes visible before the UI samples the buffer. Other gralloc
 * implementations may hand out a shadow copy on lock, so there each update locks the damaged
 * area and unlocks it again.
 */
typedef struct {
	AHardwareBuffer *buffer;
	uint8_t *addr; /* persistent mapping, or NULL */
	size_t stride; /* bytes */
	int width, height;
} HbFrame;

/* Returns an HbFrame*, or 0. */
JNIEXPORT jlong JNICALL JNI(hbCreate)(JNIEnv *env, jclass cls, jint width, jint height, jboolean persistent)
{
	(void) env; (void) cls;
	if (width <= 0 || height <= 0)
		return 0;
	AHardwareBuffer_Desc desc;
	memset(&desc, 0, sizeof(desc));
	desc.width = (uint32_t) width;
	desc.height = (uint32_t) height;
	desc.layers = 1;
	desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
	desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
	AHardwareBuffer *buffer = NULL;
	if (AHardwareBuffer_allocate(&desc, &buffer) != 0)
		return 0;
	AHardwareBuffer_describe(buffer, &desc);
	void *addr = NULL;
	if (persistent &&
	    (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, NULL, &addr) != 0 || !addr)) {
		AHardwareBuffer_release(buffer);
		return 0;
	}
	HbFrame *frame = calloc(1, sizeof(*frame));
	if (!frame) {
		if (addr)
			AHardwareBuffer_unlock(buffer, NULL);
		AHardwareBuffer_release(buffer);
		return 0;
	}
	frame->buffer = buffer;
	frame->addr = addr;
	frame->stride = (size_t) desc.stride * 4;
	frame->width = width;
	frame->height = height;
	return (jlong) (intptr_t) frame;
}

/* A android.hardware.HardwareBuffer for Bitmap.wrapHardwareBuffer (it holds its own reference). */
JNIEXPORT jobject JNICALL JNI(hbToJava)(JNIEnv *env, jclass cls, jlong handle)
{
	(void) cls;
	return AHardwareBuffer_toHardwareBuffer(env, ((HbFrame *) (intptr_t) handle)->buffer);
}

JNIEXPORT void JNICALL JNI(hbRelease)(JNIEnv *env, jclass cls, jlong handle)
{
	(void) env; (void) cls;
	HbFrame *frame = (HbFrame *) (intptr_t) handle;
	if (!frame)
		return;
	if (frame->addr)
		AHardwareBuffer_unlock(frame->buffer, NULL);
	AHardwareBuffer_release(frame->buffer);
	free(frame);
}

/*
 * Copies count rectangles (x, y, w, h quadruples in buffer pixels) of a wl_shm buffer into the
 * frame of the same size, swapping red and blue. Returns 0, -1 for a bad buffer, -2 if the
 * buffer cannot be locked.
 */
JNIEXPORT jint JNICALL JNI(hbBlit)(JNIEnv *env, jclass cls, jlong handle, jlong poolAddr, jint poolSize,
	jint offset, jint width, jint height, jint stride, jboolean opaque, jintArray rectArray, jint count)
{
	(void) cls;
	HbFrame *frame = (HbFrame *) (intptr_t) handle;
	if (!frame || poolAddr == 0 || offset < 0 || stride < width * 4 ||
	    (int64_t) offset + (int64_t) stride * (height - 1) + (int64_t) width * 4 > poolSize ||
	    frame->width != width || frame->height != height)
		return -1;
	if (count < 1)
		return 0;
	if (count > MAX_RECTS)
		count = MAX_RECTS;
	jint r[MAX_RECTS * 4];
	(*env)->GetIntArrayRegion(env, rectArray, 0, count * 4, r);

	/* Clip to the buffer, find the bounding box. */
	ARect bounds = { width, height, 0, 0 };
	for (int i = 0; i < count; i++) {
		jint *q = r + i * 4;
		int x0 = q[0] < 0 ? 0 : q[0], y0 = q[1] < 0 ? 0 : q[1];
		int64_t x1 = (int64_t) q[0] + q[2], y1 = (int64_t) q[1] + q[3];
		if (x1 > width) x1 = width;
		if (y1 > height) y1 = height;
		q[0] = x0; q[1] = y0;
		q[2] = x1 > x0 ? (jint) (x1 - x0) : 0;
		q[3] = y1 > y0 ? (jint) (y1 - y0) : 0;
		if (q[2] == 0 || q[3] == 0)
			continue;
		if (x0 < bounds.left) bounds.left = x0;
		if (y0 < bounds.top) bounds.top = y0;
		if (x0 + q[2] > bounds.right) bounds.right = x0 + q[2];
		if (y0 + q[3] > bounds.bottom) bounds.bottom = y0 + q[3];
	}
	if (bounds.right <= bounds.left || bounds.bottom <= bounds.top)
		return 0;

	uint8_t *dst = frame->addr;
	if (!dst) {
		void *addr = NULL;
		if (AHardwareBuffer_lock(frame->buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, &bounds, &addr) != 0 || !addr)
			return -2;
		dst = addr;
	}
	const uint8_t *src = (const uint8_t *) (intptr_t) poolAddr + offset;
	uint32_t alpha = opaque ? 0xFF000000u : 0;
	for (int i = 0; i < count; i++) {
		const jint *q = r + i * 4;
		for (int y = q[1]; y < q[1] + q[3]; y++)
			copy_row((uint32_t *) (dst + (size_t) y * frame->stride) + q[0],
				(const uint32_t *) (src + (size_t) y * stride) + q[0], q[2], alpha);
	}
	if (frame->addr) {
#if defined(__aarch64__)
		/* Drain write-combining buffers before the UI thread asks the GPU to sample. */
		__asm__ volatile("dsb st" ::: "memory");
#else
		__atomic_thread_fence(__ATOMIC_SEQ_CST);
#endif
	} else {
		AHardwareBuffer_unlock(frame->buffer, NULL);
	}
	return 0;
}
