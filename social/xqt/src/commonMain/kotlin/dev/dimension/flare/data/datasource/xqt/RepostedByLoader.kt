package dev.dimension.flare.data.datasource.xqt

import dev.dimension.flare.common.encodeJson
import dev.dimension.flare.data.database.cache.mapper.nextPageCursor
import dev.dimension.flare.data.database.cache.mapper.users
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.network.xqt.api.UserListApi
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.render
import dev.dimension.flare.ui.render.toUi
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlin.time.Instant

internal class RepostedByLoader(
    private val service: UserListApi,
    private val accountKey: MicroBlogKey,
    private val statusKey: MicroBlogKey,
) : RemoteLoader<UiTimelineV2> {
    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        var cursor =
            when (request) {
                PagingRequest.Refresh -> null
                is PagingRequest.Prepend -> return PagingResult()
                is PagingRequest.Append -> request.nextKey
            }
        val visited = mutableSetOf<String?>()
        while (true) {
            visited += cursor
            val response =
                service.getRetweeters(
                    variables = RetweetersVariables(statusKey.id, pageSize.coerceIn(1, 100), cursor).encodeJson(),
                )
            check(response.isSuccessful) { "Could not load X reposts (HTTP ${response.code})" }
            val body = checkNotNull(response.body()) { "X reposts did not return a user list" }
            check(body.errors.isNullOrEmpty()) { "Could not load X reposts: ${body.errors?.firstOrNull()?.message}" }

            val instructions = body.data.retweetersTimeline.timeline.instructions
            val users =
                instructions.users().map { user ->
                    val profile = user.render(accountKey)
                    UiTimelineV2.User(
                        value = profile,
                        createdAt = Instant.DISTANT_PAST.toUi(),
                        statusKey = profile.key,
                        accountType = AccountType.Specific(accountKey),
                    )
                }
            val next = instructions.nextPageCursor(cursor)?.takeUnless { it in visited }
            if (users.isNotEmpty() || next == null) return PagingResult(data = users, nextKey = next)
            // Unavailable users may leave a page with only a continuation cursor.
            cursor = next
        }
    }
}

@Serializable
private data class RetweetersVariables(
    val tweetId: String,
    val count: Int,
    val cursor: String? = null,
    @Required
    val includePromotedContent: Boolean = false,
)
