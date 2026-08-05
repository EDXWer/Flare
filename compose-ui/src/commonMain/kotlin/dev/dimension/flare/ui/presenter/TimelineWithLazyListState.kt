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

        // 2. DIE AKTIVE JAGD (Jetzt mit Brotkrümel-Fallback und mehr Geduld)
        LaunchedEffect(isHunting) {
            if (isHunting && tracker.anchorFingerprints.isNotEmpty()) {
                var huntAttempts = 0
                var lastLoadedCount = 0

                // Wir erlauben mehr Versuche (20), falls das Netzwerk langsam ist
                while (isHunting && huntAttempts <= 20) {
                    var bestMatchIndex = -1
                    var bestMatchPriority = Int.MAX_VALUE // 0 ist der Original-Post, 1 ist der darunter, etc.
                    var contiguousLoadedCount = 0

                    for (i in 0 until itemCount) {
                        val item = runCatching { peek(i) }.getOrNull()
                        if (item != null) {
                            contiguousLoadedCount = i + 1
                            val fp = getPostFingerprint(item)

                            // Welchen Brotkrümel haben wir gefunden?
                            val priority = tracker.anchorFingerprints.indexOf(fp)

                            // Wir nehmen immer den Krümel, der am nächsten am Original-Anker ist!
                            if (priority != -1 && priority < bestMatchPriority) {
                                bestMatchPriority = priority
                                bestMatchIndex = i
                            }
                        } else {
                            break
                        }
                    }

                    if (bestMatchIndex != -1) {
                        // TREFFER! Entweder der exakte Post oder der bestmögliche Fallback darunter!
                        val offset = if (bestMatchPriority == 0) tracker.anchorOffset else 0
                        lazyListState.scrollToItem(bestMatchIndex, offset)
                        tracker.highestReadIndex = bestMatchIndex
                        isHunting = false
                        break
                    } else {
                        // SICHERHEITSLEINE: Wenn wir schon 80 Posts geladen haben, geben wir auf.
                        if (contiguousLoadedCount > 80) {
                            isHunting = false
                            break
                        }

                        // FORCE PAGING3 TO LOAD MORE
                        if (contiguousLoadedCount > lastLoadedCount) {
                            lastLoadedCount = contiguousLoadedCount
                            val boundaryIndex = maxOf(0, contiguousLoadedCount - 1)
                            lazyListState.scrollToItem(boundaryIndex, 0)
                        }
                        // WICHTIG: Kein sofortiger Abbruch mehr! Wir geben Paging3 Zeit zum Laden.
                        huntAttempts++
                        delay(150) // 150ms warten, damit die Liste im Hintergrund nachwachsen kann
                    }
                }

                isHunting = false
            }
        }

        // 3. DAS HIGH-WATER MARK TRACKING (Legt die Brotkrümel aus)
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

                    val isSettingInitialAnchor = !tracker.isAnchored
                    val isBreakingRecord = index <= tracker.highestReadIndex

                    // Wenn wir aktualisieren oder nach oben scrollen, werfen wir das Netz aus
                    if (isScrolling || isSettingInitialAnchor) {
                        if (isSettingInitialAnchor || isBreakingRecord) {
                            tracker.anchorOffset = offset
                            tracker.highestReadIndex = index

                            // DIE BROTKRÜMEL: Wir speichern die Top 10 sichtbaren Posts
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

    // 5. Smarter Counter
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