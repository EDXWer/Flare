@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dimension.flare.data.network

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import platform.Foundation.NSLock
import platform.darwin.EVFILT_READ
import platform.darwin.EV_ADD
import platform.darwin.EV_EOF
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_SNDTIMEO
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.pipe
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval

internal actual class MediaRelayLock actual constructor() {
    private val lock = NSLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

internal actual class MediaRelayListener actual constructor() {
    private val lock = MediaRelayLock()
    private val queue = kqueue().also { check(it >= 0) { "Cannot create media listener event queue" } }
    private var descriptor = -1
    private var wakeupReader = -1
    private var wakeupWriter = -1
    actual val port: Int

    actual fun interrupt() {
        lock.withLock {
            if (descriptor < 0) return@withLock
            closeSocket()
            // The pipe's EOF wakes kevent without closing the queue while it is in use.
            close(wakeupWriter)
            wakeupWriter = -1
        }
    }

    actual fun close() {
        lock.withLock {
            closeSocket()
            if (wakeupWriter >= 0) close(wakeupWriter)
            if (wakeupReader >= 0) close(wakeupReader)
            close(queue)
        }
    }

    init {
        try {
            descriptor = socket(AF_INET, SOCK_STREAM, 0)
            check(descriptor >= 0) { "Cannot create media listener" }
            check(fcntl(descriptor, F_SETFL, O_NONBLOCK) == 0) { "Cannot set media listener nonblocking" }
            port =
                memScoped {
                    val address = alloc<sockaddr_in>()
                    memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
                    address.sin_len = sizeOf<sockaddr_in>().toUByte()
                    address.sin_family = AF_INET.toUByte()
                    address.sin_addr.s_addr = platform.CoreFoundation.CFSwapInt32HostToBig(0x7f000001u)
                    address.sin_port = 0u
                    check(
                        bind(descriptor, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0,
                    ) { "Cannot bind media listener" }
                    check(listen(descriptor, 64) == 0) { "Cannot start media listener" }
                    val length = alloc<socklen_tVar> { value = sizeOf<sockaddr_in>().convert() }
                    check(getsockname(descriptor, address.ptr.reinterpret(), length.ptr) == 0)
                    platform.CoreFoundation.CFSwapInt16BigToHost(address.sin_port).toInt()
                }
            memScoped {
                val wakeup = allocArray<IntVar>(2)
                check(pipe(wakeup) == 0) { "Cannot create media listener wakeup pipe" }
                wakeupReader = wakeup[0]
                wakeupWriter = wakeup[1]
            }
            watch(wakeupReader)
            watch(descriptor)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun closeSocket() {
        if (descriptor < 0) return
        close(descriptor)
        descriptor = -1
    }

    private fun watch(fd: Int) =
        memScoped {
            val event =
                alloc<kevent> {
                    ident = fd.toULong()
                    filter = EVFILT_READ.toShort()
                    flags = EV_ADD.toUShort()
                    fflags = 0u
                    data = 0
                    udata = null
                }
            var result: Int
            do {
                result = kevent(queue, event.ptr, 1, null, 0, null)
            } while (result < 0 && errno == EINTR)
            check(result == 0) { "Cannot register media listener event" }
        }

    actual fun accept(): MediaRelayConnection {
        val accepted =
            memScoped {
                val event = alloc<kevent>()
                var client = -1
                while (client < 0) {
                    if (kevent(queue, null, 0, event.ptr, 1, null) < 0) {
                        val code = errno
                        if (code == EINTR) continue
                        throw MediaRelayAcceptException(code)
                    }
                    client =
                        lock.withLock {
                            if (descriptor < 0) throw CancellationException("Media listener stopped")
                            accept(descriptor, null, null).also {
                                if (it < 0) {
                                    val code = errno
                                    if (code == EAGAIN && event.flags.toInt() and EV_EOF != 0) {
                                        throw MediaRelayAcceptException(event.fflags.toInt().takeIf { it != 0 } ?: code)
                                    }
                                    if (code != EINTR && code != EAGAIN) throw MediaRelayAcceptException(code)
                                }
                            }
                        }
                }
                client
            }
        // Darwin can inherit O_NONBLOCK from the listener. Media connections use blocking I/O with timeouts.
        if (fcntl(accepted, F_SETFL, 0) != 0) {
            val code = errno
            close(accepted)
            throw MediaRelayAcceptException(code)
        }
        memScoped {
            val enabled = alloc<IntVar> { value = 1 }
            setsockopt(accepted, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, sizeOf<IntVar>().convert())
            val timeout =
                alloc<timeval> {
                    tv_sec = 30
                    tv_usec = 0
                }
            setsockopt(accepted, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
            setsockopt(accepted, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().convert())
        }
        return object : MediaRelayConnection {
            // Keep the descriptor alive until the request exits so a blocked call cannot see a reused fd.
            override fun interrupt() {
                shutdown(accepted, SHUT_RDWR)
            }

            override fun read(bytes: ByteArray): Int =
                bytes.usePinned {
                    var count: Long
                    do {
                        count = recv(accepted, it.addressOf(0), bytes.size.convert(), 0)
                    } while (count < 0 && errno == EINTR)
                    check(count >= 0) { "Media request read failed" }
                    if (count == 0L) -1 else count.toInt()
                }

            override fun write(
                bytes: ByteArray,
                count: Int,
            ) {
                if (count == 0) return
                bytes.usePinned {
                    var offset = 0
                    while (offset < count) {
                        val written = send(accepted, it.addressOf(offset), (count - offset).convert(), 0)
                        if (written < 0 && errno == EINTR) continue
                        check(written > 0) { "Media response write failed" }
                        offset += written.toInt()
                    }
                }
            }

            override fun close() {
                platform.posix.close(accepted)
            }
        }
    }
}
