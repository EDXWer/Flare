package dev.dimension.flare.ui.component.status

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.RichText
import dev.dimension.flare.ui.component.platform.PlatformText
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.theme.PlatformContentColor
import dev.dimension.flare.ui.theme.PlatformTheme
import kotlin.math.roundToInt

@Composable
internal fun StatusRetweetHeaderComponent(
    icon: ImageVector,
    user: UiProfile?,
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = PlatformTheme.typography.caption,
    isPost: Boolean = false,
    color: androidx.compose.ui.graphics.Color = PlatformContentColor.current,
    standalone: Boolean = false,
) {
    val density = LocalDensity.current
    val iconSize = if (isPost) with(density) { textStyle.fontSize.toDp() } else textStyle.fontSize.value.dp
    val halfCapHeight = with(density) { (textStyle.fontSize.toPx() * 0.35f).roundToInt() }
    val spacing = 8.dp
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FAIcon(
            icon,
            contentDescription = null,
            modifier =
                Modifier
                    .size(iconSize)
                    .then(if (isPost) Modifier.alignBy { it.measuredHeight / 2 + halfCapHeight } else Modifier),
            tint = color,
        )
        if (user != null) {
            Spacer(modifier = Modifier.width(spacing))
            val nameWidthModifier =
                if (standalone) {
                    Modifier.layout { measurable, constraints ->
                        val maxWidth =
                            if (constraints.hasBoundedWidth) {
                                ((constraints.maxWidth - spacing.roundToPx()) / 2).coerceAtLeast(constraints.minWidth)
                            } else {
                                constraints.maxWidth
                            }
                        val name = measurable.measure(constraints.copy(maxWidth = maxWidth))
                        layout(name.width, name.height) { name.placeRelative(0, 0) }
                    }
                } else {
                    Modifier.weight(1f, fill = false)
                }
            RichText(
                text = user.name,
                layoutDirection = LocalLayoutDirection.current,
                textStyle = if (isPost) textStyle.copy(fontWeight = FontWeight.ExtraBold) else textStyle,
                modifier =
                    Modifier
                        .alignByBaseline()
                        .then(nameWidthModifier),
                maxLines = 1,
                color = color,
            )
        }
        Spacer(modifier = Modifier.width(spacing))
        PlatformText(
            text = text,
            style = textStyle,
            modifier =
                Modifier.alignByBaseline(),
            color = color,
        )
    }
}
