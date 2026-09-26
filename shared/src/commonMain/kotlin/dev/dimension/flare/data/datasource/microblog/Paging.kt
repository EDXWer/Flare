package dev.dimension.flare.data.datasource.microblog

import androidx.paging.PagingConfig
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public val pagingConfig: PagingConfig =
    PagingConfig(
        // Fork: 40 statt 20. Seit Upstream-Commit 1f94a8110 (03.09.2026) verwirft ein
        // Refresh alle Timeline-Zeilen ausserhalb der frisch geladenen Seite. Mit nur 20
        // faellt der vorherige Kopf-Post nach einer Nacht heraus, und die Anker-Suche in
        // TimelineWithLazyListState findet ihn nicht mehr. 40 ist das Maximum, das
        // Mastodon pro Abfrage liefert; Bluesky und X liefern mindestens ebenso viel.
        pageSize = 40,
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