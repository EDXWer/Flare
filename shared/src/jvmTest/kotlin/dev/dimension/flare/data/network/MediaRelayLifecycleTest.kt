package dev.dimension.flare.data.network

import com.sun.net.httpserver.HttpServer
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.ProxyRepository
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.ui.model.UiMedia
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MediaRelayLifecycleTest {
    @Test
    fun returningToForegroundReopensRelayWithFreshSources(): Unit =
        runBlocking {
            val root = createTestRootPath()
            val repository = ProxyRepository(OkioFileStorage(createTestFileSystem(), root))
            val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            upstream.createContext("/video.mp4") { exchange ->
                exchange.use {
                    val validRange = it.requestHeaders.getFirst("Range") == "bytes=2-5"
                    val body = "2345".encodeToByteArray()
                    it.responseHeaders.add("Content-Type", "video/mp4")
                    it.responseHeaders.add("Content-Range", "bytes 2-5/10")
                    it.sendResponseHeaders(if (validRange) 206 else 416, body.size.toLong())
                    it.responseBody.use { stream -> stream.write(body) }
                }
            }
            val client =
                OkHttpClient
                    .Builder()
                    .proxy(Proxy.NO_PROXY)
                    .callTimeout(5, TimeUnit.SECONDS)
                    .build()
            val media = UiMedia.Video("http://127.0.0.1:${upstream.address.port}/video.mp4", "", null, 0f, 0f, proxy = ProxyRef.System)
            upstream.start()
            startKoin { modules(module { testSingle<ProxyRepository> { repository } }) }
            try {
                val old = MediaProxy.playbackSource(media, forceRelay = true)
                assertEquals(206 to "2345", client.range(old.url))
                MediaProxy.setRelayForeground(active = false)
                assertFailsWith<ConnectException> { client.range(old.url) }
                assertFailsWith<IllegalStateException> { MediaProxy.playbackSource(media, forceRelay = true) }
                assertEquals(media.url, MediaProxy.playbackSource(media).url)

                MediaProxy.setRelayForeground(active = true)
                val fresh = MediaProxy.playbackSource(media, forceRelay = true)
                assertNotEquals(old.key, fresh.key)
                val stalePath =
                    fresh.url
                        .toHttpUrl()
                        .newBuilder()
                        .encodedPath(old.url.toHttpUrl().encodedPath)
                        .build()
                assertEquals(502, client.range(stalePath.toString()).first)
                MediaProxy.release(old)
                assertEquals(206 to "2345", client.range(fresh.url))

                val handoff = MediaProxy.playbackSource(media, forceRelay = true)
                assertEquals(fresh, handoff)
                MediaProxy.release(fresh)
                assertEquals(206 to "2345", client.range(handoff.url))
                MediaProxy.release(handoff)
            } finally {
                MediaProxy.setRelayForeground(active = false)
                MediaProxy.setRelayForeground(active = true)
                stopKoin()
                upstream.stop(0)
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
                deleteTestRootPath(root)
            }
        }

    private fun OkHttpClient.range(url: String): Pair<Int, String> {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Range", "bytes=2-5")
                .build()
        return newCall(request).execute().use { it.code to it.body.string() }
    }
}
