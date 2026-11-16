package dev.dimension.flare.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal expect object PlatformTypography {
    @get:Composable
    val caption: TextStyle

    @get:Composable
    val title: TextStyle

    @get:Composable
    val headline: TextStyle

    @get:Composable
    val h1: TextStyle

    @get:Composable
    val h2: TextStyle

    @get:Composable
    val h3: TextStyle

    @get:Composable
    val h4: TextStyle

    @get:Composable
    val h5: TextStyle

    @get:Composable
    val h6: TextStyle

    @get:Composable
    val body: TextStyle

    @get:Composable
    val postBody: TextStyle
}

internal val PlatformTypography.profileName: TextStyle
    @Composable get() =
        title.run {
            copy(
                fontSize = (fontSize.value + 4).sp,
                fontWeight = FontWeight.Bold,
            )
        }
