package dev.dimension.flare.data.datasource.bluesky

import app.bsky.actor.ProfileView
import app.bsky.feed.FeedViewPost
import app.bsky.feed.GetLikesQueryParams
import app.bsky.feed.GetQuotesQueryParams
import app.bsky.feed.GetQuotesSort
import app.bsky.feed.GetRepostedByQueryParams
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.network.bluesky.BlueskyService
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.render
import dev.dimension.flare.ui.render.toUi
import sh.christian.ozone.api.AtUri
import kotlin.time.Instant

internal class BlueskyPostDetailLoader(
    private val statusKey: MicroBlogKey,
    private val accountKey: MicroBlogKey,
    private val getService: suspend () -> BlueskyService,
) {
    fun quotes(sort: GetQuotesSort): RemoteLoader<UiTimelineV2> =
        cursorLoader { limit, cursor ->
            val response =
                getQuotes(
                    GetQuotesQueryParams(AtUri(statusKey.id), limit = limit, cursor = cursor, sort = sort),
                ).requireResponse()
            PagingResult(
                data = response.posts.map(::FeedViewPost).renderWithDownloadUrls(accountKey),
                nextKey = response.cursor,
            )
        }

    fun repostedBy(): RemoteLoader<UiTimelineV2> =
        cursorLoader { limit, cursor ->
            val response = getRepostedBy(GetRepostedByQueryParams(AtUri(statusKey.id), limit = limit, cursor = cursor)).requireResponse()
            PagingResult(
                data = response.repostedBy.map { it.toUserRow() },
                nextKey = response.cursor,
            )
        }

    fun likedBy(): RemoteLoader<UiTimelineV2> =
        cursorLoader { limit, cursor ->
            val response = getLikes(GetLikesQueryParams(AtUri(statusKey.id), limit = limit, cursor = cursor)).requireResponse()
            PagingResult(
                data = response.likes.map { it.actor.toUserRow(it.createdAt) },
                nextKey = response.cursor,
            )
        }

    private fun cursorLoader(loadPage: suspend BlueskyService.(Long, String?) -> PagingResult<UiTimelineV2>): RemoteLoader<UiTimelineV2> =
        object : RemoteLoader<UiTimelineV2> {
            override suspend fun load(
                pageSize: Int,
                request: PagingRequest,
            ): PagingResult<UiTimelineV2> {
                if (request is PagingRequest.Prepend) return PagingResult()
                val cursor = (request as? PagingRequest.Append)?.nextKey
                val result = getService().loadPage(pageSize.coerceIn(1, 100).toLong(), cursor)
                return if (cursor != null && result.nextKey == cursor) result.copy(nextKey = null) else result
            }
        }

    private fun ProfileView.toUserRow(createdAt: Instant = Instant.fromEpochMilliseconds(0)): UiTimelineV2.User {
        val profile = render(accountKey)
        return UiTimelineV2.User(
            value = profile,
            createdAt = createdAt.toUi(),
            statusKey = profile.key,
            accountType = AccountType.Specific(accountKey),
        )
    }
}
