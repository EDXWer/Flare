package dev.dimension.flare.ui.presenter.home.rss

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.ui.model.UiState
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
class RssDetailTranslatePresenterTest {
    @Test
    fun automaticTranslationCanReturnToOriginalAndReuseItsResult() =
        runTest {
            var requests = 0
            val presenter =
                RssDetailTranslatePresenter(
                    htmlContent = "<p>Original article</p>",
                    title = "Original title",
                    targetLanguage = "zh-Hans",
                    sourceLanguage = "en",
                    config = MutableStateFlow(AppSettings.TranslateConfig(preTranslate = true)),
                    translate = { _, _, _ ->
                        requests++
                        UiState.Success("<p>文章译文</p>") to UiState.Success("标题译文")
                    },
                )
            lateinit var state: RssDetailTranslatePresenter.State
            val emissions = mutableListOf<RssDetailTranslatePresenter.State>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { state = it }
            }
            runCurrent()
            assertTrue(state.showTranslation)
            assertEquals("<p>文章译文</p>", state.translatedHtml)

            state.toggleTranslation()
            runCurrent()
            assertFalse(state.showTranslation)
            assertEquals(null, state.translatedHtml)
            assertEquals(null, state.translatedTitle)

            state.toggleTranslation()
            runCurrent()
            assertEquals("<p>文章译文</p>", state.translatedHtml)
            assertEquals(1, requests)
        }

    @Test
    fun lateTranslationDoesNotOverrideOriginalChoice() =
        runTest {
            val observed =
                observe { _, _, _ ->
                    delay(1_000)
                    translated()
                }
            assertTrue(observed.state.isTranslating)
            observed.state.toggleTranslation()
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertFalse(observed.state.showTranslation)
            assertEquals("<p>Original article</p>", observed.state.displayHtml)
            observed.state.toggleTranslation()
            runCurrent()
            assertEquals("<p>文章译文</p>", observed.state.displayHtml)
        }

    @Test
    fun settingsChangesApplyWithoutRequestingAgain() =
        runTest {
            val config = MutableStateFlow(AppSettings.TranslateConfig())
            var requests = 0
            val observed =
                observe(config) { _, _, _ ->
                    requests++
                    translated()
                }
            assertFalse(observed.state.showTranslation)
            assertEquals(0, requests)
            config.value = config.value.copy(preTranslate = true)
            runCurrent()
            assertEquals("<p>文章译文</p>", observed.state.displayHtml)
            config.value = config.value.copy(showOriginalWithTranslation = true)
            runCurrent()
            assertEquals("<p>Original article</p><hr/><p>文章译文</p>", observed.state.displayHtml)
            config.value = config.value.copy(preTranslate = false)
            runCurrent()
            assertEquals("<p>Original article</p>", observed.state.displayHtml)
            assertEquals(1, requests)
        }

    @Test
    fun excludedOrMatchingLanguageCanStillBeTranslatedManually() =
        runTest {
            for (language in listOf("en-US", "zh-CN")) {
                var requests = 0
                val config =
                    MutableStateFlow(AppSettings.TranslateConfig(preTranslate = true, autoTranslateExcludedLanguages = listOf("en")))
                val observed =
                    observe(config, language) { _, _, _ ->
                        requests++
                        translated()
                    }
                assertFalse(observed.state.showTranslation)
                assertEquals(0, requests)
                observed.state.toggleTranslation()
                runCurrent()
                assertTrue(observed.state.showTranslation)
                assertEquals(1, requests)
            }
        }

    @Test
    fun failedTranslationCanBeRetried() =
        runTest {
            var requests = 0
            val observed =
                observe { _, _, _ ->
                    if (++requests == 1) {
                        UiState.Error<String>(IllegalStateException("Unavailable")) to
                            UiState.Error<String>(IllegalStateException("Unavailable"))
                    } else {
                        translated()
                    }
                }
            assertEquals("Unavailable", observed.state.error?.message)
            assertEquals("<p>Original article</p>", observed.state.displayHtml)
            observed.state.retry()
            runCurrent()
            assertEquals(null, observed.state.error)
            assertEquals("<p>文章译文</p>", observed.state.displayHtml)
            assertEquals(2, requests)
        }

    @Test
    fun responseFromPreviousProviderCannotReplaceCurrentTranslation() =
        runTest {
            val config = MutableStateFlow(AppSettings.TranslateConfig(preTranslate = true))
            var requests = 0
            val observed =
                observe(config) { _, _, _ ->
                    if (++requests == 1) {
                        withContext(NonCancellable) { delay(2_000) }
                        UiState.Success("stale") to UiState.Success("stale")
                    } else {
                        translated()
                    }
                }
            config.value = config.value.copy(provider = AppSettings.TranslateConfig.Provider.AI)
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals("<p>文章译文</p>", observed.state.displayHtml)
            assertEquals(2, requests)
        }

    @Test
    fun completedPreviousProviderNeverLeaksIntoTheNextProviderState() =
        runTest {
            val config = MutableStateFlow(AppSettings.TranslateConfig(preTranslate = true))
            var requests = 0
            val observed =
                observe(config) { _, _, _ ->
                    if (++requests == 1) {
                        translated()
                    } else {
                        delay(1_000)
                        UiState.Success("<p>新供应商</p>") to UiState.Success("新标题")
                    }
                }
            assertEquals("<p>文章译文</p>", observed.state.translatedHtml)
            val beforeChange = observed.emissions.size
            config.value = config.value.copy(provider = AppSettings.TranslateConfig.Provider.AI)
            runCurrent()
            assertTrue(observed.state.isTranslating)
            assertTrue(observed.emissions.drop(beforeChange).none { it.translatedHtml == "<p>文章译文</p>" })
        }

    @Test
    fun failedTranslationCanRetryByTogglingBackFromOriginal() =
        runTest {
            var requests = 0
            val observed =
                observe { _, _, _ ->
                    if (++requests == 1) {
                        UiState.Error<String>(IllegalStateException("Unavailable")) to
                            UiState.Error<String>(IllegalStateException("Unavailable"))
                    } else {
                        translated()
                    }
                }
            observed.state.toggleTranslation()
            runCurrent()
            assertFalse(observed.state.showTranslation)
            assertEquals(null, observed.state.error)
            observed.state.toggleTranslation()
            runCurrent()
            assertEquals("<p>文章译文</p>", observed.state.translatedHtml)
            assertEquals(2, requests)
        }

    private fun translated(): Pair<UiState<String>, UiState<String>> = UiState.Success("<p>文章译文</p>") to UiState.Success("标题译文")

    private fun TestScope.observe(
        config: MutableStateFlow<AppSettings.TranslateConfig> = MutableStateFlow(AppSettings.TranslateConfig(preTranslate = true)),
        sourceLanguage: String = "en",
        translate: suspend (String, String, String) -> Pair<UiState<String>, UiState<String>>,
    ): Observed {
        val presenter =
            RssDetailTranslatePresenter("<p>Original article</p>", "Original title", "zh-Hans", sourceLanguage, config, translate)
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
        lateinit var state: RssDetailTranslatePresenter.State
        val emissions = mutableListOf<RssDetailTranslatePresenter.State>()
    }
}
