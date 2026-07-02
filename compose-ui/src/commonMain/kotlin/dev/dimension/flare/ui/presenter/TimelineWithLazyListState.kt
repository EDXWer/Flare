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

// Nutzt Autor und Zeit, um absolut sicher auf Hintergrund-Threads zu sein
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

// Unser Speicher für die Position
private class ScrollContext {
    var anchorFingerprints: List<String> = emptyList()
    var anchorOffset: Int = 0
    var isAnchored: Boolean = false
    var knownTopFingerprint: String? = null
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

    // Der State, der unsere "Jagd" (Polling-Schleife) kontrolliert
    var isHunting by remember { mutableStateOf(false) }

    baseState.listState.onSuccess {
        val currentCount = itemCount
        val currentTopItem = if (currentCount > 0) runCatching { peek(0) }.getOrNull() else null
        val currentTopFp = if (currentTopItem != null) getPostFingerprint(currentTopItem) else null

        // 1. DATA REFRESH DETECTOR (Erkennt das Update und gibt den Startschuss)
        LaunchedEffect(currentCount, currentTopFp) {
            if (currentCount > 0 && currentTopFp != null) {
                val isTopChanged = tracker.knownTopFingerprint != null && currentTopFp != tracker.knownTopFingerprint

                // Wenn neue Daten da sind und wir einen Anker haben, starte die Jagd!
                if (isTopChanged && tracker.isAnchored) {
                    isHunting = true
                }

                tracker.knownTopFingerprint = currentTopFp
            }
        }

        // 2. DIE AKTIVE JAGD (Gräbt sich durch Paging3-Placeholder)
        LaunchedEffect(isHunting) {
            if (isHunting) {
                var huntAttempts = 0
                var lastLoadedCount = 0

                // Wir suchen maximal 15 Zyklen lang (ca. 1,5 Sekunden), um Endlosschleifen zu vermeiden
                while (isHunting && huntAttempts <= 15) {
                    var targetIndex = -1
                    var contiguousLoadedCount = 0

                    // Wir scannen alles, was Paging3 aktuell im RAM hat
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
                            // Stop beim ersten "null" Platzhalter!
                            break
                        }
                    }

                    if (targetIndex != -1) {
                        // ANKER GEFUNDEN! Festnageln und Jagd beenden!
                        lazyListState.scrollToItem(targetIndex, tracker.anchorOffset)
                        isHunting = false
                        break
                    } else {
                        // ANKER NICHT GEFUNDEN (Er liegt noch in den Placeholdern)
                        if (contiguousLoadedCount > lastLoadedCount) {
                            lastLoadedCount = contiguousLoadedCount
                            huntAttempts++

                            // Springe an die Grenze, um Paging3 zum Laden der nächsten Seite zu zwingen!
                            val boundaryIndex = maxOf(0, contiguousLoadedCount - 1)
                            lazyListState.scrollToItem(boundaryIndex, 0)
                        } else if (contiguousLoadedCount == itemCount) {
                            // Die komplette Datenbank ist geladen und er ist nicht da. Aufgeben.
                            isHunting = false
                            break
                        }

                        // Der wichtigste Teil: Warte kurz, bis Paging3 die neuen Daten geliefert hat, dann wiederhole!
                        delay(100)
                    }
                }

                // Sicherheitsnetz, falls die Schleife abbricht
                isHunting = false
            }
        }

        // 3. DAS HINTERGRUND-TRACKING (Sammelt geräuschlos deine Position)
        LaunchedEffect(lazyListState) {
            snapshotFlow {
                Triple(
                    lazyListState.firstVisibleItemIndex,
                    lazyListState.firstVisibleItemScrollOffset,
                    lazyListState.isScrollInProgress
                )
            }.collect { (index, offset, isScrolling) ->
                if (itemCount > 0) {

                    // Wenn der User aktiv scrollt, übernehmen wir NICHT die Kontrolle!
                    if (isScrolling && isHunting) {
                        isHunting = false // Jagd sofort abbrechen
                    }

                    if (isScrolling || !tracker.isAnchored) {
                        tracker.anchorOffset = offset

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

    // 5. UPDATE: Smarter Counter, der die Jagd ignoriert und nur das finale Ergebnis zählt!
    LaunchedEffect(lazyListState) {
        snapshotFlow {
            Triple(lazyListState.firstVisibleItemIndex, isHunting, showNewToots)
        }.collect { (currentIndex, hunting, showing) ->
            if (showing) {
                if (hunting) {
                    // Während der Tracker noch sucht und springt, blockieren wir falsche Zwischenzählungen
                    newPostCount = 0
                } else {
                    // Jagd ist beendet! Jetzt können wir die echten neuen Posts zählen.
                    if (currentIndex > lastRefreshIndex) {
                        val count = currentIndex - lastRefreshIndex
                        newPostCount = if (newPostCount > 0) {
                            minOf(newPostCount, count) // Zählt beim Hochscrollen sauber runter
                        } else {
                            count // Setzt die initiale, korrekte Startanzahl
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

    // 6. Balken-Schutz: Verschwindet nur bei manuellem Scrollen ganz oben
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