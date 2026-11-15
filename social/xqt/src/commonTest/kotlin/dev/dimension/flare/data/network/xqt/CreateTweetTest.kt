package dev.dimension.flare.data.network.xqt

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.data.datasource.microblog.ComposeReplyRestriction
import dev.dimension.flare.data.network.xqt.api.createPostApi
import dev.dimension.flare.data.network.xqt.api.publishTweet
import dev.dimension.flare.data.network.xqt.model.PostCreateTweetRequest
import dev.dimension.flare.data.network.xqt.model.PostCreateTweetRequestFeatures
import dev.dimension.flare.data.network.xqt.model.PostCreateTweetRequestVariables
import dev.dimension.flare.data.network.xqt.model.PostCreateTweetRequestVariablesMedia
import dev.dimension.flare.data.network.xqt.model.toXqtConversationControl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CreateTweetTest {
    @Test
    fun replyRestrictionUsesTheCreateTweetModeAndOmitsTheDefault() {
        val modes =
            mapOf(
                ComposeReplyRestriction.Following to "Community",
                ComposeReplyRestriction.Mentioned to "ByInvitation",
                ComposeReplyRestriction.Verified to "Verified",
            )
        val variables =
            PostCreateTweetRequestVariables(
                media = PostCreateTweetRequestVariablesMedia(emptyList()),
                semanticAnnotationIds = emptyList(),
            )
        assertFalse("conversation_control" in XQT_JSON.encodeToJsonElement(variables).jsonObject)
        modes.forEach { (restriction, mode) ->
            val json = XQT_JSON.encodeToJsonElement(variables.copy(conversationControl = restriction.toXqtConversationControl()))
            assertEquals(mode, json.jsonObject["conversation_control"]?.jsonObject?.get("mode")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun requestIncludesPollCardUriWhenPresent() {
        val variables =
            PostCreateTweetRequestVariables(
                media = PostCreateTweetRequestVariablesMedia(emptyList()),
                semanticAnnotationIds = emptyList(),
            )
        assertFalse("card_uri" in XQT_JSON.encodeToJsonElement(variables).jsonObject)
        val poll = XQT_JSON.encodeToJsonElement(variables.copy(cardUri = "card://1234"))
        assertEquals("card://1234", poll.jsonObject["card_uri"]?.jsonPrimitive?.content)
    }

    @Test
    fun httpRejectionFailsPublishing() =
        runTest {
            val error = assertFailsWith<IllegalStateException> {
                publish(HttpStatusCode.TooManyRequests, "{}")
            }
            assertContains(error.message.orEmpty(), "429")
        }

    @Test
    fun graphqlRejectionFailsPublishingWithServerMessage() =
        runTest {
            val error = assertFailsWith<IllegalStateException> {
                publish(HttpStatusCode.OK, """{"errors":[{"message":"The post is a duplicate."}]}""")
            }
            assertEquals("The post is a duplicate.", error.message)
        }

    @Test
    fun emptyResultDoesNotConfirmPublishing() =
        runTest {
            assertFailsWith<IllegalStateException> {
                publish(HttpStatusCode.OK, """{"data":{"create_tweet":{"tweet_results":{}}}}""")
            }
        }

    @Test
    fun returnedPostIdConfirmsPublishing() =
        runTest {
            publish(
                HttpStatusCode.OK,
                """{"data":{"create_tweet":{"tweet_results":{"result":{"rest_id":"12345"}}}},"errors":[{"message":"Optional data unavailable"}]}""",
            )
        }

    private suspend fun publish(
        status: HttpStatusCode,
        body: String,
    ) {
        val client = HttpClient(MockEngine({ respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })) {
            install(ContentNegotiation) { json(XQT_JSON) }
        }
        try {
            ktorfit {
                baseUrl("https://example.com/")
                httpClient(client)
                converterFactories(ResponseConverterFactory())
            }.createPostApi().publishTweet(
                PostCreateTweetRequest(
                    features = PostCreateTweetRequestFeatures(),
                    variables =
                        PostCreateTweetRequestVariables(
                            media = PostCreateTweetRequestVariablesMedia(emptyList()),
                            semanticAnnotationIds = emptyList(),
                            tweetText = "test post",
                        ),
                ),
            )
        } finally {
            client.close()
        }
    }
}
