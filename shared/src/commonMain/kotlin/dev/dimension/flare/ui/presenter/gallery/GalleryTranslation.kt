package dev.dimension.flare.ui.presenter.gallery

import dev.dimension.flare.data.datasource.microblog.datasource.GalleryDetail
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.RenderRun
import dev.dimension.flare.ui.render.UiRichText
import dev.dimension.flare.ui.render.uiRichTextOf
import kotlinx.collections.immutable.toImmutableList

internal fun GalleryDetail.withTranslation(showOriginal: Boolean): GalleryDetail {
    val translated = translation?.takeIf { it.state == TranslationDisplayState.Translated } ?: return this
    return copy(
        title =
            translated.title?.let {
                if (showOriginal && title.isNotBlank() && title != it) "$title\n$it" else it
            } ?: title,
        content =
            translated.content?.withOriginalTagLabels(content)?.let {
                val original = content
                if (showOriginal && original != null && !original.isEmpty && original != it) {
                    uiRichTextOf(
                        renderRuns = original.renderRuns + it.renderRuns,
                        raw = "${original.raw}\n\n${it.raw}",
                        innerText = "${original.innerText}\n\n${it.innerText}",
                    )
                } else {
                    it
                }
            } ?: content,
    )
}

private fun UiRichText.withOriginalTagLabels(original: UiRichText?): UiRichText {
    val tags =
        original
            ?.renderRuns
            ?.filterIsInstance<RenderContent.Text>()
            ?.flatMap { it.runs }
            ?.filterIsInstance<RenderRun.Text>()
            ?.filter { it.text.startsWith('#') && !it.style.link.isNullOrBlank() }
            ?.associate { it.style.link to it.text }
            .orEmpty()
    if (tags.isEmpty()) return this
    // Existing post caches can contain partially translated tag names.
    val contents =
        renderRuns.map { block ->
            if (block is RenderContent.Text) {
                block.copy(
                    runs =
                        block.runs
                            .map { run ->
                                if (run is RenderRun.Text) tags[run.style.link]?.let { run.copy(text = it) } ?: run else run
                            }.toImmutableList(),
                )
            } else {
                block
            }
        }
    return if (contents == renderRuns) this else uiRichTextOf(contents)
}
