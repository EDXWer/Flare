package dev.dimension.flare.data.datasource.mastodon

import de.jensklingenberg.ktorfit.Response
import dev.dimension.flare.data.datasource.microblog.PostDetailTab
import dev.dimension.flare.data.datasource.microblog.PostDetailTabs
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.network.mastodon.api.TimelineResources
import dev.dimension.flare.data.network.mastodon.api.model.Account
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.toAccountType
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asText
import dev.dimension.flare.ui.model.bindMediaProxy
import dev.dimension.flare.ui.model.mapper.render
import dev.dimension.flare.ui.render.toUi
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.parseHeaderValue
import io.ktor.http.takeFrom
import kotlin.time.Instant

internal fun mastodonPostDetailTabs(
    service: TimelineResources,
    host: String,
    statusKey: MicroBlogKey,
    accountKey: MicroBlogKey?,
    includeQuotes: Boolean = false,
    repliesLoader: () -> RemoteLoader<UiTimelineV2>,
): PostDetailTabs {
    val replies =
        PostDetailTab(
            id = "replies",
            title = UiStrings.Replies.asText(),
            includesPostContext = true,
            loader = { repliesLoader() },
        )
    val quotes =
        accountKey?.takeIf { includeQuotes }?.let { viewer ->
            PostDetailTab(id = "quotes", title = UiStrings.Quotes.asText()) {
                MastodonDetailListLoader(
                    fetch = { cursor, pageSize -> service.quotes(statusKey.id, cursor, pageSize.coerceAtMost(40)) },
                    render = { it.render(host = host, accountKey = viewer) },
                )
            }
        }

    fun Account.userRow(): UiTimelineV2.User {
        val profile = render(accountKey = accountKey, host = host)
        return UiTimelineV2
            .User(
                value = profile,
                createdAt = Instant.DISTANT_PAST.toUi(),
                statusKey = profile.key,
                accountType = accountKey.toAccountType(host),
            ).bindMediaProxy()
    }

    return PostDetailTabs(
        tabs =
            listOfNotNull(
                replies,
                quotes,
                PostDetailTab(id = "reposted-by", title = UiStrings.RepostedBy.asText()) {
                    MastodonDetailListLoader(
                        fetch = { cursor, pageSize -> service.rebloggedBy(statusKey.id, cursor, pageSize.coerceAtMost(80)) },
                        render = { it.userRow() },
                    )
                },
                PostDetailTab(id = "liked-by", title = UiStrings.LikedBy.asText()) {
                    MastodonDetailListLoader(
                        fetch = { cursor, pageSize -> service.favouritedBy(statusKey.id, cursor, pageSize.coerceAtMost(80)) },
                        render = { it.userRow() },
                    )
                },
            ),
        replyTabId = replies.id,
    )
}

private class MastodonDetailListLoader<T>(
    private val fetch: suspend (cursor: String?, pageSize: Int) -> Response<List<T>>,
    private val render: (T) -> UiTimelineV2,
) : RemoteLoader<UiTimelineV2> {
    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        if (request is PagingRequest.Prepend) return PagingResult(endOfPaginationReached = true)
        val cursor = (request as? PagingRequest.Append)?.nextKey
        val response = fetch(cursor, pageSize)
        val next = response.raw().nextCursor()?.takeUnless { it == cursor }
        return PagingResult(
            data = checkNotNull(response.body()) { "Mastodon detail response has no list" }.map(render),
            nextKey = next,
            endOfPaginationReached = next == null,
        )
    }
}

private fun HttpResponse.nextCursor(): String? {
    val requestUrl = call.request.url
    return headers
        .getAll(HttpHeaders.Link)
        .orEmpty()
        .asSequence()
        .flatMap { parseHeaderValue(it) }
        .filter { link -> link.params.any { it.name.equals("rel", ignoreCase = true) && it.value == "next" } }
        .firstNotNullOfOrNull { link ->
            val target =
                runCatching { URLBuilder(requestUrl).takeFrom(link.value.removeSurrounding("<", ">")).build() }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
            if (target.protocol == requestUrl.protocol && target.host.equals(requestUrl.host, ignoreCase = true) &&
                target.port == requestUrl.port
            ) {
                target.parameters["max_id"]?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
}
