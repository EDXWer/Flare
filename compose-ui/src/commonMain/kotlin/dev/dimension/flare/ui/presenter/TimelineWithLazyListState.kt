package dev.dimension.flare.ui.presenter

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.onSuccess
import dev.dimension.flare.data.model.tab.UiTimelineTabItem
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import moe.tlaster.precompose.molecule.producePresenter

@Immutable
public interface TimelineWithLazyListState : TimelineItemPresenter.State {
    public val showNewToots: Boolean
    public val lazyListState: LazyStaggeredGridState
    public val newPostsCount: Int

    public fun onNewTootsShown()

    public fun loadGap(message: UiTimelineV2.Gap) {
        message.onActivate?.invoke {}
    }
}

@Composable
public fun rememberTimelineItemPresenterWithLazyListState(
    item: UiTimelineTabItem,
    isHomeTimeline: Boolean = false,
    lazyStaggeredGridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    leadingItemCount: Int = 0,
): TimelineWithLazyListState {
    val baseState by producePresenter("timeline_${item.id}_${item.contentSourceKey}_$isHomeTimeline") {
        remember(item.id, item.contentSourceKey, isHomeTimeline) {
            TimelineItemPresenter(item, isHomeTimeline)
        }.invoke()
    }

    return key(item.id, item.contentSourceKey, isHomeTimeline) {
        rememberTimelineWithLazyListState(
            baseState = baseState,
            lazyStaggeredGridState = lazyStaggeredGridState,
            leadingItemCount = leadingItemCount,
        )
    }
}

// Temporaere Diagnose-Ausgabe. Nach der Fehlersuche wieder entfernen:
// einfach ANCHOR_DEBUG auf false setzen.
private const val ANCHOR_DEBUG = true

private fun anchorLog(message: String) {
    if (ANCHOR_DEBUG) {
        println("FLARE_ANCHOR $message")
    }
}

// Sichere ID Extraktion (Kugelsicher gegen Nulls)
private fun getPostFingerprint(item: Any?): String {
    return runCatching {
        val timelineItem = item as? UiTimelineV2 ?: return@runCatching "unknown_${item?.hashCode()}"
        when (timelineItem) {
            is UiTimelineV2.Post -> "post_${timelineItem.statusKey}"
            is UiTimelineV2.Feed -> "feed_${timelineItem.statusKey}"
            // Upstream packt Posts mit Repost/Zitat in diesen Wrapper. Ohne eigenen Zweig
            // landeten sie im else und bekamen einen instabilen hashCode als Fingerprint.
            is UiTimelineV2.TimelinePostItem -> "post_${timelineItem.statusKey}"
            // Kein hashCode-Fallback mehr: statusKey ist fuer jeden Subtyp stabil, auch
            // fuer kuenftige neue Wrapper-Typen aus dem Upstream.
            else -> timelineItem.itemKey ?: "item_${timelineItem.statusKey}"
        }
    }.getOrDefault("error_${item?.hashCode()}")
}

// Die Datenstruktur für die Brotkrümel-Spur
private class ScrollContext {
    // Die Fingerprints der obersten 10 Listeneintraege, wie sie VOR dem letzten
    // Listenwechsel aussahen. anchorFingerprints[0] ist damit der Post, der zuletzt
    // der aktuellste war - genau der soll nach einem Refresh wieder oben stehen.
    var anchorFingerprints: List<String> = emptyList()
    var isAnchored: Boolean = false

    // Der zuletzt beobachtete Kopf der Liste. Muss hier liegen und nicht als lokale
    // Variable im Effekt: Der Effekt wird neu gestartet, wenn die Liste waehrend eines
    // Refreshs kurz in einen Ladezustand geht - und wuerde dabei den alten Kopf vergessen.
    var lastCrumbs: List<String> = emptyList()

    // True nur waehrend eines Scrolls, den die Jagd selbst ausloest. Damit der
    // Scroll-Abbruch (Abschnitt 3) unser eigenes scrollToItem nicht fuer den Nutzer haelt.
    var selfScrolling: Boolean = false
}

// Scrollt und setzt dabei selfScrolling. finally laeuft auch bei Abbruch des Effekts,
// daher kann das Flag nicht haengen bleiben.
private suspend fun LazyStaggeredGridState.selfScrollTo(
    tracker: ScrollContext,
    index: Int,
    offset: Int,
) {
    tracker.selfScrolling = true
    try {
        scrollToItem(index, offset)
    } finally {
        tracker.selfScrolling = false
    }
}

// Ein Listeneintrag kann weitere Posts enthalten: eingeklappte Antwort-Ketten
// (inlineParents), Zitate und Reposts. collapseReplyChains() faltet Ketten zu einem
// Eintrag zusammen, wodurch der gesuchte Post aus der obersten Ebene verschwindet.
// Die Jagd muss deshalb auch nach innen schauen, sonst findet sie ihn nie.
private fun getCoveredFingerprints(item: Any?): List<String> =
    runCatching {
        val timelineItem = item as? UiTimelineV2 ?: return@runCatching emptyList()
        val result = mutableListOf(getPostFingerprint(timelineItem))
        val presentation =
            when (timelineItem) {
                is UiTimelineV2.TimelinePostItem -> timelineItem.presentation
                else -> null
            }
        presentation?.let { p ->
            p.inlineParents.forEach { parent -> result.add(getPostFingerprint(parent)) }
            p.quotes.forEach { quote -> result.add(getPostFingerprint(quote)) }
            p.repost?.let { repost -> result.add(getPostFingerprint(repost)) }
        }
        result
    }.getOrDefault(emptyList())

@Composable
internal fun rememberTimelineWithLazyListState(
    baseState: TimelineItemPresenter.State,
    lazyStaggeredGridState: LazyStaggeredGridState,
    leadingItemCount: Int = 0,
): TimelineWithLazyListState {
    val currentLeadingItemCount by rememberUpdatedState(leadingItemCount)
    var gapAnchor by remember { mutableStateOf<GapScrollAnchor?>(null) }
    var activatingGaps by remember { mutableStateOf(emptySet<Pair<String, Long>>()) }
    var newPostCount by remember { mutableIntStateOf(0) }

    val tracker = remember { ScrollContext() }
    var isHunting by remember { mutableStateOf(false) }

    val isAtTheTop by remember(lazyStaggeredGridState) {
        derivedStateOf {
            lazyStaggeredGridState.firstVisibleItemIndex == 0 &&
                    lazyStaggeredGridState.firstVisibleItemScrollOffset == 0
        }
    }

    if (baseState.listState is PagingState.Empty) SideEffect { gapAnchor = null }

    baseState.listState.onSuccess {
        val currentPagingState by rememberUpdatedState(this)

        // 0. UPSTREAM GAP-ANCHOR (Nur aktiv, wenn der Nutzer gezielt eine "Lücke laden" anklickt)
        SideEffect {
            val anchor = gapAnchor
            if (anchor != null && !lazyStaggeredGridState.isScrollInProgress) {
                val index = (0 until itemCount).firstOrNull { peek(it)?.stableItemKey == anchor.itemKey }
                val visible = lazyStaggeredGridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.itemKey }
                if (index != null && (visible?.index != index + currentLeadingItemCount || visible.offset.y != -anchor.scrollOffset)) {
                    lazyStaggeredGridState.requestScrollToItem(index + currentLeadingItemCount, anchor.scrollOffset)
                }
            }
        }

        LaunchedEffect(this, gapAnchor, currentLeadingItemCount) {
            val anchor = gapAnchor ?: return@LaunchedEffect
            val index = (0 until itemCount).firstOrNull { peek(it)?.stableItemKey == anchor.itemKey }
            if (index == null) {
                val keys = (0 until itemCount).mapNotNull { peek(it)?.stableItemKey }.toSet()
                val oldIndex = anchor.itemOrder.indexOf(anchor.itemKey)
                // A clipped paging window is not evidence that the reading item was deleted.
                if (anchor.itemOrder.take(oldIndex).any { it in keys } &&
                    anchor.itemOrder.drop(oldIndex + 1).any { it in keys }
                ) {
                    gapAnchor = null
                }
                return@LaunchedEffect
            }
            withContext(Dispatchers.Main.immediate) {
                snapshotFlow {
                    val layout = lazyStaggeredGridState.layoutInfo
                    layout.totalItemsCount to layout.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) }
                }.collect {
                    if (lazyStaggeredGridState.isScrollInProgress) return@collect
                    val visible = lazyStaggeredGridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.itemKey }
                    if (visible?.index == index + currentLeadingItemCount && visible.offset.y == -anchor.scrollOffset) return@collect
                    lazyStaggeredGridState.requestScrollToItem(index + currentLeadingItemCount, anchor.scrollOffset)
                }
            }
        }

        // 1. KOPF DER LISTE BEOBACHTEN
        LaunchedEffect(Unit) {
            snapshotFlow {
                val pagingState = currentPagingState
                (0 until minOf(10, pagingState.itemCount))
                    .mapNotNull { runCatching { pagingState.peek(it) }.getOrNull() }
                    .filter { !it.isPaginationGap }
                    .map { getPostFingerprint(it) }
            }.distinctUntilChanged().collect { crumbs ->
                if (crumbs.isNotEmpty() && tracker.lastCrumbs.isEmpty()) {
                    anchorLog(
                        "Erste Liste nach Start: Kopf ${crumbs.firstOrNull()}," +
                                " itemCount ${currentPagingState.itemCount}",
                    )
                }
                if (crumbs.isNotEmpty()) {
                    val headChanged =
                        tracker.lastCrumbs.isNotEmpty() && crumbs.firstOrNull() != tracker.lastCrumbs.firstOrNull()

                    if (headChanged && !isHunting) {
                        anchorLog(
                            "Kopfwechsel: neu ${crumbs.firstOrNull()}," +
                                    " gesucht wird ${tracker.lastCrumbs.firstOrNull()}",
                        )
                        tracker.anchorFingerprints = tracker.lastCrumbs
                        tracker.isAnchored = true
                        isHunting = true
                    }

                    if (!isHunting) {
                        tracker.lastCrumbs = crumbs
                    }
                }
            }
        }

        // 2. DIE AKTIVE JAGD (eigene Anker-Logik)
        LaunchedEffect(isHunting) {
            if (isHunting && tracker.anchorFingerprints.isNotEmpty()) {
                var huntAttempts = 0
                var lastLoadedCount = 0

                val startIndex = lazyStaggeredGridState.firstVisibleItemIndex
                val startOffset = lazyStaggeredGridState.firstVisibleItemScrollOffset
                var fallbackIndex = -1
                var fallbackPriority = Int.MAX_VALUE
                val patienceAttempts = 8

                val patienceWithoutAnyMatch = 40
                val fpAtStart = runCatching { peek(startIndex) }.getOrNull()?.let { getPostFingerprint(it) }
                anchorLog(
                    "Jagd startet bei Index $startIndex (dort steht: $fpAtStart)" +
                            ", gesucht wird ${tracker.anchorFingerprints.firstOrNull()}",
                )

                while (isHunting && huntAttempts <= 60) {
                    var bestMatchIndex = -1
                    var bestMatchPriority = Int.MAX_VALUE
                    var contiguousLoadedCount = 0

                    for (i in 0 until itemCount) {
                        val item = runCatching { peek(i) }.getOrNull()
                        if (item != null) {
                            contiguousLoadedCount = i + 1

                            getCoveredFingerprints(item).forEach { fp ->
                                val priority = tracker.anchorFingerprints.indexOf(fp)
                                if (priority != -1 && priority < bestMatchPriority) {
                                    bestMatchPriority = priority
                                    bestMatchIndex = i
                                }
                            }
                        } else {
                            break
                        }
                    }

                    if (bestMatchIndex != -1 && bestMatchPriority == 0) {
                        anchorLog(
                            "EXAKTER TREFFER Index $bestMatchIndex, Versuch $huntAttempts" +
                                    " -> verschiebe um ${bestMatchIndex - startIndex} Positionen",
                        )
                        lazyStaggeredGridState.selfScrollTo(
                            tracker,
                            bestMatchIndex + currentLeadingItemCount,
                            0,
                        )
                        if (bestMatchIndex > 0) {
                            newPostCount = bestMatchIndex
                        }
                        isHunting = false
                        break
                    }

                    if (bestMatchIndex != -1 && bestMatchPriority < fallbackPriority) {
                        fallbackIndex = bestMatchIndex
                        fallbackPriority = bestMatchPriority
                        anchorLog("Ersatzkruemel gemerkt: Index $bestMatchIndex, Prioritaet $bestMatchPriority, warte auf Besseres")
                    }

                    val listStoppedGrowing = contiguousLoadedCount <= lastLoadedCount

                    val patienceLimit =
                        if (fallbackIndex == -1) patienceWithoutAnyMatch else patienceAttempts

                    if (contiguousLoadedCount > 400 ||
                        (listStoppedGrowing && huntAttempts >= patienceLimit)
                    ) {
                        if (fallbackIndex != -1) {
                            anchorLog("nehme Ersatzkruemel Index $fallbackIndex, Prioritaet $fallbackPriority, Versuch $huntAttempts")
                            lazyStaggeredGridState.selfScrollTo(
                                tracker,
                                fallbackIndex + currentLeadingItemCount,
                                0,
                            )
                            if (fallbackIndex > 0) {
                                newPostCount = fallbackIndex
                            }
                        } else {
                            anchorLog("AUFGEGEBEN, Versuch $huntAttempts, itemCount $itemCount")
                            lazyStaggeredGridState.selfScrollTo(tracker, startIndex, startOffset)
                        }
                        isHunting = false
                        break
                    }

                    if (fallbackIndex == -1 && itemCount > 0) {
                        val tailIndex = itemCount - 1
                        runCatching { currentPagingState[tailIndex] }
                        if (contiguousLoadedCount > lastLoadedCount) {
                            lastLoadedCount = contiguousLoadedCount
                            anchorLog(
                                "kein Treffer, fordere Nachschub ab Index $tailIndex an" +
                                        " (geladen $contiguousLoadedCount von $itemCount)",
                            )
                        }
                    } else {
                        lastLoadedCount = maxOf(lastLoadedCount, contiguousLoadedCount)
                    }
                    huntAttempts++
                    if (huntAttempts % 5 == 0) {
                        anchorLog(
                            "Versuch $huntAttempts: itemCount $itemCount, geladen $contiguousLoadedCount" +
                                    ", waechst=${!listStoppedGrowing}, ersatz=$fallbackIndex",
                        )
                    }
                    delay(250)
                }

                if (isHunting) {
                    anchorLog("AUFGEGEBEN nach $huntAttempts Versuchen, zurueck zu Index $startIndex")
                    lazyStaggeredGridState.selfScrollTo(tracker, startIndex, startOffset)
                }
                isHunting = false
            } else if (!isHunting && tracker.lastCrumbs.isNotEmpty()) {
                val pagingState = currentPagingState
                val current =
                    (0 until minOf(10, pagingState.itemCount))
                        .mapNotNull { runCatching { pagingState.peek(it) }.getOrNull() }
                        .filter { !it.isPaginationGap }
                        .map { getPostFingerprint(it) }
                if (current.isNotEmpty()) {
                    tracker.lastCrumbs = current
                }
            }
        }

        // 3. Nutzer-Scroll bricht eine laufende Jagd ab UND blendet den Banner aus.
        LaunchedEffect(lazyStaggeredGridState) {
            snapshotFlow { lazyStaggeredGridState.isScrollInProgress }
                .filter { it }
                .collect {
                    // Wir reagieren nur auf echte menschliche Finger-Wischer
                    if (!tracker.selfScrolling) {

                        if (isHunting) {
                            anchorLog("Nutzer scrollt, Jagd abgebrochen")
                            isHunting = false
                        }

                        // Dein Claude-Tweak: Den Zähler sofort nullen, das lässt den Banner verschwinden!
                        if (newPostCount > 0) {
                            newPostCount = 0
                        }
                    }
                }
        }

        // 4. Zähler für neue Posts – Upstreams key-basierter Ansatz
        LaunchedEffect(lazyStaggeredGridState) {
            var previousKeys = emptySet<String>()
            snapshotFlow {
                val pagingState = currentPagingState
                (0 until pagingState.itemCount).mapNotNull { pagingState.peek(it)?.itemKey }
            }.collect { keys ->
                if (keys.isNotEmpty()) {
                    if (previousKeys.isNotEmpty() && (!isAtTheTop || isHunting)) {
                        newPostCount += keys.takeWhile { it !in previousKeys }.size
                    }
                    previousKeys = keys.toSet()
                }
            }
        }

        // 5. Zähler verringern, während der Nutzer durch die neuen Posts nach oben scrollt
        LaunchedEffect(lazyStaggeredGridState) {
            snapshotFlow {
                val index = lazyStaggeredGridState.firstVisibleItemIndex
                index to
                        lazyStaggeredGridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == index }
                            ?.key
            }.drop(1)
                .collect { (index, key) ->
                    if (isHunting || tracker.selfScrolling) return@collect

                    val pagingState = currentPagingState
                    val postIndex =
                        if (key == null) {
                            (index - currentLeadingItemCount).coerceAtLeast(0)
                        } else {
                            (0 until pagingState.itemCount).indexOfFirst { pagingState.peek(it)?.itemKey == key }
                        }
                    if (postIndex >= 0) {
                        newPostCount = minOf(newPostCount, postIndex)
                    }
                }
        }
    }

    LaunchedEffect(isAtTheTop, isHunting) {
        if (isAtTheTop && !isHunting && !tracker.selfScrolling) {
            newPostCount = 0
        }
    }

    return object :
        TimelineWithLazyListState,
        TimelineItemPresenter.State by baseState {
        // Nur anzeigen, wenn wirklich gezählte neue Posts über dem Anker liegen (nie "0")
        override val showNewToots = newPostCount > 0
        override val lazyListState = lazyStaggeredGridState
        override val newPostsCount = newPostCount

        override fun onNewTootsShown() {
            newPostCount = 0
        }

        override fun loadGap(message: UiTimelineV2.Gap) {
            val activate = message.onActivate ?: return
            val request = message.stableItemKey to message.revision
            if (message.loading || request in activatingGaps) return
            activatingGaps += request
            gapAnchor = null
            baseState.listState.onSuccess {
                val gapIndex = (0 until itemCount).firstOrNull { peek(it)?.stableItemKey == message.stableItemKey } ?: return@onSuccess
                val next = (gapIndex + 1 until itemCount).firstOrNull { peek(it)?.isPaginationGap == false }
                val visible = lazyStaggeredGridState.layoutInfo.visibleItemsInfo
                val below = next?.let { index -> visible.firstOrNull { it.key == peek(index)?.stableItemKey } }
                val anchor =
                    below ?: visible
                        .filter { info ->
                            val index = info.index - currentLeadingItemCount
                            info.offset.y + info.size.height > 0 && index in 0 until itemCount &&
                                    peek(index)?.let { !it.isPaginationGap && it.stableItemKey == info.key } == true
                        }.minWithOrNull(compareBy({ it.offset.y }, { it.offset.x }))
                if (anchor != null) {
                    gapAnchor =
                        GapScrollAnchor(
                            checkNotNull(peek(anchor.index - currentLeadingItemCount)).stableItemKey,
                            -anchor.offset.y,
                            (0 until itemCount).mapNotNull { peek(it)?.takeUnless { it.isPaginationGap }?.stableItemKey },
                        )
                }
            }
            activate { activatingGaps -= request }
        }
    }
}

private data class GapScrollAnchor(
    val itemKey: String,
    val scrollOffset: Int,
    val itemOrder: List<String>,
)