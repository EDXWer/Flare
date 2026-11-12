package dev.dimension.flare.feature.agent.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import dev.dimension.flare.feature.agent.common.upstreamErrorMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val AGENT_RESPONSES_SCOPE = "flare_responses_scope"

internal val OpenAIEndpoint.scopeKey: String
    get() =
        when (this) {
            OpenAIEndpoint.Completions -> "flare_completions_scope"
            OpenAIEndpoint.Responses -> AGENT_RESPONSES_SCOPE
        }

internal class AgentOpenAIClient(
    apiKey: String,
    settings: OpenAIClientSettings,
    httpClientFactory: KoogHttpClient.Factory,
    private val scope: JsonPrimitive,
    private val preferredEndpoint: OpenAIEndpoint,
    private val pinnedEndpoint: OpenAIEndpoint?,
    private val confirmEndpoint: (OpenAIEndpoint) -> Unit,
) : OpenAILLMClient(apiKey = apiKey, settings = settings, httpClientFactory = httpClientFactory) {
    private var confirmedEndpoint: OpenAIEndpoint? = null

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        val pinned = pinnedEndpoint ?: confirmedEndpoint
        var endpoint = pinned ?: preferredEndpoint
        val response =
            try {
                executeOn(endpoint, prompt, model, tools)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (pinned != null || !error.rejectsEndpoint(endpoint)) throw error
                endpoint =
                    when (endpoint) {
                        OpenAIEndpoint.Completions -> OpenAIEndpoint.Responses
                        OpenAIEndpoint.Responses -> OpenAIEndpoint.Completions
                    }
                executeOn(endpoint, prompt, model, tools)
            }
        if (tools.isNotEmpty() && confirmedEndpoint == null) {
            confirmedEndpoint = endpoint
            confirmEndpoint(endpoint)
        }
        return response
    }

    private suspend fun executeOn(
        endpoint: OpenAIEndpoint,
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        val useResponses = endpoint == OpenAIEndpoint.Responses
        val scopedPrompt =
            prompt.withMessages { messages ->
                messages.mapNotNull { message ->
                    if (message !is Message.Assistant) return@mapNotNull message
                    if (message.metaInfo.metadata?.get(endpoint.scopeKey) == scope) return@mapNotNull message
                    val parts = message.parts.filterNot { it is MessagePart.Reasoning }
                    parts.takeIf { it.isNotEmpty() }?.let {
                        message.copy(parts = it, metaInfo = message.metaInfo.copy(metadata = null), rawResponse = null)
                    }
                }
            }
        val response =
            if (useResponses) {
                val responsesModel =
                    model.copy(
                        capabilities =
                            model.capabilities.orEmpty().filterNot { it is OpenAIEndpoint } +
                                listOf(OpenAIEndpoint.Responses, LLMCapability.Thinking),
                    )
                super.execute(scopedPrompt.withParams(prompt.params.forResponses()), responsesModel, tools)
            } else {
                super.execute(scopedPrompt, model, tools)
            }
        return response.copy(
            metaInfo =
                response.metaInfo.copy(
                    metadata = JsonObject(response.metaInfo.metadata.orEmpty() + (endpoint.scopeKey to scope)),
                ),
        )
    }
}

private fun LLMParams.forResponses(): OpenAIResponsesParams {
    val extra = additionalProperties.orEmpty()
    val reasoning = (extra["reasoning"] as? JsonObject).orEmpty().toMutableMap()
    extra["reasoning_effort"]?.let { reasoning["effort"] = it }
    val reasoningDisabled = reasoning["effort"] == JsonPrimitive("none")
    val properties = extra.filterKeys { it !in RESPONSES_RESERVED_FIELDS && (reasoningDisabled || it !in SAMPLING_FIELDS) }.toMutableMap()
    if (reasoning.isNotEmpty()) properties["reasoning"] = JsonObject(reasoning)
    if (maxTokens == null) {
        (extra["max_output_tokens"] ?: extra["max_completion_tokens"] ?: extra["max_tokens"])?.let {
            properties["max_output_tokens"] = it
        }
    }
    val include = properties["include"] as? JsonArray
    if (!reasoningDisabled && include != null && LOGPROBS_INCLUDE in include) {
        val filtered = include.filterNot { it == LOGPROBS_INCLUDE }
        if (filtered.isEmpty()) properties.remove("include") else properties["include"] = JsonArray(filtered)
    }
    return OpenAIResponsesParams(
        temperature = temperature.takeIf { reasoningDisabled },
        maxTokens = maxTokens,
        schema = schema,
        toolChoice = toolChoice,
        store = false,
        additionalProperties = properties,
    )
}

private fun Exception.rejectsEndpoint(endpoint: OpenAIEndpoint): Boolean {
    val error = generateSequence<Throwable>(this) { it.cause }.filterIsInstance<KoogHttpClientException>().firstOrNull() ?: return false
    val status = error.statusCode
    if (status == 405) return true
    if (status != 400 && status != 404 && status != 422) return false
    val body = error.errorBody.orEmpty().take(16_384)
    val objectBody = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val details = objectBody?.get("error") as? JsonObject ?: objectBody
    val code = (details?.get("code") as? JsonPrimitive)?.contentOrNull?.lowercase()
    if (code == "model_not_found" || (details?.get("param") as? JsonPrimitive)?.contentOrNull == "model") return false
    if (code in setOf("unsupported_endpoint", "endpoint_not_found", "route_not_found")) return true
    val message = body.upstreamErrorMessage().trim().lowercase()
    if (status == 404 && (message in ROUTE_NOT_FOUND_MESSAGES || (message.startsWith("<") && "404 not found" in message))) return true
    val (path, name, alternativePath) =
        when (endpoint) {
            OpenAIEndpoint.Completions -> Triple("/chat/completions", "chat completions", "/responses")
            OpenAIEndpoint.Responses -> Triple("/responses", "responses api", "/chat/completions")
        }
    val prefix = """(?:/[a-z0-9._~-]+)*"""
    val requiresAlternate =
        path in message &&
            "not supported" in message &&
            Regex("""\bto use function tools,\s*use $prefix$alternativePath\b""").containsMatchIn(message)
    return Regex("""\b(?:cannot post|unrecognized request url \(post) $prefix$path\b""").containsMatchIn(message) ||
        Regex("""^(?:the )?(?:$prefix$path|$name)(?: endpoint)? is (?:not supported|not implemented)\b""").containsMatchIn(message) ||
        requiresAlternate
}

private val ROUTE_NOT_FOUND_MESSAGES = setOf("", "not found", "404", "404 not found", "404 page not found", "route not found")
private val SAMPLING_FIELDS = setOf("temperature", "top_p", "top_logprobs")
private val LOGPROBS_INCLUDE = JsonPrimitive("message.output_text.logprobs")
private val RESPONSES_RESERVED_FIELDS =
    setOf(
        "input",
        "background",
        "conversation",
        "previous_response_id",
        "store",
        "reasoning_effort",
        "reasoning",
        "max_output_tokens",
        "max_completion_tokens",
        "max_tokens",
        "seed",
        "frequency_penalty",
        "presence_penalty",
        "response_format",
        "logprobs",
    )
