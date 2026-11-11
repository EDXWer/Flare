package dev.dimension.flare.ui.presenter.home.rss

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.dimension.flare.common.Locale
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.data.translation.HtmlArticleTranslationService
import dev.dimension.flare.data.translation.canonicalTranslationLanguage
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.presenter.PresenterBase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Presenter that translates the HTML content of an RSS article
 * by translating individual text nodes in the HTML tree.
 * This preserves the original HTML structure (headings, lists, images, etc.)
 * while replacing the text content with translations.
 */
public class RssDetailTranslatePresenter(
    private val htmlContent: String,
    private val title: String,
    private val targetLanguage: String = Locale.language,
    private val sourceLanguage: String? = null,
) : PresenterBase<RssDetailTranslatePresenter.State>() {
    public constructor(
        htmlContent: String,
        title: String,
        sourceLanguage: String?,
    ) : this(htmlContent, title, Locale.language, sourceLanguage)

    private val translationService: HtmlArticleTranslationService by koinInject()
    private val settingsRepository: SettingsRepository by koinInject()
    private var config: Flow<AppSettings.TranslateConfig>? = null
    private var translate: suspend (String, String, String) -> Pair<UiState<String>, UiState<String>> =
        { html, title, language -> translationService.translate(html, title, language) }

    internal constructor(
        htmlContent: String,
        title: String,
        targetLanguage: String,
        sourceLanguage: String?,
        config: Flow<AppSettings.TranslateConfig>,
        translate: suspend (String, String, String) -> Pair<UiState<String>, UiState<String>>,
    ) : this(htmlContent, title, targetLanguage, sourceLanguage) {
        this.config = config
        this.translate = translate
    }

    @Immutable
    public interface State {
        public val translatedHtml: String?
        public val translatedTitle: String?
        public val displayHtml: String
        public val showTranslation: Boolean
        public val showOriginalWithTranslation: Boolean
        public val isTranslating: Boolean
        public val error: Throwable?

        public fun toggleTranslation()

        public fun retry()
    }

    @Composable
    override fun body(): State {
        val settings by remember { config ?: settingsRepository.appSettings.map { it.translateConfig } }
            .collectAsState(AppSettings.TranslateConfig())
        var translationOverride by remember { mutableStateOf<Boolean?>(null) }
        var refreshKey by remember { mutableIntStateOf(0) }
        val source = sourceLanguage?.let(::canonicalTranslationLanguage)
        val autoTranslate =
            settings.preTranslate &&
                (
                    source == null ||
                        (
                            source != canonicalTranslationLanguage(targetLanguage) &&
                                settings.autoTranslateExcludedLanguages.none { canonicalTranslationLanguage(it) == source }
                        )
                )
        val showTranslation by rememberUpdatedState(translationOverride ?: autoTranslate)
        val result by key(htmlContent, title, targetLanguage, settings.provider, refreshKey) {
            produceState<Pair<UiState<String>, UiState<String>>?>(null) {
                snapshotFlow { showTranslation }.first { it }
                val translated = translate(htmlContent, title, targetLanguage)
                currentCoroutineContext().ensureActive()
                value = translated
            }
        }
        val error = (result?.first as? UiState.Error)?.throwable ?: (result?.second as? UiState.Error)?.throwable
        return object : State {
            override val translatedHtml = (result?.first as? UiState.Success)?.data.takeIf { showTranslation }
            override val translatedTitle = (result?.second as? UiState.Success)?.data.takeIf { showTranslation }
            override val showTranslation = showTranslation
            override val showOriginalWithTranslation = showTranslation && settings.showOriginalWithTranslation
            override val displayHtml =
                translatedHtml?.let {
                    if (showOriginalWithTranslation && it != htmlContent) "$htmlContent<hr/>$it" else it
                } ?: htmlContent
            override val isTranslating = showTranslation && result == null
            override val error = error.takeIf { showTranslation }

            override fun toggleTranslation() {
                translationOverride = !showTranslation
                if (!showTranslation && error != null) refreshKey++
            }

            override fun retry() {
                translationOverride = true
                refreshKey++
            }
        }
    }
}
