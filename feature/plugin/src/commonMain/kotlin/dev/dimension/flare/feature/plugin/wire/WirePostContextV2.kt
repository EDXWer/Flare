package dev.dimension.flare.feature.plugin.wire

import kotlinx.serialization.Serializable

@Serializable
public data class PostContextTabsV2(
    val tabs: List<PostContextTabV2>,
    val replyTabId: String? = null,
)

@Serializable
public data class PostContextTabV2(
    val id: String,
    val title: WireTextV1,
    val filter: PostContextFilterV2? = null,
)

@Serializable
public data class PostContextFilterV2(
    val options: List<PostContextFilterOptionV2>,
    val defaultOptionId: String,
)

@Serializable
public data class PostContextFilterOptionV2(
    val id: String,
    val title: WireTextV1,
)

@Serializable
public data class PostContextPageRequestV2(
    val key: EntityKeyV1,
    val tabId: String,
    val page: PageRequestV1,
    val filterId: String? = null,
)
