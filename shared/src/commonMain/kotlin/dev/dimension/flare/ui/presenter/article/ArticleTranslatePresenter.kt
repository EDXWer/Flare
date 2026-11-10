package dev.dimension.flare.ui.presenter.article

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
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.translation.ArticleTranslationService
import dev.dimension.flare.data.translation.PreTranslationContentRules
import dev.dimension.flare.data.translation.withOriginal
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.presenter.PresenterBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

public class ArticleTranslatePresenter(
    private val source: UiArticle,
    private val targetLanguage: String = Locale.language,
) : PresenterBase<ArticleTranslatePresenter.State>() {
    private val translationService: ArticleTranslationService by koinInject()
    private val appDataStore: AppDataStore by koinInject()
    private var config: Flow<AppSettings.TranslateConfig>? = null
    private var translate: suspend (
        UiArticle,
        String,
    ) -> UiArticle = { article, language -> translationService.translate(article, language) }

    internal constructor(
        source: UiArticle,
        targetLanguage: String,
        config: Flow<AppSettings.TranslateConfig>,
        translate: suspend (UiArticle, String) -> UiArticle,
    ) : this(source, targetLanguage) {
        this.config = config
        this.translate = translate
    }

    @Immutable
    public interface State {
        public val article: UiArticle
        public val showTranslation: Boolean
        public val isTranslating: Boolean
        public val error: Throwable?

        public fun toggleTranslation()

        public fun retry()
    }

    @Composable
    override fun body(): State {
        val settings by remember { config ?: appDataStore.appSettingsStore.data.map { it.translateConfig } }
            .collectAsState(AppSettings.TranslateConfig())
        var translationOverride by remember { mutableStateOf<Boolean?>(null) }
        var refreshKey by remember { mutableIntStateOf(0) }
        val autoTranslate =
            settings.preTranslate &&
                !PreTranslationContentRules.shouldSkipForMatchingSourceLanguage(source.sourceLanguages, targetLanguage) &&
                !PreTranslationContentRules.shouldSkipForExcludedSourceLanguage(
                    source.sourceLanguages,
                    settings.autoTranslateExcludedLanguages,
                )
        val showTranslation by rememberUpdatedState(translationOverride ?: autoTranslate)
        val result by key(source, targetLanguage, settings.provider, refreshKey) {
            produceState<UiState<UiArticle>?>(null) {
                snapshotFlow { showTranslation }.first { it }
                value =
                    try {
                        val translated = translate(source, targetLanguage)
                        currentCoroutineContext().ensureActive()
                        UiState.Success(translated)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        currentCoroutineContext().ensureActive()
                        UiState.Error(error)
                    }
            }
        }
        val error = (result as? UiState.Error)?.throwable
        val translated = (result as? UiState.Success)?.data.takeIf { showTranslation }
        val displayArticle =
            remember(source, translated, settings.showOriginalWithTranslation) {
                translated?.let {
                    if (settings.showOriginalWithTranslation) it.withOriginal(source) else it
                } ?: source
            }
        return object : State {
            override val article = displayArticle
            override val showTranslation = showTranslation
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
