package dev.dimension.flare.data.network.ai

import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.datastore.model.AiRequestHeader
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.network.ktorClient
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.util.PlatformUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.minutes

@Single
internal class OpenAIService(
    private val httpClientFactory: (HttpClientConfig<*>.() -> Unit) -> HttpClient = {
        ktorClient(protectRequestHeaders = true, config = it)
    },
) {
    suspend fun models(
        serverUrl: String,
        apiKey: String,
        customHeaders: List<AiRequestHeader> = emptyList(),
    ): List<String> =
        createClient(retryModels = true, hasCustomHeaders = customHeaders.isNotEmpty()).use { client ->
            client
                .get(serverUrl) {
                    url.appendPathSegments("models")
                    openAIRequestHeaders(apiKey, customHeaders).forEach { (name, value) -> header(name, value) }
                }.openAIBody<ModelsResponse>(customHeaders)
                .data
                .map { it.id }
                .sorted()
        }

    suspend fun chatCompletion(
        config: AppSettings.AiConfig.Type.OpenAI,
        prompt: String,
    ): String {
        val body = buildChatCompletionBody(config = config, prompt = prompt)
        val url = "${config.serverUrl.trimEnd('/')}/chat/completions"
        return createClient(hasCustomHeaders = config.customHeaders.isNotEmpty())
            .use { client ->
                client
                    .post(url) {
                        openAIRequestHeaders(config.apiKey, config.customHeaders).forEach { (name, value) -> header(name, value) }
                        setBody(TextContent(body.toString(), ContentType.Application.Json))
                    }.openAIBody<ChatCompletionResponse>(config.customHeaders)
                    .choices
            }.firstOrNull()
            ?.message
            ?.content
            .orEmpty()
            .trim()
    }

    internal fun buildChatCompletionBody(
        config: AppSettings.AiConfig.Type.OpenAI,
        prompt: String,
    ) = buildJsonObject {
        config.extraBody
            .takeIf { it.isNotBlank() }
            ?.let { JSON.parseToJsonElement(it).jsonObject }
            ?.forEach { (key, value) -> put(key, value) }
        put("model", config.model)
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    },
                )
            },
        )
        config.reasoningEffort
            .takeIf { it.isNotBlank() }
            ?.let { put("reasoning_effort", it) }
    }

    suspend fun chatCompletionOrNull(
        config: AppSettings.AiConfig.Type.OpenAI,
        prompt: String,
    ): String? =
        if (config.serverUrl.isBlank() || config.model.isBlank()) {
            null
        } else {
            chatCompletion(
                config = config,
                prompt = prompt,
            )
        }

    private suspend inline fun <reified T> HttpResponse.openAIBody(customHeaders: List<AiRequestHeader>): T {
        val response = bodyAsText()
        check(status.isSuccess()) { "OpenAI request failed with HTTP ${status.value}: ${response.redactAiHeaderValues(customHeaders)}" }
        try {
            return JSON.decodeFromString(response)
        } catch (error: SerializationException) {
            val message = error.message?.redactAiHeaderValues(customHeaders)
            if (message == error.message) throw error
            throw SerializationException(message)
        }
    }

    private fun createClient(
        retryModels: Boolean = false,
        hasCustomHeaders: Boolean,
    ): HttpClient =
        httpClientFactory {
            // The default response validator includes the raw gateway body in its exceptions.
            expectSuccess = !hasCustomHeaders
            // Fetch can follow redirects before proxy routing has a chance to strip credentials.
            if (hasCustomHeaders && PlatformUtils.IS_BROWSER) followRedirects = false
            if (retryModels) {
                install(HttpRequestRetry) {
                    retryIf(maxRetries = 3) { _, response -> response.status == HttpStatusCode.TooManyRequests }
                    // Preserve the model-list retry timing used by Aallam 4.1.0.
                    exponentialDelay(base = 2.0, baseDelayMs = 1.minutes.inWholeMilliseconds)
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 1.minutes.inWholeMilliseconds
                socketTimeoutMillis = 1.minutes.inWholeMilliseconds
                connectTimeoutMillis = 1.minutes.inWholeMilliseconds
            }
        }

    @Serializable
    private data class ModelsResponse(
        val data: List<Model>,
    )

    @Serializable
    private data class Model(
        val id: String,
    )

    @Serializable
    private data class ChatCompletionResponse(
        val choices: List<Choice>,
    )

    @Serializable
    private data class Choice(
        val message: Message,
    )

    @Serializable
    private data class Message(
        val content: String? = null,
    )
}
