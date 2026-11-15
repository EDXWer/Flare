package dev.dimension.flare.data.datasource.misskey

import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.network.misskey.api.NotesApi
import dev.dimension.flare.data.network.misskey.api.model.NotesChildrenRequest
import dev.dimension.flare.data.network.misskey.api.model.NotesReactionsRequest
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.isQuote
import dev.dimension.flare.ui.model.mapper.render
import dev.dimension.flare.ui.render.toUi
import kotlin.time.Instant

private const val MAX_PAGE_SIZE = 100

internal class RenotesLoader(
    private val statusKey: MicroBlogKey,
    private val accountKey: MicroBlogKey,
    private val service: NotesApi,
    private val quotes: Boolean,
) : RemoteLoader<UiTimelineV2> {
    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        var cursor =
            when (request) {
                PagingRequest.Refresh -> null
                is PagingRequest.Append -> request.nextKey
                is PagingRequest.Prepend -> return PagingResult()
            }
        val limit = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val seenCursors = mutableSetOf(cursor)
        while (true) {
            val page = service.notesRenotes(NotesChildrenRequest(noteId = statusKey.id, limit = limit, untilId = cursor))
            // The last renote supplies the cursor even if it belongs to the other tab.
            val next = page.lastOrNull()?.id?.takeUnless(seenCursors::contains)
            val matching = page.filter { it.id !in seenCursors && it.isHidden != true && it.isQuote == quotes }
            if (matching.isNotEmpty() || next == null) return PagingResult(data = matching.render(accountKey), nextKey = next)
            seenCursors += next
            cursor = next
        }
    }
}

internal class ReactionsLoader(
    private val statusKey: MicroBlogKey,
    private val accountKey: MicroBlogKey,
    private val service: NotesApi,
    private val type: String?,
) : RemoteLoader<UiTimelineV2> {
    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        val cursor =
            when (request) {
                PagingRequest.Refresh -> null
                is PagingRequest.Append -> request.nextKey
                is PagingRequest.Prepend -> return PagingResult()
            }
        val page =
            service.notesReactions(
                NotesReactionsRequest(
                    noteId = statusKey.id,
                    type = type,
                    limit = pageSize.coerceIn(1, MAX_PAGE_SIZE),
                    untilId = cursor,
                ),
            )
        return PagingResult(
            data =
                page.filter { it.id != cursor }.map { reaction ->
                    UiTimelineV2.User(
                        value = reaction.user.render(accountKey),
                        createdAt = Instant.parse(reaction.createdAt).toUi(),
                        statusKey = MicroBlogKey(reaction.id, accountKey.host),
                        accountType = AccountType.Specific(accountKey),
                    )
                },
            nextKey = page.lastOrNull()?.id?.takeUnless { it == cursor },
        )
    }
}
