package dev.dimension.flare.data.datasource.vvo

import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.network.vvo.VVOService
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.render
import dev.dimension.flare.ui.render.toUi
import kotlin.time.Instant

internal class StatusLikedByLoader(
    private val service: VVOService,
    private val accountKey: MicroBlogKey,
    private val statusKey: MicroBlogKey,
) : RemoteLoader<UiTimelineV2> {
    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        var page =
            when (request) {
                PagingRequest.Refresh -> 1
                is PagingRequest.Prepend -> return PagingResult()
                is PagingRequest.Append -> request.nextKey.toInt()
            }
        while (true) {
            val likes = service.getStatusLikers(statusKey.id, page, accountKey)
            if (likes.isEmpty()) return PagingResult()
            page++
            val users =
                likes.mapNotNull { like ->
                    val user = like.user?.takeIf { it.id != null } ?: return@mapNotNull null
                    val profile = user.render(accountKey)
                    UiTimelineV2.User(
                        value = profile,
                        createdAt = (like.createdAt ?: Instant.DISTANT_PAST).toUi(),
                        statusKey = profile.key,
                        accountType = AccountType.Specific(accountKey),
                    )
                }
            if (users.isNotEmpty()) return PagingResult(data = users, nextKey = page.toString())
        }
    }
}
