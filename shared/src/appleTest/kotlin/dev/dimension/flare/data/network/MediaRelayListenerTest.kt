package dev.dimension.flare.data.network

import dev.dimension.flare.common.PlatformDispatchers
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.CoreFoundation.CFSwapInt16HostToBig
import platform.CoreFoundation.CFSwapInt32HostToBig
import platform.posix.AF_INET
import platform.posix.ECONNREFUSED
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.memset
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalForeignApi::class)
class MediaRelayListenerTest {
    @Test
    fun interruptClosesListeningSocketAndWakesAccept(): Unit =
        runBlocking {
            val listener = MediaRelayListener()
            val started = CompletableDeferred<Unit>()
            val accept =
                CoroutineScope(PlatformDispatchers.IO).async {
                    started.complete(Unit)
                    assertFailsWith<CancellationException> { listener.accept().close() }
                }
            accept.invokeOnCompletion { listener.close() }
            try {
                withTimeout(3_000) { started.await() }
                delay(20)
                listener.interrupt()
                val error = connectTo(listener.port)
                assertEquals(ECONNREFUSED, error)
                withTimeout(3_000) { accept.await() }
            } finally {
                listener.interrupt()
            }
        }

    private fun connectTo(port: Int): Int =
        memScoped {
            val client = socket(AF_INET, SOCK_STREAM, 0)
            check(client >= 0)
            try {
                val address = alloc<sockaddr_in>()
                memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
                address.sin_len = sizeOf<sockaddr_in>().toUByte()
                address.sin_family = AF_INET.toUByte()
                address.sin_addr.s_addr = CFSwapInt32HostToBig(0x7f000001u)
                address.sin_port = CFSwapInt16HostToBig(port.toUShort())
                if (connect(client, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) 0 else errno
            } finally {
                close(client)
            }
        }
}
