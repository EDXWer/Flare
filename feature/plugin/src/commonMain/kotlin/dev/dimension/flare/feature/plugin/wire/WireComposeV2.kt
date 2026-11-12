package dev.dimension.flare.feature.plugin.wire

import dev.dimension.flare.data.datasource.microblog.ComposeType
import dev.dimension.flare.feature.plugin.abi.PluginAbiV1
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject

@Serializable
public data class ComposeConfigV2(
    // V1 did not declare modes; only its adapter supplies the legacy defaults.
    val supportedTypes: Set<ComposeType> = emptySet(),
    val text: TextConfigV1? = null,
    val media: MediaConfigV1? = null,
    val visibility: VisibilityConfigV1? = null,
    val contentWarning: Boolean = false,
    val poll: PollConfigV1? = null,
    val language: LanguageConfigV1? = null,
) {
    @Serializable
    public data class TextConfigV1(
        val maxLength: Int,
    )

    @Serializable
    public data class MediaConfigV1(
        val minCountForNew: Int = 0,
        val maxCount: Int,
        val maxBytes: Long,
        val supportedMimeTypes: Set<String> = emptySet(),
        val altTextMaxLength: Int = 1_500,
        val canSensitive: Boolean = true,
    )

    @Serializable
    public data class VisibilityConfigV1(
        val allowed: Set<VisibilityV1>,
        val default: VisibilityV1 = VisibilityV1.Public,
    )

    @Serializable
    public data class PollConfigV1(
        val maxOptions: Int,
    )

    @Serializable
    public data class LanguageConfigV1(
        val maxCount: Int,
    )
}

@Serializable
public data class ComposeAssetV1(
    val handle: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val description: String? = null,
)

@Serializable
public data class ComposePollV1(
    val options: List<String>,
    val expiresInSeconds: Long,
    val multiple: Boolean = false,
)

@Serializable
public data class ComposeRequestV2(
    val text: String,
    val visibility: VisibilityV1,
    val languages: List<String> = emptyList(),
    val assets: List<ComposeAssetV1> = emptyList(),
    val sensitive: Boolean = false,
    val spoilerText: String? = null,
    val reference: ComposeReferenceV2? = null,
    val poll: ComposePollV1? = null,
)

internal fun Set<String>.usesLegacyCompose(): Boolean =
    PluginAbiV1.Capabilities.COMPOSE_V1 in this && PluginAbiV1.Capabilities.COMPOSE !in this

internal fun ComposeConfigV2?.withLegacyComposeTypes(legacy: Boolean): ComposeConfigV2? =
    if (legacy) this?.copy(supportedTypes = legacyComposeTypes) else this

internal val legacyComposeTypes: Set<ComposeType> = setOf(ComposeType.New, ComposeType.Reply)

internal object LegacyComposeRequestV1Serializer : JsonTransformingSerializer<ComposeRequestV2>(ComposeRequestV2.serializer()) {
    override fun transformSerialize(element: JsonElement): JsonElement {
        val request = element.jsonObject
        val reference = request["reference"]?.jsonObject
        require(reference == null || reference["type"]?.toString() == "\"reply\"") { "compose/v1 does not support quotes" }
        return JsonObject((request - "reference") + reference?.get("key")?.let { mapOf("replyTo" to it) }.orEmpty())
    }
}

@Serializable
public sealed interface ComposeReferenceV2 {
    public val key: EntityKeyV1

    @Serializable
    @SerialName("reply")
    public data class Reply(
        override val key: EntityKeyV1,
    ) : ComposeReferenceV2

    @Serializable
    @SerialName("quote")
    public data class Quote(
        override val key: EntityKeyV1,
    ) : ComposeReferenceV2
}

@Serializable
public data class ComposeResultV1(
    val post: PostV1,
)
