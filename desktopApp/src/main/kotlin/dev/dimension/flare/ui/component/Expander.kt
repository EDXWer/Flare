package dev.dimension.flare.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.composefluent.FluentTheme
import io.github.composefluent.ProvideTextStyle
import io.github.composefluent.animation.FluentDuration
import io.github.composefluent.animation.FluentEasing
import io.github.composefluent.background.BackgroundSizing
import io.github.composefluent.background.Layer
import io.github.composefluent.component.ExpanderDefaults
import io.github.composefluent.component.ExpanderItemSeparator
import io.github.composefluent.component.FontIcon
import io.github.composefluent.component.FontIconPrimitive
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.scheme.PentaVisualScheme
import io.github.composefluent.scheme.VisualStateScheme
import io.github.composefluent.scheme.collectVisualState
import io.github.composefluent.surface.Card
import io.github.composefluent.surface.CardColor

private val ExpanderHeaderHeight = 62.dp

/**
 * Local replacements for `io.github.composefluent.component` expander components.
 *
 * Upstream `compose-fluent` v0.1.0 puts `Modifier.weight(1f)` on a spacer rather than the
 * `heading` + `caption` column in `ExpanderItemContent`, causing long or wrapped captions to
 * consume the entire row width and squeeze `trailing` / `dropdown` controls to `0px`.
 */
@Composable
internal fun Expander(
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    heading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = FluentTheme.shapes.control,
    icon: (@Composable () -> Unit)? = {},
    caption: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    expandContent: @Composable ColumnScope.() -> Unit = {},
) {
    Layer(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        backgroundSizing = BackgroundSizing.InnerBorderEdge,
        clipContent = true,
    ) {
        Column {
            val targetInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
            ExpanderItemContent(
                modifier =
                    Modifier
                        .padding(top = 1.dp)
                        .heightIn(min = ExpanderHeaderHeight)
                        .background(FluentTheme.colors.background.card.default)
                        .clickable(
                            interactionSource = targetInteractionSource,
                            indication = null,
                            enabled = enabled,
                            onClick = { onExpandedChanged(!expanded) },
                        ),
                icon = icon,
                heading = heading,
                caption = caption,
                trailing = trailing,
                dropdown = {
                    SubtleButton(
                        disabled = !enabled,
                        interaction = targetInteractionSource,
                        iconOnly = true,
                        onClick = { onExpandedChanged(!expanded) },
                    ) {
                        val degrees by animateFloatAsState(if (expanded) 180f else 0f)
                        FontIcon(
                            type = FontIconPrimitive.ChevronDown,
                            contentDescription = null,
                            modifier = Modifier.graphicsLayer { rotationZ = degrees },
                        )
                    }
                },
            )
            ExpanderItemSeparator(color = FluentTheme.colors.stroke.card.default)
            AnimatedVisibility(
                visible = expanded,
                enter =
                    expandVertically(
                        animationSpec = tween(FluentDuration.MediumDuration, easing = FluentEasing.FastInvokeEasing),
                    ),
                exit =
                    shrinkVertically(
                        animationSpec = tween(FluentDuration.QuickDuration, easing = FluentEasing.SoftDismissEasing),
                    ),
            ) {
                Column(Modifier.padding(bottom = 1.dp), content = expandContent)
            }
        }
    }
}

@Composable
internal fun ExpanderItem(
    heading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = FluentTheme.colors.background.card.secondary,
    icon: (@Composable () -> Unit)? = {},
    caption: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    dropdown: (@Composable () -> Unit)? = {},
) {
    ExpanderItemContent(
        modifier = modifier.background(color),
        icon = icon,
        heading = heading,
        caption = caption,
        trailing = trailing,
        dropdown = dropdown,
    )
}

@Composable
internal fun CardExpanderItem(
    onClick: () -> Unit,
    heading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FluentTheme.shapes.control,
    enabled: Boolean = true,
    cardColors: VisualStateScheme<CardColor> = ExpanderDefaults.cardExpanderItemColors(),
    captionTextColors: VisualStateScheme<Color> =
        PentaVisualScheme(
            default = FluentTheme.colors.text.text.secondary,
            hovered = FluentTheme.colors.text.text.secondary,
            pressed = FluentTheme.colors.text.text.tertiary,
            disabled = FluentTheme.colors.text.text.disabled,
        ),
    interactionSource: MutableInteractionSource? = null,
    icon: (@Composable () -> Unit)? = {},
    caption: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    dropdown: (@Composable () -> Unit)? = null,
) {
    val targetInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val captionColor = captionTextColors.schemeFor(targetInteractionSource.collectVisualState(!enabled))
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        disabled = !enabled,
        cardColors = cardColors,
        interactionSource = targetInteractionSource,
    ) {
        ExpanderItemContent(
            modifier = Modifier.heightIn(min = ExpanderHeaderHeight),
            captionColor = captionColor,
            icon = icon,
            heading = heading,
            caption = caption,
            trailing = trailing,
            dropdown = dropdown,
        )
    }
}

@Composable
internal fun CardExpanderItem(
    heading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FluentTheme.shapes.control,
    color: Color = FluentTheme.colors.background.card.default,
    contentColor: Color = FluentTheme.colors.text.text.primary,
    captionColor: Color = FluentTheme.colors.text.text.secondary,
    icon: (@Composable () -> Unit)? = {},
    caption: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    dropdown: (@Composable () -> Unit)? = null,
) {
    Layer(
        modifier = modifier,
        shape = shape,
        color = color,
        contentColor = contentColor,
        backgroundSizing = BackgroundSizing.InnerBorderEdge,
    ) {
        ExpanderItemContent(
            modifier = Modifier.heightIn(min = ExpanderHeaderHeight),
            captionColor = captionColor,
            icon = icon,
            heading = heading,
            caption = caption,
            trailing = trailing,
            dropdown = dropdown,
        )
    }
}

@Composable
internal fun ExpanderItemContent(
    modifier: Modifier = Modifier,
    captionColor: Color = FluentTheme.colors.text.text.secondary,
    icon: (@Composable () -> Unit)? = {},
    heading: @Composable () -> Unit = {},
    caption: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    dropdown: (@Composable () -> Unit)? = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.widthIn(min = 48.dp).defaultMinSize(minWidth = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(top = 13.dp, bottom = 13.dp, end = 12.dp),
        ) {
            heading()
            ProvideTextStyle(
                FluentTheme.typography.caption.copy(color = captionColor),
            ) {
                caption()
            }
        }
        Row(
            modifier = Modifier.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            trailing()
            if (dropdown != null) {
                Box(
                    modifier = Modifier.padding(start = 4.dp).defaultMinSize(minWidth = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    dropdown()
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}
