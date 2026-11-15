package dev.dimension.flare.data.datasource.xqt

import androidx.paging.ExperimentalPagingApi
import dev.dimension.flare.common.encodeJson
import dev.dimension.flare.data.database.cache.mapper.cursor
import dev.dimension.flare.data.database.cache.mapper.isBottomEnd
import dev.dimension.flare.data.database.cache.mapper.nextPageCursor
import dev.dimension.flare.data.database.cache.mapper.tweets
import dev.dimension.flare.data.datasource.microblog.paging.ContextUpdate
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.PostContextLoader
import dev.dimension.flare.data.datasource.microblog.paging.toContextUpdate
import dev.dimension.flare.data.network.xqt.XQTService
import dev.dimension.flare.data.network.xqt.api.TweetApi
import dev.dimension.flare.data.network.xqt.model.PostCreateTweetRequestFeatures
import dev.dimension.flare.data.network.xqt.model.Tweet
import dev.dimension.flare.data.network.xqt.model.TweetPreviewDisplay
import dev.dimension.flare.data.network.xqt.model.TweetTombstone
import dev.dimension.flare.data.network.xqt.model.TweetUnavailable
import dev.dimension.flare.data.network.xqt.model.TweetWithVisibilityResults
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.render
import dev.dimension.flare.ui.model.mapper.toTweetOrNull
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalPagingApi::class)
internal class StatusDetailRemoteMediator(
    override val statusKey: MicroBlogKey,
    private val service: XQTService,
    override val accountKey: MicroBlogKey,
    private val statusOnly: Boolean,
) : PostContextLoader {
    override val collapseReplyChains: Boolean = false

    override val pagingKey: String = statusDetailPagingKey(statusKey, accountKey, statusOnly)

    private var conversationId: String? = null

    override fun contextUpdate(
        request: PagingRequest,
        result: PagingResult<UiTimelineV2>,
        initial: Boolean,
    ): ContextUpdate =
        if (request == PagingRequest.Refresh && !statusOnly) {
            ContextUpdate(posts = result.data)
        } else {
            result.toContextUpdate(statusKey, if (initial) PagingRequest.Refresh else request, initial)
        }

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> =
        when (request) {
            is PagingRequest.Append -> {
                if (statusOnly) {
                    PagingResult(
                        endOfPaginationReached = true,
                    )
                } else {
                    val cursor = request.nextKey.takeIf { it.isNotEmpty() }
                    val response =
                        service
                            .getTweetDetail(
                                variables =
                                    TweetDetailRequest(
                                        focalTweetID = statusKey.id,
                                        cursor = cursor,
                                    ).encodeJson(),
                            ).body()
                            ?.data
                            ?.threadedConversationWithInjectionsV2
                            ?.instructions
                            .orEmpty()

                    val tweet =
                        service
                            .getTweetResultByRestId(
                                variables =
                                    TweetDetailWithRestIdRequest(
                                        tweetID = statusKey.id,
                                        withCommunity = true,
                                        includePromotedContent = true,
                                        withVoice = true,
                                    ).encodeJson(),
                                features =
                                    PostCreateTweetRequestFeatures(
                                        responsiveWebTwitterArticleTweetConsumptionEnabled = true,
                                    ).encodeJson(),
                            ).body()
                            ?.data
                            ?.tweetResult
                            ?.result

                    conversationId =
                        when (tweet) {
                            is Tweet -> tweet.legacy?.conversationIdStr

                            is TweetPreviewDisplay,
                            is TweetTombstone,
                            -> null

                            is TweetUnavailable -> null

                            is TweetWithVisibilityResults -> tweet.tweet.legacy?.conversationIdStr

                            null -> null
                        }

                    val actualTweet =
                        response
                            .tweets()
                            .filter {
                                when (val result = it.tweets.tweetResults.result) {
                                    is TweetTombstone -> false

                                    is TweetPreviewDisplay,
                                    is TweetUnavailable,
                                    -> false

                                    null -> false

                                    is Tweet -> result.legacy?.conversationIdStr == conversationId

                                    is TweetWithVisibilityResults -> result.tweet.legacy?.conversationIdStr == conversationId
                                }
                            }

                    PagingResult(
                        endOfPaginationReached = response.isBottomEnd() || actualTweet.size == 1 || response.cursor() == null,
                        data =
                            actualTweet.mapNotNull {
                                it.render(accountKey)
                            },
                        nextKey = response.cursor(),
                    )
                }
            }

            is PagingRequest.Prepend -> {
                PagingResult(
                    endOfPaginationReached = true,
                )
            }

            PagingRequest.Refresh -> {
                val response =
                    service
                        .getTweetDetail(
                            variables =
                                TweetDetailRequest(
                                    focalTweetID = statusKey.id,
                                    cursor = null,
                                ).encodeJson(),
                        ).body()
                        ?.data
                        ?.threadedConversationWithInjectionsV2
                        ?.instructions
                        .orEmpty()
                val tweet = response.tweets()
                val item = tweet.firstOrNull { it.id == statusKey.id }

                PagingResult(
                    endOfPaginationReached = statusOnly,
                    data =
                        listOfNotNull(item).mapNotNull {
                            it.render(accountKey)
                        },
                    nextKey = if (statusOnly) null else "",
                )
            }
        }
}

internal class RankedStatusDetailLoader(
    override val statusKey: MicroBlogKey,
    private val service: TweetApi,
    override val accountKey: MicroBlogKey,
    private val rankingMode: ReplyRanking,
) : PostContextLoader {
    override val collapseReplyChains: Boolean = false

    override val pagingKey: String =
        statusDetailPagingKey(statusKey, accountKey) + if (rankingMode == ReplyRanking.Relevance) "" else "_${rankingMode.name}"

    private var conversationId: String? = null

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        val cursor =
            when (request) {
                PagingRequest.Refresh -> null
                is PagingRequest.Append -> request.nextKey
                is PagingRequest.Prepend -> return PagingResult(endOfPaginationReached = true)
            }
        val response =
            service.getTweetDetail(
                variables = TweetDetailRequest(focalTweetID = statusKey.id, cursor = cursor, rankingMode = rankingMode).encodeJson(),
            )
        check(response.isSuccessful) { "X TweetDetail failed with HTTP ${response.code}" }
        val body = checkNotNull(response.body()) { "X TweetDetail returned no response" }
        check(body.errors.isNullOrEmpty()) { "X TweetDetail failed: ${body.errors?.firstOrNull()?.message}" }

        val instructions = body.data.threadedConversationWithInjectionsV2.instructions
        val tweets = instructions.tweets()
        val anchorIndex = tweets.indexOfFirst { it.id == statusKey.id }
        if (request == PagingRequest.Refresh) {
            check(anchorIndex >= 0) { "X TweetDetail did not contain $statusKey" }
            conversationId =
                tweets[anchorIndex]
                    .tweets.tweetResults.result
                    ?.toTweetOrNull()
                    ?.legacy
                    ?.conversationIdStr ?: statusKey.id
        }
        val conversation = checkNotNull(conversationId) { "X TweetDetail requires a first page before loading more replies" }
        val page = if (request == PagingRequest.Refresh) tweets else tweets.drop(anchorIndex + 1)
        return PagingResult(
            data =
                page
                    .filter { item ->
                        item.id == statusKey.id ||
                            item.tweets.tweetResults.result
                                ?.toTweetOrNull()
                                ?.legacy
                                ?.conversationIdStr == conversation
                    }.mapNotNull { it.render(accountKey) },
            nextKey = instructions.nextPageCursor(cursor),
        )
    }
}

private fun statusDetailPagingKey(
    statusKey: MicroBlogKey,
    accountKey: MicroBlogKey,
    statusOnly: Boolean = false,
): String {
    val mode = if (statusOnly) "status_only_" else ""
    return "status_detail_$mode${statusKey}_$accountKey"
}

@Serializable
internal enum class ReplyRanking(
    val title: UiStrings,
) {
    Relevance(UiStrings.Relevance),
    Recency(UiStrings.Recency),
    Likes(UiStrings.Likes),
}

@Serializable
internal data class TweetDetailWithRestIdRequest(
    @SerialName("tweetId")
    val tweetID: String,
    @Required
    val withCommunity: Boolean = false,
    @Required
    val includePromotedContent: Boolean = false,
    @Required
    val withVoice: Boolean = false,
    @Required
    val withBirdwatchNotes: Boolean = true,
)

@Serializable
internal data class TweetDetailRequest(
    @SerialName("focalTweetId")
    val focalTweetID: String,
    val cursor: String? = null,
    val rankingMode: ReplyRanking? = null,
    @Required
    val referrer: String = "tweet",
    @SerialName("with_rux_injections")
    @Required
    val withRuxInjections: Boolean = false,
    @Required
    val includePromotedContent: Boolean = true,
    @Required
    val withCommunity: Boolean = true,
    @Required
    val withQuickPromoteEligibilityTweetFields: Boolean = true,
    @Required
    val withBirdwatchNotes: Boolean = true,
    @Required
    val withVoice: Boolean = true,
    @Required
    val withV2Timeline: Boolean = true,
)
