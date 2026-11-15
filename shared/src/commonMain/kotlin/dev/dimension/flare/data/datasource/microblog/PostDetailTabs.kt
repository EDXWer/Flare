package dev.dimension.flare.data.datasource.microblog

import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlin.native.HiddenFromObjC

/** An empty catalog represents a post with no child sections. */
@HiddenFromObjC
public data class PostDetailTabs(
    val tabs: List<PostDetailTab>,
    val replyTabId: String? = null,
)

@HiddenFromObjC
public data class PostDetailTab(
    val id: String,
    val title: UiText,
    val filter: PostDetailFilter? = null,
    val includesPostContext: Boolean = false,
    val loader: (filterId: String?) -> RemoteLoader<UiTimelineV2>,
)

@HiddenFromObjC
public data class PostDetailFilter(
    val options: List<PostDetailFilterOption>,
    val defaultOptionId: String,
)

@HiddenFromObjC
public data class PostDetailFilterOption(
    val id: String,
    val title: UiText,
)
