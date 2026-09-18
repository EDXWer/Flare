package dev.dimension.flare.ui.presenter

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.dimension.flare.common.onSuccess
import dev.dimension.flare.data.model.tab.UiTimelineTabItem
import dev.dimension.flare.data.model.tab.isSystemHomeMixedTimeline
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import moe.tlaster.precompose.molecule.producePresenter

@Immutable
public interface TimelineWithLazyListState : TimelineItemPresenter.State {
    public val showNewToots: Boolean
    public val lazyListState: LazyStaggeredGridState
    public val newPostsCount: Int

    public fun onNewTootsShown()
}

@Composable
public fun rememberTimelineItemPresenterWithLazyListState(
    item: UiTimelineTabItem,
    lazyStaggeredGridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
): TimelineWithLazyListState {

    val baseState by producePresenter<TimelineItemPresenter.State>(key = "timeline_${item.id}") {
        val presenter = remember { TimelineItemPresenter(item) }
        presenter.invoke()
    }

    return rememberTimelineWithLazyListState(
        baseState,
        lazyStaggeredGridState,
        isSystemHomeMixedTimeline = item.isSystemHomeMixedTimeline,
    )
}

// Sichere ID Extraktion (Kugelsicher gegen Nulls)
private fun getPostFingerprint(item: Any?): String {
    return runCatching {
        val timelineItem = item as? UiTimelineV2 ?: return@runCatching "unknown_${item?.hashCode()}"
        when (timelineItem) {
            is UiTimelineV2.Post -> "post_${timelineItem.statusKey}"
            is UiTimelineV2.Feed -> "feed_${timelineItem.statusKey}"
            else -> "${timelineItem.itemKey ?: timelineItem.hashCode()}"
        }
    }.getOrDefault("error_${item?.hashCode()}")
}

// Die neue Datenstruktur für die Brotkrümel-Spur
private class ScrollContext {
    var anchorFingerprints: List<String> = emptyList() // Speichert die Top 10 Posts als Fallback-Netz!
    var anchorOffset: Int = 0
    var isAnchored: Boolean = false
    var knownTopFingerprint: String? = null
    var highestReadIndex: Int = Int.MAX_VALUE
}

@Composable
internal fun rememberTimelineWithLazyListState(
    baseState: TimelineItemPresenter.State,
    lazyListState: LazyStaggeredGridState,
    isSystemHomeMixedTimeline: Boolean = false,
): TimelineWithLazyListState {
    var newPostCount by remember { mutableIntStateOf(0) }

    val tracker = remember { ScrollContext() }
    var isHunting by remember { mutableStateOf(false) }

    val isAtTheTop by remember(lazyListState) {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0 &&
                    lazyListState.firstVisibleItemScrollOffset == 0
        }
    }

    baseState.listState.onSuccess {
        val currentPagingState by rememberUpdatedState(this)
        val currentCount = itemCount
        val currentTopItem = if (currentCount > 0) runCatching { peek(0) }.getOrNull() else null
        val currentTopFp = if (currentTopItem != null) getPostFingerprint(currentTopItem) else null

        // 1. DATA REFRESH DETECTOR (eigene Anker-Logik – unverändert)
        LaunchedEffect(currentCount, currentTopFp) {
            if (currentCount > 0 && currentTopFp != null) {
                val isTopChanged = tracker.knownTopFingerprint != null && currentTopFp != tracker.knownTopFingerprint

                if (isTopChanged && tracker.isAnchored) {
                    isHunting = true
                }

                tracker.knownTopFingerprint = currentTopFp
            }
        }

        // 2. DIE AKTIVE JAGD (eigene Anker-Logik – unverändert)
        LaunchedEffect(isHunting) {
            if (isHunting && tracker.anchorFingerprints.isNotEmpty()) {
                var huntAttempts = 0
                var lastLoadedCount = 0

                while (isHunting && huntAttempts <= 20) {
                    var bestMatchIndex = -1
                    var bestMatchPriority = Int.MAX_VALUE
                    var contiguousLoadedCount = 0

                    for (i in 0 until itemCount) {
                        val item = runCatching { peek(i) }.getOrNull()
                        if (item != null) {
                            contiguousLoadedCount = i + 1
                            val fp = getPostFingerprint(item)

                            val priority = tracker.anchorFingerprints.indexOf(fp)

                            if (priority != -1 && priority < bestMatchPriority) {
                                bestMatchPriority = priority
                                bestMatchIndex = i
                            }
                        } else {
                            break
                        }
                    }

                    if (bestMatchIndex != -1) {
                        val offset = if (bestMatchPriority == 0) tracker.anchorOffset else 0
                        lazyListState.scrollToItem(bestMatchIndex, offset)
                        tracker.highestReadIndex = bestMatchIndex
                        isHunting = false
                        break
                    } else {
                        if (contiguousLoadedCount > 80) {
                            isHunting = false
                            break
                        }

                        if (contiguousLoadedCount > lastLoadedCount) {
                            lastLoadedCount = contiguousLoadedCount
                            val boundaryIndex = maxOf(0, contiguousLoadedCount - 1)
                            lazyListState.scrollToItem(boundaryIndex, 0)
                        }
                        huntAttempts++
                        delay(150)
                    }
                }

                isHunting = false
            }
        }

        // 3. HIGH-WATER MARK TRACKING (eigene Anker-Logik – unverändert)
        LaunchedEffect(lazyListState) {
            snapshotFlow {
                Triple(
                    lazyListState.firstVisibleItemIndex,
                    lazyListState.firstVisibleItemScrollOffset,
                    lazyListState.isScrollInProgress,
                )
            }.collect { (index, offset, isScrolling) ->
                if (itemCount > 0) {
                    if (isScrolling && isHunting) {
                        isHunting = false
                    }

                    val isSettingInitialAnchor = !tracker.isAnchored
                    val isBreakingRecord = index <= tracker.highestReadIndex

                    if (isScrolling || isSettingInitialAnchor) {
                        if (isSettingInitialAnchor || isBreakingRecord) {
                            tracker.anchorOffset = offset
                            tracker.highestReadIndex = index

                            val breadcrumbs = mutableListOf<String>()
                            for (i in 0 until 10) {
                                val pos = index + i
                                if (pos < itemCount) {
                                    val item = runCatching { peek(pos) }.getOrNull()
                                    if (item != null) {
                                        breadcrumbs.add(getPostFingerprint(item))
                                    }
                                }
                            }

                            if (breadcrumbs.isNotEmpty()) {
                                tracker.anchorFingerprints = breadcrumbs
                                tracker.isAnchored = true
                            }
                        }
                    }
                }
            }
        }

        // 4. Zähler für neue Posts – Upstreams key-basierter Ansatz (robuster als reiner Index-Vergleich)
        LaunchedEffect(lazyListState) {
            var previousKeys = emptySet<String>()
            snapshotFlow {
                val pagingState = currentPagingState
                (0 until pagingState.itemCount).mapNotNull { pagingState.peek(it)?.itemKey }
            }.collect { keys ->
                if (keys.isNotEmpty()) {
                    // Während der Jagd zählen wir nicht mit, da sich Indizes/Keys währenddessen
                    // noch nicht stabilisiert haben.
                    if (previousKeys.isNotEmpty() && !isAtTheTop) {
                        newPostCount += keys.takeWhile { it !in previousKeys }.size
                    }
                    previousKeys = keys.toSet()
                }
            }
        }

        // 5. Zähler verringern, während der Nutzer durch die neuen Posts scrollt (Upstream)
        LaunchedEffect(lazyListState) {
            snapshotFlow {
                val index = lazyListState.firstVisibleItemIndex
                index to
                        lazyListState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == index }
                            ?.key
            }.drop(1)
                .collect { (index, key) ->
                    val pagingState = currentPagingState
                    val postIndex =
                        if (key == null) {
                            index
                        } else {
                            (0 until pagingState.itemCount).indexOfFirst { pagingState.peek(it)?.itemKey == key }
                        }
                    if (postIndex >= 0) {
                        newPostCount = minOf(newPostCount, postIndex)
                    }
                }
        }
    }

    if (isSystemHomeMixedTimeline) {
        LaunchedEffect(lazyListState) {
            snapshotFlow { lazyListState.isScrollInProgress }
                .filter { it }
                .collect { newPostCount = 0 }
        }
    }

    LaunchedEffect(isAtTheTop) {
        if (isAtTheTop) {
            newPostCount = 0
        }
    }

    return object :
        TimelineWithLazyListState,
        TimelineItemPresenter.State by baseState {
        override val showNewToots = newPostCount > 0
        override val lazyListState = lazyListState
        override val newPostsCount = newPostCount

        override fun onNewTootsShown() {
            newPostCount = 0
        }
    }
}