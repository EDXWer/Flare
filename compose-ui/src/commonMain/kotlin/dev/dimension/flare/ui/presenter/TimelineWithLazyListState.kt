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
import kotlinx.coroutines.flow.distinctUntilChanged
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

// Die neue Datenstruktur für die Brotkrümel-Spur
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

        // 1. KOPF DER LISTE BEOBACHTEN
        // Der Anker ist der jeweils aktuellste Post. Wechselt der Kopf der Liste
        // (Refresh oder neue Posts), war der vorherige Kopf das Ziel: dorthin zurueck,
        // sodass das Neue darueber liegt. Die Leseposition spielt bewusst keine Rolle.
        LaunchedEffect(Unit) {
            snapshotFlow {
                val pagingState = currentPagingState
                (0 until minOf(10, pagingState.itemCount))
                    .mapNotNull { runCatching { pagingState.peek(it) }.getOrNull() }
                    .map { getPostFingerprint(it) }
            }.distinctUntilChanged().collect { crumbs ->
                if (crumbs.isNotEmpty() && tracker.lastCrumbs.isEmpty()) {
                    // Diagnose: Was sieht die Logik beim Start als Allererstes?
                    // Ist das noch der Stand von gestern (aus der Datenbank) oder schon
                    // der frische vom Server?
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

                    // Nicht waehrend der Jagd ueberschreiben, sonst verlieren wir das Ziel.
                    if (!isHunting) {
                        tracker.lastCrumbs = crumbs
                    }
                }
            }
        }

        // 2. DIE AKTIVE JAGD (eigene Anker-Logik – unverändert)
        LaunchedEffect(isHunting) {
            if (isHunting && tracker.anchorFingerprints.isNotEmpty()) {
                var huntAttempts = 0
                var lastLoadedCount = 0

                // Startposition merken: Falls die Jagd scheitert, kehren wir hierher zurueck,
                // statt den Nutzer dort stehen zu lassen, wo der letzte Zwangs-Scroll war.
                val startIndex = lazyListState.firstVisibleItemIndex
                val startOffset = lazyListState.firstVisibleItemScrollOffset
                var fallbackIndex = -1
                var fallbackPriority = Int.MAX_VALUE
                // So viele Runden (a 150 ms) warten wir auf den exakten Anker, bevor wir
                // uns mit einem Ersatzkruemel zufriedengeben.
                val patienceAttempts = 8

                // Wurde ueberhaupt kein Kruemel gefunden, ist die Alternative "oben stehen
                // bleiben" - dann lohnt langes Warten. Nach einer Nacht liegt der Lesepost
                // etliche Seiten tiefer und Paging braucht Zeit.
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

                            // Auch eingeklappte Posts beruecksichtigen, nicht nur die
                            // oberste Ebene des Eintrags.
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

                    // Exakter Treffer: sofort annehmen, mit Original-Offset.
                    if (bestMatchIndex != -1 && bestMatchPriority == 0) {
                        anchorLog(
                            "EXAKTER TREFFER Index $bestMatchIndex, Versuch $huntAttempts" +
                                    " -> verschiebe um ${bestMatchIndex - startIndex} Positionen",
                        )
                        lazyListState.selfScrollTo(tracker, bestMatchIndex, 0)
                        isHunting = false
                        break
                    }

                    // Nur ein Ersatzkruemel gefunden: merken, aber noch nicht zuschlagen.
                    // Die Liste waechst nach einem Refresh oft noch, und der exakte Anker
                    // taucht haeufig erst ein, zwei Ladevorgaenge spaeter auf. Genau hier
                    // hat die Jagd bisher zu frueh abgebrochen.
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
                            lazyListState.selfScrollTo(tracker, fallbackIndex, 0)
                        } else {
                            anchorLog("AUFGEGEBEN, Versuch $huntAttempts, itemCount $itemCount")
                            lazyListState.selfScrollTo(tracker, startIndex, startOffset)
                        }
                        isHunting = false
                        break
                    }

                    // Nur nachladen erzwingen, wenn noch gar kein Kruemel gefunden wurde.
                    // Haben wir schon einen Ersatz, warten wir einfach ab, statt den
                    // Nutzer sichtbar durch die Liste zu ziehen.
                    // Nachschub anfordern. Entscheidend ist get() statt peek():
                    // peek() liest nur, get() schickt Paging3 den Ladehinweis. Ohne das
                    // waechst die Liste nie, egal wie weit wir scrollen.
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

                // Schleife ohne Treffer beendet (20 Versuche aufgebraucht):
                // ebenfalls zurueck an den Ausgangspunkt.
                if (isHunting) {
                    anchorLog("AUFGEGEBEN nach $huntAttempts Versuchen, zurueck zu Index $startIndex")
                    lazyListState.selfScrollTo(tracker, startIndex, startOffset)
                }
                // Achtung: isHunting = false startet diesen Effekt neu. Code danach wird
                // nicht mehr ausgefuehrt - deshalb steht hier nichts mehr dahinter.
                isHunting = false
            } else if (!isHunting && tracker.lastCrumbs.isNotEmpty()) {
                // Jagd vorbei (oder gar nicht noetig): Der jetzige Kopf ist das Ziel fuer
                // den naechsten Refresh. Ohne das wuerde der naechste Refresh nach dem
                // vorletzten Kopf suchen, weil waehrend der Jagd nicht mitgeschrieben wurde.
                val pagingState = currentPagingState
                val current =
                    (0 until minOf(10, pagingState.itemCount))
                        .mapNotNull { runCatching { pagingState.peek(it) }.getOrNull() }
                        .map { getPostFingerprint(it) }
                if (current.isNotEmpty()) {
                    tracker.lastCrumbs = current
                }
            }
        }

        // 3. Nutzer-Scroll bricht eine laufende Jagd ab.
        LaunchedEffect(lazyListState) {
            snapshotFlow { lazyListState.isScrollInProgress }
                .filter { it }
                .collect {
                    if (isHunting && !tracker.selfScrolling) {
                        anchorLog("Nutzer scrollt, Jagd abgebrochen")
                        isHunting = false
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