package dev.dimension.flare.data.datasource.vvo

import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.PostActionFamily
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.network.vvo.VVOService
import dev.dimension.flare.data.platform.VVoCredential
import dev.dimension.flare.data.repository.LoginExpiredException
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asText
import dev.dimension.flare.ui.model.contentPostOrNull
import dev.dimension.flare.ui.route.DeeplinkRoute
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StatusCommentRemoteMediatorTest {
    @Test
    fun postDetailOffersLikedByAfterCommentsAndReposts() =
        runTest {
            val source = VVODataSource(accountKey, flowOf(VVoCredential(chocolate = "")))

            val catalog = source.postDetailTabs(statusKey)

            assertEquals(listOf("comments", "reposts", "liked-by"), catalog.tabs.map { it.id })
            assertEquals(UiStrings.LikedBy.asText(), catalog.tabs.last().title)
            assertNull(catalog.tabs.last().filter)
            assertEquals("comments", catalog.replyTabId)
        }

    @Test
    fun hotCommentsUseWebFlowAndKeepThePostAsTheReplyRoot() =
        runTest {
            val service =
                service { request ->
                    assertEquals("weibo.com", request.url.host)
                    assertEquals("/ajax/statuses/buildComments", request.url.encodedPath)
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("MLOGIN=1; SUB=example", request.headers[HttpHeaders.Cookie])
                    assertEquals("https://weibo.com/detail/5357831171867297", request.headers[HttpHeaders.Referrer])
                    assertEquals("5357831171867297", request.url.parameters["id"])
                    assertEquals("0", request.url.parameters["is_mix"])
                    assertEquals("0", request.url.parameters["fetch_level"])
                    assertEquals("1", request.url.parameters["flow"])
                    assertEquals("20", request.url.parameters["count"])
                    assertNull(request.url.parameters["max_id"])
                    """
                    {"ok":1,"data":[{"id":5358284531043660,"mid":"5358284531043660","text":"评论",
                    "like_counts":14,"like":1,"total_number":5,"user":{"id":30,"screen_name":"甲"}}],"max_id":"1456657314135"}
                    """.trimIndent()
                }
            val page = StatusCommentRemoteMediator(service, statusKey, accountKey).load(20, PagingRequest.Refresh)

            assertEquals("1456657314135", page.nextKey)
            val comment = assertNotNull(page.data.single().contentPostOrNull())
            val actions = comment.actions.filterIsInstance<ActionMenu.Item>()
            assertEquals("5358284531043660", comment.statusKey.id)
            val likeAction = actions.single { it.actionFamily == PostActionFamily.Like }
            assertEquals(14L, likeAction.count?.value)
            assertEquals(UiIcon.Unlike, likeAction.icon)
            val replyAction = actions.single { it.actionFamily == PostActionFamily.Comment }
            assertEquals(5L, replyAction.count?.value)
            val route =
                assertIs<DeeplinkRoute.Compose.VVOReplyComment>(
                    DeeplinkRoute.parse(assertIs<ClickEvent.Deeplink>(replyAction.clickEvent).url),
                )
            assertEquals(comment.statusKey, route.replyTo)
            assertEquals(statusKey.id, route.rootId)
        }

    @Test
    fun hotAndTimePagesKeepTheirOwnCursors() =
        runTest {
            val service =
                service { request ->
                    val flow = request.url.parameters["flow"]
                    val cursor = request.url.parameters["max_id"]
                    when (flow to cursor) {
                        "1" to null -> """{"ok":1,"data":[{"id":101}],"max_id":201}"""
                        "0" to null -> """{"ok":1,"data":{"data":[{"id":"102"}],"max_id":"202"}}"""
                        "1" to "201" -> """{"ok":1,"data":[{"id":103}],"max_id":201}"""
                        "0" to "202" -> """{"ok":1,"data":[{"id":104}],"max_id":0}"""
                        else -> error("Unexpected flow $flow and cursor $cursor")
                    }
                }
            val hot = StatusCommentRemoteMediator(service, statusKey, accountKey, StatusCommentSort.Hot)
            val time = StatusCommentRemoteMediator(service, statusKey, accountKey, StatusCommentSort.Time)

            assertNotEquals(hot.pagingKey, time.pagingKey)
            val hotCursor = assertNotNull(hot.load(20, PagingRequest.Refresh).nextKey)
            val timeCursor = assertNotNull(time.load(20, PagingRequest.Refresh).nextKey)
            val hotAppend = hot.load(20, PagingRequest.Append(hotCursor))
            val timeAppend = time.load(20, PagingRequest.Append(timeCursor))

            val hotComment = assertNotNull(hotAppend.data.single().contentPostOrNull())
            val timeComment = assertNotNull(timeAppend.data.single().contentPostOrNull())
            assertEquals("103", hotComment.statusKey.id)
            assertNull(hotAppend.nextKey)
            assertEquals("104", timeComment.statusKey.id)
            assertNull(timeAppend.nextKey)
        }

    @Test
    fun webSessionFailureNeedsRelogin() =
        runTest {
            val service = service { """{"ok":-100,"url":"https://passport.weibo.com/sso/signin"}""" }
            val loader = StatusCommentRemoteMediator(service, statusKey, accountKey)

            val error = assertFailsWith<LoginExpiredException> { loader.load(20, PagingRequest.Refresh) }

            assertEquals(accountKey, error.accountKey)
        }

    @Test
    fun emptyCommentsResponseEndsPagination() =
        runTest {
            val service = service { """{"ok":0,"msg":"暂无数据"}""" }
            val loader = StatusCommentRemoteMediator(service, statusKey, accountKey)

            val page = loader.load(20, PagingRequest.Refresh)

            assertEquals(emptyList(), page.data)
            assertNull(page.nextKey)
        }

    @Test
    fun likedByReadsTheMobileListAndPagesPastHiddenUsers() =
        runTest {
            val firstPage =
                """
                {"ok":1,"data":{"data":[{"created_at":"2026-11-15T06:00:00Z","user":{"id":30,"screen_name":"甲"}}],"total_number":99}}
                """.trimIndent()
            val service =
                service { request ->
                    assertEquals("m.weibo.cn", request.url.host)
                    assertEquals("/api/attitudes/show", request.url.encodedPath)
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("MLOGIN=1; SUB=example", request.headers[HttpHeaders.Cookie])
                    assertEquals("https://m.weibo.cn/detail/${statusKey.id}", request.headers[HttpHeaders.Referrer])
                    assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
                    assertEquals("1", request.headers["MWeibo-Pwa"])
                    assertEquals(statusKey.id, request.url.parameters["id"])
                    when (request.url.parameters["page"]) {
                        "1" -> firstPage
                        "2" -> """{"ok":1,"data":{"data":[{"user":null},{"user":{"screen_name":"用户不可用"}}]}}"""
                        "3" -> """{"ok":1,"data":{"data":[{"user":{"id":31,"screen_name":"乙"}}]}}"""
                        "4" -> """{"ok":0,"msg":"这里还没有内容"}"""
                        else -> error("Unexpected Weibo likes page")
                    }
                }
            val loader = StatusLikedByLoader(service, accountKey, statusKey)
            val first = loader.load(20, PagingRequest.Refresh)
            assertEquals("30", assertIs<UiTimelineV2.User>(first.data.single()).value.key.id)
            assertEquals("2", first.nextKey)

            val second = loader.load(20, PagingRequest.Append(assertNotNull(first.nextKey)))
            assertEquals("31", assertIs<UiTimelineV2.User>(second.data.single()).value.key.id)
            assertEquals("4", second.nextKey)

            val last = loader.load(20, PagingRequest.Append(assertNotNull(second.nextKey)))
            assertEquals(emptyList(), last.data)
            assertNull(last.nextKey)
        }

    @Test
    fun likedBySurfacesExpiredSessionsAndUnexpectedErrors() =
        runTest {
            val expired = StatusLikedByLoader(service { """{"ok":-100}""" }, accountKey, statusKey)
            val error = assertFailsWith<LoginExpiredException> { expired.load(20, PagingRequest.Refresh) }
            assertEquals(accountKey, error.accountKey)

            val failed = StatusLikedByLoader(service { """{"ok":0,"msg":"访问频繁"}""" }, accountKey, statusKey)
            assertFailsWith<IllegalStateException> { failed.load(20, PagingRequest.Refresh) }
        }

    private fun service(webResponse: (HttpRequestData) -> String): VVOService =
        VVOService(
            credentialFlow = flowOf(VVoCredential(chocolate = "MLOGIN=1; SUB=example")),
            httpClientFactory = { config ->
                HttpClient(
                    MockEngine { request ->
                        val body =
                            if (request.url.encodedPath == "/api/config") {
                                """{"ok":1,"data":{"login":true,"st":"st-test"}}"""
                            } else {
                                webResponse(request)
                            }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { config.invoke(this) }
            },
        )

    private val accountKey = MicroBlogKey(id = "23", host = "m.weibo.cn")
    private val statusKey = MicroBlogKey(id = "5357831171867297", host = "m.weibo.cn")
}
