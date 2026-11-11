package dev.dimension.flare.ui.component

import androidx.compose.runtime.Composable
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Language
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.status_detail_translate
import dev.dimension.flare.compose.ui.translation_retry
import dev.dimension.flare.compose.ui.translation_show_original
import dev.dimension.flare.ui.component.platform.PlatformIconButton
import dev.dimension.flare.ui.component.platform.PlatformText
import dev.dimension.flare.ui.component.platform.PlatformTextButton
import org.jetbrains.compose.resources.stringResource

@Composable
public fun TranslationButton(
    showTranslation: Boolean,
    onClick: () -> Unit,
) {
    PlatformIconButton(onClick = onClick) {
        FAIcon(
            imageVector = FontAwesomeIcons.Solid.Language,
            contentDescription =
                stringResource(
                    if (showTranslation) Res.string.translation_show_original else Res.string.status_detail_translate,
                ),
        )
    }
}

@Composable
public fun TranslationRetryButton(onClick: () -> Unit) {
    PlatformTextButton(onClick = onClick) {
        PlatformText(stringResource(Res.string.translation_retry))
    }
}
