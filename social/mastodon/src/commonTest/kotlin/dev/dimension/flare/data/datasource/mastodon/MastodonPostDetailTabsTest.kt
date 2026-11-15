package dev.dimension.flare.data.datasource.mastodon

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.datasource.guest.mastodon.GuestMastodonDataSource
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.notSupported
import dev.dimension.flare.data.network.mastodon.api.InstanceResources
import dev.dimension.flare.data.network.mastodon.api.createTimelineResources
import dev.dimension.flare.data.network.mastodon.api.model.APIVersions
import dev.dimension.flare.data.network.mastodon.api.model.InstanceData
import dev.dimension.flare.data.network.mastodon.api.model.InstanceInfoV1
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class MastodonPostDetailTabsTest {
    private val host = "example.social"
    private val statusKey = MicroBlogKey("post", host)

    @Test
    fun guestShowsThePublicSectionsWithRepliesAsTheContext() =
        runTest {
            val source = GuestMastodonDataSource(host = host, locale = "en")

            val catalog = assertNotNull(source.postDetailTabs(statusKey))

            assertEquals(listOf("replies", "reposted-by", "liked-by"), catalog.tabs.map { it.id })
            assertEquals("replies", catalog.replyTabId)
            assertTrue(catalog.tabs.first().includesPostContext)
        }

    @Test
    fun repostedByUsesLinkCursorForUserPages() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(host, request.url.host)
                    assertEquals("/api/v1/statuses/post/reblogged_by", request.url.encodedPath)
                    assertEquals("20", request.url.parameters["limit"])
                    when (request.url.parameters["max_id"]) {
                        null -> {
                            respond(
                                """[{"id":"boost-user","username":"boost"}]""",
                                headers =
                                    Headers.build {
                                        append(HttpHeaders.ContentType, "application/json")
                                        append(
                                            HttpHeaders.Link,
                                            "<https://$host/api/v1/statuses/post/reblogged_by?max_id=boost%2Bcursor>; rel=\"next\"",
                                        )
                                    },
                            )
                        }

                        "boost+cursor" -> {
                            respond(
                                """[{"id":"other-user","username":"other"}]""",
                                headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
                            )
                        }

                        else -> {
                            error("Unexpected cursor ${request.url.parameters["max_id"]}")
                        }
                    }
                }
            newClient(engine).use { client ->
                val loader = catalog(client).tabs.single { it.id == "reposted-by" }.loader(null)

                val first = loader.load(20, PagingRequest.Refresh)
                val user = assertIs<UiTimelineV2.User>(first.data.single())
                assertEquals(MicroBlogKey("boost-user", host), user.value.key)
                assertEquals(AccountType.GuestHost(host), user.accountType)
                assertEquals("boost+cursor", first.nextKey)

                val second = loader.load(20, PagingRequest.Append(nextKey = assertNotNull(first.nextKey)))
                assertEquals(MicroBlogKey("other-user", host), second.data.single().statusKey)
                assertNull(second.nextKey)
            }
        }

    @Test
    fun likedByIgnoresPreviousAndCrossOriginLinks() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("/api/v1/statuses/post/favourited_by", request.url.encodedPath)
                    respond(
                        """[{"id":"liked-user","username":"liked"}]""",
                        headers =
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/json")
                                append(HttpHeaders.Link, "<https://$host/api/v1/statuses/post/favourited_by?max_id=previous>; rel=\"prev\"")
                                append(
                                    HttpHeaders.Link,
                                    "<https://other.social/api/v1/statuses/post/favourited_by?max_id=foreign>; rel=\"next\"",
                                )
                            },
                    )
                }
            val accountKey = MicroBlogKey("me", host)
            newClient(engine).use { client ->
                val loader = catalog(client, accountKey).tabs.single { it.id == "liked-by" }.loader(null)

                val page = loader.load(20, PagingRequest.Refresh)

                val user = assertIs<UiTimelineV2.User>(page.data.single())
                assertEquals(AccountType.Specific(accountKey), user.accountType)
                assertNull(page.nextKey)
            }
        }

    @Test
    fun quotesRenderPostsWithTheSignedInAccount() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("/api/v1/statuses/post/quotes", request.url.encodedPath)
                    respond(
                        """
                        [
                          {
                            "id": "quote",
                            "content": "<p>A quote</p>",
                            "account": {"id": "author", "username": "author"},
                            "quote": {
                              "state": "accepted",
                              "quoted_status": {"id": "post", "account": {"id": "original", "username": "original"}}
                            }
                          }
                        ]
                        """.trimIndent(),
                        headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
                    )
                }
            val accountKey = MicroBlogKey("me", host)
            newClient(engine).use { client ->
                assertFalse(catalog(client, accountKey).tabs.any { it.id == "quotes" })
                val loader = catalog(client, accountKey, includeQuotes = true).tabs.single { it.id == "quotes" }.loader(null)

                val page = loader.load(20, PagingRequest.Refresh)

                val post = assertIs<UiTimelineV2.TimelinePostItem>(page.data.single())
                assertEquals(MicroBlogKey("quote", host), post.statusKey)
                assertEquals(
                    statusKey,
                    post.presentation.quotes
                        .single()
                        .statusKey,
                )
                assertEquals(AccountType.Specific(accountKey), post.accountType)
            }
        }

    @Test
    fun quoteCapabilityCachesTheAdvertisedVersion() =
        runTest {
            for ((version, expected) in listOf(null to false, 6L to false, 7L to true, 8L to true)) {
                var requests = 0
                val support =
                    MastodonQuoteSupport(
                        instanceResources {
                            requests++
                            InstanceData(apiVersions = version?.let { APIVersions(mastodon = it) })
                        },
                    )

                assertEquals(expected, support.isAvailable())
                assertEquals(expected, support.isAvailable())
                assertEquals(1, requests)
            }
        }

    @Test
    fun quoteCapabilityRecoversAfterInstanceMetadataFailure() =
        runTest {
            var requests = 0
            val clock = TestTimeSource()
            val support =
                MastodonQuoteSupport(
                    instanceResources {
                        requests++
                        check(requests > 1) { "Instance unavailable" }
                        InstanceData(apiVersions = APIVersions(mastodon = 7))
                    },
                    clock = clock,
                )

            assertFalse(support.isAvailable())
            assertFalse(support.isAvailable())
            assertEquals(1, requests)
            clock += 1.minutes
            assertTrue(support.isAvailable())
        }

    private fun newClient(engine: MockEngine) =
        HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(JSON) }
        }

    private fun catalog(
        client: HttpClient,
        accountKey: MicroBlogKey? = null,
        includeQuotes: Boolean = false,
    ) = mastodonPostDetailTabs(
        service =
            ktorfit {
                baseUrl("https://$host/")
                httpClient(client)
                converterFactories(ResponseConverterFactory())
            }.createTimelineResources(),
        host = host,
        statusKey = statusKey,
        accountKey = accountKey,
        includeQuotes = includeQuotes,
        repliesLoader = { notSupported() },
    )

    private fun instanceResources(fetch: suspend () -> InstanceData) =
        object : InstanceResources {
            override suspend fun instance(): InstanceData = fetch()

            override suspend fun instanceV1(): InstanceInfoV1 = error("Expected v2 metadata")
        }
}
