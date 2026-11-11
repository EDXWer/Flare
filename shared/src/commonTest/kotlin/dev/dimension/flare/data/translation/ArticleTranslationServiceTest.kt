package dev.dimension.flare.data.translation

import dev.dimension.flare.common.OnDeviceAI
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.ai.AiCompletionService
import dev.dimension.flare.data.network.ai.OpenAIService
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiArticleContentGateReason
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.uiArticleContentOf
import dev.dimension.flare.ui.render.RenderBlockStyle
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.RenderRun
import dev.dimension.flare.ui.render.RenderTextStyle
import dev.dimension.flare.ui.render.plainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.test.runTest
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ArticleTranslationServiceTest {
    private lateinit var root: Path

    @BeforeTest fun setup() {
        root = createTestRootPath()
    }

    @AfterTest fun cleanup() {
        deleteTestRootPath(root)
    }

    @Test
    fun translatesRichTextAndEmbedLabelsWhilePreservingStructureAndMedia() =
        runTest {
            val source = article()
            val service = service { template -> template.replace("Original", "译文").replace("Example", "示例") }
            val translated = service.translate(source, "zh-Hans")
            assertEquals("译文 title", translated.title)
            assertEquals(source.copy(title = translated.title, content = translated.content), translated)
            assertEquals(source.content.blocks.map { it.key }, translated.content.blocks.map { it.key })
            val text = assertIs<UiArticleBlock.Text>(translated.content.blocks[0])
            val originalText = assertIs<UiArticleBlock.Text>(source.content.blocks[0])
            assertEquals(originalText.content.block, text.content.block)
            assertEquals("译文 paragraph示例 https://example.com original_code", text.content.plainText())
            assertEquals(
                originalText.content.runs.filterIsInstance<RenderRun.Text>().map {
                    it.style
                },
                text.content.runs
                    .filterIsInstance<RenderRun.Text>()
                    .map { it.style },
            )
            assertEquals(originalText.content.runs.last(), text.content.runs.last())
            for (index in listOf(1, 2, 3, 5)) assertEquals(source.content.blocks[index], translated.content.blocks[index])
            val embed = assertIs<UiArticleBlock.Embed>(translated.content.blocks[4])
            assertEquals("示例 embed", embed.title)
            assertEquals("译文 description", embed.description)
            assertEquals(
                assertIs<UiArticleBlock.Embed>(source.content.blocks[4]).copy(title = embed.title, description = embed.description),
                embed,
            )
            assertEquals("Original title", source.title)

            val bilingual = translated.withOriginal(source)
            assertEquals(source.content.blocks.size, bilingual.content.blocks.size)
            assertEquals("Original title\n\n译文 title", bilingual.title)
            assertEquals(
                originalText.content.plainText() + "\n\n" + text.content.plainText(),
                assertIs<UiArticleBlock.Text>(bilingual.content.blocks[0]).content.plainText(),
            )
            assertEquals("Example embed\n\n示例 embed", assertIs<UiArticleBlock.Embed>(bilingual.content.blocks[4]).title)
            for (index in listOf(1, 2, 3, 5)) assertEquals(source.content.blocks[index], bilingual.content.blocks[index])
        }

    @Test
    fun identicalTitleAndBodyKeepTheirOwnTranslations() =
        runTest {
            val source =
                article().copy(
                    title = "Same",
                    content =
                        uiArticleContentOf(
                            listOf(UiArticleBlock.Text("body", RenderContent.Text(persistentListOf(RenderRun.Text("Same"))))),
                        ),
                )
            val service =
                service {
                    """
                    <<<B0>>>
                    {{T0}}标题译文
                    <<<E0>>>
                    <<<B1>>>
                    {{T0}}正文译文
                    <<<E1>>>
                    """.trimIndent()
                }
            val translated = service.translate(source, "zh-Hans")
            assertEquals("标题译文", translated.title)
            assertEquals("正文译文", assertIs<UiArticleBlock.Text>(translated.content.blocks.single()).content.plainText())
        }

    @Test
    fun nullAndMalformedProviderResponsesAreErrors() =
        runTest {
            var response: String? = null
            val service = service { response }
            for (value in listOf(null, "invalid translation")) {
                response = value
                assertFailsWith<IllegalArgumentException> { service.translate(article(), "zh-Hans") }
            }
        }

    private suspend fun service(response: (String) -> String?): ArticleTranslationService {
        val store = AppDataStore(OkioFileStorage(createTestFileSystem(), root))
        store.appSettingsStore.updateData {
            it.copy(
                aiConfig = it.aiConfig.copy(type = AppSettings.AiConfig.Type.OnDevice),
                translateConfig = AppSettings.TranslateConfig(provider = AppSettings.TranslateConfig.Provider.AI),
            )
        }
        val ai =
            object : OnDeviceAI {
                override suspend fun isAvailable(): Boolean = true

                override suspend fun translate(
                    source: String,
                    targetLanguage: String,
                    prompt: String,
                ): String? = response(source)

                override suspend fun tldr(
                    source: String,
                    targetLanguage: String,
                    prompt: String,
                ): String? = null
            }
        return ArticleTranslationService(store, AiCompletionService(OpenAIService(), ai))
    }

    private fun article(): UiArticle =
        UiArticle(
            key = "article",
            title = "Original title",
            sourceUrl = "https://example.com/article",
            sourceLanguages = persistentListOf("en"),
            cover = UiMedia.Image("https://example.com/cover.jpg"),
            content =
                uiArticleContentOf(
                    listOf(
                        UiArticleBlock.Text(
                            "text",
                            RenderContent.Text(
                                runs =
                                    persistentListOf(
                                        RenderRun.Text("Original paragraph", RenderTextStyle(bold = true)),
                                        RenderRun.Text("Example", RenderTextStyle(link = "https://example.com")),
                                        RenderRun.Text(" https://example.com "),
                                        RenderRun.Text("original_code", RenderTextStyle(code = true)),
                                        RenderRun.Image("https://example.com/emoji.png", ""),
                                    ),
                                block = RenderBlockStyle(headingLevel = 2, isBlockQuote = true),
                            ),
                        ),
                        UiArticleBlock.Image("image", UiMedia.Image("https://example.com/image.jpg")),
                        UiArticleBlock.Video("video", UiMedia.Video("https://example.com/video.mp4", "", "Video", 16f, 9f)),
                        UiArticleBlock.File(
                            "file",
                            "Original.pdf",
                            "https://example.com/file.pdf",
                            customHeaders = persistentMapOf("X-Test" to "test"),
                            proxy = ProxyRef.Global,
                        ),
                        UiArticleBlock.Embed(
                            "embed",
                            "https://example.com/embed",
                            "Example embed",
                            "Original description",
                            htmlFallback = "<iframe src='https://example.com/embed'></iframe>",
                        ),
                        UiArticleBlock.ContentGate(
                            "gate",
                            UiArticleContentGateReason.SubscriptionRequired("Fanbox", 500),
                            "https://example.com/subscribe",
                        ),
                    ),
                ),
        )
}
