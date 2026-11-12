package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.common.Locale
import dev.dimension.flare.feature.plugin.host.PluginCredentialAccess
import dev.dimension.flare.feature.plugin.host.PluginCredentialSnapshot
import dev.dimension.flare.feature.plugin.host.PluginHostException
import dev.dimension.flare.feature.plugin.host.accountHost
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.login.requireLoginSuccess
import dev.dimension.flare.feature.plugin.manifest.socialPlatform
import dev.dimension.flare.feature.plugin.runtime.PluginCallException
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeKeyV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeUnavailableException
import dev.dimension.flare.feature.plugin.wire.AccountCapabilitiesRequestV1
import dev.dimension.flare.feature.plugin.wire.AccountCapabilitiesV1
import dev.dimension.flare.feature.plugin.wire.LoginSuccessV1
import dev.dimension.flare.feature.plugin.wire.PluginAccountCredentialV1
import dev.dimension.flare.feature.plugin.wire.PluginErrorCodeV1
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

internal class PluginAccountCapabilitiesV1(
    private val plugin: RunningPluginV1,
    private val runtimePool: PluginRuntimePool,
    private val scope: CoroutineScope,
) {
    private val locksMutex = Mutex()
    private val locks = mutableMapOf<MicroBlogKey, Mutex>()

    fun start(context: PlatformDataSourceContext) {
        if (!context.credential(PluginAccountCredentialV1.serializer()).needsCapabilityNegotiation(plugin)) return
        scope.launch {
            val lock = locksMutex.withLock { locks.getOrPut(context.accountKey) { Mutex() } }
            lock.withLock {
                var retryDelay = 5_000L
                while (true) {
                    try {
                        if (refresh(context)) return@withLock
                    } catch (_: TimeoutCancellationException) {
                        // A call timeout must not turn a metadata refresh into a login requirement.
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: PluginCallException) {
                        if (error.error.code !in
                            setOf(
                                PluginErrorCodeV1.Network,
                                PluginErrorCodeV1.Remote,
                                PluginErrorCodeV1.RateLimited,
                                PluginErrorCodeV1.AuthenticationRequired,
                            )
                        ) {
                            return@withLock
                        }
                        retryDelay = maxOf(retryDelay, (error.error.retryAfterSeconds ?: 0).coerceIn(0, 86_400) * 1_000)
                    } catch (error: PluginRuntimeUnavailableException) {
                        if (error.cause !is TimeoutCancellationException) return@withLock
                    } catch (_: IllegalArgumentException) {
                        return@withLock
                    } catch (_: Exception) {
                        // Keep the saved capabilities until an offline or temporarily failing provider recovers.
                    }
                    delay(retryDelay)
                    retryDelay = (retryDelay * 6).coerceAtMost(300_000L)
                }
            }
        }
    }

    internal suspend fun refresh(context: PlatformDataSourceContext): Boolean {
        plugin.session.ensureActive()
        val account = context.credentialFlow(PluginAccountCredentialV1.serializer()).first()
        account.requireValid(plugin)
        require(context.accountKey == MicroBlogKey(account.snapshot.accountId, Url(account.snapshot.origin).accountHost()))
        val platform = plugin.installed.manifest.socialPlatform
        if (account.snapshot.capabilitiesPackageHash == plugin.installed.packageHash) {
            if (context.credential(PluginAccountCredentialV1.serializer()).snapshot.capabilitiesPackageHash !=
                plugin.installed.packageHash
            ) {
                context.invalidateDataSource()
            }
            return true
        }
        if (account.snapshot.credentialSchemaVersion != platform.credentialSchemaVersion) return true
        val access =
            object : PluginCredentialAccess {
                override suspend fun ensureActive() {
                    plugin.session.ensureActive()
                    val current = context.credentialFlow(PluginAccountCredentialV1.serializer()).first()
                    if (current.sessionEpoch != account.sessionEpoch) throw CancellationException("Account session was replaced")
                }

                override suspend fun read(): JsonElement {
                    ensureActive()
                    return account.credential
                }

                override suspend fun readVersioned(): PluginCredentialSnapshot =
                    PluginCredentialSnapshot(read(), requireNotNull(account.credentialRevision))

                override suspend fun replace(value: JsonElement): Unit =
                    throw PluginHostException("host.permissionDenied", "Capability negotiation cannot replace credentials")

                override suspend fun replaceIfCurrent(
                    value: JsonElement,
                    revision: String,
                ): Boolean = throw PluginHostException("host.permissionDenied", "Capability negotiation cannot replace credentials")
            }
        val result =
            try {
                runtimePool
                    .invoke(
                        plugin = plugin,
                        key =
                            PluginRuntimeKeyV1.account(
                                plugin.installed.pluginId,
                                plugin.installed.packageHash,
                                account.snapshot.origin,
                                account.snapshot.accountId,
                                epoch = account.sessionEpoch.takeIf { plugin.installed.manifest.apiVersion >= 2 },
                            ),
                        context = plugin.accountInvocationContext(account, Locale.language, access),
                        method = "capabilities.accountCapabilities.negotiate",
                        request =
                            AccountCapabilitiesRequestV1(
                                AccountCapabilitiesV1(
                                    account.snapshot.negotiatedCapabilities,
                                    account.snapshot.composeConfig,
                                    account.snapshot.composeConfigV3,
                                ),
                            ),
                        requestSerializer = AccountCapabilitiesRequestV1.serializer(),
                        responseSerializer = AccountCapabilitiesV1.serializer(),
                    ).also { access.ensureActive() }
            } catch (error: Exception) {
                access.ensureActive()
                throw error
            }
        val validated =
            plugin.requireLoginSuccess(
                LoginSuccessV1(
                    account.snapshot.accountId,
                    account.snapshot.origin,
                    account.credential,
                    account.snapshot.profile,
                    result.capabilities,
                    result.composeConfig,
                    result.composeConfigV3,
                ),
                account.snapshot.origin,
                account.snapshot.accountId,
            )
        context.invalidateCachedContent()
        return withContext(NonCancellable) {
            val updated =
                context.mutateCredential(PluginAccountCredentialV1.serializer()) { current ->
                    plugin.session.ensureActive()
                    if (!current.sameInputs(account)) return@mutateCredential null
                    current
                        .copy(
                            snapshot =
                                current.snapshot.copy(
                                    packageHash = plugin.installed.packageHash,
                                    manifestCapabilities = platform.capabilities.mapValues { it.value.operations.keys },
                                    negotiatedCapabilities = validated.capabilities,
                                    composeConfig = validated.composeConfig,
                                    composeConfigV3 = validated.composeConfigV3,
                                    capabilitiesPackageHash = plugin.installed.packageHash,
                                ),
                        ).also { it.requireValid(plugin) }
                }
            if (updated != null) context.invalidateDataSource()
            updated != null
        }
    }
}

private fun PluginAccountCredentialV1.sameInputs(other: PluginAccountCredentialV1): Boolean =
    copy(snapshot = snapshot.copy(packageHash = other.snapshot.packageHash)) == other
