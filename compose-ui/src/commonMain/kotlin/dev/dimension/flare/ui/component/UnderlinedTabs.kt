package dev.dimension.flare.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.theme.PlatformTheme

@Composable
public fun UnderlinedTabs(
    titles: List<UiText>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState()).selectableGroup()) {
        titles.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            Box(
                Modifier.height(48.dp).selectable(selected, role = Role.Tab) { onTabSelected(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(title, modifier = Modifier.padding(horizontal = 16.dp))
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
