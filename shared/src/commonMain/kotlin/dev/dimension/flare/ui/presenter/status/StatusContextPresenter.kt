package dev.dimension.flare.ui.presenter.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.paging.LoadState
import dev.dimension.flare.common.CacheState
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.isEndOfList
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.connect
import dev.dimension.flare.data.database.cache.model.DbPagingTimeline
import dev.dimension.flare.data.database.cache.model.DbStatus
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.PostDetailTab
import dev.dimension.flare.data.datasource.microblog.PostDetailTabs
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.notSupported
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.accountServiceFlow
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.DbAccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asTimelinePostItem
import dev.dimension.flare.ui.model.flattenUiState
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.model.toUi
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.ui.presenter.home.TimelinePresenter
import dev.dimension.flare.ui.presenter.home.TimelineState
import dev.dimension.flare.web.shared.WebPresenter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@WebPresenter("statusContext")
public class StatusContextPresenter(
    private val accountType: AccountType,
    private val statusKey: MicroBlogKey,
) : PresenterBase<StatusContextPresenter.State>() {
    @Immutable
    public interface State : TimelineState {
        public val current: UiState<UiTimelineV2>
        public val header: ImmutableList<UiTimelineV2>
        public val tabs: ImmutableList<Choice>
        public val selectedTabId: String?
        public val filters: ImmutableList<Choice>
        public val selectedFilterId: String?
        public val defaultFilterId: String?

        public fun selectTab(id: String)

        public fun selectFilter(id: String)

        public fun retryChildren() {
            when (val paging = listState) {
                is PagingState.Error -> paging.onRetry()
                is PagingState.Success -> paging.retry()
                is PagingState.Empty -> paging.refresh()
                is PagingState.Loading -> Unit
            }
        }
    }

    @Immutable
    public data class Choice(
        val id: String,
        val title: UiText,
    )

    private val database: CacheDatabase by koinInject()
    private val accountRepository: AccountRepository by koinInject()
    private val sourceFlow by lazy { accountServiceFlow(accountType, accountRepository) }

    @Composable
    override fun body(): State {
        val source by sourceFlow.collectAsState(null)
        val state = source?.let { key(it) { detail(it) } } ?: DetailState()
        if (state.current is UiState.Success) {
            remember { LogStatusHistoryPresenter(accountType = accountType, statusKey = statusKey) }.body()
        }
        return state
    }

    @Composable
    private fun detail(source: MicroblogDataSource): State {
        val cachedPostFlow =
            remember(source) {
                source.capabilities.post
                    ?.postHandler
                    ?.post(statusKey)
                    ?.data
            }
        val cachedPost = (cachedPostFlow?.collectAsState(CacheState.Empty())?.value as? CacheState.Success)?.data
        var attempt by remember { mutableIntStateOf(0) }
        val catalog by produceState<UiState<Catalog>>(UiState.Loading(), attempt) {
            value =
                try {
                    UiState.Success(Catalog(source.postDetailTabs(statusKey)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    UiState.Error(e)
                }
        }
        val scope = rememberCoroutineScope()
        return when (val result = catalog) {
            is UiState.Loading -> {
                DetailState(
                    current = cachedPost?.let { UiState.Success(it) } ?: UiState.Loading(),
                    header = cachedPost?.let { detailHeader(emptyList(), it) } ?: persistentListOf(),
                )
            }

            is UiState.Error -> {
                val post = cachedPost ?: currentPost(source).takeSuccess()
                DetailState(
                    current = post?.let { UiState.Success(it) } ?: UiState.Loading(),
                    header = post?.let { detailHeader(emptyList(), it) } ?: persistentListOf(),
                    listState = PagingState.Error(result.throwable) { attempt++ },
                    onRefresh = { attempt++ },
                    refreshScope = { scope.launch { it() } },
                )
            }

            is UiState.Success -> {
                val tabs = result.data.tabs
                if (tabs == null) {
                    var revision by remember { mutableIntStateOf(0) }
                    val current = currentPost(source, revision)
                    val timeline = remember { timeline(source.capabilities.post?.context(statusKey) ?: notSupported(), seed = true) }.body()
                    DetailState(
                        current = current,
                        listState = timeline.listState,
                        onRefresh = {
                            revision++
                            timeline.refresh()
                        },
                        refreshScope = { scope.launch { it() } },
                    )
                } else {
                    key(tabs) { sectioned(source, tabs, cachedPost) }
                }
            }
        }
    }

    @Composable
    private fun currentPost(
        source: MicroblogDataSource,
        revision: Int = 0,
    ): UiState<UiTimelineV2> =
        remember(revision) {
            source.postDetailPost(statusKey) ?: source.capabilities.post
                ?.postHandler
                ?.post(statusKey)
                ?.toUi()
                ?: flowOf(UiState.Error(IllegalStateException("Current service does not support post details")))
        }.flattenUiState().value

    @Composable
    private fun sectioned(
        source: MicroblogDataSource,
        catalog: PostDetailTabs,
        cachedPost: UiTimelineV2?,
    ): State {
        val tabs = catalog.tabs
        val initial = tabs.firstOrNull()
        val context = tabs.firstOrNull { it.includesPostContext }?.defaultPage()
        var selected by remember { mutableStateOf(initial) }
        val selectedFilters = remember { mutableStateMapOf<String, String>() }
        val visited = remember { mutableStateListOf(*listOfNotNull(initial?.defaultPage(), context).distinct().toTypedArray()) }
        val active = selected?.let { Page(it, selectedFilters[it.id] ?: it.filter?.defaultOptionId) }
        val pages =
            visited.associateWith { page ->
                key(page) {
                    pageState(page)
                }
            }
        val contextPage = pages[context]
        val activePage = pages[active]
        val currentContext = contextPage?.sections
        var headerRevision by remember { mutableIntStateOf(0) }
        var replyRevision by remember { mutableIntStateOf(0) }
        val refreshed = remember { mutableMapOf<Page, Int>() }
        LaunchedEffect(source) {
            accountRepository.publishedReplies.collect { (account, post) ->
                if (accountType != AccountType.Specific(account) || post != statusKey) return@collect
                replyRevision++
                if (context == null) {
                    headerRevision++
                } else {
                    launch {
                        source.capabilities.post
                            ?.postHandler
                            ?.post(statusKey)
                            ?.refreshState
                            ?.first { it !is LoadState.Loading }
                    }
                }
            }
        }
        LaunchedEffect(active, replyRevision) {
            if (active != null && active.tab.id == catalog.replyTabId && replyRevision > (refreshed[active] ?: 0)) {
                val revision = replyRevision
                activePage?.timeline?.refresh()
                refreshed[active] = revision
            }
        }
        val contextPost = currentContext?.post
        val contextError = currentContext?.children as? PagingState.Error
        val current =
            when {
                contextPage == null -> {
                    val post = currentPost(source, headerRevision)
                    cachedPost?.takeIf { post !is UiState.Success }?.let { UiState.Success(it) } ?: post
                }

                contextPost != null -> {
                    UiState.Success(contextPost)
                }

                cachedPost != null -> {
                    UiState.Success(cachedPost)
                }

                contextError != null -> {
                    currentPost(source, headerRevision).takeSuccess()?.let { UiState.Success(it) } ?: UiState.Error(contextError.error)
                }

                else -> {
                    UiState.Loading()
                }
            }
        val header = current.takeSuccess()?.let { detailHeader(currentContext?.parents.orEmpty(), it) } ?: persistentListOf()
        val scope = rememberCoroutineScope()
        val refresh =
            suspend {
                if (contextPage != null && contextPage !== activePage) contextPage.timeline.refreshAsync()
                if (contextPage == null || (contextPost == null && contextError != null)) headerRevision++
                activePage?.timeline?.refresh()
                Unit
            }
        val children = activePage?.sections?.children ?: activePage?.timeline?.listState
        return DetailState(
            current = current,
            header = header,
            listState =
                when {
                    current is UiState.Error && header.isEmpty() -> PagingState.Error(current.throwable) { scope.launch { refresh() } }
                    header.isEmpty() -> PagingState.Loading()
                    children != null -> children
                    else -> PagingState.Empty { scope.launch { refresh() } }
                },
            tabs = remember(tabs) { tabs.map { Choice(it.id, it.title) }.toImmutableList() },
            selectedTabId = active?.tab?.id,
            filters =
                selected
                    ?.filter
                    ?.options
                    .orEmpty()
                    .map { Choice(it.id, it.title) }
                    .toImmutableList(),
            selectedFilterId = active?.filterId,
            defaultFilterId = selected?.filter?.defaultOptionId,
            onTab = { id ->
                tabs.firstOrNull { it.id == id }?.let { tab ->
                    val page = Page(tab, selectedFilters[id] ?: tab.filter?.defaultOptionId)
                    if (page !in visited) visited += page
                    selected = tab
                }
            },
            onFilter = { id ->
                val tab = selected
                if (tab != null &&
                    tab.filter
                        ?.options
                        .orEmpty()
                        .any { it.id == id }
                ) {
                    val page = Page(tab, id)
                    if (page !in visited) visited += page
                    selectedFilters[tab.id] = id
                }
            },
            onRefresh = refresh,
            refreshScope = { scope.launch { it() } },
        )
    }

    @Composable
    private fun pageState(page: Page): PageState {
        val timeline =
            remember(
                page,
            ) { timeline(page.tab.loader(page.filterId), seed = false, context = page.tab.includesPostContext) }.body()
        val sections =
            if (page.tab.includesPostContext) timeline.listState.splitPostDetail(statusKey, timeline::refreshAsync) else null
        if (sections?.appendFrom != null) {
            LaunchedEffect(timeline.listState) {
                (timeline.listState as? PagingState.Success)?.get(sections.appendFrom)
            }
        }
        return PageState(timeline, sections)
    }

    private fun timeline(
        remote: RemoteLoader<UiTimelineV2>,
        seed: Boolean,
        context: Boolean = false,
    ): TimelinePresenter =
        object : TimelinePresenter() {
            override fun allowLongTextTranslationDisplay(loader: RemoteLoader<UiTimelineV2>): Boolean = true

            override fun keepContextAnchor(item: UiTimelineV2): Boolean = context && item.statusKey == statusKey

            override val loader =
                flow {
                    if ((seed || context) && remote is CacheableRemoteLoader<UiTimelineV2>) seedPost(remote.pagingKey)
                    emit(remote)
                }
        }

    private suspend fun seedPost(pagingKey: String) {
        val account = accountType as DbAccountType
        if (database.pagingTimelineDao().existsPaging(account, pagingKey)) return
        if (database.statusDao().get(statusKey, account).firstOrNull() == null) return
        database.connect {
            database.pagingTimelineDao().insertAll(
                listOf(DbPagingTimeline(statusId = DbStatus.createId(account, statusKey), pagingKey = pagingKey, sortId = 0)),
            )
        }
    }

    private data class Catalog(
        val tabs: PostDetailTabs?,
    )

    private data class Page(
        val tab: PostDetailTab,
        val filterId: String?,
    )

    private fun PostDetailTab.defaultPage() = Page(this, filter?.defaultOptionId)

    private data class PageState(
        val timeline: TimelineState,
        val sections: PostDetailSections?,
    )

    private class DetailState(
        override val current: UiState<UiTimelineV2> = UiState.Loading(),
        override val header: ImmutableList<UiTimelineV2> = persistentListOf(),
        override val listState: PagingState<UiTimelineV2> = PagingState.Loading(),
        override val tabs: ImmutableList<Choice> = persistentListOf(),
        override val selectedTabId: String? = null,
        override val filters: ImmutableList<Choice> = persistentListOf(),
        override val selectedFilterId: String? = null,
        override val defaultFilterId: String? = null,
        private val onTab: (String) -> Unit = {},
        private val onFilter: (String) -> Unit = {},
        private val onRefresh: suspend () -> Unit = {},
        private val refreshScope: (suspend () -> Unit) -> Unit = {},
    ) : State {
        override fun selectTab(id: String) = onTab(id)

        override fun selectFilter(id: String) = onFilter(id)

        override fun refreshAsync() = refreshScope(onRefresh)

        override suspend fun refresh() = onRefresh()
    }
}

internal data class PostDetailSections(
    val parents: List<UiTimelineV2> = emptyList(),
    val post: UiTimelineV2? = null,
    val children: PagingState<UiTimelineV2>,
    val appendFrom: Int? = null,
)

internal fun PagingState<UiTimelineV2>.splitPostDetail(
    statusKey: MicroBlogKey,
    onRefresh: () -> Unit,
): PostDetailSections {
    fun missingPost() = PagingState.Error<UiTimelineV2>(IllegalStateException("Post context does not contain $statusKey"), onRefresh)
    if (this is PagingState.Empty) return PostDetailSections(children = missingPost())
    if (this !is PagingState.Success) return PostDetailSections(children = this)

    val anchor = (0 until itemCount).firstOrNull { peek(it)?.statusKey == statusKey }
    val sourceAppend = sourceAppendState
    val append = appendState
    val error = (sourceAppend as? LoadState.Error) ?: (append as? LoadState.Error)
    // The local cache can have more rows even after the remote thread reaches its end.
    val hasMore =
        (sourceAppend is LoadState.NotLoading && !sourceAppend.isEndOfList) ||
            (sourceAppend.isEndOfList && append is LoadState.NotLoading && !append.isEndOfList)
    val children =
        when {
            anchor != null && anchor + 1 < itemCount -> PagingState.Success.After(this, anchor + 1)
            hasMore || sourceAppend is LoadState.Loading -> PagingState.Loading()
            error != null -> PagingState.Error(error.error, ::retry)
            append.isEndOfList && !isRefreshing -> if (anchor == null) missingPost() else PagingState.Empty(onRefresh)
            else -> PagingState.Loading()
        }
    return PostDetailSections(
        parents = anchor?.let { (0 until it).mapNotNull(::peek) }.orEmpty(),
        post = anchor?.let(::peek),
        children = children,
        appendFrom = (itemCount - 1).takeIf { it >= 0 && children is PagingState.Loading && hasMore },
    )
}

internal fun detailHeader(
    parents: List<UiTimelineV2>,
    post: UiTimelineV2,
): ImmutableList<UiTimelineV2> {
    val item = post.asTimelinePostItem() ?: return (parents + post).toImmutableList()
    val inlineParents = item.presentation.inlineParents
    if (inlineParents.isEmpty()) return (parents + post).toImmutableList()
    return (parents + inlineParents + item.copy(presentation = item.presentation.copy(inlineParents = persistentListOf())))
        .distinctBy { it.statusKey }
        .toImmutableList()
}
