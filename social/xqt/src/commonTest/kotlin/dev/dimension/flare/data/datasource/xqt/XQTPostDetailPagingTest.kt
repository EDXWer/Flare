package dev.dimension.flare.data.datasource.xqt

import de.jensklingenberg.ktorfit.Ktorfit
import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PostContextLoader
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.network.xqt.XQT_JSON
import dev.dimension.flare.data.network.xqt.api.TweetApi
import dev.dimension.flare.data.network.xqt.api.createTweetApi
import dev.dimension.flare.data.network.xqt.api.createUserListApi
import dev.dimension.flare.data.platform.XQTCredential
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asText
import dev.dimension.flare.ui.model.mapper.XqtRenderTestModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.plugin.module.dsl.modules
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XQTPostDetailPagingTest {
    private val accountKey = MicroBlogKey("1", "x.com")
    private val focalKey = MicroBlogKey("120", "x.com")

    @BeforeTest
    fun setup() {
        startKoin {
            modules(XqtRenderTestModule::class)
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun replyFiltersPreserveTheLegacyDefaultCacheAndIsolateOtherOrders() =
        runTest {
            val source = XQTDataSource(accountKey, flowOf(XQTCredential(chocolate = "")))
            val catalog = source.postDetailTabs(focalKey)
            assertEquals(listOf("replies", "quotes", "reposted-by"), catalog.tabs.map { it.id })
            assertEquals(UiStrings.RepostedBy.asText(), catalog.tabs.last().title)
            assertEquals("replies", catalog.replyTabId)
            val replies = catalog.tabs.first()
            val filter = assertNotNull(replies.filter)
            assertEquals("Relevance", filter.defaultOptionId)
            assertEquals(listOf("Relevance", "Recency", "Likes"), filter.options.map { it.id })
            val legacyKey = "status_detail_120@x.com_1@x.com"
            assertEquals(legacyKey, source.context(focalKey).pagingKey)
            assertEquals(
                listOf(legacyKey, "${legacyKey}_Recency", "${legacyKey}_Likes"),
                filter.options.map { assertIs<PostContextLoader>(replies.loader(it.id)).pagingKey },
            )
        }

    @Test
    fun rankedReplyPagesKeepBackendOrderAndPageAfterOneReply() =
        runTest {
            withTweetApi(
                response = { request ->
                    assertEquals("TweetDetail", request.url.segments.last())
                    val variables = request.variables()
                    assertEquals("120", variables.getValue("focalTweetId").jsonPrimitive.content)
                    assertEquals("Likes", variables.getValue("rankingMode").jsonPrimitive.content)
                    val body =
                        when (variables["cursor"]?.jsonPrimitive?.contentOrNull) {
                            null -> {
                                detailPage(
                                    tweetEntry(100),
                                    tweetEntry(120, conversationId = 100),
                                    tweetEntry(180, conversationId = 100),
                                    tweetEntry(190, conversationId = 100),
                                    tweetEntry(200),
                                    bottomCursor("second"),
                                )
                            }

                            "second" -> {
                                detailPage(tweetEntry(150, conversationId = 100), bottomCursor("third", nested = true))
                            }

                            "third" -> {
                                detailPage(tweetEntry(170, conversationId = 100), bottomCursor("third"))
                            }

                            else -> {
                                error("Unexpected cursor")
                            }
                        }
                    HttpStatusCode.OK to body
                },
            ) { service ->
                val loader = RankedStatusDetailLoader(focalKey, service, accountKey, rankingMode = ReplyRanking.Likes)

                val first = loader.load(20, PagingRequest.Refresh)
                val update = loader.contextUpdate(PagingRequest.Refresh, first, initial = true)
                assertEquals(listOf("100"), update.before?.keys?.map { it.id })
                assertEquals(listOf("180", "190"), update.after?.keys?.map { it.id })

                val second = loader.load(20, PagingRequest.Append(assertNotNull(first.nextKey)))
                assertEquals(listOf("150"), second.data.map { it.statusKey.id })
                assertEquals("third", second.nextKey)

                val third = loader.load(20, PagingRequest.Append(assertNotNull(second.nextKey)))
                assertEquals(listOf("170"), third.data.map { it.statusKey.id })
                assertEquals(null, third.nextKey)
            }
        }

    @Test
    fun quotePagingSkipsUnrelatedResultsAndContinuesThroughReplacementCursors() =
        runTest {
            withTweetApi(
                response = { request ->
                    assertEquals("SearchTimeline", request.url.segments.last())
                    val variables = request.variables()
                    assertEquals("quoted_tweet_id:120", variables.getValue("rawQuery").jsonPrimitive.content)
                    assertEquals("tdqt", variables.getValue("querySource").jsonPrimitive.content)
                    assertEquals("Top", variables.getValue("product").jsonPrimitive.content)
                    val body =
                        when (variables["cursor"]?.jsonPrimitive?.contentOrNull) {
                            null -> {
                                searchPage(addEntries(tweetEntry(201, quotedId = 100), bottomCursor("second")))
                            }

                            "second" -> {
                                searchPage(
                                    addEntries(
                                        tweetEntry(202, quotedId = 120),
                                        tweetEntry(203, quotedId = 120, retweetedId = 202),
                                    ),
                                    """{"type":"TimelineReplaceEntry","entry_id_to_replace":"cursor-bottom","entry":${bottomCursor(
                                        "third",
                                    )}}""",
                                )
                            }

                            "third" -> {
                                searchPage(addEntries(tweetEntry(204, embeddedQuoteId = 120)))
                            }

                            else -> {
                                error("Unexpected cursor")
                            }
                        }
                    HttpStatusCode.OK to body
                },
            ) { service ->
                val loader = SearchStatusPagingSource(service, accountKey, focalKey)

                val first = loader.load(20, PagingRequest.Refresh)
                assertEquals(listOf("202"), first.data.map { it.statusKey.id })
                assertEquals("third", first.nextKey)

                val next = loader.load(20, PagingRequest.Append(assertNotNull(first.nextKey)))
                assertEquals(listOf("204"), next.data.map { it.statusKey.id })
                assertEquals(null, next.nextKey)
            }
        }

    @Test
    fun quotePagingPropagatesHttpAndGraphQlErrors() =
        runTest {
            val emptySearch = searchPage(addEntries())
            val failures =
                listOf(
                    HttpStatusCode.TooManyRequests to emptySearch,
                    HttpStatusCode.OK to emptySearch.dropLast(1) + ",\"errors\":[{\"message\":\"Rate limited\"}]}",
                )
            failures.forEach { reply ->
                withTweetApi(response = { reply }) { service ->
                    val loader = SearchStatusPagingSource(service, accountKey, focalKey)
                    assertFails { loader.load(20, PagingRequest.Refresh) }
                }
            }
        }

    @Test
    fun repostedBySkipsInvisibleEntriesAndUsesReplacementCursors() =
        runTest {
            withKtorfit(
                response = { request ->
                    assertEquals("Retweeters", request.url.segments.last())
                    val variables = request.variables()
                    assertEquals(focalKey.id, variables.getValue("tweetId").jsonPrimitive.content)
                    assertEquals("20", variables.getValue("count").jsonPrimitive.content)
                    assertEquals("false", variables.getValue("includePromotedContent").jsonPrimitive.content)
                    val body =
                        when (variables["cursor"]?.jsonPrimitive?.contentOrNull) {
                            null -> {
                                repostedByPage(addEntries(bottomCursor("visible")))
                            }

                            "visible" -> {
                                repostedByPage(
                                    addEntries(userEntry(21), bottomCursor("old")),
                                    """{"type":"TimelineReplaceEntry","entry_id_to_replace":"cursor-bottom","entry":${bottomCursor(
                                        "next",
                                    )}}""",
                                )
                            }

                            "next" -> {
                                repostedByPage(addEntries(userEntry(22), bottomCursor("next")))
                            }

                            else -> {
                                error("Unexpected retweeters cursor")
                            }
                        }
                    HttpStatusCode.OK to body
                },
            ) { ktorfit ->
                val loader = RepostedByLoader(ktorfit.createUserListApi(), accountKey, focalKey)
                val first = loader.load(20, PagingRequest.Refresh)
                val firstUser = assertIs<UiTimelineV2.User>(first.data.single())
                assertEquals(MicroBlogKey("21", "x.com"), firstUser.value.key)
                assertEquals(AccountType.Specific(accountKey), firstUser.accountType)
                assertEquals("next", first.nextKey)

                val next = loader.load(20, PagingRequest.Append(assertNotNull(first.nextKey)))
                assertEquals("22", assertIs<UiTimelineV2.User>(next.data.single()).value.key.id)
                assertNull(next.nextKey)
            }
        }

    @Test
    fun repostedByDistinguishesAnEmptyTimelineFromRequestErrors() =
        runTest {
            withKtorfit(response = { HttpStatusCode.OK to repostedByPage() }) { ktorfit ->
                val loader = RepostedByLoader(ktorfit.createUserListApi(), accountKey, focalKey)
                val result = loader.load(20, PagingRequest.Refresh)
                assertEquals(emptyList(), result.data)
                assertNull(result.nextKey)
            }
            val error = """{"data":{"retweeters_timeline":{"timeline":{"instructions":[]}}},"errors":[{"message":"Unavailable"}]}"""
            for (response in listOf(HttpStatusCode.Forbidden to "{}", HttpStatusCode.OK to error)) {
                withKtorfit(response = { response }) { ktorfit ->
                    val loader = RepostedByLoader(ktorfit.createUserListApi(), accountKey, focalKey)
                    assertFails { loader.load(20, PagingRequest.Refresh) }
                }
            }
        }

    private suspend fun withTweetApi(
        response: (HttpRequestData) -> Pair<HttpStatusCode, String>,
        test: suspend (TweetApi) -> Unit,
    ) = withKtorfit(response) { test(it.createTweetApi()) }

    private suspend fun withKtorfit(
        response: (HttpRequestData) -> Pair<HttpStatusCode, String>,
        test: suspend (Ktorfit) -> Unit,
    ) {
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val (status, body) = response(request)
                        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                install(ContentNegotiation) { nullableFallbackJson(XQT_JSON) }
            }
        try {
            val service =
                ktorfit {
                    baseUrl("https://example.com/")
                    httpClient(client)
                    converterFactories(ResponseConverterFactory())
                }
            test(service)
        } finally {
            client.close()
        }
    }

    private fun HttpRequestData.variables() = XQT_JSON.parseToJsonElement(checkNotNull(url.parameters["variables"])).jsonObject

    private fun searchPage(vararg instructions: String): String =
        """{"data":{"search_by_raw_query":{"search_timeline":{"timeline":{"instructions":[${instructions.joinToString()}]}}}}}"""

    private fun repostedByPage(vararg instructions: String): String =
        """{"data":{"retweeters_timeline":{"timeline":{"instructions":[${instructions.joinToString()}]}}}}"""

    private fun userEntry(id: Int): String =
        """
        {"entryId":"user-$id","sortIndex":"$id","content":{"entryType":"TimelineTimelineItem",
         "itemContent":{"__typename":"TimelineUser","itemType":"TimelineUser",
         "user_results":{"result":{"__typename":"User","rest_id":"$id"}}}}}
        """.trimIndent()

    private fun detailPage(vararg entries: String): String =
        """{"data":{"threaded_conversation_with_injections_v2":{"instructions":[${addEntries(*entries)}]}}}"""

    private fun addEntries(vararg entries: String): String = """{"type":"TimelineAddEntries","entries":[${entries.joinToString()}]}"""

    private fun bottomCursor(
        value: String,
        nested: Boolean = false,
    ): String {
        val cursor = "\"cursorType\":\"Bottom\",\"value\":\"$value\""
        val content =
            if (nested) {
                """{"entryType":"TimelineTimelineItem","itemContent":{"__typename":"TimelineTimelineCursor",$cursor}}"""
            } else {
                """{"entryType":"TimelineTimelineCursor",$cursor}"""
            }
        return """{"entryId":"cursor-bottom","sortIndex":"1","content":$content}"""
    }

    private fun tweetEntry(
        id: Int,
        conversationId: Int = id,
        quotedId: Int? = null,
        embeddedQuoteId: Int? = null,
        retweetedId: Int? = null,
    ): String {
        val embeddedQuote =
            embeddedQuoteId?.let { ",\"quoted_status_result\":{\"result\":{\"__typename\":\"Tweet\",\"rest_id\":\"$it\"}}" }.orEmpty()
        return """
            {
              "entryId":"tweet-$id","sortIndex":"$id",
              "content":{"entryType":"TimelineTimelineItem","itemContent":{"__typename":"TimelineTweet","itemType":"TimelineTweet","tweet_results":{"result":{
                "__typename":"Tweet","rest_id":"$id",
                "legacy":{
                  "id_str":"$id","conversation_id_str":"$conversationId","created_at":"Tue Oct 10 20:19:24 +0000 2023",
                  "display_text_range":[0,4],"entities":{},"favorite_count":0,"favorited":false,"full_text":"Post",
                  "is_quote_status":${quotedId != null || embeddedQuoteId != null},"lang":"en","quote_count":0,"reply_count":0,
                  "retweet_count":0,"retweeted":false,"quoted_status_id_str":${quotedId?.let { "\"$it\"" }},
                  "retweeted_status_id_str":${retweetedId?.let { "\"$it\"" }}
                }$embeddedQuote
              }}}}
            }
            """.trimIndent()
    }
}
