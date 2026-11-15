package dev.dimension.flare.data.datasource.mastodon

import dev.dimension.flare.data.network.mastodon.api.InstanceResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class MastodonQuoteSupport(
    private val resources: InstanceResources,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private var supported: Boolean? = null
    private var retryAfter: TimeMark? = null

    suspend fun isAvailable(): Boolean =
        mutex.withLock {
            supported?.let { return@withLock it }
            if (retryAfter?.hasNotPassedNow() == true) return@withLock false

            val instance =
                try {
                    withTimeoutOrNull(5.seconds) { resources.instance() }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            if (instance == null) {
                retryAfter = clock.markNow() + 30.seconds
                false
            } else {
                ((instance.apiVersions?.mastodon ?: 0) >= 7).also { supported = it }
            }
        }
}
