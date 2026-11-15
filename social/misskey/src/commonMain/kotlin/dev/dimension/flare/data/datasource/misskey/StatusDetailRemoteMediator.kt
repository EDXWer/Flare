package dev.dimension.flare.data.datasource.misskey

import androidx.paging.ExperimentalPagingApi
import dev.dimension.flare.data.datasource.microblog.paging.ContextUpdate
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.PostContextLoader
import dev.dimension.flare.data.datasource.microblog.paging.toContextUpdate
import dev.dimension.flare.data.network.misskey.MisskeyService
import dev.dimension.flare.data.network.misskey.api.model.IPinRequest
import dev.dimension.flare.data.network.misskey.api.model.Note
import dev.dimension.flare.data.network.misskey.api.model.NotesChildrenRequest
import dev.dimension.flare.data.network.misskey.api.model.NotesConversationRequest
import dev.dimension.flare.data.network.misskey.api.model.NotesRepliesRequest
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.render
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

@OptIn(ExperimentalPagingApi::class)
internal class StatusDetailRemoteMediator(
    override val statusKey: MicroBlogKey,
    override val accountKey: MicroBlogKey,
    private val service: MisskeyService,
    private val statusOnly: Boolean,
    private val repliesOnly: Boolean = false,
    initialNote: Note? = null,
) : PostContextLoader {
    private data class ParentFallback(
        val revision: Long = 0,
        val parent: Note? = null,
        val initialNote: Note? = null,
    )

    private val parentFallback = MutableStateFlow(ParentFallback(initialNote = initialNote))

    override val pagingKey: String =
        buildString {
            append("status_detail_")
            if (statusOnly) {
                append("status_only_")
            }
            if (repliesOnly) {
                append("replies_")
            }
            append(statusKey.toString())
            append("_")
            append(accountKey.toString())
        }

    override fun contextUpdate(
        request: PagingRequest,
        result: PagingResult<UiTimelineV2>,
        initial: Boolean,
    ): ContextUpdate =
        if (request == PagingRequest.Refresh && !statusOnly) {
            ContextUpdate(posts = result.data)
        } else {
            result.toContextUpdate(statusKey, request, initial)
        }

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        if (statusOnly && request != PagingRequest.Refresh) {
            return PagingResult(endOfPaginationReached = true)
        }
        var previousKey: String? = null
        val (result, nextKey) =
            when (request) {
                is PagingRequest.Append -> {
                    loadReplyPage(
                        pageSize = pageSize,
                        untilId = request.nextKey.takeIf { it.isNotEmpty() },
                    )
                }

                is PagingRequest.Prepend -> {
                    val fallback = parentFallback.value.parent?.takeIf { it.id == request.previousKey }
                    val ancestors =
                        service
                            .notesConversation(
                                NotesConversationRequest(
                                    noteId = statusKey.id,
                                    limit = MAX_CONTEXT_NOTES,
                                ),
                            ).asReversed()
                            .ifEmpty { listOfNotNull(fallback) }
                    ancestors.distinctBy { it.id } to null
                }

                PagingRequest.Refresh -> {
                    val previous = parentFallback.getAndUpdate { ParentFallback(revision = it.revision + 1) }
                    val revision = previous.revision + 1
                    val current =
                        previous.initialNote ?: service.notesShow(IPinRequest(noteId = statusKey.id))
                    parentFallback.update { if (it.revision == revision) it.copy(parent = current.reply) else it }
                    previousKey = if (statusOnly) null else current.reply?.id ?: current.replyId.orEmpty()
                    listOf(current) to if (statusOnly) null else ""
                }
            }

        return PagingResult(
            endOfPaginationReached = statusOnly || result.isEmpty(),
            data =
                result.render(accountKey),
            nextKey = nextKey,
            previousKey = previousKey,
        )
    }

    private suspend fun loadReplyPage(
        pageSize: Int,
        untilId: String?,
    ): Pair<List<Note>, String?> {
        val limit = pageSize.coerceIn(1, MAX_CONTEXT_NOTES)
        val directChildren = fetchChildren(statusKey.id, limit, untilId)
        val result = mutableListOf<Note>()
        val visited = mutableSetOf(statusKey.id)
        var nestedRequests = 0

        suspend fun appendChildren(
            parentId: String,
            children: List<Note>,
        ) {
            for (child in children) {
                if (result.size >= MAX_CONTEXT_NOTES) return
                if (!visited.add(child.id)) continue
                result += child
                if (
                    result.size >= MAX_CONTEXT_NOTES ||
                    child.replyId != parentId ||
                    child.repliesCount <= 0 ||
                    nestedRequests >= MAX_NESTED_CONTEXT_REQUESTS
                ) {
                    continue
                }
                nestedRequests++
                appendChildren(
                    parentId = child.id,
                    children = fetchChildren(child.id, minOf(limit, MAX_CONTEXT_NOTES - result.size)),
                )
            }
        }

        // ponytail: Bound recursive API work; raise the budgets only if real threads regularly exceed them.
        appendChildren(statusKey.id, directChildren)
        return result to directChildren.lastOrNull()?.id
    }

    private suspend fun fetchChildren(
        noteId: String,
        limit: Int,
        untilId: String? = null,
    ): List<Note> =
        if (repliesOnly) {
            service.notesReplies(NotesRepliesRequest(noteId = noteId, limit = limit, untilId = untilId))
        } else {
            service.notesChildren(NotesChildrenRequest(noteId = noteId, limit = limit, untilId = untilId))
        }

    private companion object {
        const val MAX_CONTEXT_NOTES = 100
        const val MAX_NESTED_CONTEXT_REQUESTS = 20
    }
}
