package dev.dimension.flare.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/** Playback events for App Log. Callers supply status values only. */
public object VideoPlaybackDiagnostics {
    private const val MAX_MESSAGES = 250
    private val entries = MutableStateFlow<List<String>>(emptyList())
    internal val messages = entries.asStateFlow()

    public fun record(message: String) {
        val line = "${Clock.System.now()} [VideoPlayback] ${LogSanitizer.sanitize(message)}"
        entries.update { (it + line).takeLast(MAX_MESSAGES) }
    }

    internal fun clear() {
        entries.value = emptyList()
    }
}
