package dev.dimension.flare.data.datasource.vvo

import androidx.paging.ExperimentalPagingApi
import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.network.vvo.VVOService
import dev.dimension.flare.data.platform.VVO_PLATFORM_ID
import dev.dimension.flare.data.repository.LoginExpiredException
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.render

internal enum class StatusCommentSort(
    val id: String,
    val flow: Int,
) {
    Hot("hot", 1),
    Time("time", 0),
}

@OptIn(ExperimentalPagingApi::class)
internal class StatusCommentRemoteMediator(
    private val service: VVOService,
    private val statusKey: MicroBlogKey,
    private val accountKey: MicroBlogKey,
    private val sort: StatusCommentSort? = StatusCommentSort.Hot,
) : CacheableRemoteLoader<UiTimelineV2> {
    override val pagingKey: String =
        if (sort == null) "status_comments_${statusKey}_$accountKey" else "status_comments_web_${sort.id}_${statusKey}_$accountKey"

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        val config = service.config()
        if (config.data?.login != true) {
            throw LoginExpiredException(
                accountKey = accountKey,
                platformId = VVO_PLATFORM_ID,
            )
        }

        val cursor =
            when (request) {
                PagingRequest.Refresh -> {
                    null
                }

                is PagingRequest.Prepend -> {
                    return PagingResult(
                        endOfPaginationReached = true,
                    )
                }

                is PagingRequest.Append -> {
                    val cursor = request.nextKey.toLongOrNull()
                    if (sort != null && (cursor == null || cursor <= 0L)) {
                        return PagingResult(endOfPaginationReached = true)
                    }
                    cursor
                }
            }

        val page =
            if (sort == null) {
                service.getHotComments(id = statusKey.id, mid = statusKey.id, maxId = cursor).data
            } else {
                service.getWebComments(id = statusKey.id, flow = sort.flow, maxId = cursor, accountKey = accountKey)
            }
        val maxId =
            page
                ?.maxID
                ?.takeIf { it != 0L }
                ?.takeUnless { sort != null && (it < 0L || it == cursor) }
        val rootStatusId = statusKey.id.takeIf { sort != null }
        return PagingResult(
            endOfPaginationReached = maxId == null,
            data =
                page
                    ?.data
                    .orEmpty()
                    .map { it.render(accountKey, rootStatusId) },
            nextKey = maxId?.toString(),
        )
    }
}
