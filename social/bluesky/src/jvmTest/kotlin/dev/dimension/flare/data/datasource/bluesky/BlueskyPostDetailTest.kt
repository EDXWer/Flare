package dev.dimension.flare.data.datasource.bluesky

import com.sun.net.httpserver.HttpServer
import dev.dimension.flare.common.TestFormatter
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PostContextLoader
import dev.dimension.flare.data.platform.BlueskyCredential
import dev.dimension.flare.di.startProxyTestStore
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.UiTimelineV2
import io.ktor.http.Url
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueskyPostDetailTest {
    private val accountKey = MicroBlogKey("did:plc:viewer", "bsky.social")
    private val focalKey = MicroBlogKey(postUri("anchor"), accountKey.host)

    @Test
    fun replyTabKeepsTheDefaultCacheAndIsolatesOtherSorts() =
        runBlocking {
            val source = source("https://bsky.example")
            val tabs = assertNotNull(source.postDetailTabs(focalKey))
            assertEquals(listOf("replies", "quotes", "reposted-by", "liked-by"), tabs.tabs.map { it.id })
            assertEquals("replies", tabs.replyTabId)
            val replies = tabs.tabs.first()
            assertTrue(replies.includesPostContext)
            val filter = assertNotNull(replies.filter)
            assertEquals("top", filter.defaultOptionId)
            assertEquals(listOf("top", "newest", "oldest"), filter.options.map { it.id })
            val legacyKey = "status_detail_${focalKey}_$accountKey"
            assertEquals(legacyKey, source.context(focalKey).pagingKey)
            val keys = filter.options.map { assertIs<PostContextLoader>(replies.loader(it.id)).pagingKey }
            assertEquals(legacyKey, keys.first())
            assertEquals(3, keys.toSet().size)
        }

    @Test
    fun replyOrdersKeepAncestorsAndUseTheRequestedServerSort() =
        runBlocking {
            withPds({ request ->
                when (request.segments.last()) {
                    "app.bsky.feed.getPosts" -> {
                        """{"posts":[${post("anchor")}]}"""
                    }

                    "app.bsky.unspecced.getPostThreadV2" -> {
                        assertEquals(focalKey.id, request.parameters["anchor"])
                        val sort = request.parameters["sort"]
                        val items = listOf(threadItem("parent", -1), threadItem("anchor", 0), threadItem("reply-$sort", 1))
                        """{"thread":[${items.joinToString()}],"hasOtherReplies":false}"""
                    }

                    else -> {
                        error("Unexpected request: $request")
                    }
                }
            }) { source ->
                val replies = assertNotNull(source.postDetailTabs(focalKey)).tabs.first()
                for (sort in listOf("top", "newest", "oldest")) {
                    val loader = assertIs<PostContextLoader>(replies.loader(sort))
                    val anchor = loader.load(20, PagingRequest.Refresh)
                    val request = PagingRequest.Append(assertNotNull(anchor.nextKey))
                    val thread = loader.load(20, request)
                    val context = loader.contextUpdate(request, thread, initial = true)
                    assertEquals(listOf(postUri("parent")), context.before?.keys?.map { it.id })
                    assertEquals(listOf(postUri("reply-$sort")), context.after?.keys?.map { it.id })
                }
            }
        }

    @Test
    fun quoteTabPreservesSortWhilePaging() =
        runBlocking {
            withPds({ request ->
                assertEquals("app.bsky.feed.getQuotes", request.segments.last())
                assertEquals(focalKey.id, request.parameters["uri"])
                val sort = request.parameters["sort"]
                when (val cursor = request.parameters["cursor"]) {
                    null -> """{"uri":"${focalKey.id}","posts":[${post("$sort-first")}],"cursor":"second"}"""
                    "second" -> """{"uri":"${focalKey.id}","posts":[${post("$sort-second")}]}"""
                    else -> error("Unexpected cursor: $cursor")
                }
            }) { source ->
                val quotes = assertNotNull(source.postDetailTabs(focalKey)).tabs.single { it.id == "quotes" }
                assertEquals("latest", quotes.filter?.defaultOptionId)
                for (sort in listOf("latest", "top")) {
                    val loader = quotes.loader(sort)
                    val first = loader.load(20, PagingRequest.Refresh)
                    val next = loader.load(20, PagingRequest.Append(assertNotNull(first.nextKey)))
                    assertEquals(listOf(postUri("$sort-first"), postUri("$sort-second")), (first.data + next.data).map { it.statusKey.id })
                    assertNull(next.nextKey)
                }
            }
        }

    @Test
    fun actorTabsPageThroughProfilesForTheCorrectAction() =
        runBlocking {
            withPds({ request ->
                assertEquals(focalKey.id, request.parameters["uri"])
                val page = request.parameters["cursor"] ?: "first"
                val cursor = if (page == "first") ",\"cursor\":\"second\"" else ""
                val data =
                    when (request.segments.last()) {
                        "app.bsky.feed.getRepostedBy" -> {
                            """"repostedBy":[${profile("reposted-$page")}]"""
                        }

                        "app.bsky.feed.getLikes" -> {
                            """"likes":[{"indexedAt":"$timestamp","createdAt":"$timestamp","actor":${profile("liked-$page")}}]"""
                        }

                        else -> {
                            error("Unexpected request: $request")
                        }
                    }
                """{"uri":"${focalKey.id}",$data$cursor}"""
            }) { source ->
                val tabs = assertNotNull(source.postDetailTabs(focalKey)).tabs
                for ((id, action) in listOf("reposted-by" to "reposted", "liked-by" to "liked")) {
                    val loader = tabs.single { it.id == id }.loader(null)
                    val first = loader.load(20, PagingRequest.Refresh)
                    val next = loader.load(20, PagingRequest.Append(assertNotNull(first.nextKey)))
                    val users = (first.data + next.data).map { assertIs<UiTimelineV2.User>(it) }
                    assertEquals(listOf("did:plc:$action-first", "did:plc:$action-second"), users.map { it.value.key.id })
                    assertNull(next.nextKey)
                }
            }
        }

    private fun source(baseUrl: String): BlueskyDataSource =
        BlueskyDataSource(accountKey, flowOf(BlueskyCredential.Password(baseUrl, "fixture-access", "fixture-refresh"))) {}

    private suspend fun withPds(
        response: (Url) -> String,
        test: suspend (BlueskyDataSource) -> Unit,
    ) = startProxyTestStore().use {
        GlobalContext.get().loadModules(listOf(module { single<PlatformFormatter> { TestFormatter() } }))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        server.createContext("/xrpc/") { exchange ->
            val url = Url("$origin${exchange.requestURI}")
            val body =
                if (url.segments.last() == "com.atproto.server.getSession") {
                    """{"did":"did:plc:viewer","handle":"viewer.bsky.social"}"""
                } else {
                    response(url)
                }.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            withTimeout(10_000) { test(source(origin)) }
        } finally {
            server.stop(0)
        }
    }

    private fun postUri(id: String) = "at://did:plc:author/app.bsky.feed.post/$id"

    private fun profile(id: String) = """{"did":"did:plc:$id","handle":"$id.bsky.social","displayName":"$id"}"""

    private fun post(id: String) =
        """{"uri":"${postUri(id)}","cid":"cid-$id","author":${profile("author")},"record":{"text":"$id"},"indexedAt":"$timestamp"}"""

    private fun threadItem(
        id: String,
        depth: Int,
    ) = """{"uri":"${postUri(
        id,
    )}","depth":$depth,"value":{"${'$'}type":"app.bsky.unspecced.defs#threadItemPost","post":${post(
        id,
    )},"moreParents":false,"moreReplies":0,"opThread":false,"hiddenByThreadgate":false,"mutedByViewer":false}}"""

    private val timestamp = "2024-01-01T00:00:00Z"
}
