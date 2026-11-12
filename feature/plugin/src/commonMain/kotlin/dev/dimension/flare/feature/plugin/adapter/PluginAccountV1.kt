package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.data.model.IconType
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.RequireReLoginException
import dev.dimension.flare.feature.plugin.abi.PluginAbiV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.host.PluginCredentialAccess
import dev.dimension.flare.feature.plugin.host.PluginCredentialSnapshot
import dev.dimension.flare.feature.plugin.host.PluginInvocationContextV1
import dev.dimension.flare.feature.plugin.host.PluginUrlPolicy
import dev.dimension.flare.feature.plugin.host.accountHost
import dev.dimension.flare.feature.plugin.host.platformUuid
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.manifest.fallbackText
import dev.dimension.flare.feature.plugin.manifest.socialPlatform
import dev.dimension.flare.feature.plugin.manifest.toUiText
import dev.dimension.flare.feature.plugin.wire.AccountPluginSnapshotV1
import dev.dimension.flare.feature.plugin.wire.ComposeConfigV2
import dev.dimension.flare.feature.plugin.wire.LoginSuccessV1
import dev.dimension.flare.feature.plugin.wire.PluginAccountCredentialV1
import dev.dimension.flare.feature.plugin.wire.WireLimitsV1
import dev.dimension.flare.feature.plugin.wire.legacyComposeTypes
import dev.dimension.flare.feature.plugin.wire.requireValid
import dev.dimension.flare.feature.plugin.wire.usesLegacyCompose
import dev.dimension.flare.feature.plugin.wire.withLegacyComposeTypes
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import dev.dimension.flare.model.PlatformMetadata
import dev.dimension.flare.ui.model.UiAccount
import io.ktor.http.Url
import io.ktor.http.encodeURLPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

public fun RunningPluginV1.accountCredential(success: LoginSuccessV1): PluginAccountCredentialV1 {
    success.requireValid()
    val manifestCapabilities =
        PluginAbiV1.knownCapabilities(
            installed.manifest.socialPlatform.capabilities
                .mapValues { it.value.operations.keys },
        )
    return PluginAccountCredentialV1(
        snapshot =
            AccountPluginSnapshotV1(
                pluginId = installed.pluginId,
                platformId = installed.manifest.socialPlatform.id,
                packageHash = installed.packageHash,
                origin = PluginUrlPolicy.requireOrigin(success.origin),
                accountId = success.accountId,
                profile = success.profile,
                manifestCapabilities = manifestCapabilities,
                negotiatedCapabilities = PluginAbiV1.knownCapabilities(success.capabilities),
                composeConfig = success.composeConfig,
                credentialSchemaVersion = installed.manifest.socialPlatform.credentialSchemaVersion,
                composeConfigV3 = success.composeConfigV3,
                capabilitiesPackageHash = installed.packageHash,
            ),
        credential = success.credential,
        sessionEpoch = if (installed.manifest.apiVersion >= 2) platformUuid() else null,
        credentialRevision = if (installed.manifest.apiVersion >= 2) platformUuid() else null,
    ).also { it.requireValid(this) }
}

internal fun RunningPluginV1.platformMetadata(): PlatformMetadata {
    val platform = installed.manifest.socialPlatform
    val normalized = iconPath.replace('\\', '/')
    val absolute = if (normalized.startsWith('/')) normalized else "/$normalized"
    return PlatformMetadata(
        displayName = platform.name.fallbackText,
        icon = IconType.Url("file://${absolute.encodeURLPath()}"),
        displayNameText = platform.name.toUiText(installed.pluginId),
    )
}

internal suspend fun AccountService.addPluginAccount(
    plugin: RunningPluginV1,
    success: LoginSuccessV1,
    beforeCommit: () -> Unit = {},
): MicroBlogKey {
    plugin.session.ensureActive()
    val metadata = plugin.platformMetadata()
    val accountKey =
        MicroBlogKey(
            success.accountId,
            Url(success.origin).accountHost(),
        )
    addAccount(
        account =
            UiAccount(
                accountKey = accountKey,
                platformId = plugin.installed.manifest.socialPlatform.id,
                platformDisplayName = metadata.displayName,
                platformIcon = metadata.icon,
                platformDisplayNameText = metadata.displayNameText,
            ),
        credential = plugin.accountCredential(success),
        serializer = PluginAccountCredentialV1.serializer(),
        beforeCommit = {
            plugin.session.ensureActive()
            beforeCommit()
        },
    )
    return accountKey
}

internal fun PluginAccountCredentialV1.requireValid(plugin: RunningPluginV1) {
    require(
        sessionEpoch == null || (sessionEpoch.length in 1..256 && sessionEpoch.none(Char::isISOControl)),
    ) { "Invalid account session epoch" }
    require(credentialRevision == null || (credentialRevision.length in 1..256 && credentialRevision.none(Char::isISOControl))) {
        "Invalid credential revision"
    }
    val manifest = plugin.installed.manifest
    require(snapshot.pluginId == plugin.installed.pluginId && snapshot.platformId == manifest.socialPlatform.id) {
        "Plugin account identity mismatch"
    }
    require(PACKAGE_HASH.matches(snapshot.packageHash)) { "Invalid plugin account package hash" }
    require(snapshot.capabilitiesPackageHash == null || PACKAGE_HASH.matches(snapshot.capabilitiesPackageHash)) {
        "Invalid capability negotiation package hash"
    }
    require(snapshot.accountId.isNotBlank() && snapshot.accountId.length <= WireLimitsV1.MAX_ID_LENGTH) {
        "Invalid plugin account ID"
    }
    require(PluginUrlPolicy.requireOrigin(snapshot.origin) == snapshot.origin) { "Invalid plugin account origin" }
    snapshot.profile.requireValid()
    require(
        snapshot.profile.key.id == snapshot.accountId &&
            snapshot.profile.key.host
                .equals(Url(snapshot.origin).accountHost(), true),
    ) {
        "Plugin account profile identity mismatch"
    }
    require(snapshot.credentialSchemaVersion > 0) { "Invalid plugin account schema" }
    require(snapshot.manifestCapabilities.isCapabilitySnapshot() && snapshot.negotiatedCapabilities.isCapabilitySnapshot()) {
        "Invalid plugin account capabilities"
    }
    if (needsCapabilityNegotiation(plugin)) {
        // An upgraded manifest may replace incompatible compose limits; validate the saved snapshot independently until negotiation.
        snapshot.composeConfig.withLegacyComposeTypes(snapshot.negotiatedCapabilities.keys.usesLegacyCompose())?.requireValid(
            requireSupportedTypes =
                snapshot.negotiatedCapabilities.keys.any {
                    it == PluginAbiV1.Capabilities.COMPOSE_V1 || it == PluginAbiV1.Capabilities.COMPOSE
                },
        )
        snapshot.composeConfigV3?.requireValid(allowExtendedVisibility = true)
    } else {
        composeConfig(plugin)
        mergePluginComposeConfigV2(manifest.socialPlatform.composeDefaultsV3, snapshot.composeConfigV3, allowExtendedVisibility = true)
    }
    require(
        PluginJsonV1
            .encodeToString(JsonElement.serializer(), credential)
            .encodeToByteArray()
            .size <= WireLimitsV1.MAX_CREDENTIAL_BYTES,
    ) { "Plugin credential is too large" }
}

internal fun PluginAccountCredentialV1.effectiveCapabilities(plugin: RunningPluginV1): Map<String, Set<String>> {
    requireValid(plugin)
    return effectiveOperations(plugin)
}

internal fun PluginAccountCredentialV1.needsCapabilityNegotiation(plugin: RunningPluginV1): Boolean =
    snapshot.capabilitiesPackageHash != plugin.installed.packageHash &&
        snapshot.credentialSchemaVersion == plugin.installed.manifest.socialPlatform.credentialSchemaVersion &&
        "negotiate" in
        plugin.installed.manifest.socialPlatform.capabilities[PluginAbiV1.Capabilities.ACCOUNT_CAPABILITIES]
            ?.operations
            .orEmpty()

internal fun PluginAccountCredentialV1.composeConfig(plugin: RunningPluginV1): ComposeConfigV2? {
    val platform = plugin.installed.manifest.socialPlatform
    val operations = effectiveOperations(plugin)
    if ("publish" !in operations[PluginAbiV1.Capabilities.COMPOSE_V1].orEmpty() &&
        "publish" !in operations[PluginAbiV1.Capabilities.COMPOSE].orEmpty()
    ) {
        snapshot.composeConfig?.requireValid(requireSupportedTypes = false)
        return null
    }
    val legacy =
        "publish" in operations[PluginAbiV1.Capabilities.COMPOSE_V1].orEmpty() &&
            "publish" !in operations[PluginAbiV1.Capabilities.COMPOSE].orEmpty()
    val legacySnapshot =
        PluginAbiV1.Capabilities.COMPOSE_V1 in snapshot.manifestCapabilities &&
            PluginAbiV1.Capabilities.COMPOSE !in snapshot.negotiatedCapabilities
    val hadCompose =
        PluginAbiV1.Capabilities.COMPOSE_V1 in snapshot.manifestCapabilities ||
            PluginAbiV1.Capabilities.COMPOSE in snapshot.manifestCapabilities
    if (!hadCompose) snapshot.composeConfig?.requireValid(requireSupportedTypes = false)
    val config =
        mergePluginComposeConfigV2(
            platform.composeDefaults.withLegacyComposeTypes(legacy || platform.capabilities.keys.usesLegacyCompose()),
            snapshot.composeConfig.takeIf { hadCompose }.withLegacyComposeTypes(legacySnapshot),
            requireModeOverlap = !legacySnapshot || legacy,
        ) ?: return null
    return if (legacy || legacySnapshot) config.copy(supportedTypes = config.supportedTypes intersect legacyComposeTypes) else config
}

private fun PluginAccountCredentialV1.effectiveOperations(plugin: RunningPluginV1): Map<String, Set<String>> =
    plugin.installed.manifest.socialPlatform.capabilities
        .mapValues { (capability, declaration) ->
            val previousCapability =
                if (capability == PluginAbiV1.Capabilities.COMPOSE && snapshot.manifestCapabilities.keys.usesLegacyCompose()) {
                    PluginAbiV1.Capabilities.COMPOSE_V1
                } else {
                    capability
                }
            val previousManifest = snapshot.manifestCapabilities[previousCapability].orEmpty()
            val negotiated = snapshot.negotiatedCapabilities[previousCapability].orEmpty()
            declaration.operations.keys.filterTo(linkedSetOf()) { operation ->
                operation in negotiated ||
                    (
                        capability != PluginAbiV1.Capabilities.ACCOUNT_HTTP &&
                            capability != PluginAbiV1.Capabilities.COMPOSE_V3 &&
                            operation !in previousManifest
                    )
            }
        }.filterValues(Set<String>::isNotEmpty)
        .let(PluginAbiV1::knownCapabilities)

internal fun RunningPluginV1.prepareAccountCredential(json: String): String {
    if (installed.manifest.apiVersion < 2) return json
    session.ensureActive()
    val account = PluginJsonV1.decodeFromString<PluginAccountCredentialV1>(json).also { it.requireValid(this) }
    if (account.snapshot.credentialSchemaVersion != installed.manifest.socialPlatform.credentialSchemaVersion) return json
    if (account.sessionEpoch != null && account.credentialRevision != null) return json
    require(account.sessionEpoch == null && account.credentialRevision == null) { "Incomplete plugin account session" }
    return PluginJsonV1.encodeToString(
        PluginAccountCredentialV1.serializer(),
        account.copy(sessionEpoch = platformUuid(), credentialRevision = platformUuid()),
    )
}

internal class PlatformDataSourceCredentialAccessV1(
    private val plugin: RunningPluginV1,
    private val context: PlatformDataSourceContext,
) : PluginCredentialAccess {
    private val packageRefreshMutex = Mutex()
    private val epoch = context.credential(PluginAccountCredentialV1.serializer()).sessionEpoch
    private val capabilitiesPackageHash = context.credential(PluginAccountCredentialV1.serializer()).snapshot.capabilitiesPackageHash

    override suspend fun read(): JsonElement = current().credential

    override suspend fun replace(value: JsonElement) {
        update(value, null)
    }

    override suspend fun readVersioned(): PluginCredentialSnapshot =
        current().let { PluginCredentialSnapshot(it.credential, requireNotNull(it.credentialRevision)) }

    override suspend fun replaceIfCurrent(
        value: JsonElement,
        revision: String,
    ): Boolean = update(value, revision)

    override suspend fun ensureActive() {
        current()
    }

    private suspend fun update(
        value: JsonElement,
        revision: String?,
    ): Boolean =
        mutateCurrent { current ->
            if (revision != null && revision != current.credentialRevision) {
                null
            } else {
                current
                    .copy(
                        credential = value,
                        credentialRevision = if (plugin.installed.manifest.apiVersion >= 2) platformUuid() else current.credentialRevision,
                    ).also { it.requireValid(plugin) }
            }
        } != null

    private suspend fun mutateCurrent(update: (PluginAccountCredentialV1) -> PluginAccountCredentialV1?): PluginAccountCredentialV1? =
        context.mutateCredential(PluginAccountCredentialV1.serializer()) { current ->
            requireCurrentAccount(current)
            if (plugin.installed.manifest.apiVersion >= 2) requireEpoch(current)
            if (current.snapshot.credentialSchemaVersion != plugin.installed.manifest.socialPlatform.credentialSchemaVersion) {
                throw RequireReLoginException(context.accountKey, plugin.installed.manifest.socialPlatform.id)
            }
            plugin.session.ensureActive()
            update(current)
        }

    private fun requireEpoch(account: PluginAccountCredentialV1) {
        if (epoch == null || account.credentialRevision == null) {
            throw RequireReLoginException(context.accountKey, plugin.installed.manifest.socialPlatform.id)
        }
        if (account.sessionEpoch != epoch) throw CancellationException("Plugin account session was replaced")
        if (account.snapshot.credentialSchemaVersion != plugin.installed.manifest.socialPlatform.credentialSchemaVersion) {
            throw RequireReLoginException(context.accountKey, plugin.installed.manifest.socialPlatform.id)
        }
    }

    fun initial(): PluginAccountCredentialV1 =
        context
            .credential(PluginAccountCredentialV1.serializer())
            .also(::requireCurrentAccount)

    suspend fun current(): PluginAccountCredentialV1 =
        packageRefreshMutex.withLock {
            val account = mutateCurrent { it } ?: throw CancellationException("Plugin account is unavailable")
            if (account.snapshot.packageHash == plugin.installed.packageHash) {
                account
            } else {
                context.invalidateCachedContent()
                mutateCurrent { current ->
                    current.copy(snapshot = current.snapshot.copy(packageHash = plugin.installed.packageHash))
                } ?: throw CancellationException("Plugin account is unavailable")
            }
        }

    private fun requireCurrentAccount(account: PluginAccountCredentialV1) {
        if (account.snapshot.capabilitiesPackageHash != capabilitiesPackageHash) {
            throw CancellationException("Plugin account capabilities were updated")
        }
        account.requireValid(plugin)
        val expectedKey = MicroBlogKey(account.snapshot.accountId, Url(account.snapshot.origin).accountHost())
        if (expectedKey != context.accountKey) {
            throw RequireReLoginException(context.accountKey, plugin.installed.manifest.socialPlatform.id)
        }
    }
}

internal fun RunningPluginV1.accountInvocationContext(
    account: PluginAccountCredentialV1,
    locale: String,
    credentialAccess: PluginCredentialAccess,
    assets: Map<String, dev.dimension.flare.feature.plugin.host.PluginAsset> = emptyMap(),
): PluginInvocationContextV1 =
    PluginInvocationContextV1.account(
        pluginId = installed.pluginId,
        platformId = installed.manifest.socialPlatform.id,
        packageHash = installed.packageHash,
        origin = account.snapshot.origin,
        accountId = account.snapshot.accountId,
        locale = locale,
        credential = credentialAccess,
        assets = assets,
        httpOrigins = installed.manifest.permissions.httpOrigins,
    )

private fun Map<String, Set<String>>.isCapabilitySnapshot(): Boolean =
    size <= 32 &&
        all { (id, operations) -> PluginAbiV1.capabilityIdPattern.matches(id) && operations.size <= 32 } &&
        PluginAbiV1.knownCapabilities(this).all { (_, operations) ->
            operations.isNotEmpty()
        }

private val PACKAGE_HASH = Regex("[0-9a-f]{64}")
