package dev.dimension.flare.data.datasource.microblog.datasource

import androidx.compose.runtime.Immutable
import dev.dimension.flare.common.Cacheable
import dev.dimension.flare.common.SerializableImmutableList
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.media.UgoiraMetadata
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.humanizer.Formatter.humanize
import dev.dimension.flare.ui.model.ClickContext
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.DeeplinkEvent
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.onClicked
import dev.dimension.flare.ui.render.UiDateTime
import dev.dimension.flare.ui.render.UiRichText
import kotlinx.serialization.Serializable
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public interface GalleryDataSource {
    public fun galleryDetail(statusKey: MicroBlogKey): Cacheable<GalleryDetail>

    public fun galleryComments(statusKey: MicroBlogKey): RemoteLoader<UiTimelineV2>

    public fun galleryRecommendations(statusKey: MicroBlogKey): RemoteLoader<UiTimelineV2>

    public suspend fun ugoiraMetadata(statusKey: MicroBlogKey): UgoiraMetadata
}

@Serializable
@Immutable
public data class GalleryDetail(
    val orientation: GalleryOrientation,
    val statusKey: MicroBlogKey,
    val accountType: AccountType,
    val url: String,
    val images: SerializableImmutableList<UiMedia>,
    val title: String,
    val author: UiProfile?,
    val createdAt: UiDateTime,
    val content: UiRichText?,
    val isBookmarked: Boolean,
    val bookmarkAction: ClickEvent,
    val matrix: SerializableImmutableList<Matrix>,
    val translation: Translation? = null,
) {
    val onBookmark: ClickContext.() -> Unit by lazy {
        bookmarkAction.onClicked
    }

    val onTranslate: ClickContext.() -> Unit
        get() = translationAction.onClicked

    internal val translationAction: ClickEvent
        get() {
            val accountKey = (accountType as? AccountType.Specific)?.accountKey ?: return ClickEvent.Noop
            val state = translation?.state ?: return ClickEvent.Noop
            val event =
                when (state) {
                    TranslationDisplayState.Translating,
                    TranslationDisplayState.Translated,
                    -> DeeplinkEvent.TranslationEvent.ShowOriginal(statusKey)

                    TranslationDisplayState.Failed -> DeeplinkEvent.TranslationEvent.RetryTranslation(statusKey)

                    TranslationDisplayState.Hidden -> DeeplinkEvent.TranslationEvent.Translate(statusKey)
                }
            return ClickEvent.Deeplink(DeeplinkEvent(accountKey = accountKey, translationEvent = event))
        }

    @Serializable
    @Immutable
    public data class Translation(
        val title: String?,
        val content: UiRichText?,
        val state: TranslationDisplayState,
    )

    @Serializable
    @Immutable
    public data class Matrix(
        public val icon: UiIcon,
        public val count: Long,
    ) {
        public val humanizedCount: String by lazy {
            count.humanize()
        }
    }
}

@Serializable
@Immutable
public enum class GalleryOrientation {
    Horizontal,
    Vertical,
}
