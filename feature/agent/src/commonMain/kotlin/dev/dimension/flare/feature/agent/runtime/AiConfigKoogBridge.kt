package dev.dimension.flare.feature.agent.runtime

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.datastore.model.AgentOpenAIApi
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.feature.agent.common.AgentConfigurationException
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

internal class AiConfigKoogBridge {
    private val lastSuccessfulEndpoint = MutableStateFlow<Pair<AppSettings.AiConfig.Type.OpenAI, OpenAIEndpoint>?>(null)

    fun availability(aiConfig: AppSettings.AiConfig): AgentAvailability =
        when (val type = aiConfig.effectiveAgentType) {
            AppSettings.AiConfig.Type.OnDevice -> {
                AgentAvailability.Unavailable(AgentAvailability.Reason.OnDeviceAiUnsupported)
            }

            is AppSettings.AiConfig.Type.OpenAI -> {
                when {
                    type.serverUrl.isBlank() -> {
                        AgentAvailability.Unavailable(AgentAvailability.Reason.MissingOpenAIEndpoint)
                    }

                    type.apiKey.isBlank() -> {
                        AgentAvailability.Unavailable(AgentAvailability.Reason.MissingOpenAIApiKey)
                    }

                    type.model.isBlank() -> {
                        AgentAvailability.Unavailable(AgentAvailability.Reason.MissingOpenAIModel)
                    }

                    else -> {
                        AgentAvailability.Available
                    }
                }
            }
        }

    fun createRuntime(
        aiConfig: AppSettings.AiConfig,
        httpClientFactory: KoogHttpClient.Factory,
        retryToolCalls: List<Message.Assistant> = emptyList(),
    ): FlareAgentRuntime? {
        if (availability(aiConfig) != AgentAvailability.Available) {
            return null
        }

        val openAIConfig = aiConfig.effectiveAgentType as? AppSettings.AiConfig.Type.OpenAI ?: return null
        val baseUrl = openAIConfig.serverUrl.trimEnd('/')
        val scope =
            JsonPrimitive(
                listOf(baseUrl, openAIConfig.model, openAIConfig.apiKey)
                    .joinToString("\u0000")
                    .encodeUtf8()
                    .sha256()
                    .hex(),
            )
        val explicitEndpoint =
            when (aiConfig.agentOpenAIApi) {
                AgentOpenAIApi.Auto -> null
                AgentOpenAIApi.Responses -> OpenAIEndpoint.Responses
                AgentOpenAIApi.ChatCompletions -> OpenAIEndpoint.Completions
            }
        val retryEndpoint =
            retryToolCalls.lastOrNull()?.let { call ->
                listOf(OpenAIEndpoint.Responses, OpenAIEndpoint.Completions)
                    .firstOrNull { call.metaInfo.metadata?.containsKey(it.scopeKey) == true }
                    ?: throw AgentConfigurationException(
                        "The original API connection for this turn's completed tools could not be verified. Send a new message to continue.",
                    )
            }
        if (retryEndpoint != null &&
            (
                (explicitEndpoint != null && explicitEndpoint != retryEndpoint) ||
                    retryToolCalls.any { it.metaInfo.metadata?.get(retryEndpoint.scopeKey) != scope }
            )
        ) {
            val apiName = if (retryEndpoint == OpenAIEndpoint.Responses) "Responses" else "Chat Completions"
            throw AgentConfigurationException(
                "This turn has completed tool results. Restore its API server, key, model, and $apiName protocol, or send a new message.",
            )
        }
        // shortcut: Koog forces Chat Completions on Azure OpenAI, revisit when its client supports Azure Responses.
        val azure = "openai.azure.com" in baseUrl
        if (azure && (explicitEndpoint ?: retryEndpoint) == OpenAIEndpoint.Responses) {
            throw AgentConfigurationException("This Azure connection uses Chat Completions. Select Auto or Chat Completions.")
        }
        val pinnedEndpoint = explicitEndpoint ?: retryEndpoint ?: OpenAIEndpoint.Completions.takeIf { azure }
        val contextWindow =
            openAIConfig.contextWindow?.also { require(it >= 1_024) { "Context window must be at least 1024 tokens" } }?.toLong()
                ?: OpenAIModels.models.firstOrNull { it.id == openAIConfig.model }?.contextLength
        val extraBody = openAIConfig.extraBody.takeIf { it.isNotBlank() }?.let { JSON.parseToJsonElement(it).jsonObject }
        val params =
            LLMParams(
                additionalProperties =
                    buildJsonObject {
                        extraBody
                            ?.filterKeys { it !in AGENT_REQUEST_FIELDS }
                            ?.forEach { (key, value) -> put(key, value) }
                        openAIConfig.reasoningEffort
                            .takeIf { it.isNotBlank() }
                            ?.let { put("reasoning_effort", it) }
                    },
            )
        val preferredEndpoint =
            lastSuccessfulEndpoint.value?.takeIf { it.first == openAIConfig }?.second
                ?: if (runCatching { Url(baseUrl).host.equals("api.openai.com", ignoreCase = true) }.getOrDefault(false)) {
                    OpenAIEndpoint.Responses
                } else {
                    OpenAIEndpoint.Completions
                }
        val client =
            AgentOpenAIClient(
                apiKey = openAIConfig.apiKey,
                settings =
                    OpenAIClientSettings(
                        baseUrl = baseUrl,
                        chatCompletionsPath = "chat/completions",
                        responsesAPIPath = "responses",
                        embeddingsPath = "embeddings",
                        moderationsPath = "moderations",
                        modelsPath = "models",
                    ),
                httpClientFactory = httpClientFactory,
                scope = scope,
                preferredEndpoint = preferredEndpoint,
                pinnedEndpoint = pinnedEndpoint,
            ) {
                if (explicitEndpoint == null) lastSuccessfulEndpoint.value = openAIConfig to it
            }
        val model =
            LLModel(
                provider = LLMProvider.OpenAI,
                id = openAIConfig.model,
                capabilities =
                    listOf(
                        LLMCapability.Completion,
                        LLMCapability.OpenAIEndpoint.Completions,
                        LLMCapability.Temperature,
                        LLMCapability.Tools,
                        LLMCapability.ToolChoice,
                        LLMCapability.Vision.Image,
                    ),
                contextLength = contextWindow,
            )

        return FlareAgentRuntime(
            promptExecutor = MultiLLMPromptExecutor(client),
            model = model,
            params = params,
        )
    }

    private companion object {
        // The agent owns conversation history, tool selection and response transport.
        val AGENT_REQUEST_FIELDS = setOf("model", "messages", "tools", "tool_choice", "stream", "stream_options", "n")
    }
}
