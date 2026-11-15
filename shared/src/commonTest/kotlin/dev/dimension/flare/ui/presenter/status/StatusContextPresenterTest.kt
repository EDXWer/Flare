package dev.dimension.flare.ui.presenter.status

import androidx.paging.LoadState
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class StatusContextPresenterTest {
    private val accountKey = MicroBlogKey("me", "mastodon.example")
    private val accountType = AccountType.Specific(accountKey)
    private val detailKey = MicroBlogKey("detail", "mastodon.example")

    @Test
    fun contextualTabRendersOnlyRowsAfterTheFocalPost() {
        val parent = createPost("parent", "2024-01-01T00:00:00Z")
        val focal = createPost(detailKey.id, "2024-01-01T00:01:00Z")
        val reply = createPost("reply", "2024-01-01T00:02:00Z")
        val paging = PagingState.Success.ImmutableSuccess<UiTimelineV2>(persistentListOf(parent, focal, reply))

        val sections = paging.splitPostDetail(detailKey) {}
        assertEquals(listOf(parent), sections.parents)
        assertEquals(focal, sections.post)
        val children = assertIs<PagingState.Success<UiTimelineV2>>(sections.children)
        assertEquals(1, children.itemCount)
        assertEquals(reply, children[0])
        assertEquals(reply.statusKey, children.itemKey { it.statusKey }(0))
    }

    @Test
    fun contextualAppendErrorRemainsBelowTheFocalPost() {
        val focal = createPost(detailKey.id, "2024-01-01T00:01:00Z")
        val failure = IllegalStateException("Failed to load replies")
        val paging =
            PagingState.Success.ImmutableSuccess<UiTimelineV2>(
                data = persistentListOf(focal),
                appendState = LoadState.Error(failure),
            )

        val sections = paging.splitPostDetail(detailKey) {}
        assertEquals(focal, sections.post)
        assertEquals(failure, assertIs<PagingState.Error<UiTimelineV2>>(sections.children).error)
    }

    @Test
    fun contextualPageOfAncestorsRequestsMoreRowsToFindThePost() {
        val parent = createPost("parent", "2024-01-01T00:00:00Z")
        val paging =
            PagingState.Success.ImmutableSuccess<UiTimelineV2>(
                data = persistentListOf(parent),
                sourceAppendState = LoadState.NotLoading(endOfPaginationReached = false),
            )

        val sections = paging.splitPostDetail(detailKey) {}
        assertIs<PagingState.Loading<UiTimelineV2>>(sections.children)
        assertEquals(0, sections.appendFrom)
    }

    @Test
    fun contextualPageEndingAtTheFocalPostRequestsCachedReplies() {
        val focal = createPost(detailKey.id, "2024-01-01T00:01:00Z")
        val paging =
            PagingState.Success.ImmutableSuccess<UiTimelineV2>(
                data = persistentListOf(focal),
                sourceAppendState = LoadState.NotLoading(endOfPaginationReached = false),
            )

        val sections = paging.splitPostDetail(detailKey) {}
        assertEquals(focal, sections.post)
        assertIs<PagingState.Loading<UiTimelineV2>>(sections.children)
        assertEquals(0, sections.appendFrom)
    }

    @Test
    fun completedContextWithoutTheFocalPostExposesRetry() {
        val parent = createPost("parent", "2024-01-01T00:00:00Z")
        val paging = PagingState.Success.ImmutableSuccess<UiTimelineV2>(persistentListOf(parent))
        var refreshed = false

        val sections = paging.splitPostDetail(detailKey) { refreshed = true }

        assertIs<PagingState.Error<UiTimelineV2>>(sections.children).onRetry()
        assertTrue(refreshed)
    }

    @Test
    fun detailHeaderRendersAnInlineParentOnceAboveTheFocalPost() {
        val parent =
            createPost(
                id = "parent",
                createdAt = "2024-01-01T00:00:00Z",
            )
        val detail =
            createItem(
                post =
                    createPost(
                        id = detailKey.id,
                        createdAt = "2024-01-01T00:01:00Z",
                    ),
                parents = listOf(parent),
            )

        val header = detailHeader(listOf(parent), detail)

        assertEquals(listOf(parent.statusKey, detail.statusKey), header.map { it.statusKey })
        assertTrue(assertIs<UiTimelineV2.TimelinePostItem>(header.last()).presentation.inlineParents.isEmpty())
    }

    @Test
    fun contextualChildrenKeepTheirInlineParents() {
        val olderParent =
            createPost(
                id = "older-parent",
                createdAt = "2024-01-01T00:00:00Z",
            )
        val detail =
            createPost(
                id = detailKey.id,
                createdAt = "2024-01-01T00:01:00Z",
            )
        val newerParent =
            createPost(
                id = "newer-parent",
                createdAt = "2024-01-01T00:02:00Z",
            )
        val descendant =
            createItem(
                post =
                    createPost(
                        id = "descendant",
                        createdAt = "2024-01-01T00:03:00Z",
                    ),
                parents = listOf(olderParent, detail, newerParent),
            )

        val paging = PagingState.Success.ImmutableSuccess<UiTimelineV2>(persistentListOf(detail, descendant))
        val children = assertIs<PagingState.Success<UiTimelineV2>>(paging.splitPostDetail(detailKey) {}.children)

        val post = assertIs<UiTimelineV2.TimelinePostItem>(children[0])
        assertEquals(
            listOf(olderParent.statusKey, detail.statusKey, newerParent.statusKey),
            post.presentation.inlineParents.map { it.statusKey },
        )
    }

    private fun createPost(
        id: String,
        createdAt: String,
    ): UiTimelineV2.Post =
        UiTimelineV2.Post(
            platformId = "Mastodon",
            images = persistentListOf(),
            sensitive = false,
            contentWarning = null,
            user = null,
            content = UiTranslatableText(id.toUiPlainText()),
            actions = persistentListOf(),
            poll = null,
            statusKey = MicroBlogKey(id, "mastodon.example"),
            card = null,
            createdAt = Instant.parse(createdAt).toUi(),
            emojiReactions = persistentListOf(),
            sourceChannel = null,
            visibility = null,
            replyToHandle = null,
            references = persistentListOf(),
            clickEvent = ClickEvent.Noop,
            accountType = accountType,
        )

    private fun createItem(
        post: UiTimelineV2.Post,
        parents: List<UiTimelineV2.Post>,
    ): UiTimelineV2.TimelinePostItem =
        UiTimelineV2.TimelinePostItem(
            post = post,
            presentation =
                UiTimelineV2.PostPresentation(
                    inlineParents = parents.map { UiTimelineV2.TimelinePostItem(it) }.toPersistentList(),
                ),
        )
}
