package dev.dimension.flare.ui.presenter.article

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.uiArticleContentOf
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.RenderRun
import dev.dimension.flare.ui.render.plainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleTranslatePresenterTest : RobolectricTest() {
    private val source = article("Original")
    private val translated = article("译文")

    @Test
    fun automaticTranslationCanSwitchToOriginalAndReuseTheResult() =
        runTest {
            var requests = 0
            val observed =
                observe { _, _ ->
                    requests++
                    translated
                }
            assertEquals(translated, observed.state.article)
            observed.state.toggleTranslation()
            runCurrent()
            assertEquals(source, observed.state.article)
            assertFalse(observed.state.showTranslation)
            observed.state.toggleTranslation()
            runCurrent()
            assertEquals(translated, observed.state.article)
            assertEquals(1, requests)
        }

    @Test
    fun lateTranslationKeepsTheOriginalChoice() =
        runTest {
            val observed =
                observe { _, _ ->
                    delay(1_000)
                    translated
                }
            assertTrue(observed.state.isTranslating)
            observed.state.toggleTranslation()
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(source, observed.state.article)
            assertFalse(observed.state.isTranslating)
            observed.state.toggleTranslation()
            runCurrent()
            assertEquals(translated, observed.state.article)
        }

    @Test
    fun liveSettingsAndBilingualModeDoNotRepeatTranslation() =
        runTest {
            val config = MutableStateFlow(AppSettings.TranslateConfig())
            var requests = 0
            val observed =
                observe(config) { _, _ ->
                    requests++
                    translated
                }
            assertEquals(0, requests)
            config.value = config.value.copy(preTranslate = true)
            runCurrent()
            assertEquals(translated, observed.state.article)
            config.value = config.value.copy(showOriginalWithTranslation = true)
            runCurrent()
            assertEquals("Original\n\n译文", observed.state.article.title)
            assertEquals(
                "Original body\n\n译文 body",
                (
                    observed.state.article.content.blocks
                        .single() as UiArticleBlock.Text
                ).content.plainText(),
            )
            config.value = config.value.copy(preTranslate = false)
            runCurrent()
            assertEquals(source, observed.state.article)
            assertEquals(1, requests)
        }

    @Test
    fun matchingAndExcludedLanguagesStillAllowManualTranslation() =
        runTest {
            for (language in listOf("en-US", "zh-CN")) {
                var requests = 0
                val config =
                    MutableStateFlow(AppSettings.TranslateConfig(preTranslate = true, autoTranslateExcludedLanguages = listOf("en")))
                val observed =
                    observe(config, source.copy(sourceLanguages = persistentListOf(language))) { _, _ ->
                        requests++
                        translated
                    }
                assertEquals(0, requests)
                assertFalse(observed.state.showTranslation)
                observed.state.toggleTranslation()
                runCurrent()
                assertEquals(translated, observed.state.article)
                assertEquals(1, requests)
            }
        }

    @Test
    fun failureKeepsOriginalAndCanBeRetried() =
        runTest {
            var requests = 0
            val observed =
                observe { _, _ ->
                    if (++requests == 1) error("Unavailable") else translated
                }
            assertEquals("Unavailable", observed.state.error?.message)
            assertEquals(source, observed.state.article)
            observed.state.retry()
            runCurrent()
            assertEquals(null, observed.state.error)
            assertEquals(translated, observed.state.article)
            assertEquals(2, requests)
        }

    @Test
    fun oldProviderResponseCannotReplaceCurrentTranslation() =
        runTest {
            val config = MutableStateFlow(AppSettings.TranslateConfig(preTranslate = true))
            var requests = 0
            val observed =
                observe(config) { _, _ ->
                    if (++requests == 1) {
                        withContext(NonCancellable) { delay(2_000) }
                        article("Stale")
                    } else {
                        translated
                    }
                }
            config.value = config.value.copy(provider = AppSettings.TranslateConfig.Provider.AI)
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(translated, observed.state.article)
            assertEquals(2, requests)
        }

    @Test
    fun completedPreviousProviderNeverLeaksIntoTheNextProviderState() =
        runTest {
            val config = MutableStateFlow(AppSettings.TranslateConfig(preTranslate = true))
            var requests = 0
            val observed =
                observe(config) { _, _ ->
                    if (++requests == 1) {
                        translated
                    } else {
                        delay(1_000)
                        article("New provider")
                    }
                }
            assertEquals(translated, observed.state.article)
            val beforeChange = observed.emissions.size
            config.value = config.value.copy(provider = AppSettings.TranslateConfig.Provider.AI)
            runCurrent()
            assertTrue(observed.state.isTranslating)
            assertTrue(observed.emissions.drop(beforeChange).none { it.article == translated })
        }

    @Test
    fun failedTranslationCanRetryByTogglingBackFromOriginal() =
        runTest {
            var requests = 0
            val observed =
                observe { _, _ ->
                    if (++requests == 1) error("Unavailable") else translated
                }
            observed.state.toggleTranslation()
            runCurrent()
            assertFalse(observed.state.showTranslation)
            assertEquals(null, observed.state.error)
            observed.state.toggleTranslation()
            runCurrent()
            assertEquals(translated, observed.state.article)
            assertEquals(2, requests)
        }

    private fun TestScope.observe(
        config: MutableStateFlow<AppSettings.TranslateConfig> = MutableStateFlow(AppSettings.TranslateConfig(preTranslate = true)),
        article: UiArticle = source,
        translate: suspend (UiArticle, String) -> UiArticle,
    ): Observed {
        val presenter = ArticleTranslatePresenter(article, "zh-Hans", config, translate)
        val observed = Observed()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect {
                observed.state = it
                observed.emissions.add(it)
            }
        }
        runCurrent()
        return observed
    }

    private class Observed {
        lateinit var state: ArticleTranslatePresenter.State
        val emissions = mutableListOf<ArticleTranslatePresenter.State>()
    }

    private fun article(title: String): UiArticle =
        UiArticle(
            key = "article",
            title = title,
            content =
                uiArticleContentOf(
                    listOf(UiArticleBlock.Text("body", RenderContent.Text(persistentListOf(RenderRun.Text("$title body"))))),
                ),
        )
}
