package dev.dimension.flare.feature.agent.common

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.typeToken
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.dimension.flare.data.datastore.model.AgentOpenAIApi
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.feature.agent.database.AGENT_CONTEXT_MIGRATION
import dev.dimension.flare.feature.agent.database.AgentDatabase
import dev.dimension.flare.feature.agent.presenter.AgentMessagePart
import dev.dimension.flare.feature.agent.presenter.agentMessageText
import dev.dimension.flare.feature.agent.runtime.AiConfigKoogBridge
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.model.withProxy
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentChatHistoryProviderTest {
    @Test
    fun retriesResponsesToolResultsWithTheirReasoningAfterRecreatingTheRuntime() =
        runTest {
            val config =
                AppSettings.AiConfig.Type.OpenAI(
                    "https://agent.example/v1/",
                    "fixture",
                    "provider-alias",
                    "high",
                    extraBody =
                        """
                        {"reasoning":{"effort":"low","summary":"auto"},"top_p":0.8,"max_completion_tokens":1200,
                         "include":["message.output_text.logprobs","reasoning.encrypted_content"]}
                        """.trimIndent(),
                )
            val tool = ReplayTool()
            val prompt = Prompt.build("retry") { user("Echo hello") }
            val request =
                FlareAgentRequest(
                    prompt,
                    "Use the echo tool",
                    "retry",
                    "retry",
                    "analyze",
                    "tools",
                    "results",
                    toolSet = AgentToolSet.Empty.copy(toolRegistry = ToolRegistry { tool(tool) }),
                )
            val calls = mutableListOf<String>()
            val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            val engine =
                MockEngine { httpRequest ->
                    calls += httpRequest.url.encodedPath
                    val body = Json.parseToJsonElement((httpRequest.body as TextContent).text).jsonObject
                    if (calls.size == 1) {
                        assertEquals("/v1/chat/completions", calls.last())
                        respond(
                            """{"error":{"message":"Function tools with reasoning_effort are not supported for provider-alias in /v1/chat/completions. To use function tools, use /v1/responses or set reasoning_effort to 'none'."}}""",
                            HttpStatusCode.BadRequest,
                        )
                    } else {
                        assertEquals("/v1/responses", calls.last(), "HTTP request #${calls.size}")
                        val reasoningConfig = body.getValue("reasoning").jsonObject
                        assertEquals("high", reasoningConfig["effort"]?.jsonPrimitive?.content)
                        assertEquals("auto", reasoningConfig["summary"]?.jsonPrimitive?.content)
                        assertEquals("1200", body["max_output_tokens"]?.jsonPrimitive?.content)
                        assertEquals("false", body["store"]?.jsonPrimitive?.content)
                        assertFalse("temperature" in body)
                        assertFalse("top_p" in body)
                        assertFalse("reasoning_effort" in body)
                        val include = body.getValue("include").jsonArray.single()
                        assertEquals("reasoning.encrypted_content", include.jsonPrimitive.content)
                        val input = body.getValue("input").jsonArray.map { it.jsonObject }
                        val reasoning = input.filter { it["type"]?.jsonPrimitive?.content == "reasoning" }
                        if (calls.size in 3..4) {
                            assertEquals("opaque", reasoning.single()["encrypted_content"]?.jsonPrimitive?.content)
                            val result = input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
                            assertEquals("call_echo", result["call_id"]?.jsonPrimitive?.content)
                            val call = input.single { it["type"]?.jsonPrimitive?.content == "function_call" }
                            assertEquals("fc_echo", call["id"]?.jsonPrimitive?.content)
                            val progress = input.single { it["phase"]?.jsonPrimitive?.content == "commentary" }
                            assertEquals("msg_progress", progress["id"]?.jsonPrimitive?.content)
                        } else {
                            assertTrue(reasoning.isEmpty())
                            assertTrue(input.none { "phase" in it })
                        }
                        when (calls.size) {
                            2 -> {
                                respond(
                                    """{"id":"response","object":"response","created_at":0,"model":"provider-alias","parallel_tool_calls":false,"status":"completed","text":{"format":{"type":"text"}},"output":[{"type":"reasoning","id":"rs_echo","encrypted_content":"opaque","summary":[]},{"type":"message","id":"msg_progress","role":"assistant","phase":"commentary","status":"completed","content":[{"type":"output_text","text":"Checking","annotations":[]}]},{"type":"function_call","id":"fc_echo","call_id":"call_echo","name":"echo","arguments":"{\"text\":\"hello\"}","status":"completed"}]}""",
                                    headers = jsonHeaders,
                                )
                            }

                            3 -> {
                                respond("""{"error":{"message":"Method not allowed"}}""", HttpStatusCode.MethodNotAllowed)
                            }

                            else -> {
                                respond(
                                    """{"id":"response","object":"response","created_at":0,"model":"provider-alias","parallel_tool_calls":false,"status":"completed","text":{"format":{"type":"text"}},"output":[{"type":"message","role":"assistant","id":"msg_done","status":"completed","content":[{"type":"output_text","text":"done","annotations":[]}]}]}""",
                                    headers = jsonHeaders,
                                )
                            }
                        }
                    }
                }
            Fixture().use { fixture ->
                HttpClient(engine).use { client ->
                    for (retry in listOf(null, AgentRetryMode.Resume)) {
                        val retryToolCalls = fixture.history.retryToolCallMessages(CONVERSATION)
                        val httpFactory = KtorKoogHttpClient.Factory(baseClient = client)
                        if (retry != null) {
                            val settings = AppSettings.AiConfig(type = config)
                            for (changed in listOf(
                                settings.copy(agentOpenAIApi = AgentOpenAIApi.ChatCompletions),
                                settings.copy(type = config.copy(model = "other-model")),
                                settings.copy(type = config.copy(apiKey = "other-key")),
                                settings.copy(type = config.copy(serverUrl = "https://other.example/v1/")),
                            )) {
                                assertFailsWith<AgentConfigurationException> {
                                    AiConfigKoogBridge().createRuntime(changed, httpFactory, retryToolCalls)
                                }
                            }
                            assertEquals(3, calls.size)
                            assertEquals(1, tool.calls)
                        }
                        val runtime =
                            assertNotNull(
                                AiConfigKoogBridge().createRuntime(
                                    AppSettings.AiConfig(type = config),
                                    httpFactory,
                                    retryToolCalls,
                                ),
                            )
                        val agent = runtime.createAgent(request.copy(retryMode = retry), {}, fixture.history)
                        try {
                            if (retry == null) {
                                val failure = assertFailsWith<AgentRetryableException> { agent.run(prompt, CONVERSATION) }
                                assertEquals(3, calls.size, failure.cause?.stackTraceToString())
                                assertEquals(AgentRetryMode.Resume, failure.retryMode)
                                val reasoning =
                                    fixture.history
                                        .load(CONVERSATION)
                                        .flatMap { it.parts }
                                        .filterIsInstance<MessagePart.Reasoning>()
                                assertEquals("opaque", reasoning.single().encrypted)
                            } else {
                                assertEquals("done", agent.run(prompt, CONVERSATION))
                            }
                        } finally {
                            agent.close()
                            runtime.promptExecutor.close()
                        }
                    }
                    val runtime =
                        assertNotNull(
                            AiConfigKoogBridge().createRuntime(
                                AppSettings.AiConfig(type = config.copy(serverUrl = "https://api.openai.com/v1/", apiKey = "other-key")),
                                KtorKoogHttpClient.Factory(baseClient = client),
                            ),
                        )
                    try {
                        val history = fixture.history.load(CONVERSATION)
                        runtime.promptExecutor.execute(prompt.withParams(runtime.params).withMessages { history + it }, runtime.model)
                    } finally {
                        runtime.promptExecutor.close()
                    }
                }
            }
            assertEquals(listOf("/v1/chat/completions") + List(4) { "/v1/responses" }, calls)
            assertEquals(1, tool.calls)
        }

    @Test
    fun retryCanChangeProtocolBeforeToolsAndPinsCompletionsAfterTools() =
        runTest {
            val connection = AppSettings.AiConfig.Type.OpenAI("https://api.openai.com/v1", "fixture", "old-model")
            val initial = AppSettings.AiConfig(type = connection, agentOpenAIApi = AgentOpenAIApi.Responses)
            val chat =
                initial.copy(type = connection.copy(model = "new-model"), agentOpenAIApi = AgentOpenAIApi.ChatCompletions)
            val tool = ReplayTool()
            val prompt = Prompt.build("retry") { user("Echo hello") }
            val request =
                FlareAgentRequest(
                    prompt,
                    "Use the echo tool",
                    "retry",
                    "retry",
                    "analyze",
                    "tools",
                    "results",
                    toolSet = AgentToolSet.Empty.copy(toolRegistry = ToolRegistry { tool(tool) }),
                )
            val paths = mutableListOf<String>()
            val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            val engine =
                MockEngine { request ->
                    paths += request.url.encodedPath
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals(if (paths.size == 1) "old-model" else "new-model", body.getValue("model").jsonPrimitive.content)
                    when (paths.size) {
                        1 -> {
                            respond("Invalid parameters", HttpStatusCode.BadRequest)
                        }

                        2 -> {
                            respond(
                                """{"id":"chat","object":"chat.completion","created":0,"model":"new-model","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_echo","type":"function","function":{"name":"echo","arguments":"{\"text\":\"hello\"}"}}]},"finish_reason":"tool_calls"}]}""",
                                headers = jsonHeaders,
                            )
                        }

                        3 -> {
                            respond("Method Not Allowed", HttpStatusCode.MethodNotAllowed)
                        }

                        else -> {
                            val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
                            assertEquals(1, messages.count { it["role"]?.jsonPrimitive?.content == "user" })
                            val result = messages.single { it["role"]?.jsonPrimitive?.content == "tool" }
                            assertEquals("call_echo", result.getValue("tool_call_id").jsonPrimitive.content)
                            respond(
                                """{"id":"chat","object":"chat.completion","created":0,"model":"new-model","choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""",
                                headers = jsonHeaders,
                            )
                        }
                    }
                }
            Fixture().use { fixture ->
                HttpClient(engine).use { client ->
                    suspend fun attempt(
                        settings: AppSettings.AiConfig,
                        retryMode: AgentRetryMode?,
                    ): String {
                        val runtime =
                            assertNotNull(
                                AiConfigKoogBridge().createRuntime(
                                    settings,
                                    KtorKoogHttpClient.Factory(baseClient = client),
                                    fixture.history.retryToolCallMessages(CONVERSATION),
                                ),
                            )
                        val agent = runtime.createAgent(request.copy(retryMode = retryMode), {}, fixture.history)
                        return try {
                            agent.run(prompt, CONVERSATION)
                        } finally {
                            agent.close()
                            runtime.promptExecutor.close()
                        }
                    }
                    assertFailsWith<AgentRetryableException> { attempt(initial, null) }
                    assertTrue(fixture.history.retryToolCallMessages(CONVERSATION).isEmpty())
                    val failed = assertFailsWith<AgentRetryableException> { attempt(chat, AgentRetryMode.Resume) }
                    assertEquals(1, tool.calls, failed.cause?.stackTraceToString())
                    assertEquals("done", attempt(chat.copy(agentOpenAIApi = AgentOpenAIApi.Auto), AgentRetryMode.Resume))
                }
            }
            assertEquals(listOf("/v1/responses") + List(3) { "/v1/chat/completions" }, paths)
            assertEquals(1, tool.calls)
        }

    @Test
    fun deepSeekImageRetryKeepsTheSourceCardBeforeAndAfterTheModelFinishes() =
        runTest {
            withContext(Dispatchers.Default) {
                Fixture().use { fixture ->
                    val id = "status-insight:image-retry"
                    val image =
                        UiMedia.Image(
                            url = "https://image.example/fixture.png",
                            previewUrl = "https://image.example/fixture.png",
                            description = null,
                            width = 100f,
                            height = 100f,
                            sensitive = false,
                            proxy = ProxyRef.Account(MicroBlogKey("viewer", "example.social")),
                        )
                    val source = AgentMessagePart.PostCard(post().copy(images = persistentListOf(image)))
                    fixture.history.ensureInitialUserUiMessage(id, "fixture", source)
                    val initial =
                        fixture.history
                            .observeMessages(id)
                            .first()
                            .single()
                    val initialId = initial.id
                    val retryReached = CompletableDeferred<Unit>()
                    val finish = CompletableDeferred<Unit>()
                    var calls = 0
                    var downloads = 0
                    val client =
                        HttpClient(
                            MockEngine {
                                if (++calls == 1) {
                                    respond(
                                        """{"error":{"message":".messages[1].image[0]: Failed to download image from ${image.url}","type":"invalid_request_error"}}""",
                                        HttpStatusCode.BadRequest,
                                    )
                                } else {
                                    retryReached.complete(Unit)
                                    finish.await()
                                    respond(
                                        """{"id":"response","object":"chat.completion","created":0,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}]}""",
                                    )
                                }
                            },
                        )
                    val runtime =
                        assertNotNull(
                            AiConfigKoogBridge().createRuntime(
                                AppSettings.AiConfig(
                                    type = AppSettings.AiConfig.Type.OpenAI("https://agent.example/v1", "fixture", "test-model"),
                                ),
                                KtorKoogHttpClient.Factory(baseClient = client),
                            ),
                        )
                    val prompt =
                        Prompt.build("image-retry") {
                            user {
                                text("Analyze this social post for the user.\nPost:\nplatform: Mastodon\ncontent: fixture")
                                image(image.url)
                            }
                        }
                    val request =
                        FlareAgentRequest(
                            prompt,
                            "Explain the post",
                            "image-retry",
                            "image-retry",
                            "analyze",
                            "tools",
                            "results",
                            supportingParts = listOf(source),
                            toolSet = AgentToolSet.Empty,
                            images = listOf(image),
                        )
                    val agent =
                        runtime.createAgent(
                            request,
                            {},
                            fixture.history,
                            prepareImage = { _, _ ->
                                downloads++
                                AttachmentSource.Image(
                                    AttachmentContent.Binary.Bytes(byteArrayOf(1, 2, 3)),
                                    "png",
                                    "image/png",
                                    "fixture.png",
                                )
                            },
                            storeMessages = {
                                conversationId,
                                messages,
                                ->
                                fixture.history.storeAssistantResponse(conversationId, messages, listOf(source), null)
                            },
                        )
                    val result = async { agent.run(prompt, id) }
                    try {
                        retryReached.await()
                        val pending =
                            fixture.history
                                .observeMessages(id)
                                .first()
                                .single { it.isUser }
                        assertEquals(initialId, pending.id)
                        assertIs<AgentMessagePart.PostCard>(pending.parts.single())
                        finish.complete(Unit)
                        assertEquals("Done", result.await())
                        val completed =
                            fixture.history
                                .observeMessages(id)
                                .first()
                                .single { it.isUser }
                        assertEquals(initialId, completed.id)
                        assertEquals(initial.parts, completed.parts)
                        assertEquals(2, calls)
                        assertEquals(1, downloads)
                    } finally {
                        finish.complete(Unit)
                        result.cancel()
                        agent.close()
                        runtime.promptExecutor.close()
                        client.close()
                    }
                }
            }
        }

    @Test
    fun sourceCardSurvivesACompetingModelHistorySave() =
        runTest {
            withContext(Dispatchers.Default) {
                val directory = Files.createTempDirectory("agent-source-card-race")
                val driver = PausedHistoryDriver()
                val database =
                    Room
                        .databaseBuilder<AgentDatabase>(directory.resolve("agent.db").toString())
                        .setDriver(driver)
                        .setQueryCoroutineContext(Dispatchers.IO)
                        .build()
                try {
                    val history = AgentChatHistoryProvider(database) { null }
                    history.ensureConversationTitle(CONVERSATION, "fixture")
                    driver.armed.set(true)
                    val modelSave =
                        async(Dispatchers.IO) {
                            val messages =
                                Prompt
                                    .build("source-card-race") {
                                        system("System")
                                        user("fixture")
                                        assistant("Done")
                                    }.messages
                            history.storeAssistantResponse(CONVERSATION, messages, emptyList(), null)
                        }
                    assertTrue(withContext(Dispatchers.IO) { driver.readReached.await(5, TimeUnit.SECONDS) })
                    val cardSave =
                        async(Dispatchers.IO) {
                            history.storeUserUiMessage(CONVERSATION, "fixture", listOf(AgentMessagePart.PostCard(post())))
                        }
                    // The old writer allowed this insert after reading an empty, stale snapshot.
                    withTimeoutOrNull(200) { cardSave.await() }
                    driver.resume.countDown()
                    modelSave.await()
                    cardSave.await()
                    assertTrue(
                        history.observeMessages(CONVERSATION).first().any { it.isUser && it.parts.single() is AgentMessagePart.PostCard },
                    )
                } finally {
                    driver.resume.countDown()
                    database.close()
                    directory.toFile().deleteRecursively()
                }
            }
        }

    @Test
    fun initialCardRepairsLegacyTextWithoutChangingModelHistoryOrFollowupQuestions() =
        runTest {
            Fixture().use { fixture ->
                val id = "status-insight:legacy"
                val source =
                    user(
                        "Analyze this social post for the user.\nPost:\nplatform: Mastodon\ncontent: fixture\nLatest user question:\nQuoted source text",
                        "source",
                    )
                val followup =
                    user(
                        "Continue the conversation about this social post.\nLatest user question:\nWhy?\n\nCurrent post snapshot:\nAnalyze this social post for the user.",
                        "question",
                    )
                fixture.history.store(id, listOf(source, assistant("Analysis", "answer"), followup))
                val before = fixture.history.load(id)
                val sourceId =
                    fixture.history
                        .observeMessages(id)
                        .first()
                        .last()
                        .id
                repeat(2) { fixture.history.ensureInitialUserUiMessage(id, "fixture", AgentMessagePart.PostCard(post())) }
                val visible = fixture.history.observeMessages(id).first()
                assertEquals(3, visible.size)
                assertEquals(sourceId, visible.last().id)
                assertIs<AgentMessagePart.PostCard>(visible.last().parts.single())
                assertEquals("Why?", (visible.first().parts.single() as AgentMessagePart.Text).markdown)
                assertEquals(before, fixture.history.load(id))
            }
        }

    @Test
    fun initialCardIsIdempotentAndExcludedFromModelReplay() =
        runTest {
            Fixture().use { fixture ->
                val id = "status-insight:initial"
                repeat(2) { fixture.history.ensureInitialUserUiMessage(id, "fixture", AgentMessagePart.PostCard(post())) }
                val initial =
                    fixture.history
                        .observeMessages(id)
                        .first()
                        .single()
                assertTrue(fixture.history.load(id).isEmpty())
                val prompt = listOf(user("Analyze this social post for the user.\nPost:\nplatform: Mastodon\ncontent: fixture", "source"))
                fixture.history.store(id, prompt)
                val stored =
                    fixture.history
                        .observeMessages(id)
                        .first()
                        .single()
                assertEquals(initial.id, stored.id)
                assertIs<AgentMessagePart.PostCard>(stored.parts.single())
                assertEquals(
                    prompt.single().id,
                    fixture.history
                        .load(id)
                        .single()
                        .id,
                )
            }
        }

    @Test
    fun reopeningLegacyProfileRestoresItsViewerScopeInPlace() =
        runTest {
            Fixture().use { fixture ->
                val id = "profile-insight:legacy"
                val source = user("Analyze this social profile for the user.\nProfile:\nplatform: Mastodon\ndisplayName: fixture", "source")
                fixture.history.store(id, listOf(source, assistant("Analysis", "answer")))
                val before = fixture.history.load(id)
                val proxy = ProxyRef.Account(MicroBlogKey("viewer", "example.social"))
                val profile = createSampleUser().withProxy(proxy)
                repeat(2) { fixture.history.ensureInitialUserUiMessage(id, "fixture", AgentMessagePart.UserCard(profile)) }
                val visible = fixture.history.observeMessages(id).first()
                assertEquals(2, visible.size)
                val restored = assertIs<AgentMessagePart.UserCard>(visible.last().parts.single()).user
                assertEquals(profile.avatar, restored.avatar)
                assertEquals(profile.banner, restored.banner)
                assertEquals(before, fixture.history.load(id))
            }
        }

    @Test
    fun missingInitialCardDoesNotReplaceAnExistingFollowup() =
        runTest {
            Fixture().use { fixture ->
                val id = "profile-insight:followup"
                fixture.history.storeUserUiMessage(id, "Followup question")
                repeat(2) { fixture.history.ensureInitialUserUiMessage(id, "fixture", AgentMessagePart.PostCard(post())) }
                val messages = fixture.history.observeMessages(id).first()
                assertEquals(2, messages.size)
                assertEquals("Followup question", (messages.first().parts.single() as AgentMessagePart.Text).markdown)
                assertIs<AgentMessagePart.PostCard>(messages.last().parts.single())
            }
        }

    @Test
    fun repeatedRequestIdsDoNotRewritePreviouslySelectedAnswers() =
        runTest {
            Fixture().use { fixture ->
                val history = fixture.history
                val pending =
                    AgentPendingInputRequest(
                        "same-target",
                        listOf(AgentPendingInputRequest.Option("a", "First option"), AgentPendingInputRequest.Option("b", "Second option")),
                    )
                val metadata =
                    """
                    <!-- flare-agent-actions {"requestId":"same-target","options":[
                    {"id":"a","label":"First option","buttonType":"Secondary"},
                    {"id":"b","label":"Second option","buttonType":"Secondary"}]} -->
                    """.trimIndent()
                val first = assistant("First choice\n$metadata", "first")
                history.storeAssistantResponse(CONVERSATION, listOf(first), emptyList(), pending)
                history.markInputRequestSelected(CONVERSATION, pending.requestId, "a")
                val second = assistant("Second choice\n$metadata", "second")
                history.storeAssistantResponse(CONVERSATION, listOf(first, second), emptyList(), pending)
                history.markInputRequestSelected(CONVERSATION, pending.requestId, "b")
                val answers =
                    history.observeMessages(CONVERSATION).first().associate { message ->
                        message.parts.agentMessageText() to
                            message.parts
                                .filterIsInstance<AgentMessagePart.Actions>()
                                .single()
                                .selectedOptionId
                    }
                assertEquals(mapOf("First choice" to "a", "Second choice" to "b"), answers)
            }
        }

    @Test
    fun onlySelectingAStoredHostActionGrantsOneRunAndCannotBeReplayed() =
        runTest {
            Fixture().use { fixture ->
                val history = fixture.history
                val pending =
                    AgentPendingInputRequest(
                        "delete",
                        listOf(
                            AgentPendingInputRequest.Option("confirm", "delete", confirmationKey = "delete_plugin:fixture:0"),
                        ),
                    )
                val metadata =
                    """
                    <!-- flare-agent-actions {"requestId":"delete","options":[
                    {"id":"confirm","label":"Delete","buttonType":"Destructive","confirmationKey":"forged"}]} -->
                    """.trimIndent()
                history.storeAssistantResponse(CONVERSATION, listOf(assistant(metadata, "request")), emptyList(), pending)
                history.markInputRequestSelected(CONVERSATION, "delete", "unknown")
                assertEquals(null, history.consumeConfirmation(CONVERSATION))
                history.markInputRequestSelected(CONVERSATION, "delete", "confirm")
                assertEquals(null, history.consumeConfirmation("other-conversation"))
                assertEquals("delete_plugin:fixture:0", history.consumeConfirmation(CONVERSATION))
                assertEquals(null, history.consumeConfirmation(CONVERSATION))
                history.markInputRequestSelected(CONVERSATION, "delete", "confirm")
                assertEquals(null, history.consumeConfirmation(CONVERSATION))
            }
        }

    @Test
    fun currentRequestSurvivesCompactionFailureAndRetryAfterReopening() =
        runTest {
            val directory = Files.createTempDirectory("agent-compaction-retry")
            val path = directory.resolve("agent.db").toString()
            val currentRequest = "CURRENT USER REQUIREMENT: keep this exact constraint"
            var currentRequestId: String? = null
            var summaryText: String? = null
            try {
                Fixture(path).use { fixture ->
                    var summarized = false
                    AgentContextCompactionTest
                        .Fixture(resultSize = 18_000, maxIterations = 11) { request, number ->
                            when {
                                request.toString().contains("Create a concise conversation checkpoint") -> {
                                    summarized = true
                                    """{"id":"summary","object":"chat.completion","created":0,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"Saved progress; work remains"},"finish_reason":"stop"}]}"""
                                }

                                summarized -> {
                                    """{"error":{"message":"Invalid parameter"}}"""
                                }

                                else -> {
                                    """{"id":"response-$number","object":"chat.completion","created":0,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","tool_calls":[{"id":"call-$number","type":"function","function":{"name":"work","arguments":"{\"id\":\"call-$number\"}"}}]},"finish_reason":"tool_calls"}]}"""
                                }
                            }
                        }.use { agent ->
                            assertFailsWith<AgentRetryableException> {
                                agent.run(
                                    historyProvider = fixture.history,
                                    userText = currentRequest,
                                    storeCheckpoint = fixture.history::storeCheckpoint,
                                )
                            }
                            assertTrue(summarized)
                            assertTrue(currentRequest in agent.requests.last().toString())
                            assertTrue(agent.toolCalls > 0)
                        }
                    val loaded = fixture.history.load(CONVERSATION)
                    summaryText = loaded.first().textContent()
                    currentRequestId = loaded.single { it.textContent() == currentRequest }.id
                }
                Fixture(path).use { fixture ->
                    val loaded = fixture.history.load(CONVERSATION)
                    assertEquals(currentRequestId, loaded.single { it.textContent() == currentRequest }.id)
                    AgentContextCompactionTest
                        .Fixture { _, _ ->
                            """{"id":"retry","object":"chat.completion","created":0,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"Recovered"},"finish_reason":"stop"}]}"""
                        }.use { agent ->
                            assertEquals(
                                "Recovered",
                                agent.run(
                                    historyProvider = fixture.history,
                                    userText = "MUST NOT APPEND A SECOND USER MESSAGE",
                                    retryMode = AgentRetryMode.Resume,
                                    storeCheckpoint = fixture.history::storeCheckpoint,
                                ),
                            )
                            assertTrue(currentRequest in agent.requests.single().toString())
                            assertFalse("MUST NOT APPEND" in agent.requests.single().toString())
                            assertEquals(0, agent.toolCalls)
                        }
                    assertEquals(
                        summaryText,
                        fixture.history
                            .load(CONVERSATION)
                            .first()
                            .textContent(),
                    )
                    fixture.history.store(CONVERSATION, fixture.history.load(CONVERSATION) + user("Next request", "next"))
                    val next = fixture.history.load(CONVERSATION)
                    assertTrue(next.any { it.id == "next" })
                    assertFalse(next.any { it.id == currentRequestId })
                    assertTrue(
                        fixture.history
                            .observeMessages(CONVERSATION)
                            .first()
                            .any { it.parts.agentMessageText() == currentRequest },
                    )
                }
            } finally {
                Files.list(directory).use { paths -> paths.forEach(Files::delete) }
                Files.delete(directory)
            }
        }

    @Test
    fun consumedRetryDoesNotBecomeAvailableAgainAfterAnInterruptedRunAndReopen() =
        runTest {
            val path = Files.createTempFile("agent-retry-interrupted", ".db")
            val failure =
                AgentRetryableException(
                    IllegalStateException("Temporary failure"),
                    AgentRetryMode.Resume,
                ).toAgentFailure("Question")
            try {
                Fixture(path.toString()).use { fixture ->
                    fixture.history.updateRoomState(CONVERSATION, errorMessage = failure.encode())
                    assertEquals(failure, fixture.history.consumeRetry(CONVERSATION))
                }
                Fixture(path.toString()).use { fixture ->
                    val interrupted =
                        fixture.history
                            .observeRoom(CONVERSATION)
                            .first()!!
                            .error!!
                    assertEquals(AgentFailureKind.Interrupted, interrupted.kind)
                    assertFalse(interrupted.canRetry)
                    assertEquals(null, fixture.history.consumeRetry(CONVERSATION))
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun retryFailureSurvivesReopenWithCardsAndCheckpointWithoutChangingVisibleHistory() =
        runTest {
            val path = Files.createTempFile("agent-retry", ".db")
            val messages = listOf(user("Original question", "user"), assistant("Completed work", "reply"))
            val failure =
                AgentRetryableException(IllegalStateException("Upstream error"), AgentRetryMode.Resume)
                    .apply {
                        supportingParts = listOf(AgentMessagePart.PostCard(post()))
                        inputRequest = AgentPendingInputRequest("pending", listOf(AgentPendingInputRequest.Option("confirm", "yes")))
                    }.toAgentFailure("Original question")
            try {
                Fixture(path.toString()).use { fixture ->
                    fixture.history.storeCheckpoint(CONVERSATION, messages, AgentContextCheckpoint("Completed work", "user"))
                    fixture.history.updateRoomState(CONVERSATION, errorMessage = failure.encode())
                }
                Fixture(path.toString()).use { fixture ->
                    val reopened =
                        fixture.history
                            .observeRoom(CONVERSATION)
                            .first()!!
                            .error!!
                    assertEquals(failure.encode(), reopened.encode())
                    assertTrue(reopened.canRetry)
                    assertEquals(
                        listOf("Completed work", "Original question"),
                        fixture.history.observeMessages(CONVERSATION).first().map {
                            it.parts.agentMessageText()
                        },
                    )
                    assertEquals(
                        AGENT_CONTEXT_SUMMARY_ID,
                        fixture.history
                            .load(CONVERSATION)
                            .first()
                            .id,
                    )
                    assertEquals(
                        "reply",
                        fixture.history
                            .load(CONVERSATION)
                            .last()
                            .id,
                    )
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun trimmedModelMemoryDoesNotRemoveVisibleRepliesOrChangeTheirIds() =
        runTest {
            Fixture().use { fixture ->
                val history = fixture.history
                val question = user("Check that.", "question")
                val first = assistant("I will check that.", "first")
                val final = assistant("Here is the result.", "final")
                history.store(CONVERSATION, listOf(question, first, final))
                val before = history.observeMessages(CONVERSATION).first()

                history.store(CONVERSATION, listOf(final))

                val after = history.observeMessages(CONVERSATION).first()
                assertEquals(listOf("Here is the result.", "I will check that.", "Check that."), after.map { it.parts.agentMessageText() })
                assertEquals(before.map { it.id }, after.map { it.id })
            }
        }

    @Test
    fun optimisticUserMessageKeepsItsIdAndSystemPromptStaysFirst() =
        runTest {
            Fixture().use { fixture ->
                val history = fixture.history
                history.storeUserUiMessage(CONVERSATION, "Check that.")
                val before = history.observeMessages(CONVERSATION).first().single()
                val messages =
                    Prompt
                        .build("input") {
                            system("Be helpful.")
                            user("Check that.")
                            assistant("I will check that.")
                        }.messages
                        .mapIndexed { index, message -> message.withAgentMessageId("message-$index") }

                history.store(CONVERSATION, messages)

                assertEquals(
                    listOf(Message.Role.System, Message.Role.User, Message.Role.Assistant),
                    history.load(CONVERSATION).map { it.role },
                )
                val after = history.observeMessages(CONVERSATION).first()
                assertEquals(listOf("I will check that.", "Check that."), after.map { it.parts.agentMessageText() })
                assertEquals(before.id, after.last().id)
            }
        }

    @Test
    fun legacyMessagesAndIdenticalNewRepliesKeepSeparateStableIds() =
        runTest {
            Fixture().use { fixture ->
                val history = fixture.history
                val legacy = assistant("Same answer.", "legacy").copy(id = null)
                history.store(CONVERSATION, listOf(legacy))
                val before = history.observeMessages(CONVERSATION).first().single()
                val loaded = history.load(CONVERSATION)
                val next = legacy.copy(id = "next")

                history.store(CONVERSATION, loaded + next)
                history.store(CONVERSATION, listOf(next))

                val after = history.observeMessages(CONVERSATION).first()
                assertEquals(listOf("Same answer.", "Same answer."), after.map { it.parts.agentMessageText() })
                assertEquals(before.id, after.last().id)
                assertEquals(2, after.map { it.id }.distinct().size)
            }
        }

    @Test
    fun toolAndReasoningOnlyRepliesDoNotCreateVisibleMessagesOrActions() =
        runTest {
            Fixture().use { fixture ->
                val response =
                    assistant("", "tools").copy(
                        parts = listOf(MessagePart.Reasoning("private reasoning"), MessagePart.Tool.Call("call-1", "lookup", "{}")),
                    )
                val pending =
                    AgentPendingInputRequest(
                        requestId = "selection",
                        options = listOf(AgentPendingInputRequest.Option("post", "selected", postPreview = post())),
                    )

                fixture.history.storeAssistantResponse(CONVERSATION, listOf(response), emptyList(), pending)

                assertTrue(
                    fixture.history
                        .observeMessages(CONVERSATION)
                        .first()
                        .isEmpty(),
                )
                assertTrue(fixture.history.load(CONVERSATION).isEmpty())
            }
        }

    @Test
    fun cardsAndSelectedActionsSurviveCompletionFailureAndDatabaseReopen() =
        runTest {
            val directory = Files.createTempDirectory("flare-agent-history-")
            val path = directory.resolve("agent.db").toString()
            val post = post()
            val pending =
                AgentPendingInputRequest(
                    requestId = "confirmation",
                    options = listOf(AgentPendingInputRequest.Option("confirm", "confirmed=true")),
                )
            val metadata =
                """<!-- flare-agent-actions {"requestId":"confirmation","options":[{"id":"confirm","label":"Confirm","buttonType":"Primary"}]} -->"""
            val first =
                assistant("Found ${post.agentAttachmentMarker()} $metadata", "first").let {
                    it.copy(
                        parts =
                            it.parts +
                                MessagePart.Reasoning("private reasoning") +
                                MessagePart.Tool.Call("call-1", "lookup", "{\"secret\":true}"),
                    )
                }
            val final = assistant("Confirm ${post.agentAttachmentMarker()} $metadata", "final")
            var expectedIds: List<String> = emptyList()
            var intermediateId: String? = null
            try {
                Fixture(path).use { fixture ->
                    val history = fixture.history
                    history.storeAssistantResponse(CONVERSATION, listOf(first), listOf(AgentMessagePart.PostCard(post)), pending)
                    val intermediate = history.observeMessages(CONVERSATION).first().single()
                    assertEquals(
                        post.statusKey,
                        intermediate.parts
                            .filterIsInstance<AgentMessagePart.PostCard>()
                            .single()
                            .post.statusKey,
                    )
                    assertTrue(intermediate.parts.none { it is AgentMessagePart.Actions })
                    assertEquals("Found", intermediate.parts.agentMessageText())
                    intermediateId = intermediate.id
                    history.updateRoomState(CONVERSATION, errorMessage = "Model request failed", isRunning = false)
                }

                Fixture(path).use { fixture ->
                    val history = fixture.history
                    assertEquals(
                        intermediateId,
                        history
                            .observeMessages(CONVERSATION)
                            .first()
                            .single()
                            .id,
                    )
                    assertEquals("Model request failed", history.observeRoom(CONVERSATION).first()?.errorMessage)
                    history.storeAssistantResponse(CONVERSATION, listOf(first, final), listOf(AgentMessagePart.PostCard(post)), pending)
                    val completed = history.observeMessages(CONVERSATION).first()
                    assertEquals(intermediateId, completed.last().id)
                    val actions = assertIs<AgentMessagePart.Actions>(completed.first().parts.last())
                    assertEquals(
                        "Confirm",
                        actions.request.options
                            .single()
                            .label,
                    )
                    history.markInputRequestSelected(CONVERSATION, pending.requestId, "confirm")
                    history.store(CONVERSATION, listOf(final))
                    history.updateRoomState(CONVERSATION, errorMessage = "Next request failed", isRunning = false)
                    expectedIds = history.observeMessages(CONVERSATION).first().map { it.id }
                }

                Fixture(path).use { fixture ->
                    val history = fixture.history
                    val reopened = history.observeMessages(CONVERSATION).first()
                    assertEquals(expectedIds, reopened.map { it.id })
                    assertEquals(2, reopened.size)
                    assertTrue(reopened.all { message -> message.parts.any { it is AgentMessagePart.PostCard } })
                    assertTrue(assertIs<AgentMessagePart.Actions>(reopened.first().parts.last()).selected)
                    assertFalse(reopened.joinToString { it.parts.agentMessageText() }.contains("flare-agent-actions"))
                    assertEquals("Next request failed", history.observeRoom(CONVERSATION).first()?.errorMessage)
                    assertTrue(
                        history.load(CONVERSATION).all { message ->
                            message.parts.none {
                                it is MessagePart.Tool ||
                                    it is MessagePart.Reasoning
                            }
                        },
                    )
                }
            } finally {
                Files.list(directory).use { paths -> paths.forEach(Files::delete) }
                Files.delete(directory)
            }
        }

    @Test
    fun checkpointsSurviveReopenWithoutChangingVisibleHistoryAndClearWithTheConversation() =
        runTest {
            val directory = Files.createTempDirectory("flare-agent-checkpoint-")
            val path = directory.resolve("agent.db").toString()
            val messages =
                listOf(
                    user("Original requirement", "q1"),
                    assistant("Completed step", "a1"),
                    user("Recent question", "q2"),
                    assistant("Recent answer", "a2"),
                )
            try {
                var ids: List<String> = emptyList()
                Fixture(path).use { fixture ->
                    fixture.history.store(CONVERSATION, messages)
                    ids =
                        fixture.history
                            .observeMessages(CONVERSATION)
                            .first()
                            .map { it.id }
                    fixture.history.storeCheckpoint(CONVERSATION, messages, AgentContextCheckpoint("Saved progress", "a1"))
                    assertEquals(
                        ids,
                        fixture.history
                            .observeMessages(CONVERSATION)
                            .first()
                            .map { it.id },
                    )
                }
                Fixture(path).use { fixture ->
                    val loaded = fixture.history.load(CONVERSATION)
                    assertEquals(listOf(AGENT_CONTEXT_SUMMARY_ID, "q2", "a2"), loaded.map { it.id })
                    assertTrue(loaded.first().textContent().contains("Saved progress"))
                    val next = assistant("Continued", "a3")
                    fixture.history.store(CONVERSATION, loaded + next)
                    assertEquals(
                        ids,
                        fixture.history
                            .observeMessages(CONVERSATION)
                            .first()
                            .drop(1)
                            .map { it.id },
                    )
                    assertFalse(
                        fixture.history
                            .observeMessages(CONVERSATION)
                            .first()
                            .any { it.parts.agentMessageText().contains("Saved progress") },
                    )
                    fixture.history.storeCheckpoint(CONVERSATION, loaded + next, AgentContextCheckpoint("Updated progress", "a2"))
                    assertEquals(listOf(AGENT_CONTEXT_SUMMARY_ID, "q2", "a3"), fixture.history.load(CONVERSATION).map { it.id })
                    fixture.history.clear(CONVERSATION)
                    assertTrue(fixture.history.load(CONVERSATION).isEmpty())
                    fixture.history.storeCheckpoint(CONVERSATION, messages, AgentContextCheckpoint("New progress", "a1"))
                    fixture.history.deleteConversation(CONVERSATION)
                    assertTrue(fixture.history.load(CONVERSATION).isEmpty())
                }
            } finally {
                Files.list(directory).use { paths -> paths.forEach(Files::delete) }
                Files.delete(directory)
            }
        }

    @Test
    fun invalidCheckpointRollsBackBothTheBoundaryAndNewMessages() =
        runTest {
            Fixture().use { fixture ->
                val original = listOf(user("Question", "q"), assistant("Answer", "a"))
                fixture.history.storeCheckpoint(CONVERSATION, original, AgentContextCheckpoint("Existing summary", "q"))
                val before = fixture.history.load(CONVERSATION)
                assertFailsWith<NoSuchElementException> {
                    fixture.history.storeCheckpoint(
                        CONVERSATION,
                        original + assistant("Must not persist", "new"),
                        AgentContextCheckpoint("Invalid", "missing"),
                    )
                }
                assertEquals(before.map { it.textContent() }, fixture.history.load(CONVERSATION).map { it.textContent() })
                assertEquals(
                    2,
                    fixture.history
                        .observeMessages(CONVERSATION)
                        .first()
                        .size,
                )
            }
        }

    @Test
    fun replayKeepsCompleteToolExchangesAndDropsOrphanCallsAndResults() =
        runTest {
            Fixture().use { fixture ->
                val call = assistant("", "call").copy(parts = listOf(MessagePart.Tool.Call("tool-1", "lookup", "{}")))
                val result =
                    Prompt
                        .build("results") {
                            user { toolResult(MessagePart.Tool.Result("tool-1", "lookup", "Found item")) }
                        }.messages
                        .single()
                        .withAgentMessageId("result")
                val orphan =
                    assistant(
                        "Visible progress",
                        "orphan",
                    ).copy(parts = listOf(MessagePart.Text("Visible progress"), MessagePart.Tool.Call("tool-1", "lookup", "{}")))
                fixture.history.storeCheckpoint(
                    CONVERSATION,
                    listOf(user("Requirement", "old"), call, result, orphan),
                    AgentContextCheckpoint("Requirement", "old"),
                )
                val replay = fixture.history.load(CONVERSATION)
                assertEquals(1, replay.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Call>().size)
                assertEquals(1, replay.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().size)
                assertEquals("Visible progress", replay.last().textContent())
                assertEquals(
                    2,
                    fixture.history
                        .observeMessages(CONVERSATION)
                        .first()
                        .size,
                )
            }
        }

    @Test
    fun versionNineMigrationPreservesExistingChatData() =
        runTest {
            val directory = Files.createTempDirectory("flare-agent-migration-")
            val path = directory.resolve("agent.db").toString()
            try {
                BundledSQLiteDriver().open(path).use { connection ->
                    connection.execSQL(
                        "CREATE TABLE agent_conversations (conversationId TEXT NOT NULL PRIMARY KEY, title TEXT, titleGenerated INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, isRunning INTEGER NOT NULL, errorMessage TEXT)",
                    )
                    connection.execSQL(
                        "CREATE TABLE agent_messages (conversationId TEXT NOT NULL, position INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, contentJson TEXT NOT NULL, messageJson TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(conversationId, position))",
                    )
                    connection.execSQL("INSERT INTO agent_conversations VALUES ('conversation', 'Existing conversation', 1, 0, 0, 0, NULL)")
                    connection.execSQL("INSERT INTO agent_messages VALUES ('conversation', 0, 'User', 'Existing question', '', '', 0)")
                    connection.execSQL("PRAGMA user_version = 9")
                }
                Fixture(path).use { fixture ->
                    assertEquals(
                        "Existing conversation",
                        fixture.history
                            .observeRoom(CONVERSATION)
                            .first()
                            ?.title,
                    )
                    assertEquals(
                        "Existing question",
                        fixture.history
                            .observeMessages(CONVERSATION)
                            .first()
                            .single()
                            .parts
                            .agentMessageText(),
                    )
                    val messages = listOf(user("Next", "q"), assistant("Answer", "a"))
                    fixture.history.storeCheckpoint(CONVERSATION, messages, AgentContextCheckpoint("Migrated", "q"))
                    assertTrue(
                        fixture.history
                            .load(CONVERSATION)
                            .first()
                            .textContent()
                            .contains("Migrated"),
                    )
                }
            } finally {
                Files.list(directory).use { paths -> paths.forEach(Files::delete) }
                Files.delete(directory)
            }
        }

    @Test
    fun aPinnedOldRequestCannotMoveTheCheckpointBoundaryBackwards() =
        runTest {
            Fixture().use { fixture ->
                val original = listOf(user("Original requirement", "q1"), assistant("Completed", "a1"), assistant("Recent", "a2"))
                fixture.history.storeCheckpoint(CONVERSATION, original, AgentContextCheckpoint("First summary", "a1"))
                val loaded = fixture.history.load(CONVERSATION)
                fixture.history.storeCheckpoint(
                    CONVERSATION,
                    listOf(loaded.first(), original.first()) + loaded.drop(1),
                    AgentContextCheckpoint("Second summary", "q1"),
                )
                val restored = fixture.history.load(CONVERSATION)
                assertEquals(listOf(AGENT_CONTEXT_SUMMARY_ID, "q1", "a2"), restored.map { it.id })
                assertTrue(restored.first().textContent().contains("Second summary"))
                assertEquals(
                    3,
                    fixture.history
                        .observeMessages(CONVERSATION)
                        .first()
                        .size,
                )
            }
        }

    @Test
    fun ruleRecoveryDoesNotOverwriteOriginalMessagesOrCheckpointAfterReopening() =
        runTest {
            val directory = Files.createTempDirectory("agent-recovery-history")
            val path = directory.resolve("agent.db").toString()
            val output = "Original completed output " + "x".repeat(6_000)
            val toolResult = MessagePart.Tool.Result("completed-call", "work", output, isError = true)
            val original =
                listOf(
                    user("Earlier request", "old-q"),
                    assistant("Earlier answer", "old-a"),
                    user("Current request", "q"),
                    assistant(
                        "",
                        "call",
                    ).copy(parts = listOf(MessagePart.Tool.Call("completed-call", "work", "{\"id\":\"completed-call\"}"))),
                    user("", "result").copy(parts = listOf(toolResult)),
                )
            try {
                Fixture(path).use { fixture ->
                    fixture.history.storeCheckpoint(CONVERSATION, original, AgentContextCheckpoint("Existing summary", "old-a"))
                    AgentContextCompactionTest
                        .Fixture { _, number ->
                            if (number == 1) {
                                """{"error":{"code":"context_length_exceeded","message":"maximum context length exceeded"}}"""
                            } else {
                                """{"id":"response","object":"chat.completion","created":0,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"Recovered"},"finish_reason":"stop"}]}"""
                            }
                        }.use { agent ->
                            assertEquals("Recovered", agent.run(historyProvider = fixture.history))
                            assertEquals(2, agent.requests.size)
                            assertFalse(output in agent.requests.last().toString())
                            assertTrue(agent.checkpoints.isEmpty())
                            assertEquals(0, agent.toolCalls)
                        }
                }
                Fixture(path).use { fixture ->
                    val restored = fixture.history.load(CONVERSATION)
                    assertEquals(toolResult, restored.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().single())
                    assertEquals(agentContextSummary("Existing summary").textContent(), restored.first().textContent())
                    assertTrue(restored.none { it.textContent().contains("Some earlier context was omitted") })
                    val visible = fixture.history.observeMessages(CONVERSATION).first()
                    assertTrue(visible.any { it.parts.agentMessageText() == "Earlier request" })
                    assertTrue(visible.any { it.parts.agentMessageText() == "Earlier answer" })
                    assertTrue(visible.any { it.parts.agentMessageText() == "Recovered" })
                }
            } finally {
                Files.list(directory).use { paths -> paths.forEach(Files::delete) }
                Files.delete(directory)
            }
        }

    private class ReplayTool : SimpleTool<ReplayTool.Args>(argsType = typeToken<Args>(), name = "echo", description = "Echo text") {
        var calls = 0

        @Serializable
        data class Args(
            val text: String,
        )

        override suspend fun execute(args: Args): String {
            calls++
            return args.text
        }
    }

    private class Fixture(
        path: String? = null,
    ) : AutoCloseable {
        private val database =
            (if (path == null) Room.inMemoryDatabaseBuilder<AgentDatabase>() else Room.databaseBuilder<AgentDatabase>(path))
                .addMigrations(AGENT_CONTEXT_MIGRATION)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val history = AgentChatHistoryProvider(database) { null }

        override fun close() = database.close()
    }

    private fun assistant(
        text: String,
        id: String,
    ): Message.Assistant = (Prompt.build(id) { assistant(text) }.messages.single() as Message.Assistant).copy(id = id)

    private fun user(
        text: String,
        id: String,
    ): Message.User = (Prompt.build(id) { user(text) }.messages.single() as Message.User).copy(id = id)

    private fun post(): UiTimelineV2.Post =
        UiTimelineV2.Post(
            platformId = "Mastodon",
            images = persistentListOf(),
            sensitive = false,
            contentWarning = null,
            user = null,
            content = UiTranslatableText("fixture".toUiPlainText()),
            actions = persistentListOf(),
            poll = null,
            statusKey = MicroBlogKey("post", "example.social"),
            card = null,
            createdAt =
                kotlin.time.Clock.System
                    .now()
                    .toUi(),
            emojiReactions = persistentListOf(),
            sourceChannel = null,
            visibility = null,
            replyToHandle = null,
            clickEvent = ClickEvent.Noop,
            accountType = AccountType.Specific(MicroBlogKey("viewer", "example.social")),
        )

    private companion object {
        const val CONVERSATION = "conversation"
    }

    private class PausedHistoryDriver(
        private val delegate: SQLiteDriver = BundledSQLiteDriver(),
    ) : SQLiteDriver by delegate {
        val armed = AtomicBoolean(false)
        val readReached = CountDownLatch(1)
        val resume = CountDownLatch(1)

        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    val target = sql.contains("FROM agent_messages") && sql.contains("ORDER BY position ASC")
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            val hasRow = statement.step()
                            if (target && !hasRow && armed.compareAndSet(true, false)) {
                                statement.reset()
                                readReached.countDown()
                                check(resume.await(5, TimeUnit.SECONDS)) { "Competing card write timed out" }
                            }
                            return hasRow
                        }
                    }
                }
            }
        }
    }
}
