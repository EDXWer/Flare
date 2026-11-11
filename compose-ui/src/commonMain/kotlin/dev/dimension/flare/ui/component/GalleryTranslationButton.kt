package dev.dimension.flare.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Language
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.status_detail_translate
import dev.dimension.flare.compose.ui.translation_retry
import dev.dimension.flare.compose.ui.translation_show_original
import dev.dimension.flare.data.datasource.microblog.datasource.GalleryDetail
import dev.dimension.flare.ui.component.platform.PlatformCircularProgressIndicator
import dev.dimension.flare.ui.component.platform.PlatformIconButton
import dev.dimension.flare.ui.model.ClickContext
import dev.dimension.flare.ui.model.TranslationDisplayState
import org.jetbrains.compose.resources.stringResource

@Composable
public fun GalleryTranslationButton(detail: GalleryDetail) {
    val translation = detail.translation ?: return
    val uriHandler = LocalUriHandler.current
    val loading = translation.state == TranslationDisplayState.Translating
    PlatformIconButton(
        onClick = { detail.onTranslate.invoke(ClickContext(uriHandler::openUri)) },
    ) {
        if (loading) {
            val description = stringResource(Res.string.translation_show_original)
            PlatformCircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = description })
        } else {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.Language,
                contentDescription =
                    stringResource(
                        when (translation.state) {
                            TranslationDisplayState.Translated -> Res.string.translation_show_original
                            TranslationDisplayState.Failed -> Res.string.translation_retry
                            TranslationDisplayState.Translating -> Res.string.translation_show_original
                            TranslationDisplayState.Hidden -> Res.string.status_detail_translate
                        },
                    ),
            )
        }
    }
}
