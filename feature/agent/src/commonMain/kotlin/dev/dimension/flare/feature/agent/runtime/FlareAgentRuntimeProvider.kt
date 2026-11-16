package dev.dimension.flare.feature.agent.runtime

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.message.Message
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.network.ktorClient
import dev.dimension.flare.data.repository.SettingsRepository
import io.ktor.util.PlatformUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single
internal class FlareAgentRuntimeProvider(
    private val settingsRepository: SettingsRepository,
) {
    private val bridge = AiConfigKoogBridge()
    private val httpClientFactory: KoogHttpClient.Factory by lazy {
        KtorKoogHttpClient.Factory(
            baseClient = ktorClient(protectRequestHeaders = true, config = {}),
        )
    }
    private val browserCustomHeadersFactory: KoogHttpClient.Factory by lazy {
        KtorKoogHttpClient.Factory(
            baseClient = ktorClient(protectRequestHeaders = true) { followRedirects = false },
        )
    }

    val availability: Flow<AgentAvailability> =
        settingsRepository
            .appSettings
            .map { settings -> bridge.availability(settings.aiConfig) }
            .distinctUntilChanged()

    suspend fun availability(): AgentAvailability =
        bridge.availability(
            settingsRepository
                .appSettings
                .first()
                .aiConfig,
        )

    suspend fun createRuntime(retryToolCalls: List<Message.Assistant> = emptyList()): FlareAgentRuntime? {
        val config = settingsRepository.appSettings.first().aiConfig
        val connection = config.effectiveAgentType as? AppSettings.AiConfig.Type.OpenAI
        val factory =
            if (PlatformUtils.IS_BROWSER && connection?.customHeaders?.isNotEmpty() == true) {
                browserCustomHeadersFactory
            } else {
                httpClientFactory
            }
        return bridge.createRuntime(
            aiConfig = config,
            httpClientFactory = factory,
            retryToolCalls = retryToolCalls,
        )
    }
}
