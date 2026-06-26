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
import dev.dimension.flare.data.model.tab.TimelineTabItemV2
import dev.dimension.flare.data.model.tab.isSystemHomeMixedTimeline
import dev.dimension.flare.ui.model.UiTimelineV2
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
    item: TimelineTabItemV2,
    lazyStaggeredGridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
): TimelineWithLazyListState {
    val baseState by producePresenter("timeline_${item.id}") {
        remember { TimelineItemPresenter(item) }.invoke()
    }
    return rememberTimelineWithLazyListState(
        baseState,
        lazyStaggeredGridState,
        isSystemHomeMixedTimeline = item.isSystemHomeMixedTimeline,
    )
}

// DIE GENIALE IDEE DES USERS (Crash-Safe)
// Nutzt nur Autor und Zeit, um absolut sicher auf Hintergrund-Threads zu sein!
private fun getPostFingerprint(item: Any?): String {
    return runCatching {
        val timelineItem = item as? UiTimelineV2 ?: return@runCatching "unknown_${item?.hashCode()}"
        when (timelineItem) {
            is UiTimelineV2.Post -> {
                val author = timelineItem.user?.name?.raw ?: "no_author"
                val time = timelineItem.createdAt.toString()
                "post_${author}_${time}"
            }
            is UiTimelineV2.Feed -> "feed_${timelineItem.source.name}_${timelineItem.title}"
            else -> "${timelineItem.itemKey ?: timelineItem.hashCode()}"
        }
    }.getOrDefault("error_${item?.hashCode()}")
}

// Die Stealth-Datenklasse für Ruckelfreiheit
// UPDATE: Speichert jetzt eine kleine Historie (Liste) der obersten Posts als Fallback!
private class ScrollTracker {
    var fingerprints: List<String> = emptyList()
    var offset: Int = 0
    var previousCount: Int = 0
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

    val tracker = remember { ScrollTracker() }

    baseState.listState.onSuccess {
        val currentCount = itemCount

        // 1. Inhalts-Tracking MIT SCHUTZSCHILD UND FALLBACK-HISTORIE
        LaunchedEffect(lazyListState) {
            snapshotFlow {
                Triple(
                    lazyListState.firstVisibleItemIndex,
                    lazyListState.firstVisibleItemScrollOffset,
                    lazyListState.isScrollInProgress
                )
            }.collect { (index, offset, isScrolling) ->
                if (index in 0 until itemCount) {
                    if (isScrolling || tracker.fingerprints.isEmpty()) {
                        // Wir speichern nicht nur den obersten Post, sondern die 3 obersten!
                        val history = mutableListOf<String>()
                        for (i in 0 until 3) {
                            val pos = index + i
                            if (pos < itemCount) {
                                history.add(getPostFingerprint(peek(pos)))
                            }
                        }
                        tracker.fingerprints = history
                        tracker.offset = offset
                    }
                }
            }
        }

        // 2. Sicherer Restorer (Immun gegen gelöschte Posts und Pull-to-Refresh-Löschungen)
        LaunchedEffect(currentCount) {
            if (currentCount > 0) {
                if (tracker.previousCount > 0 && currentCount > tracker.previousCount) {
                    val targetFingerprints = tracker.fingerprints
                    if (targetFingerprints.isNotEmpty()) {
                        var newIndex = -1
                        val limit = minOf(currentCount, 250)

                        // Versuche nacheinander, einen der gemerkten Posts zu finden.
                        // Ist Post 1 gelöscht? Finde Post 2! Ist Post 2 weg? Nimm Post 3!
                        for (targetFingerprint in targetFingerprints) {
                            for (i in 0 until limit) {
                                if (getPostFingerprint(peek(i)) == targetFingerprint) {
                                    newIndex = i
                                    break
                                }
                            }
                            if (newIndex != -1) break // Einen treuen Post gefunden, abbrechen!
                        }

                        // Falls wirklich alles weg ist, nutze Mathematik
                        if (newIndex == -1 && lazyListState.firstVisibleItemIndex == 0) {
                            newIndex = currentCount - tracker.previousCount
                        }

                        // Wenn gefunden, springe sicher an die Position!
                        if (newIndex != -1 && newIndex != lazyListState.firstVisibleItemIndex) {
                            lazyListState.scrollToItem(newIndex, tracker.offset)
                        }
                    }
                }
                tracker.previousCount = currentCount
            }
        }

        // 3. Trigger für den blauen Balken
        LaunchedEffect(lazyListState) {
            snapshotFlow {
                if (itemCount > 0) getPostFingerprint(peek(0)) else null
            }.mapNotNull { it }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    showNewToots = true
                    lastRefreshIndex = lazyListState.firstVisibleItemIndex
                }
        }
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect {
                if (it > lastRefreshIndex && showNewToots) {
                    newPostCount =
                        if (newPostCount > 0) {
                            minOf(newPostCount, it - lastRefreshIndex)
                        } else {
                            it - lastRefreshIndex
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

    // 4. Balken-Schutz: Verschwindet nur bei manuellem Scrollen ganz oben
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