package dev.dimension.flare.feature.plugin.login

import dev.dimension.flare.feature.plugin.host.PluginUrlPolicy
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.manifest.LoginInteractionV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.wire.LoginBeginRequestV1
import dev.dimension.flare.feature.plugin.wire.LoginSuccessV1
import dev.dimension.flare.feature.plugin.wire.LoginTransitionV1
import dev.dimension.flare.feature.plugin.wire.QrPollRequestV2
import dev.dimension.flare.feature.plugin.wire.requireValid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class PluginQrLoginV2(
    private val runtimePool: PluginRuntimePool,
    private val plugin: RunningPluginV1,
    private val methodId: String,
    origin: String,
    locale: String,
    private val expectedAccountId: String?,
) {
    private val origin = PluginUrlPolicy.requireOrigin(origin)
    private val context = plugin.loginContext(this.origin, locale, expectedAccountId)

    suspend fun login(onCode: (content: String, scanned: Boolean) -> Unit): LoginSuccessV1? {
        require(plugin.installed.manifest.apiVersion >= 2) { "QR login requires Host API v2" }
        plugin.requireLoginMethod(methodId, LoginInteractionV1.QrCode)
        val flowId = PlatformPluginLoginEntropyV1.newFlowId()
        val key = plugin.loginKey(flowId)
        try {
            val start =
                coroutineScope {
                    runtimePool.invoke(
                        plugin = plugin,
                        key = key,
                        context = context,
                        method = "login.$methodId.begin",
                        request = LoginBeginRequestV1(methodId = methodId, origin = origin, flowId = flowId),
                        requestSerializer = LoginBeginRequestV1.serializer(),
                        responseSerializer = LoginTransitionV1.serializer(),
                        validate = LoginTransitionV1::requireValid,
                    )
                } as? LoginTransitionV1.QrCode ?: throw PluginLoginException("login.transition", "QR login did not return a code")
            return withTimeoutOrNull(start.expiresInMillis) {
                onCode(start.content, false)
                var pendingPayload = start.pendingPayload
                var scanned = false
                var result: LoginSuccessV1? = null
                while (true) {
                    delay(start.pollIntervalMillis)
                    val transition =
                        // The plugin can be reloaded between requests while the QR is visible.
                        coroutineScope {
                            runtimePool.invoke(
                                plugin = plugin,
                                key = key,
                                context = context,
                                method = "login.$methodId.poll",
                                request = QrPollRequestV2(methodId, origin, flowId, pendingPayload),
                                requestSerializer = QrPollRequestV2.serializer(),
                                responseSerializer = LoginTransitionV1.serializer(),
                                validate = LoginTransitionV1::requireValid,
                            )
                        }
                    when (transition) {
                        is LoginTransitionV1.QrPending -> {
                            pendingPayload = transition.pendingPayload ?: pendingPayload
                            if (scanned != transition.scanned) {
                                scanned = transition.scanned
                                onCode(start.content, scanned)
                            }
                        }

                        LoginTransitionV1.QrExpired -> {
                            break
                        }

                        is LoginTransitionV1.Success -> {
                            result = plugin.requireLoginSuccess(transition.value, origin, expectedAccountId)
                            break
                        }

                        else -> {
                            throw PluginLoginException("login.transition", "Unsupported QR login poll result")
                        }
                    }
                }
                result
            }
        } finally {
            withContext(NonCancellable) { runtimePool.close(key) }
        }
    }
}
