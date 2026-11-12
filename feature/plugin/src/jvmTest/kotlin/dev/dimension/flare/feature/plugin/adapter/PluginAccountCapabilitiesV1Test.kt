package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.data.datasource.microblog.ComposeType
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.feature.plugin.abi.PluginAbiV1.Capabilities
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.host.PluginHttpTransport
import dev.dimension.flare.feature.plugin.host.PluginTransportRequestV1
import dev.dimension.flare.feature.plugin.host.PluginTransportResponseV1
import dev.dimension.flare.feature.plugin.installer.PluginInstaller
import dev.dimension.flare.feature.plugin.installer.TestFppFactory
import dev.dimension.flare.feature.plugin.lifecycle.PluginStateStore
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.login.PluginFormLoginCoordinatorV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthLoginCoordinatorV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingStoreV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingV1
import dev.dimension.flare.feature.plugin.login.PluginWebCookieLoginCoordinatorV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.wire.ComposeConfigV2
import dev.dimension.flare.feature.plugin.wire.PluginAccountCredentialV1
import dev.dimension.flare.feature.plugin.wire.VisibilityV1
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.SYSTEM
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginAccountCapabilitiesV1Test {
    @Test
    fun upgradedAccountsNegotiateOnceWithoutLoggingInAndRefreshTheDatasource() =
        runBlocking<Unit> {
            for (apiVersion in listOf(1, 2)) {
                withPlugin(apiVersion = apiVersion) { fixture ->
                    val before = fixture.context.value.value
                    val access = PlatformDataSourceCredentialAccessV1(fixture.plugin, fixture.context)
                    val original = fixture.platform.createDataSource(fixture.context)
                    assertFalse(
                        ComposeType.Quote in requireNotNull(original.capabilities.compose).composeConfig(ComposeType.New).supportedTypes,
                    )
                    repeat(8) { fixture.platform.createDataSource(fixture.context) }
                    withTimeout(10_000) { fixture.context.refreshed.await() }
                    val after = fixture.context.value.value
                    assertEquals(before.credential, after.credential)
                    assertEquals(before.sessionEpoch, after.sessionEpoch)
                    assertEquals(before.credentialRevision, after.credentialRevision)
                    assertEquals(fixture.plugin.installed.packageHash, after.snapshot.capabilitiesPackageHash)
                    assertEquals(1, fixture.context.invalidations)
                    assertFailsWith<CancellationException> { access.read() }
                    val upgraded = fixture.platform.createDataSource(fixture.context)
                    assertTrue(
                        ComposeType.Quote in requireNotNull(upgraded.capabilities.compose).composeConfig(ComposeType.New).supportedTypes,
                    )
                    assertEquals(
                        UiTimelineV2.Post.Visibility.SelfOnly,
                        upgraded.capabilities.compose!!
                            .composeConfig(ComposeType.New)
                            .visibility
                            ?.defaultVisibility,
                    )
                    if (apiVersion == 2) assertNotNull(upgraded.capabilities.accountHttp)
                    assertTrue(fixture.refresher.refresh(fixture.context))
                    assertEquals(1, fixture.requests)
                }
            }
        }

    @Test
    fun failedOrInvalidNegotiationKeepsTheExistingAccount() =
        runBlocking<Unit> {
            for (result in listOf(
                "throw flare.error({code: 'Network', message: {value: 'offline'}});",
                "throw flare.error({code: 'AuthenticationRequired', message: {value: 'expired'}});",
                "return {capabilities: {'flare.datasource.search/v1': ['posts']}};",
                "return {capabilities: {'flare.datasource.timeline/v1': ['page']}, composeConfig: {supportedTypes: ['new'], text: {maxLength: -1}}};",
                "await flare.credential.replace({token: 'must-not-write'}); return RESULT;",
            )) {
                withPlugin(body = result) { fixture ->
                    val before = fixture.context.value.value
                    assertFailsWith<Exception> { fixture.refresher.refresh(fixture.context) }
                    assertEquals(before, fixture.context.value.value)
                    assertFalse(fixture.context.refreshed.isCompleted)
                }
            }
        }

    @Test
    fun concurrentCredentialRefreshDiscardsTheOldNegotiationThenRetriesWithTheNewCredential() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withPlugin(onRequest = {
                started.complete(Unit)
                release.await()
            }) { fixture ->
                val old = fixture.context.value.value
                val pending = async { fixture.refresher.refresh(fixture.context) }
                withTimeout(10_000) { started.await() }
                fixture.context.value.value = old.copy(credential = JsonPrimitive("new-token"), credentialRevision = "new-revision")
                release.complete(Unit)
                assertFalse(pending.await())
                assertNull(fixture.context.value.value.snapshot.capabilitiesPackageHash)
                assertTrue(fixture.refresher.refresh(fixture.context))
                assertEquals(JsonPrimitive("new-token"), fixture.context.value.value.credential)
                assertEquals("new-revision", fixture.context.value.value.credentialRevision)
                assertEquals(listOf("old-token", "new-token"), fixture.tokens)
            }
        }

    @Test
    fun removedAccountAndRetiredPluginCannotCommitLateResults() =
        runBlocking<Unit> {
            for (retire in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                withPlugin(onRequest = {
                    started.complete(Unit)
                    release.await()
                }) { fixture ->
                    val before = fixture.context.value.value
                    val pending = async { fixture.refresher.refresh(fixture.context) }
                    withTimeout(10_000) { started.await() }
                    if (retire) fixture.plugin.session.retire() else fixture.context.removed = true
                    release.complete(Unit)
                    assertFailsWith<CancellationException> { pending.await() }
                    assertEquals(before, fixture.context.value.value)
                    assertFalse(fixture.context.refreshed.isCompleted)
                }
            }
        }

    @Test
    fun transientFailureRetriesInTheBackgroundWhileTheOldTimelineWorks() =
        runBlocking<Unit> {
            val attempted = CompletableDeferred<Unit>()
            withPlugin(
                body =
                    """
                    if (JSON.parse(response.body).attempt === 1) {
                      throw flare.error({code: 'Network', message: {value: 'offline'}});
                    }
                    return RESULT;
                    """.trimIndent(),
                onRequest = { attempted.complete(Unit) },
            ) { fixture ->
                val source = fixture.platform.createDataSource(fixture.context) as PluginNamedTimelineDataSourceV1
                withTimeout(10_000) { attempted.await() }
                assertTrue(
                    source
                        .timeline("home")
                        .load(20, PagingRequest.Refresh)
                        .data
                        .isEmpty(),
                )
                withTimeout(15_000) { fixture.context.refreshed.await() }
                assertEquals(2, fixture.requests)
                assertEquals(JsonPrimitive("old-token"), fixture.context.value.value.credential)
            }
        }

    @Test
    fun incompatibleOldComposeLimitsDoNotBlockNegotiationOrTheTimeline() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withPlugin(onRequest = {
                started.complete(Unit)
                release.await()
            }) { fixture ->
                fixture.context.value.value =
                    fixture.context.value.value.let { account ->
                        account.copy(
                            snapshot =
                                account.snapshot.copy(
                                    composeConfig =
                                        ComposeConfigV2(
                                            visibility =
                                                ComposeConfigV2.VisibilityConfigV1(
                                                    setOf(VisibilityV1.Followers),
                                                    VisibilityV1.Followers,
                                                ),
                                        ),
                                ),
                        )
                    }
                val saved = PluginJsonV1.encodeToString(PluginAccountCredentialV1.serializer(), fixture.context.value.value)
                assertEquals(saved, fixture.platform.prepareCredential(saved))
                val source = fixture.platform.createDataSource(fixture.context)
                assertNull(source.capabilities.compose)
                withTimeout(10_000) { started.await() }
                assertTrue(
                    (source as PluginNamedTimelineDataSourceV1)
                        .timeline("home")
                        .load(20, PagingRequest.Refresh)
                        .data
                        .isEmpty(),
                )
                release.complete(Unit)
                withTimeout(10_000) { fixture.context.refreshed.await() }
                assertNotNull(
                    fixture.platform
                        .createDataSource(fixture.context)
                        .capabilities.compose,
                )
            }
        }

    @Test
    fun malformedNegotiationDoesNotPauseTheWorkingPlugin() =
        runBlocking<Unit> {
            withPlugin(body = "return {};") { fixture ->
                repeat(4) { assertFailsWith<Exception> { fixture.refresher.refresh(fixture.context) } }
                val source = fixture.platform.createDataSource(fixture.context) as PluginNamedTimelineDataSourceV1
                assertTrue(
                    source
                        .timeline("home")
                        .load(20, PagingRequest.Refresh)
                        .data
                        .isEmpty(),
                )
                assertNull(fixture.context.value.value.snapshot.capabilitiesPackageHash)
            }
        }

    @Test
    fun savedNegotiationRetriesDatasourceInvalidationWithoutRepeatingTheProviderCall() =
        runBlocking<Unit> {
            withPlugin { fixture ->
                val before = fixture.context.value.value
                val captured =
                    object : PlatformDataSourceContext by fixture.context {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : Any> credential(serializer: KSerializer<T>): T = before as T
                    }
                fixture.context.invalidationFailure = IllegalStateException("temporary storage failure")
                assertFailsWith<IllegalStateException> { fixture.refresher.refresh(captured) }
                assertEquals(fixture.plugin.installed.packageHash, fixture.context.value.value.snapshot.capabilitiesPackageHash)
                fixture.context.invalidationFailure = null
                assertTrue(fixture.refresher.refresh(captured))
                assertTrue(fixture.context.refreshed.isCompleted)
                assertEquals(1, fixture.requests)
            }
        }

    @Test
    fun incompatibleCredentialSchemaDoesNotInvokeNegotiation() =
        runBlocking<Unit> {
            withPlugin { fixture ->
                val before =
                    fixture.context.value.value
                        .let { it.copy(snapshot = it.snapshot.copy(credentialSchemaVersion = 2)) }
                fixture.context.value.value = before
                assertTrue(fixture.refresher.refresh(fixture.context))
                assertEquals(0, fixture.requests)
                assertEquals(before, fixture.context.value.value)
            }
        }

    private suspend fun withPlugin(
        apiVersion: Int = 2,
        body: String = "return RESULT;",
        onRequest: suspend () -> Unit = {},
        block: suspend (Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("plugin-account-capabilities").toOkioPath()
        val files = FileSystem.SYSTEM
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tokens = mutableListOf<String>()
        val pool =
            PluginRuntimePool(
                files,
                object : PluginHttpTransport {
                    override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 {
                        tokens += request.headers.getValue("Authorization").removePrefix("Bearer ")
                        onRequest()
                        return PluginTransportResponseV1(200, emptyMap(), "{\"attempt\":${tokens.size}}".encodeToByteArray())
                    }
                },
            )
        try {
            val http =
                if (apiVersion ==
                    2
                ) {
                    "\"${Capabilities.ACCOUNT_HTTP}\": {\"operations\": {\"authenticate\": {}}, \"authOrigins\": [\"\$accountOrigin\"]},"
                } else {
                    ""
                }
            val manifest =
                TestFppFactory.validManifest
                    .replace("\"apiVersion\": 1", "\"apiVersion\": $apiVersion")
                    .replace(
                        "\"capabilities\": {",
                        """
                        "composeDefaults": {"supportedTypes": ["new", "reply", "quote"], "visibility": {"allowed": ["public"], "default": "public"}},
                        "composeDefaultsV3": {"supportedTypes": ["new", "reply", "quote"], "visibility": {"allowed": ["selfOnly"], "default": "selfOnly"}},
                        "capabilities": {
                          "${Capabilities.ACCOUNT_CAPABILITIES}": {"operations": {"negotiate": {}}},
                          "${Capabilities.COMPOSE}": {"operations": {"publish": {}}},
                          "${Capabilities.COMPOSE_V3}": {"operations": {"publish": {}}},
                          $http
                        """.trimIndent(),
                    )
            val resultHttp = if (apiVersion == 2) "'${Capabilities.ACCOUNT_HTTP}': ['authenticate']," else ""
            val script =
                TestFppFactory.validScript
                    .replace("return { type: \"Pending\" };", "throw new Error('Login must not run');")
                    .replace(
                        "capabilities: {",
                        """
                        capabilities: {
                          accountCapabilities: { async negotiate(request, context) {
                            const token = await flare.credential.read();
                            const response = await flare.http.request({
                              url: context.origin + '/negotiate', method: 'GET', authorization: {type: 'bearer', token}
                            });
                            const RESULT = {
                              capabilities: { 'flare.datasource.timeline/v1': ['page'], '${Capabilities.COMPOSE}': ['publish'], '${Capabilities.COMPOSE_V3}': ['publish'], $resultHttp },
                              composeConfig: {supportedTypes: ['new', 'reply', 'quote']},
                              composeConfigV3: {supportedTypes: ['new', 'reply', 'quote'], visibility: {allowed: ['selfOnly'], default: 'selfOnly'}}
                            };
                            $body
                          } },
                          accountHttp: { async authenticate() { return {Authorization: await flare.credential.read()}; } },
                          compose: { async publish() { throw new Error('unused'); } },
                          composeV3: { async publish() { throw new Error('unused'); } },
                        """.trimIndent(),
                    )
            val archive = root / "plugin.fpp"
            TestFppFactory.write(archive, TestFppFactory.validEntries(manifest, script))
            val store = PluginStateStore.open(files, root / "state")
            val installer = PluginInstaller(files, store)
            installer.commit(installer.inspect(archive), confirmed = true, activate = store::install)
            val running = PluginStateStore.open(files, root / "state").running
            val plugin = running.plugins.values.single()
            val pending =
                object : PluginOAuthPendingStoreV1 {
                    override suspend fun save(pending: PluginOAuthPendingV1): Unit = error("Login must not run")

                    override suspend fun load(flowId: String): PluginOAuthPendingV1? = error("Login must not run")

                    override suspend fun consume(pending: PluginOAuthPendingV1): Boolean = error("Login must not run")
                }
            val platform =
                PluginPlatformSpecSourceV1(
                    running,
                    pool,
                    PluginOAuthLoginCoordinatorV1(pool, pending, runningPlugin = running.plugins::get),
                    PluginFormLoginCoordinatorV1(pool),
                    PluginWebCookieLoginCoordinatorV1(pool),
                    scope,
                ).load(emptySet()).single()
            val json = """{
                "snapshot": {
                  "pluginId": "dev.dimension.flare.test.plugin", "platformId": "TestPlugin", "packageHash": "${"a".repeat(64)}",
                  "origin": "https://instance.example", "accountId": "me",
                  "profile": {"key": {"id": "me", "host": "instance.example"}, "handle": "me", "displayName": "Me"},
                  "manifestCapabilities": {"flare.datasource.timeline/v1": ["page"], "flare.datasource.compose/v1": ["publish"]},
                  "negotiatedCapabilities": {"flare.datasource.timeline/v1": ["page"], "flare.datasource.compose/v1": ["publish"]},
                  "composeConfig": {}, "credentialSchemaVersion": 1
                }, "credential": "old-token"
            }"""
            val account = PluginJsonV1.decodeFromString<PluginAccountCredentialV1>(platform.prepareCredential(json))
            val context = Context(account)
            block(Fixture(plugin, platform, context, PluginAccountCapabilitiesV1(plugin, pool, scope), tokens))
        } finally {
            scope.cancel()
            pool.close()
            files.deleteRecursively(root)
        }
    }

    private class Fixture(
        val plugin: RunningPluginV1,
        val platform: PlatformSpec,
        val context: Context,
        val refresher: PluginAccountCapabilitiesV1,
        val tokens: List<String>,
    ) {
        val requests get() = tokens.size
    }

    private class Context(
        initial: PluginAccountCredentialV1,
    ) : PlatformDataSourceContext {
        val value = MutableStateFlow(initial)
        val refreshed = CompletableDeferred<Unit>()
        var removed = false
        var invalidations = 0
        var invalidationFailure: Exception? = null
        private val mutex = Mutex()
        override val accountKey = MicroBlogKey("me", "instance.example")

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> credential(serializer: KSerializer<T>): T = value.value as T

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> credentialFlow(serializer: KSerializer<T>): Flow<T> =
            value.map {
                if (removed) throw CancellationException("Account replaced")
                it as T
            }

        override suspend fun <T : Any> updateCredential(
            serializer: KSerializer<T>,
            credential: T,
        ) = error("Use atomic updates")

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> mutateCredential(
            serializer: KSerializer<T>,
            update: (T) -> T?,
        ): T? =
            mutex.withLock {
                if (removed) throw CancellationException("Account replaced")
                update(value.value as T)?.also { value.value = it as PluginAccountCredentialV1 }
            }

        override suspend fun invalidateDataSource() {
            invalidationFailure?.let { throw it }
            invalidations++
            refreshed.complete(Unit)
        }
    }
}
