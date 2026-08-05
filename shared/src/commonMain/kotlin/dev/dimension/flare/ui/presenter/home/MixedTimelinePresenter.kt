package dev.dimension.flare.ui.presenter.home

import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.datasource.microblog.MixedRemoteMediator
import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.notSupported
import dev.dimension.flare.data.model.tab.TimelineMergePolicy
import dev.dimension.flare.data.model.tab.TimelinePostKind
import dev.dimension.flare.data.model.tab.TimelineResolver
import dev.dimension.flare.data.model.tab.UiGroupTimelineTabItem
import dev.dimension.flare.data.model.tab.UiTimelineTabItem
import dev.dimension.flare.data.model.tab.isSystemHomeMixedTimeline
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.ReferenceType
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

public class MixedTimelinePresenter(
    id: String,
    private val fallbackSubTimelinePresenter: List<TimelinePresenter> = emptyList(),
    private val fallbackMergePolicy: TimelineMergePolicy = TimelineMergePolicy.TimePerPage,
) : TimelinePresenter(tabId = id) {
    private val groupId = id

    public constructor(
        subTimelinePresenter: List<TimelinePresenter>,
        mergePolicy: TimelineMergePolicy = TimelineMergePolicy.TimePerPage,
    ) : this(
        id = "legacy_mixed_timeline",
        fallbackSubTimelinePresenter = subTimelinePresenter,
        fallbackMergePolicy = mergePolicy,
    )

    private val database: CacheDatabase by koinInject()
    private val settingsRepository: SettingsRepository by koinInject()
    private val timelineResolver: TimelineResolver by koinInject()

    private val groupTabFlow: Flow<UiGroupTimelineTabItem?> by lazy {
        settingsRepository
            .homeTimelineTab(groupId)
            .map { it as? UiGroupTimelineTabItem }
    }

    private val mergePolicyFlow: Flow<TimelineMergePolicy> by lazy {
        groupTabFlow
            .map { it?.mergePolicy ?: fallbackMergePolicy }
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val subTimelineLoadersFlow: Flow<List<RemoteLoader<UiTimelineV2>>> by lazy {
        groupTabFlow
            .map { group ->
                group
                    ?.children
                    ?.filter { it.enabled }
            }.distinctUntilChanged().flatMapLatest { tabs ->
                if (tabs == null) {
                    if (fallbackSubTimelinePresenter.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(fallbackSubTimelinePresenter.map { it.loader }) { it.toList() }
                    }
                } else if (tabs.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    // HIER IST DER FIX FÜR DIE MANUELLE GRUPPE:
                    // Wir entwirren den Flow und hüllen den nackten Loader in unseren Türsteher ein
                    val flows = tabs.map { tab ->
                        timelineResolver.resolveLoader(tab).map { rawLoader ->
                            if (rawLoader is CacheableRemoteLoader<UiTimelineV2>) {
                                FilteredRemoteLoader(rawLoader, tab)
                            } else {
                                rawLoader
                            }
                        }
                    }
                    combine(flows) { it.toList() }
                }
            }
    }

    override val loader: Flow<RemoteLoader<UiTimelineV2>>
        get() =
            combine(subTimelineLoadersFlow, mergePolicyFlow) { loaders, mergePolicy ->
                if (loaders.isEmpty()) {
                    notSupported()
                } else {
                    MixedRemoteMediator(
                        database = database,
                        mediators = loaders.filterIsInstance<CacheableRemoteLoader<UiTimelineV2>>(),
                        mergePolicy = mergePolicy,
                    )
                }
            }
}

public class SystemHomeMixedTimelinePresenter(
    id: String,
    isHomeTimeline: Boolean = false,
) : TimelinePresenter(tabId = id, isHomeTimeline = isHomeTimeline) {
    private val groupId = id

    private val database: CacheDatabase by koinInject()
    private val settingsRepository: SettingsRepository by koinInject()
    private val timelineResolver: TimelineResolver by koinInject()

    private val groupTabFlow: Flow<UiGroupTimelineTabItem?> by lazy {
        settingsRepository
            .homeTimelineTab(groupId)
            .map { it as? UiGroupTimelineTabItem }
    }

    private val mergePolicyFlow: Flow<TimelineMergePolicy> by lazy {
        groupTabFlow
            .map { it?.mergePolicy ?: TimelineMergePolicy.TimePerPage }
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val subTimelineLoadersFlow: Flow<List<RemoteLoader<UiTimelineV2>>> by lazy {
        settingsRepository.homeTimelineTabs
            .map { tabs ->
                tabs
                    .filterNot { it.isSystemHomeMixedTimeline }
                    .filter { it.enabled }
            }.distinctUntilChanged()
            .flatMapLatest { tabs ->
                if (tabs.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    // HIER IST DER FIX FÜR DIE SYSTEM-GRUPPE:
                    val flows = tabs.map { tab ->
                        timelineResolver.resolveLoader(tab).map { rawLoader ->
                            if (rawLoader is CacheableRemoteLoader<UiTimelineV2>) {
                                FilteredRemoteLoader(rawLoader, tab)
                            } else {
                                rawLoader
                            }
                        }
                    }
                    combine(flows) { it.toList() }
                }
            }
    }

    override val loader: Flow<RemoteLoader<UiTimelineV2>>
        get() =
            combine(subTimelineLoadersFlow, mergePolicyFlow) { loaders, mergePolicy ->
                if (loaders.isEmpty()) {
                    notSupported()
                } else {
                    MixedRemoteMediator(
                        database = database,
                        mediators = loaders.filterIsInstance<CacheableRemoteLoader<UiTimelineV2>>(),
                        mergePolicy = mergePolicy,
                    )
                }
            }
}

/**
 * Der aktualisierte Türsteher: Angepasst an die brandneue Paging-Architektur!
 * Er liest die excludedKinds (z.B. TimelinePostKind.Reply) deines Tabs aus und blockiert unerwünschte Posts.
 */
private class FilteredRemoteLoader(
    private val delegate: CacheableRemoteLoader<UiTimelineV2>,
    private val tabItem: UiTimelineTabItem
) : CacheableRemoteLoader<UiTimelineV2> {

    override val pagingKey: String = delegate.pagingKey

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest
    ): PagingResult<UiTimelineV2> {
        val result = delegate.load(pageSize, request)

        // Tab-Einstellungen präzise auslesen (Was soll blockiert werden?)
        val excluded = tabItem.filterConfig.excludedKinds
        val hideReplies = excluded.contains(TimelinePostKind.Reply)
        val hideReposts = excluded.contains(TimelinePostKind.Repost)

        // Wenn weder Replies noch Reposts blockiert sind, winken wir die Liste direkt durch
        if (!hideReplies && !hideReposts) {
            return result
        }

        val filteredData = result.data.filter { item ->
            when (item) {
                is UiTimelineV2.TimelinePostItem -> {
                    if (hideReposts && item.presentation.repost != null) return@filter false
                    if (hideReplies && item.presentation.inlineParents.isNotEmpty()) return@filter false
                    true
                }
                is UiTimelineV2.Post -> {
                    if (hideReposts && item.references.any { it.type == ReferenceType.Retweet }) return@filter false
                    if (hideReplies && item.references.any { it.type == ReferenceType.Reply }) return@filter false
                    true
                }
                else -> true
            }
        }

        // Wir packen die sauberen Daten wieder sicher in das von Upstream geforderte PagingResult
        return PagingResult(
            data = filteredData,
            previousKey = result.previousKey,
            nextKey = result.nextKey
        )
    }
}