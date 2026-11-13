package dev.dimension.flare.data.repository

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class VideoPlaybackDiagnosticsTest {
    @Test
    fun exportsPlaybackEventsWithNetworkLoggingDisabled() {
        val event = "test playback diagnostic"
        val secret = "sample-token-value"
        DebugRepository.setEnabled(false)
        try {
            VideoPlaybackDiagnostics.record("$event token=$secret")
            assertContains(VideoPlaybackDiagnostics.messages.value.last(), "$event token=[REDACTED]")
            assertContains(DebugRepository.printToString(), "[VideoPlayback] $event")
            assertFalse(secret in DebugRepository.printToString())
        } finally {
            DebugRepository.clear()
        }
        assertFalse(event in DebugRepository.printToString())
    }
}
