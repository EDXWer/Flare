package dev.dimension.flare.data.datasource.microblog

import dev.dimension.flare.common.FileItem
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.presenter.compose.ComposeStatus
import kotlinx.serialization.Serializable

public data class ComposeData(
    val content: String,
    // Null uses the publishing account's default visibility.
    val visibility: UiTimelineV2.Post.Visibility? = null,
    val language: List<String> = listOf("en"),
    val medias: List<Media> = emptyList(),
    val sensitive: Boolean = false,
    val spoilerText: String? = null,
    val poll: Poll? = null,
    val localOnly: Boolean = false,
    val referenceStatus: ReferenceStatus? = null,
    val replyRestriction: ComposeReplyRestriction? = null,
) {
    public val type: ComposeType
        get() =
            when (referenceStatus?.composeStatus) {
                is ComposeStatus.Reply -> ComposeType.Reply
                is ComposeStatus.Quote -> ComposeType.Quote
                null -> ComposeType.New
            }

    public data class Poll(
        val options: List<String>,
        val expiredAfter: Long,
        val multiple: Boolean,
    )

    public data class Media(
        val file: FileItem,
        val altText: String?,
    )

    public data class ReferenceStatus(
        val composeStatus: ComposeStatus,
    )
}

@Serializable
public enum class ComposeReplyRestriction {
    Everyone,
    Following,
    Mentioned,
    Verified,
}
