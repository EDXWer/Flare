package dev.dimension.flare.ui.component.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dimension.flare.ui.theme.PlatformContentColor
import dev.dimension.flare.ui.theme.PlatformTheme
import dev.dimension.flare.ui.theme.isLightTheme

public val LocalStatusBackgroundColor: ProvidableCompositionLocal<Color?> = staticCompositionLocalOf { null }

public object StatusComponentDefaults {
    public val horizontalPadding: Dp = 10.dp
    public val avatarSize: Dp = 48.dp
    public val quotePadding: Dp = 12.dp
    public val topPadding: Dp = 12.dp
    internal val attachmentSpacing: Dp = 10.dp
    internal val actionsSpacing: Dp = 2.dp

    internal val contentColor: Color
        @Composable
        get() =
            if (usesDarkPostColors) {
                Color(0xFFD9D9D9)
            } else {
                PlatformContentColor.current
            }

    internal val secondaryColor: Color
        @Composable
        get() =
            if (usesDarkPostColors && LocalStatusBackgroundColor.current == Color.Black) {
                Color(0xFF71767B)
            } else {
                PlatformTheme.colorScheme.caption
            }

    private val usesDarkPostColors: Boolean
        @Composable
        get() {
            val background = LocalStatusBackgroundColor.current ?: return false
            return !isLightTheme() && background.alpha == 1f &&
                background.red <= 0.5f && background.green <= 0.5f && background.blue <= 0.5f &&
                background != PlatformTheme.colorScheme.primaryContainer
        }
}
