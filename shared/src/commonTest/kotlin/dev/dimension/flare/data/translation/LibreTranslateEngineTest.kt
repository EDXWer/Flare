package dev.dimension.flare.data.translation

import dev.dimension.flare.common.decodeJson
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.ui.render.TranslationDocument
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibreTranslateEngineTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val translated =
        """{"translatedText":["Hello"],"detectedLanguage":{"language":"fr","confidence":100}}"""

    @Test
    fun translatesWithAutomaticSourceDetection() =
        runTest {
            client { request ->
                val body =
                    request.body
                        .toByteArray()
                        .decodeToString()
                        .decodeJson<JsonObject>()
                assertEquals("/libre/translate", request.url.encodedPath)
                assertEquals(JsonArray(listOf(JsonPrimitive("Bonjour"))), body["q"])
                assertEquals(JsonPrimitive("test-key"), body["api_key"])
                if (body["source"] == JsonPrimitive("auto")) {
                    respond(translated, headers = jsonHeaders)
                } else {
                    respond("""{"error":"Invalid request: missing source parameter"}""", HttpStatusCode.BadRequest, jsonHeaders)
                }
            }.use { client ->
                assertEquals("Hello", translate(client, "en"))
            }
        }

    @Test
    fun sendsServerLanguageCodesForSystemLocales() =
        runTest {
            for ((locale, code) in listOf("en-US" to "en", "pt_BR" to "pb", "zh-Hant-TW" to "zt", "zh-Hans-HK" to "zh")) {
                client { request ->
                    val body =
                        request.body
                            .toByteArray()
                            .decodeToString()
                            .decodeJson<JsonObject>()
                    assertEquals(JsonPrimitive(code), body["target"], "Target code for $locale")
                    respond(translated, headers = jsonHeaders)
                }.use { client ->
                    assertEquals("Hello", translate(client, locale))
                }
            }
        }

    @Test
    fun reportsServerErrorWhenTheApiKeyIsRejected() =
        runTest {
            client {
                respond("""{"error":"Invalid API key"}""", HttpStatusCode.Forbidden, jsonHeaders)
            }.use { client ->
                val failure = assertFailsWith<ClientRequestException> { translate(client, "en") }
                assertEquals(HttpStatusCode.Forbidden, failure.response.status)
                assertContains(failure.message.orEmpty(), "Invalid API key")
            }
        }

    private fun client(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine) {
            engine { addHandler(handler) }
            install(ContentNegotiation) { nullableFallbackJson() }
        }

    private suspend fun translate(
        client: HttpClient,
        targetLanguage: String,
    ): String =
        LibreTranslateEngine(
            config = AppSettings.TranslateConfig.Provider.LibreTranslate("https://example.com/libre/", "test-key"),
            httpClientFactory = { client },
        ).translateDocumentJson(
            sourceTemplate = "",
            sourceJson = """{"blocks":[{"id":0,"tokens":[{"id":0,"kind":"Translatable","text":"Bonjour"}]}]}""",
            targetLanguage = targetLanguage,
            prompt = "",
        ).decodeJson<TranslationDocument>()
            .blocks
            .single()
            .tokens
            .single()
            .text
}
