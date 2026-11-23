/*
 * HandTop native helpers for the Wayland compositor (the protocol itself is in Kotlin):
 * Unix sockets with file-descriptor passing, shared-memory mapping and pixel blits.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>
#include <android/bitmap.h>

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
	for (int y = 0; y < height; y++) {
		const uint32_t *s = (const uint32_t *) (src + (size_t) y * stride);
		uint32_t *d = (uint32_t *) (dst + (size_t) y * info.stride);
		for (int x = 0; x < width; x++) {
			uint32_t p = s[x];
			/* 0xAARRGGBB -> memory R,G,B,A == 0xAABBGGRR */
			d[x] = (p & 0xFF00FF00u) | ((p >> 16) & 0xFFu) | ((p & 0xFFu) << 16) | alpha;
		}
	}
	AndroidBitmap_unlockPixels(env, bitmap);
	return 0;
}
