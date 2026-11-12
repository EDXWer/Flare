package dev.dimension.flare.feature.agent.runtime

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.serialization.typeToken
import dev.dimension.flare.data.datastore.model.AgentOpenAIApi
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.feature.agent.common.AgentConfigurationException
import dev.dimension.flare.feature.agent.common.AgentToolSet
import dev.dimension.flare.feature.agent.common.FlareAgentRequest
import dev.dimension.flare.feature.agent.common.createAgent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentAiConfigurationTest {
    private val basic = AppSettings.AiConfig.Type.OpenAI("https://basic.example/v1/", "basic-key", "light", "low", "{\"seed\":1}")
    private val independent =
        AppSettings.AiConfig.Type.OpenAI(
            "https://agent.example/custom/v1/",
            "agent-key",
            "strong",
            "high",
            "{\"seed\":2}",
        )

    @Test
    fun contextWindowUsesAnOverrideOrKnownMetadataAndLeavesUnknownModelsUnknown() {
        val client = HttpClient(MockEngine { error("Model metadata must not require a request") })
        try {
            for ((config, expected) in listOf(
                basic to null,
                basic.copy(model = "gpt-4o") to 128_000L,
                basic.copy(model = "gpt-4o", contextWindow = 32_768) to 32_768L,
                basic.copy(contextWindow = 16_384) to 16_384L,
            )) {
                val runtime =
                    assertNotNull(
                        AiConfigKoogBridge().createRuntime(
                            AppSettings.AiConfig(type = config),
                            KtorKoogHttpClient.Factory(baseClient = client),
                        ),
                    )
                assertEquals(expected, runtime.model.contextLength)
                runtime.promptExecutor.close()
            }
            assertFailsWith<IllegalArgumentException> {
                AiConfigKoogBridge().createRuntime(
                    AppSettings.AiConfig(type = basic.copy(contextWindow = -1)),
                    KtorKoogHttpClient.Factory(baseClient = client),
                )
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun realAgentRequestsUseResolvedSettingsAcrossToolCalls() =
        runTest {
            for (
            config in listOf(
                AppSettings.AiConfig(type = basic),
                AppSettings.AiConfig(type = basic, agentType = independent),
                AppSettings.AiConfig(type = AppSettings.AiConfig.Type.OnDevice, agentType = independent),
            ) +
                listOf("none", "minimal", "xhigh", "max").map { effort ->
                    AppSettings.AiConfig(type = basic, agentType = independent.copy(reasoningEffort = effort))
                }
            ) {
                val expected = config.effectiveAgentType as AppSettings.AiConfig.Type.OpenAI
                val requests = mutableListOf<JsonObject>()
                val engine =
                    MockEngine { request ->
                        assertEquals("${expected.serverUrl}chat/completions", request.url.toString())
                        assertEquals("Bearer ${expected.apiKey}", request.headers[HttpHeaders.Authorization])
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        requests += body
                        assertEquals(expected.model, body["model"]?.jsonPrimitive?.content)
                        assertEquals(expected.reasoningEffort, body["reasoning_effort"]?.jsonPrimitive?.content)
                        assertEquals(Json.parseToJsonElement(expected.extraBody).jsonObject["seed"], body["seed"])
                        val message =
                            if (requests.size == 1) {
                                """{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"echo","arguments":"{\"text\":\"hello\"}"}}]}"""
                            } else {
                                """{"role":"assistant","content":"done"}"""
                            }
                        val finishReason = if (requests.size == 1) "tool_calls" else "stop"
                        respond(
                            """
                            {"id":"response","object":"chat.completion","created":0,"model":"${expected.model}",
                             "choices":[{"index":0,"message":$message,"finish_reason":"$finishReason"}]}
                            """.trimIndent(),
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                HttpClient(engine).use { client ->
                    val runtime = assertNotNull(AiConfigKoogBridge().createRuntime(config, KtorKoogHttpClient.Factory(baseClient = client)))
                    val tool = EchoTool()
                    val request =
                        FlareAgentRequest(
                            prompt = Prompt.build("input") { user("echo hello") },
                            systemPrompt = "Use echo to repeat the text.",
                            agentId = "config-test",
                            strategyName = "config-test",
                            analyzeNodeName = "analyze",
                            executeToolsNodeName = "tools",
                            sendToolResultsNodeName = "results",
                            toolSet = AgentToolSet.Empty.copy(toolRegistry = ToolRegistry { tool(tool) }),
                        )
                    val history = MemoryHistory()
                    val agent = runtime.createAgent(request, onTrace = {}, chatHistoryProvider = history)
                    try {
                        assertEquals("done", agent.run(request.prompt, "conversation"))
                        assertEquals(1, tool.calls)
                        assertEquals(2, requests.size)
                        assertTrue(requests[0].getValue("tools").jsonArray.isNotEmpty())
                        assertTrue(
                            requests[1].getValue("messages").jsonArray.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" },
                        )
                        assertTrue(history.messages.isNotEmpty())
                    } finally {
                        agent.close()
                        runtime.promptExecutor.close()
                    }
                }
            }
        }

    @Test
    fun extraBodyPreservesNestedValuesButCannotReplaceAgentOwnedFields() =
        runTest {
            val config =
                independent.copy(
                    extraBody =
                        """
                        {"thinking":{"type":"enabled","budget":2048},"reasoning_effort":"low",
                         "model":"wrong","messages":[],"tools":[],"tool_choice":"none",
                         "stream":true,"stream_options":{},"n":2}
                        """.trimIndent(),
                )
            val engine =
                MockEngine { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("strong", body["model"]?.jsonPrimitive?.content)
                    assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
                    assertEquals(
                        "enabled",
                        body["thinking"]
                            ?.jsonObject
                            ?.get("type")
                            ?.jsonPrimitive
                            ?.content,
                    )
                    assertEquals(
                        "2048",
                        body["thinking"]
                            ?.jsonObject
                            ?.get("budget")
                            ?.jsonPrimitive
                            ?.content,
                    )
                    assertEquals(
                        "hello",
                        body
                            .getValue("messages")
                            .jsonArray
                            .single()
                            .jsonObject["content"]
                            ?.jsonPrimitive
                            ?.content,
                    )
                    assertFalse(body["stream"]?.jsonPrimitive?.content == "true")
                    assertFalse(body["tool_choice"]?.jsonPrimitive?.content == "none")
                    assertFalse(body["n"]?.jsonPrimitive?.content == "2")
                    respond(
                        """{"id":"response","object":"chat.completion","created":0,"model":"strong","choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""",
                    )
                }
            HttpClient(engine).use { client ->
                val runtime =
                    assertNotNull(
                        AiConfigKoogBridge().createRuntime(
                            AppSettings.AiConfig(type = basic, agentType = config),
                            KtorKoogHttpClient.Factory(baseClient = client),
                        ),
                    )
                try {
                    runtime.promptExecutor.execute(Prompt.build("test", params = runtime.params) { user("hello") }, runtime.model)
                } finally {
                    runtime.promptExecutor.close()
                }
            }
        }

    @Test
    fun defaultReasoningIsOmittedAndExtraBodyReasoningCanBeUsed() =
        runTest {
            HttpClient(MockEngine { error("No request expected") }).use { client ->
                for (extraBody in listOf("", "{\"reasoning_effort\":\"low\"}")) {
                    val runtime =
                        assertNotNull(
                            AiConfigKoogBridge().createRuntime(
                                AppSettings.AiConfig(type = basic.copy(reasoningEffort = "", extraBody = extraBody)),
                                KtorKoogHttpClient.Factory(baseClient = client),
                            ),
                        )
                    try {
                        assertEquals(
                            if (extraBody.isEmpty()) null else "low",
                            runtime.params.additionalProperties
                                ?.get("reasoning_effort")
                                ?.jsonPrimitive
                                ?.content,
                        )
                    } finally {
                        runtime.promptExecutor.close()
                    }
                }
            }
        }

    @Test
    fun invalidIndependentConfigNeverUsesBasicCredentialsOrModel() {
        val bridge = AiConfigKoogBridge()
        val missing =
            listOf(
                independent.copy(serverUrl = "") to AgentAvailability.Reason.MissingOpenAIEndpoint,
                independent.copy(apiKey = "") to AgentAvailability.Reason.MissingOpenAIApiKey,
                independent.copy(model = "") to AgentAvailability.Reason.MissingOpenAIModel,
            )
        HttpClient(MockEngine { error("No request expected") }).use { client ->
            missing.forEach { (agent, reason) ->
                val config = AppSettings.AiConfig(type = basic, agentType = agent)
                assertEquals(AgentAvailability.Unavailable(reason), bridge.availability(config))
                assertNull(bridge.createRuntime(config, KtorKoogHttpClient.Factory(baseClient = client)))
            }
            for (extra in listOf("[]", "null", "not json")) {
                assertFailsWith<IllegalArgumentException> {
                    bridge.createRuntime(
                        AppSettings.AiConfig(type = basic, agentType = independent.copy(extraBody = extra)),
                        KtorKoogHttpClient.Factory(baseClient = client),
                    )
                }
            }
        }
    }

    @Test
    fun remembersCompletionsWhenThePreferredEndpointIsUnavailable() =
        runTest {
            val bridge = AiConfigKoogBridge()
            val requests = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requests += request.url.encodedPath
                    if (request.url.encodedPath == "/v1/responses") {
                        respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound)
                    } else {
                        assertEquals("/v1/chat/completions", request.url.encodedPath)
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
                        respond(
                            """{"id":"response","object":"chat.completion","created":0,"model":"future-model","choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""",
                        )
                    }
                }
            HttpClient(engine).use { client ->
                val config = independent.copy(serverUrl = "https://api.openai.com/v1/", model = "future-model")
                for (connection in listOf(config, config, config.copy(apiKey = "another-key"))) {
                    val runtime =
                        assertNotNull(
                            bridge.createRuntime(AppSettings.AiConfig(type = connection), KtorKoogHttpClient.Factory(baseClient = client)),
                        )
                    try {
                        runtime.promptExecutor.execute(
                            Prompt.build("test", params = runtime.params) { user("hello") },
                            runtime.model,
                            listOf(EchoTool().descriptor),
                        )
                    } finally {
                        runtime.promptExecutor.close()
                    }
                }
            }
            assertEquals(
                listOf(
                    "/v1/responses",
                    "/v1/chat/completions",
                    "/v1/chat/completions",
                    "/v1/responses",
                    "/v1/chat/completions",
                ),
                requests,
            )
        }

    @Test
    fun doesNotSwitchForAuthenticationModelOrParameterErrors() =
        runTest {
            for ((status, body) in listOf(
                HttpStatusCode.Unauthorized to """{"error":{"message":"Unauthorized"}}""",
                HttpStatusCode.TooManyRequests to """{"error":{"message":"Rate limited"}}""",
                HttpStatusCode.NotFound to """{"error":{"code":"model_not_found","message":"The model does not exist"}}""",
                HttpStatusCode.BadRequest to """{"error":{"message":"Invalid reasoning_effort"}}""",
                HttpStatusCode.BadRequest to """{"error":{"message":"temperature is not supported on /v1/responses"}}""",
                HttpStatusCode.NotImplemented to """{"error":{"message":"Upstream service unavailable"}}""",
            )) {
                val requests = mutableListOf<String>()
                HttpClient(
                    MockEngine { request ->
                        requests += request.url.encodedPath
                        respond(body, status)
                    },
                ).use { client ->
                    val runtime =
                        assertNotNull(
                            AiConfigKoogBridge().createRuntime(
                                AppSettings.AiConfig(type = independent.copy(serverUrl = "https://api.openai.com/v1/")),
                                KtorKoogHttpClient.Factory(baseClient = client),
                            ),
                        )
                    try {
                        assertFailsWith<Exception> {
                            runtime.promptExecutor.execute(Prompt.build("test", params = runtime.params) { user("hello") }, runtime.model)
                        }
                    } finally {
                        runtime.promptExecutor.close()
                    }
                }
                assertEquals(listOf("/v1/responses"), requests)
            }
        }

    @Test
    fun explicitProtocolsOverrideTheHostDefaultAndStayPinnedOnRouteFailure() =
        runTest {
            for ((api, connection, path) in listOf(
                Triple(AgentOpenAIApi.Responses, independent, "/custom/v1/responses"),
                Triple(AgentOpenAIApi.ChatCompletions, basic.copy(serverUrl = "https://api.openai.com/v1"), "/v1/chat/completions"),
            )) {
                val requests = mutableListOf<String>()
                HttpClient(
                    MockEngine { request ->
                        requests += request.url.encodedPath
                        respond("Method Not Allowed", HttpStatusCode.MethodNotAllowed)
                    },
                ).use { client ->
                    val runtime =
                        assertNotNull(
                            AiConfigKoogBridge().createRuntime(
                                AppSettings.AiConfig(type = connection, agentOpenAIApi = api),
                                KtorKoogHttpClient.Factory(baseClient = client),
                            ),
                        )
                    try {
                        assertFailsWith<Exception> {
                            runtime.promptExecutor.execute(Prompt.build("test", params = runtime.params) { user("Hello") }, runtime.model)
                        }
                    } finally {
                        runtime.promptExecutor.close()
                    }
                }
                assertEquals(listOf(path), requests)
            }
        }

    @Test
    fun explicitAzureResponsesReportsAnIncompatibleConfiguration() {
        HttpClient(MockEngine { error("Explicit Responses must not silently use Azure Chat Completions") }).use { client ->
            assertFailsWith<AgentConfigurationException> {
                AiConfigKoogBridge().createRuntime(
                    AppSettings.AiConfig(
                        type = basic.copy(serverUrl = "https://example.openai.azure.com/openai/v1/"),
                        agentOpenAIApi = AgentOpenAIApi.Responses,
                    ),
                    KtorKoogHttpClient.Factory(baseClient = client),
                )
            }
        }
    }

    private class MemoryHistory : ChatHistoryProvider {
        var messages: List<Message> = emptyList()

        override suspend fun store(
            conversationId: String,
            messages: List<Message>,
        ) {
            this.messages = messages
        }

        override suspend fun load(conversationId: String): List<Message> = messages
    }

    private class EchoTool : SimpleTool<EchoTool.Args>(argsType = typeToken<Args>(), name = "echo", description = "Echo text") {
        var calls = 0

        @Serializable
        data class Args(
            val text: String,
        )

        override suspend fun execute(args: Args): String {
            calls += 1
            return args.text
        }
    }
}
