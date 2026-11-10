package dev.dimension.flare.ui.presenter.gallery

import dev.dimension.flare.data.datasource.microblog.datasource.GalleryDetail
import dev.dimension.flare.data.datasource.microblog.datasource.GalleryOrientation
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.DeeplinkEvent
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.RenderRun
import dev.dimension.flare.ui.render.parseHtml
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Instant

class GalleryTranslationTest {
    @Test
    fun displaysCachedTitleAndCaptionWithoutReplacingMediaOrBookmark() {
        val source = detail()
        val displayed = source.withTranslation(false)
        assertEquals("翻译标题", displayed.title)
        assertSame(source.translation?.content, displayed.content)
        assertSame(source.images, displayed.images)
        assertSame(source.images.first(), displayed.images.first())
        assertSame(source.bookmarkAction, displayed.bookmarkAction)
        assertSame(source.matrix, displayed.matrix)
        assertIs<DeeplinkEvent.TranslationEvent.ShowOriginal>(displayed.translationEvent())
    }

    @Test
    fun originalModeKeepsCachedTranslationAndCanDisplayItAgain() {
        val source = detail()
        val original = source.copy(translation = source.translation!!.copy(state = TranslationDisplayState.Hidden))
        assertSame(original, original.withTranslation(false))
        assertEquals("原文标题", original.title)
        assertEquals("翻译标题", original.translation?.title)
        assertIs<DeeplinkEvent.TranslationEvent.Translate>(original.translationEvent())
        assertEquals("翻译标题", original.copy(translation = source.translation).withTranslation(false).title)
    }

    @Test
    fun bilingualModePreservesOriginalRichTextAndTagTargets() {
        val source = detail()
        val displayed = source.withTranslation(true)
        assertEquals("原文标题\n翻译标题", displayed.title)
        assertEquals(source.content!!.renderRuns + source.translation!!.content!!.renderRuns, displayed.content!!.renderRuns)
        val tag =
            displayed.content
                .renderRuns
                .filterIsInstance<RenderContent.Text>()
                .flatMap { it.runs }
                .filterIsInstance<RenderRun.Text>()
                .first { it.text == "#風景" }
        assertEquals("https://example.com/search?q=original", tag.style.link)
        assertEquals("原文标题", source.title)
    }

    @Test
    fun cachedTranslationKeepsCompleteOriginalTagLabelsAndSearchTargets() {
        for (tag in listOf("Hatsune Miku", "C++ / C#")) {
            val target = "https://example.com/search?q=original"
            val original = parseHtml("<p>Original caption</p><a href=\"$target\">#$tag</a>").toUi()
            val cached = parseHtml("<p>翻译说明</p><a href=\"$target\">#Hatsune 翻译后的标签</a>").toUi()
            val source = detail().let { it.copy(content = original, translation = it.translation!!.copy(content = cached)) }
            val displayed = source.withTranslation(false)
            val runs =
                displayed.content!!
                    .renderRuns
                    .filterIsInstance<RenderContent.Text>()
                    .flatMap { it.runs }
            val linkedTag = runs.filterIsInstance<RenderRun.Text>().single { it.style.link == target }
            assertEquals("#$tag", linkedTag.text)
            assertEquals(target, linkedTag.style.link)
            assertEquals(
                "翻译说明",
                (displayed.content.renderRuns.first() as RenderContent.Text)
                    .runs
                    .filterIsInstance<RenderRun.Text>()
                    .single()
                    .text,
            )
            assertSame(cached, source.translation?.content)
        }
    }

    @Test
    fun missingTranslatedFieldsFallBackToOriginal() {
        val source = detail().let { it.copy(translation = it.translation!!.copy(title = null, content = null)) }
        val displayed = source.withTranslation(false)
        assertEquals(source.title, displayed.title)
        assertSame(source.content, displayed.content)
    }

    @Test
    fun loadingAndFailureKeepOriginalAndExposeCorrectAction() {
        val source = detail()
        val loading = source.copy(translation = source.translation!!.copy(state = TranslationDisplayState.Translating))
        val failed = source.copy(translation = source.translation.copy(state = TranslationDisplayState.Failed))
        assertSame(loading, loading.withTranslation(false))
        assertIs<DeeplinkEvent.TranslationEvent.ShowOriginal>(loading.translationEvent())
        assertSame(failed, failed.withTranslation(false))
        assertIs<DeeplinkEvent.TranslationEvent.RetryTranslation>(failed.translationEvent())
    }

    @Test
    fun bookmarkRefreshKeepsTranslationAndMedia() {
        val source = detail()
        val refreshed = source.copy(isBookmarked = true)
        assertEquals("翻译标题", refreshed.withTranslation(false).title)
        assertSame(source.images, refreshed.withTranslation(false).images)
        assertEquals(true, refreshed.withTranslation(false).isBookmarked)
    }

    @Test
    fun pluginGalleryWithoutTranslationRemainsUnchangedAndDecodes() {
        val source = detail().copy(translation = null)
        val json = Json.encodeToString(source)
        val decoded = Json.decodeFromString<GalleryDetail>(json)
        assertEquals(null, decoded.translation)
        assertSame(source, source.withTranslation(true))
        assertEquals(ClickEvent.Noop, source.translationAction)
    }

    @Test
    fun translationMetadataSurvivesSerialization() {
        val source = detail()
        assertEquals(source, Json.decodeFromString<GalleryDetail>(Json.encodeToString(source)))
    }

    private fun GalleryDetail.translationEvent(): DeeplinkEvent.TranslationEvent? =
        DeeplinkEvent.parse(assertIs<ClickEvent.Deeplink>(translationAction).url)?.translationEvent

    private fun detail() =
        GalleryDetail(
            orientation = GalleryOrientation.Vertical,
            statusKey = MicroBlogKey("work", "pixiv.net"),
            accountType = AccountType.Specific(MicroBlogKey("viewer", "pixiv.net")),
            url = "https://www.pixiv.net/artworks/work",
            images = persistentListOf(UiMedia.Image("https://example.com/image.png")),
            title = "原文标题",
            author = null,
            createdAt = Instant.fromEpochMilliseconds(1).toUi(),
            content = parseHtml("<p>原文说明</p><a href=\"https://example.com/search?q=original\">#風景</a>").toUi(),
            isBookmarked = false,
            bookmarkAction = ClickEvent.Deeplink("https://example.com/bookmark"),
            matrix = persistentListOf(),
            translation =
                GalleryDetail.Translation(
                    title = "翻译标题",
                    content = "翻译说明".toUiPlainText(),
                    state = TranslationDisplayState.Translated,
                ),
        )
}
