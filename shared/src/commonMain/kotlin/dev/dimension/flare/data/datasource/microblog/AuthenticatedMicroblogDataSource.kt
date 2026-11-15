package dev.dimension.flare.data.datasource.microblog

import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.flow.first
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public interface AuthenticatedMicroblogDataSource : MicroblogDataSource {
    public val accountKey: MicroBlogKey
}

@HiddenFromObjC
public interface NotificationTimelineDataSource : AuthenticatedMicroblogDataSource {
    public fun notification(type: NotificationFilter = NotificationFilter.All): RemoteLoader<UiTimelineV2>

    public val supportedNotificationFilter: List<NotificationFilter>
}

@HiddenFromObjC
public interface ComposeDataSource : AuthenticatedMicroblogDataSource {
    public suspend fun compose(
        data: ComposeData,
        progress: () -> Unit,
    ) {
        // ponytail: Use cached/default limits like the editor; add provider expiry if stale limits cause failures.
        val config = composeConfig(data.type)
        require(data.type in config.supportedTypes) { "This account does not support ${data.type.name.lowercase()} posts." }
        val poll =
            data.poll?.let { poll ->
                val support = requireNotNull(config.poll) { "This account does not support polls for this post." }
                val options = requireNotNull(support.validOptions(poll.options)) { "The poll options do not meet this account's limits." }
                require(support.allowsMultiple || !poll.multiple) { "This account only supports single-choice polls." }
                require(support.allowsMedia || data.medias.isEmpty()) { "This account does not support polls with attachments." }
                poll.copy(options = options)
            }
        val visibility = data.visibility ?: config.visibility?.defaultVisibility ?: UiTimelineV2.Post.Visibility.Public
        require(
            config.visibility?.allVisibilities?.contains(visibility)
                ?: (visibility == UiTimelineV2.Post.Visibility.Public),
        ) { "The selected visibility is not supported by this account for this post." }
        val remaining = config.text?.remainingLength(data.content, data.spoilerText)?.first()
        require(remaining?.isValid != false) { "Post text exceeds the platform limits." }
        publish(data.copy(visibility = visibility, poll = poll), progress)
    }

    // Platform hook; callers use compose to validate before uploading media.
    public suspend fun publish(
        data: ComposeData,
        progress: () -> Unit,
    )

    public fun composeConfig(type: ComposeType): ComposeConfig
}
