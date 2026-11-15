package dev.dimension.flare.data.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeiboDirectMessageLoggingTest {
    @Test
    fun absoluteWeiboChatAndSessionRequestsAreExcludedFromHttpLogging() {
        for (path in listOf("/message/msglist", "/api/chat/list", "/api/chat/send", "/api/config", "/api/remind/unread")) {
            assertTrue(
                HttpRequestBuilder().apply { url("https://m.weibo.cn$path?uid=42") }.isPrivateWeiboRequest(),
                path,
            )
        }
        assertFalse(HttpRequestBuilder().apply { url("https://m.weibo.cn/api/container/getIndex") }.isPrivateWeiboRequest())
        assertFalse(HttpRequestBuilder().apply { url("https://example.com/api/chat/send") }.isPrivateWeiboRequest())
    }
}
