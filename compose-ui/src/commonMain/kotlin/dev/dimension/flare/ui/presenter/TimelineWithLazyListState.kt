package dev.dimension.flare.ui.presenter

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.dimension.flare.common.onSuccess
import dev.dimension.flare.data.model.tab.UiTimelineTabItem
import dev.dimension.flare.data.model.tab.isSystemHomeMixedTimeline
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
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

    // Das Langzeitgedächtnis für reibungslose Navigation
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

// Die kugelsicheren Upstream-IDs (Viel besser als die alte Author+Time Methode)
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

// Unser aktualisierter Speicher (jetzt mit Höchststands-Tracker!)
private class ScrollContext {
    var anchorFingerprints: List<String> = emptyList()
    var anchorOffset: Int = 0
    var isAnchored: Boolean = false
    var knownTopFingerprint: String? = null
    var highestReadIndex: Int = Int.MAX_VALUE // DAS UPGRADE: Blockiert das Speichern beim Runterscrollen
}

@Composable
private fun rememberTimelineWithLazyListState(
    baseState: TimelineItemPresenter.State,
    lazyListState: LazyStaggeredGridState,
    isSystemHomeMixedTimeline: Boolean = false,
): TimelineWithLazyListState {
    var showNewToots by remember { mutableStateOf(false) }
    var lastRefreshIndex by remember { mutableStateOf(0) }
    var newPostCount by remember { mutableStateOf(0) }

    val tracker = remember { ScrollContext() }
    var isHunting by remember { mutableStateOf(false) }

    baseState.listState.onSuccess {
        val currentCount = itemCount
        val currentTopItem = if (currentCount > 0) runCatching { peek(0) }.getOrNull() else null
        val currentTopFp = if (currentTopItem != null) getPostFingerprint(currentTopItem) else null

        // 1. DATA REFRESH DETECTOR
        LaunchedEffect(currentCount, currentTopFp) {
            if (currentCount > 0 && currentTopFp != null) {
                val isTopChanged = tracker.knownTopFingerprint != null && currentTopFp != tracker.knownTopFingerprint

                if (isTopChanged && tracker.isAnchored) {
                    isHunting = true
                }

                tracker.knownTopFingerprint = currentTopFp
            }
        }

        // 2. DIE AKTIVE JAGD (Das Meisterstück aus dem alten Code)
        LaunchedEffect(isHunting) {
            if (isHunting) {
                var huntAttempts = 0
                var lastLoadedCount = 0

                while (isHunting && huntAttempts <= 15) {
                    var targetIndex = -1
                    var contiguousLoadedCount = 0

                    for (i in 0 until itemCount) {
                        val item = runCatching { peek(i) }.getOrNull()
                        if (item != null) {
                            contiguousLoadedCount = i + 1

                            if (targetIndex == -1 && tracker.anchorFingerprints.isNotEmpty()) {
                                val fpItem = getPostFingerprint(item)
                                if (tracker.anchorFingerprints.contains(fpItem)) {
                                    targetIndex = i
                                }
                            }
                        } else {
                            break
                        }
                    }

                    if (targetIndex != -1) {
                        // ANKER GEFUNDEN!
                        lazyListState.scrollToItem(targetIndex, tracker.anchorOffset)
                        tracker.highestReadIndex = targetIndex // Höchststand auf den neuen Index kalibrieren
                        isHunting = false
                        break
                    } else {
                        // FORCE PAGING3 TO LOAD MORE
                        if (contiguousLoadedCount > lastLoadedCount) {
                            lastLoadedCount = contiguousLoadedCount
                            huntAttempts++

                            val boundaryIndex = maxOf(0, contiguousLoadedCount - 1)
                            lazyListState.scrollToItem(boundaryIndex, 0)
                        } else if (contiguousLoadedCount == itemCount) {
                            isHunting = false
                            break
                        }

                        delay(100) // Warten, bis Paging3 die Daten liefert
                    }
                }

                isHunting = false
            }
        }

        // 3. DAS HIGH-WATER MARK TRACKING
        LaunchedEffect(lazyListState) {
            snapshotFlow {
                Triple(
                    lazyListState.firstVisibleItemIndex,
                    lazyListState.firstVisibleItemScrollOffset,
                    lazyListState.isScrollInProgress
                )
            }.collect { (index, offset, isScrolling) ->
                if (itemCount > 0) {

                    if (isScrolling && isHunting) {
                        isHunting = false
                    }

                    // DAS UPGRADE: Wir aktualisieren den Anker NUR, wenn wir einen neuen Höchststand erreichen!
                    val isSettingInitialAnchor = !tracker.isAnchored
                    val isBreakingRecord = index <= tracker.highestReadIndex

                    if (isScrolling || isSettingInitialAnchor) {
                        if (isSettingInitialAnchor || isBreakingRecord) {
                            tracker.anchorOffset = offset
                            tracker.highestReadIndex = index

                            val history = mutableListOf<String>()
                            for (i in 0 until 3) {
                                val pos = index + i
                                if (pos < itemCount) {
                                    val item = runCatching { peek(pos) }.getOrNull()
                                    if (item != null) history.add(getPostFingerprint(item))
                                }
                            }
                            if (history.isNotEmpty()) {
                                tracker.anchorFingerprints = history
                                tracker.isAnchored = true
                            }
                        }
                    }
                }
            }
        }

        // 4. Trigger für den blauen Balken
        LaunchedEffect(lazyListState) {
            snapshotFlow {
                val item = runCatching { peek(0) }.getOrNull()
                if (item != null) getPostFingerprint(item) else null
            }.mapNotNull { it }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    showNewToots = true
                    lastRefreshIndex = lazyListState.firstVisibleItemIndex
                }
        }
    }

    // 5. Smarter Counter (Ignoriert die Jagd)
    LaunchedEffect(lazyListState) {
        snapshotFlow {
            Triple(lazyListState.firstVisibleItemIndex, isHunting, showNewToots)
        }.collect { (currentIndex, hunting, showing) ->
            if (showing) {
                if (hunting) {
                    newPostCount = 0
                } else {
                    if (currentIndex > lastRefreshIndex) {
                        val count = currentIndex - lastRefreshIndex
                        newPostCount = if (newPostCount > 0) {
                            minOf(newPostCount, count)
                        } else {
                            count
                        }
                    }
                }
            }
        }
    }

    if (isSystemHomeMixedTimeline) {
        LaunchedEffect(lazyListState) {
            snapshotFlow { lazyListState.isScrollInProgress }
                .filter { it }
                .collect { showNewToots = false }
        }
    }

    val isAtTheTop by remember(lazyListState) {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0 &&
                    lazyListState.firstVisibleItemScrollOffset == 0
        }
    }

    LaunchedEffect(isAtTheTop, lazyListState.isScrollInProgress) {
        if (isAtTheTop && lazyListState.isScrollInProgress) {
            showNewToots = false
        }
    }

    LaunchedEffect(showNewToots) {
        if (!showNewToots) {
            newPostCount = 0
        }
    }

    return object :
        TimelineWithLazyListState,
        TimelineItemPresenter.State by baseState {
        override val showNewToots = showNewToots
        override val lazyListState = lazyListState
        override val newPostsCount = newPostCount

        override fun onNewTootsShown() {
            showNewToots = false
        }
    }
}