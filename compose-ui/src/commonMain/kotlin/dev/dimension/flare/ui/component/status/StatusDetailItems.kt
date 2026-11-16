package dev.dimension.flare.ui.component.status

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Check
import compose.icons.fontawesomeicons.solid.ChevronDown
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.status_detail_filter_empty
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.Text
import dev.dimension.flare.ui.component.platform.PlatformDropdownMenu
import dev.dimension.flare.ui.component.platform.PlatformDropdownMenuItem
import dev.dimension.flare.ui.component.platform.PlatformTextButton
import dev.dimension.flare.ui.presenter.status.StatusContextPresenter
import dev.dimension.flare.ui.theme.PlatformTheme
import org.jetbrains.compose.resources.stringResource

public fun LazyStaggeredGridScope.statusDetail(
    state: StatusContextPresenter.State,
    statusKey: MicroBlogKey,
    tabs: @Composable () -> Unit = { StatusDetailTabs(state) },
    controlsDivider: @Composable () -> Unit = {},
) {
    state.header.forEachIndexed { index, post ->
        item(key = post.stableItemKey) {
            AdaptiveCard(index = index, totalCount = state.header.size, respectTimelineMode = true) {
                StatusItem(post, detailStatusKey = statusKey)
            }
        }
    }
    // Keep the controls from becoming the scroll anchor before the post arrives.
    if (state.header.isNotEmpty() && (state.tabs.size > 1 || state.filters.isNotEmpty())) {
        item(key = "status_detail_controls", span = StaggeredGridItemSpan.FullLine) {
            Column(Modifier.fillMaxWidth()) {
                StatusDetailControls(state, tabs)
                controlsDivider()
            }
        }
    }
    val children = state.listState
    when {
        children !is PagingState.Empty -> {
            status(children, detailStatusKey = statusKey)
        }

        state.selectedFilterId != state.defaultFilterId -> {
            item(span = StaggeredGridItemSpan.FullLine) {
                ListEmptyView(Modifier.fillMaxWidth(), text = stringResource(Res.string.status_detail_filter_empty))
            }
        }

        state.tabs.isNotEmpty() || state.header.isEmpty() -> {
            status(children, detailStatusKey = statusKey)
        }
    }
}

@Composable
private fun StatusDetailControls(
    state: StatusContextPresenter.State,
    tabs: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (state.tabs.size > 1) tabs()
        }
        val filter = state.filters.firstOrNull { it.id == state.selectedFilterId }
        if (filter != null) {
            var expanded by remember(state.selectedTabId) { mutableStateOf(false) }
            Box(Modifier.padding(horizontal = 8.dp)) {
                PlatformTextButton(onClick = { expanded = true }, modifier = Modifier.testTag("status-detail-filter")) {
                    Text(filter.title, modifier = Modifier.widthIn(max = 112.dp))
                    FAIcon(FontAwesomeIcons.Solid.ChevronDown, null, modifier = Modifier.padding(start = 6.dp).size(10.dp))
                }
                PlatformDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    state.filters.forEach { option ->
                        PlatformDropdownMenuItem(
                            text = { Text(option.title) },
                            onClick = {
                                state.selectFilter(option.id)
                                expanded = false
                            },
                            trailingIcon =
                                if (option.id == filter.id) {
                                    { FAIcon(FontAwesomeIcons.Solid.Check, null) }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDetailTabs(state: StatusContextPresenter.State) {
    Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup()) {
        state.tabs.forEach { tab ->
            val selected = tab.id == state.selectedTabId
            Box(
                Modifier.height(48.dp).selectable(selected, role = Role.Tab) { state.selectTab(tab.id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(tab.title, modifier = Modifier.padding(horizontal = 16.dp))
                if (selected) {
                    Spacer(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .size(width = 24.dp, height = 3.dp)
                            .background(PlatformTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}
