package dev.dimension.flare.data.datasource.microblog

import androidx.paging.PagingConfig
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public val pagingConfig: PagingConfig =
    PagingConfig(
        // Fork: 100 statt 20 (Upstream-Wert).
        //
        // Hintergrund: Seit Upstream-Commit 1f94a8110 (03.09.2026) verwirft ein Refresh
        // alle Timeline-Zeilen ausserhalb der frisch geladenen Seite. Mit nur 20 fiel der
        // vorherige Kopf-Post nach einer Nacht heraus, und die Anker-Suche in
        // TimelineWithLazyListState fand ihn nicht mehr.
        //
        // pageSize wird unveraendert als API-Limit durchgereicht und gilt PRO QUELLE PRO
        // REFRESH - ein Pull-to-Refresh holt genau eine Seite je Quelle, alles Aeltere
        // wird gar nicht erst angefragt. Bei 40 reichte eine aktive X-Timeline nur wenige
        // Stunden zurueck, sodass zwischen den frischen Posts und dem letzten Stand von
        // gestern Abend eine Luecke blieb. 100 deckt eine Nacht in der Regel ab.
        //
        // Server mit kleinerem Maximum (Mastodon: 40) begrenzen selbst und ignorieren den
        // hoeheren Wert, X und Bluesky liefern bis etwa 100.
        pageSize = 100,
        prefetchDistance = 1,
        // Fork: grosses erstes Lesefenster. initialLoadSize betrifft nur, wie viele
        // Zeilen aus der lokalen Datenbank gelesen werden - kein zusaetzlicher
        // Netzwerkverkehr. Noetig, weil die gemischte Timeline nach einem Refresh
        // nicht mehr nachlaedt (MixedRemoteMediator meldet endOfPaginationReached,
        // sobald keine Unterquelle mehr einen nextKey liefert). Ohne grosses Fenster
        // bleiben bereits gespeicherte aeltere Posts unsichtbar.
        initialLoadSize = 200,
    )

@HiddenFromObjC
public val offsetPagingConfig: PagingConfig
    get() =
        PagingConfig(
            pageSize = pagingConfig.pageSize,
            prefetchDistance = pagingConfig.prefetchDistance,
            enablePlaceholders = false,
            initialLoadSize = pagingConfig.initialLoadSize,
            maxSize = pagingConfig.maxSize,
            jumpThreshold = pagingConfig.jumpThreshold,
        )