package dev.dimension.flare.data.translation

import dev.dimension.flare.common.OnDeviceAI
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.network.ai.AiCompletionService
import dev.dimension.flare.data.network.ai.OpenAIService
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.ui.model.UiState
import kotlinx.coroutines.test.runTest
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

class HtmlArticleTranslationServiceTest {
    private lateinit var root: Path

    @BeforeTest
    fun setup() {
        root = createTestRootPath()
    }

    @AfterTest
    fun cleanup() {
        deleteTestRootPath(root)
    }

    @Test
    fun emptyProviderResponseIsAnErrorInsteadOfUntranslatedSuccess() =
        runTest {
            val store = AppDataStore(OkioFileStorage(createTestFileSystem(), root))
            store.appSettingsStore.updateData {
                it.copy(
                    aiConfig = it.aiConfig.copy(type = AppSettings.AiConfig.Type.OnDevice),
                    translateConfig = AppSettings.TranslateConfig(provider = AppSettings.TranslateConfig.Provider.AI),
                )
            }
            val unavailableAI =
                object : OnDeviceAI {
                    override suspend fun isAvailable(): Boolean = false

                    override suspend fun translate(
                        source: String,
                        targetLanguage: String,
                        prompt: String,
                    ): String? = null

                    override suspend fun tldr(
                        source: String,
                        targetLanguage: String,
                        prompt: String,
                    ): String? = null
                }
            val service = HtmlArticleTranslationService(store, AiCompletionService(OpenAIService(), unavailableAI))
            val (html, title) = service.translate("<p>Original article</p>", "Original title", "zh-Hans")
            assertIs<UiState.Error<String>>(html)
            assertIs<UiState.Error<String>>(title)
        }
}
