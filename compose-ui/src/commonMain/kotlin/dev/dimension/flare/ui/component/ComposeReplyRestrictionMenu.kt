package dev.dimension.flare.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.At
import compose.icons.fontawesomeicons.solid.Check
import compose.icons.fontawesomeicons.solid.CircleCheck
import compose.icons.fontawesomeicons.solid.Globe
import compose.icons.fontawesomeicons.solid.Users
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.compose_x_reply_everyone
import dev.dimension.flare.compose.ui.compose_x_reply_following
import dev.dimension.flare.compose.ui.compose_x_reply_mentioned
import dev.dimension.flare.compose.ui.compose_x_reply_restriction
import dev.dimension.flare.compose.ui.compose_x_reply_verified
import dev.dimension.flare.data.datasource.microblog.ComposeReplyRestriction
import dev.dimension.flare.ui.component.platform.PlatformDropdownMenu
import dev.dimension.flare.ui.component.platform.PlatformDropdownMenuItem
import dev.dimension.flare.ui.component.platform.PlatformIconButton
import dev.dimension.flare.ui.component.platform.PlatformText
import dev.dimension.flare.ui.theme.PlatformTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
public fun ComposeReplyRestrictionMenu(
    restriction: ComposeReplyRestriction,
    onSelect: (ComposeReplyRestriction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val title = stringResource(Res.string.compose_x_reply_restriction)
    val current = stringResource(restriction.labelRes)

    Box(modifier = modifier) {
        PlatformIconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { stateDescription = current },
        ) {
            FAIcon(imageVector = restriction.icon, contentDescription = title)
        }
        PlatformDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlatformText(
                text = title,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = PlatformTheme.typography.caption,
                color = PlatformTheme.colorScheme.caption,
            )
            ComposeReplyRestriction.entries.forEach { option ->
                val isSelected = option == restriction
                PlatformDropdownMenuItem(
                    text = { PlatformText(text = stringResource(option.labelRes)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    modifier = Modifier.semantics { selected = isSelected },
                    leadingIcon = { FAIcon(imageVector = option.icon, contentDescription = null) },
                    trailingIcon = {
                        if (isSelected) {
                            FAIcon(imageVector = FontAwesomeIcons.Solid.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

private val ComposeReplyRestriction.labelRes: StringResource
    get() =
        when (this) {
            ComposeReplyRestriction.Everyone -> Res.string.compose_x_reply_everyone
            ComposeReplyRestriction.Following -> Res.string.compose_x_reply_following
            ComposeReplyRestriction.Mentioned -> Res.string.compose_x_reply_mentioned
            ComposeReplyRestriction.Verified -> Res.string.compose_x_reply_verified
        }

private val ComposeReplyRestriction.icon: ImageVector
    get() =
        when (this) {
            ComposeReplyRestriction.Everyone -> FontAwesomeIcons.Solid.Globe
            ComposeReplyRestriction.Following -> FontAwesomeIcons.Solid.Users
            ComposeReplyRestriction.Mentioned -> FontAwesomeIcons.Solid.At
            ComposeReplyRestriction.Verified -> FontAwesomeIcons.Solid.CircleCheck
        }
