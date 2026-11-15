package dev.dimension.flare.ui.component.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import dev.dimension.flare.ui.component.status.LocalStatusBackgroundColor
import io.github.composefluent.FluentTheme
import io.github.composefluent.surface.Card
import io.github.composefluent.surface.CardDefaults

@Composable
internal actual fun PlatformCard(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    shape: Shape?,
    elevated: Boolean,
    containerColor: Color?,
    content: @Composable () -> Unit,
) {
    val color =
        if (containerColor != null) {
            CardDefaults
                .cardColors()
                .let {
                    it.copy(
                        default =
                            it.default.copy(
                                fillColor = containerColor,
                            ),
                    )
                }
        } else {
            CardDefaults.cardColors()
        }
    if (onClick == null) {
        Card(
            modifier = modifier,
            content = {
                CompositionLocalProvider(
                    LocalStatusBackgroundColor provides
                        FluentTheme.colors.background.layer.default.compositeOver(
                            LocalStatusBackgroundColor.current ?: FluentTheme.colors.background.mica.base,
                        ),
                ) {
                    content()
                }
            },
            shape = shape ?: FluentTheme.shapes.overlay,
        )
    } else {
        Card(
            modifier = modifier,
            content = content,
            shape = shape ?: FluentTheme.shapes.overlay,
            onClick = { onClick.invoke() },
            cardColors = color,
        )
    }
}
