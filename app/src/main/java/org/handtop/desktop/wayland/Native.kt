package org.handtop.desktop.wayland

import android.graphics.Bitmap

/** JNI helpers (app/src/main/cpp/handtop.c). Negative return values are -errno. */
object Native {
    init {
        System.loadLibrary("handtop")
    }

    @JvmStatic external fun listenUnix(path: String): Int
    @JvmStatic external fun accept(serverFd: Int): Int

    /** Reads into [buf]; received fds are stored in fdsOut[1..fdsOut[0]]. Returns bytes read. */
    @JvmStatic external fun recv(fd: Int, buf: ByteArray, offset: Int, len: Int, fdsOut: IntArray): Int
    @JvmStatic external fun send(fd: Int, buf: ByteArray, offset: Int, len: Int, fds: IntArray?): Int
    @JvmStatic external fun close(fd: Int)
    @JvmStatic external fun shutdown(fd: Int)

    @JvmStatic external fun mmap(fd: Int, size: Int): Long
    @JvmStatic external fun munmap(addr: Long, size: Int)
    @JvmStatic external fun memfdWithData(data: ByteArray): Int

    /** Copies an ARGB8888/XRGB8888 shm buffer into an ARGB_8888 bitmap of the same size. */
    @JvmStatic external fun blit(
        poolAddr: Long, poolSize: Int, offset: Int,
        width: Int, height: Int, stride: Int, opaque: Boolean, bitmap: Bitmap,
    ): Int
}
