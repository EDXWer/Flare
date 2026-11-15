package dev.dimension.flare.data.datasource.xqt

import dev.dimension.flare.common.encodeJson
import dev.dimension.flare.data.database.cache.mapper.cursor
import dev.dimension.flare.data.database.cache.mapper.isBottomEnd
import dev.dimension.flare.data.database.cache.mapper.nextPageCursor
import dev.dimension.flare.data.database.cache.mapper.tweets
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.network.xqt.api.TweetApi
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.render
import dev.dimension.flare.ui.model.mapper.toTweetOrNull
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

internal class SearchStatusPagingSource private constructor(
    private val service: TweetApi,
    private val accountKey: MicroBlogKey,
    private val query: String,
    private val quotedTweetId: String?,
) : RemoteLoader<UiTimelineV2> {
    constructor(service: TweetApi, accountKey: MicroBlogKey, query: String) : this(service, accountKey, query, null)

    constructor(service: TweetApi, accountKey: MicroBlogKey, quotedPost: MicroBlogKey) : this(
        service,
        accountKey,
        "quoted_tweet_id:${quotedPost.id}",
        quotedPost.id,
    )

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        val cursor =
            when (request) {
                PagingRequest.Refresh -> null
                is PagingRequest.Prepend -> return PagingResult(endOfPaginationReached = true)
                is PagingRequest.Append -> request.nextKey
            }
        if (quotedTweetId != null) return loadQuotes(quotedTweetId, pageSize, cursor)

        val instructions =
            search(pageSize, cursor)
                .body()
                ?.data
                ?.searchByRawQuery
                ?.searchTimeline
                ?.timeline
                ?.instructions
                .orEmpty()
        return PagingResult(
            endOfPaginationReached = instructions.isBottomEnd(),
            data = instructions.tweets().mapNotNull { it.render(accountKey) },
            nextKey = instructions.cursor(),
        )
    }

    private suspend fun loadQuotes(
        quotedTweetId: String,
        pageSize: Int,
        initialCursor: String?,
    ): PagingResult<UiTimelineV2> {
        require(quotedTweetId.isNotEmpty() && quotedTweetId.all { it in '0'..'9' }) { "Invalid X post ID" }
        var cursor = initialCursor
        val visitedCursors = mutableSetOf<String?>()
        while (true) {
            check(visitedCursors.add(cursor)) { "X quote search repeated a pagination cursor" }
            val response = search(pageSize, cursor)
            check(response.isSuccessful) { "X quote search failed with HTTP ${response.code}" }
            val body = checkNotNull(response.body()) { "X quote search returned no response" }
            check(body.errors.isNullOrEmpty()) { "X quote search failed: ${body.errors?.firstOrNull()?.message}" }

            val instructions = body.data.searchByRawQuery.searchTimeline.timeline.instructions
            val quotes =
                instructions
                    .tweets()
                    .flatMap { it.parents + it.copy(parents = emptyList()) }
                    .mapNotNull { item ->
                        if (item.tweets.promotedMetadata != null) return@mapNotNull null
                        val tweet =
                            item.tweets.tweetResults.result
                                ?.toTweetOrNull() ?: return@mapNotNull null
                        if (tweet.legacy?.retweetedStatusIdStr != null ||
                            tweet.legacy?.retweetedStatusResult != null
                        ) {
                            return@mapNotNull null
                        }
                        val quoteId =
                            tweet.legacy?.quotedStatusIdStr ?: tweet.quotedStatusResult
                                ?.result
                                ?.toTweetOrNull()
                                ?.restId
                        if (quoteId == quotedTweetId) item.render(accountKey) else null
                    }
            val nextCursor = instructions.nextPageCursor(cursor)
            if (quotes.isNotEmpty() || nextCursor == null) return PagingResult(data = quotes, nextKey = nextCursor)
            // A PagingSource with zero items has no viewport access to trigger the next request.
            cursor = nextCursor
        }
    }

    private suspend fun search(
        pageSize: Int,
        cursor: String?,
    ) = service.getSearchTimeline(
        variables =
            SearchRequest(
                rawQuery = query,
                count = pageSize.toLong(),
                cursor = cursor,
                querySource = if (quotedTweetId == null) "typed_query" else "tdqt",
            ).encodeJson(),
        referer = "https://${accountKey.host}/search?q=${query.encodeURLQueryComponent()}",
    )
}

@Serializable
internal data class SearchRequest(
    val rawQuery: String? = null,
    val count: Long? = null,
    val cursor: String? = null,
    @Required
    val querySource: String = "typed_query",
    @Required
    val product: String = "Top",
)
