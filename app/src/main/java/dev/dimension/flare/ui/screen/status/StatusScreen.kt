package dev.dimension.flare.ui.screen.status

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dimension.flare.R
import dev.dimension.flare.data.model.BottomBarBehavior
import dev.dimension.flare.data.model.TimelineDisplayMode
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.BackButton
import dev.dimension.flare.ui.component.FlareScaffold
import dev.dimension.flare.ui.component.FlareTopAppBar
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.RefreshContainer
import dev.dimension.flare.ui.component.platform.isBigScreen
import dev.dimension.flare.ui.component.platform.isCompatScreen
import dev.dimension.flare.ui.component.status.LazyStatusVerticalStaggeredGrid
import dev.dimension.flare.ui.component.status.statusDetail
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.status.StatusContextPresenter
import dev.dimension.flare.ui.theme.isLightTheme
import kotlinx.coroutines.launch
import moe.tlaster.precompose.molecule.producePresenter
import dev.dimension.flare.ui.component.Text as FlareText

@Composable
internal fun StatusScreen(
    statusKey: MicroBlogKey,
    onBack: () -> Unit,
    accountType: AccountType,
) {
    val state by producePresenter("$accountType:$statusKey") {
        statusPresenter(accountType = accountType, statusKey = statusKey)
    }
    StatusDetailScreenContent(statusKey, state.state, onBack, state.isRefreshing, state::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusDetailScreenContent(
    statusKey: MicroBlogKey,
    state: StatusContextPresenter.State,
    onBack: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val isBigScreen = isBigScreen()
    val topAppBarScrollBehavior =
        if (LocalGlobalAppearance.current.bottomBarBehavior == BottomBarBehavior.AlwaysShow) {
            TopAppBarDefaults.pinnedScrollBehavior()
        } else {
            TopAppBarDefaults.enterAlwaysScrollBehavior()
        }
    FlareScaffold(
        topBar = {
            FlareTopAppBar(
                colors =
                    when (LocalTimelineAppearance.current.timelineDisplayMode) {
                        TimelineDisplayMode.Plain if isLightTheme() && isCompatScreen() -> {
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrolledContainerColor = MaterialTheme.colorScheme.surface,
                                actionIconContentColor = MaterialTheme.colorScheme.primary,
                            )
                        }

                        else -> {
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                scrolledContainerColor = MaterialTheme.colorScheme.background,
                                actionIconContentColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                scrollBehavior = topAppBarScrollBehavior,
                title = {
                    Text(text = stringResource(id = R.string.status_title))
                },
                navigationIcon = {
                    BackButton(onBack = onBack)
                },
            )
        },
        modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
    ) {
        RefreshContainer(
            onRefresh = onRefresh,
            isRefreshing = isRefreshing,
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
            indicatorPadding = it,
            content = {
                LazyStatusVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(1),
                    modifier =
                        Modifier
                            .let {
                                if (isBigScreen) {
                                    it
                                        .fillMaxHeight()
                                        .widthIn(max = 480.dp)
                                } else {
                                    it.fillMaxSize()
                                }
                            },
                    contentPadding = it,
                ) {
                    statusDetail(
                        state = state,
                        statusKey = statusKey,
                        tabs = { StatusDetailTabs(state) },
                        controlsDivider = { HorizontalDivider() },
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusDetailTabs(state: StatusContextPresenter.State) {
    val selectedIndex = state.tabs.indexOfFirst { it.id == state.selectedTabId }
    SecondaryScrollableTabRow(
        selectedTabIndex = selectedIndex.coerceAtLeast(0),
        containerColor = Color.Transparent,
        edgePadding = 4.dp,
        minTabWidth = 48.dp,
        divider = {},
        indicator = {
            if (selectedIndex >= 0) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(selectedIndex),
                    height = 2.dp,
                )
            }
        },
    ) {
        state.tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            Tab(
                selected = selected,
                onClick = { state.selectTab(tab.id) },
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = {
                    FlareText(
                        text = tab.title,
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                    )
                },
            )
        }
    }
}

@Composable
private fun statusPresenter(
    statusKey: MicroBlogKey,
    accountType: AccountType,
) = run {
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val state =
        remember(accountType, statusKey) {
            StatusContextPresenter(accountType = accountType, statusKey = statusKey)
        }.invoke()

    object {
        val state = state
        val isRefreshing = isRefreshing

        fun refresh() {
            scope.launch {
                isRefreshing = true
                state.refresh()
                isRefreshing = false
            }
        }
    }
}
