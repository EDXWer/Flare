package dev.dimension.flare.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
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

    val richTextLineHeightReduction: Float
}

internal fun TextStyle.withRichTextSpacing(): TextStyle {
    val adjustedLineHeight =
        if (lineHeight.isSp) {
            val minimum = if (fontSize.isSp) minOf(fontSize.value, lineHeight.value) else 0f
            (lineHeight.value - PlatformTypography.richTextLineHeightReduction).coerceAtLeast(minimum).sp
        } else {
            lineHeight
        }
    return copy(lineHeight = adjustedLineHeight, letterSpacing = 0.sp)
}

internal fun TextStyle.withWiderRichTextLineSpacing(): TextStyle =
    if (fontSize.isSp && lineHeight.isSp && lineHeight.value > fontSize.value) {
        copy(lineHeight = (fontSize.value + (lineHeight.value - fontSize.value) * 1.5f).sp)
    } else {
        this
    }
