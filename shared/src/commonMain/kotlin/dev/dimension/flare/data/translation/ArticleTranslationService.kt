package dev.dimension.flare.data.translation

import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.network.ai.AiCompletionService
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.uiArticleContentOf
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.RenderRun
import dev.dimension.flare.ui.render.applyTranslationJson
import dev.dimension.flare.ui.render.plainText
import dev.dimension.flare.ui.render.toTranslationDocument
import dev.dimension.flare.ui.render.toTranslationJson
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.render.uiRichTextOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

@Single
internal class ArticleTranslationService(
    private val appDataStore: AppDataStore,
    private val aiCompletionService: AiCompletionService,
) {
    suspend fun translate(
        article: UiArticle,
        targetLanguage: String,
    ): UiArticle {
        val settings = appDataStore.appSettingsStore.data.first()
        val fields =
            buildList {
                add(article.title.toArticleText())
                article.content.blocks.forEach { block ->
                    when (block) {
                        is UiArticleBlock.Text -> {
                            add(block.content)
                        }

                        is UiArticleBlock.Embed -> {
                            block.title?.let { add(it.toArticleText()) }
                            block.description?.let { add(it.toArticleText()) }
                        }

                        else -> {}
                    }
                }
            }
        val source = uiRichTextOf(fields)
        val document = source.toTranslationDocument(targetLanguage)
        if (document.blocks.isEmpty()) return article
        val promptTemplate = AiPlaceholderTranslationSupport.buildPromptTemplate(document)
        val response =
            TranslationProvider.translateDocumentJson(
                settings = settings,
                aiCompletionService = aiCompletionService,
                sourceTemplate = promptTemplate,
                sourceJson = source.toTranslationJson(targetLanguage),
                targetLanguage = targetLanguage,
                prompt = TranslationPromptFormatter.buildTranslatePrompt(settings, targetLanguage, promptTemplate),
            )
        val translated =
            source
                .applyTranslationJson(
                    TranslationResponseSanitizer.clean(requireNotNull(response) { "Translation returned empty response" }),
                ).renderRuns
                .iterator()

        fun nextText() = translated.next() as RenderContent.Text
        val title = nextText().plainText()
        val blocks =
            article.content.blocks.map { block ->
                when (block) {
                    is UiArticleBlock.Text -> {
                        block.copy(content = nextText())
                    }

                    is UiArticleBlock.Embed -> {
                        block.copy(
                            title = block.title?.let { nextText().plainText() },
                            description = block.description?.let { nextText().plainText() },
                        )
                    }

                    else -> {
                        block
                    }
                }
            }
        return article.copy(title = title, content = uiArticleContentOf(blocks))
    }
}

internal fun UiArticle.withOriginal(original: UiArticle): UiArticle =
    copy(
        title = bilingualText(original.title, title),
        content =
            uiArticleContentOf(
                content.blocks.mapIndexed { index, block ->
                    val source = original.content.blocks[index]
                    when {
                        block is UiArticleBlock.Text && source is UiArticleBlock.Text && block.content != source.content -> {
                            block.copy(
                                content =
                                    block.content.copy(
                                        runs = (source.content.runs + RenderRun.Text("\n\n") + block.content.runs).toImmutableList(),
                                    ),
                            )
                        }

                        block is UiArticleBlock.Embed && source is UiArticleBlock.Embed -> {
                            block.copy(
                                title = block.title?.let { bilingualText(source.title.orEmpty(), it) },
                                description = block.description?.let { bilingualText(source.description.orEmpty(), it) },
                            )
                        }

                        else -> {
                            block
                        }
                    }
                },
            ),
    )

private fun String.toArticleText(): RenderContent.Text = toUiPlainText().renderRuns.single() as RenderContent.Text

private fun bilingualText(
    original: String,
    translated: String,
): String = if (original.isNotBlank() && original != translated) "$original\n\n$translated" else translated
