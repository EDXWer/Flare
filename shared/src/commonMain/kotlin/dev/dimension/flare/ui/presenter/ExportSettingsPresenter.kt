package dev.dimension.flare.ui.presenter

import androidx.compose.runtime.Composable
import dev.dimension.flare.common.encodeJson
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.model.SettingsExport
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.di.koinInject
import kotlinx.coroutines.flow.first

public class ExportSettingsPresenter : PresenterBase<ExportState>() {
    private val settingsRepository: SettingsRepository by koinInject()

    @Composable
    override fun body(): ExportState =
        object : ExportState {
            override suspend fun export(): String = this@ExportSettingsPresenter.export()
        }

    public suspend fun export(): String {
        val export =
            SettingsExport(
                appearanceBag = settingsRepository.appearanceBag.first(),
                appSettings = settingsRepository.appSettings.first().withoutCustomAIHeaders(),
                tabSettingsV2 = settingsRepository.tabSettingsV2.first(),
            )
        return export.encodeJson(SettingsExport.serializer())
    }
}

private fun AppSettings.withoutCustomAIHeaders(): AppSettings =
    copy(
        aiConfig =
            aiConfig.copy(
                type = (aiConfig.type as? AppSettings.AiConfig.Type.OpenAI)?.copy(customHeaders = emptyList()) ?: aiConfig.type,
                agentType = aiConfig.agentType?.copy(customHeaders = emptyList()),
            ),
        imageTranslationConfig = imageTranslationConfig.copy(openAIHeaders = emptyList()),
    )
