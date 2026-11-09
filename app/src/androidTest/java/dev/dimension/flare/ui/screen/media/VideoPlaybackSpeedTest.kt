package dev.dimension.flare.ui.screen.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.R
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.di.koinGet
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.SurfaceBindingManager
import dev.dimension.flare.ui.component.VideoPlayer
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
class VideoPlaybackSpeedTest {
    init {
        ComposeUiFlags.isMediaQueryIntegrationEnabled = true
    }

    // Media3 listener cleanup needs the legacy rule's real Android main looper.
    @get:Rule val compose = createComposeRule(Dispatchers.Main.immediate)

    @Test
    fun viewerRemembersSpeedAndLongPressRestoresThePausedSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = koinGet<SettingsRepository>()
        val manager = koinGet<SurfaceBindingManager>()
        val previousSpeed = runBlocking { repository.appSettings.first().videoPlaybackSpeed }
        val file = File(context.cacheDir, "video-playback-speed.mp4")
        instrumentation.context.assets
            .open(file.name)
            .use { source -> file.outputStream().use { source.copyTo(it) } }
        val preview = File(context.cacheDir, "video-playback-speed.png")
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            retriever.frameAtTime!!.let { frame ->
                preview.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
            }
        }
        var media by mutableStateOf(
            UiMedia.Video(
                file.toURI().toString(),
                preview.toURI().toString(),
                "Fixture video",
                180f,
                320f,
                proxy = ProxyRef.Unbound,
            ),
        )
        var visible by mutableStateOf(true)

        fun awaitSpeed(rate: Float) {
            compose.waitUntil(10_000) {
                var matches = false
                instrumentation.runOnMainSync { matches = manager.playerFor(media)?.playbackParameters?.speed == rate }
                matches
            }
        }

        fun awaitControl(description: String) {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
            }
        }

        fun screenshot(name: String) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(context.externalCacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            runBlocking { repository.updateAppSettings { copy(videoPlaybackSpeed = 1f) } }
            compose.setContent {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(),
                ) {
                    FlareTheme(darkTheme = true, dynamicColor = false) {
                        if (visible) {
                            MediaViewerScreen(
                                medias = UiState.Success(persistentListOf<UiMedia>(media)),
                                initialIndex = 0,
                                preview = null,
                                previewProxy = ProxyRef.Unbound,
                                onDismiss = { visible = false },
                                toAltText = {},
                                uriHandler =
                                    object : UriHandler {
                                        override fun openUri(uri: String) = Unit
                                    },
                                fileName = { "fixture.mp4" },
                                fileNames = { emptyMap() },
                            )
                        } else {
                            Box {
                                VideoPlayer(media, "Timeline video", modifier = Modifier.fillMaxSize())
                                TextButton(onClick = { visible = true }) { Text("Open video") }
                            }
                        }
                    }
                }
            }
            awaitSpeed(1f)
            instrumentation.runOnMainSync { manager.player.pause() }
            awaitControl(context.getString(R.string.media_playback_speed))
            compose.onNodeWithText(" / ", substring = true).assertIsDisplayed()
            val speedButton = compose.onNodeWithContentDescription(context.getString(R.string.media_playback_speed))
            speedButton.performClick()
            compose.onNodeWithText("1.25×").performClick()
            awaitSpeed(1.25f)
            compose.onNodeWithText("1.25×").assertIsDisplayed()
            screenshot("android-video-speed-control")
            speedButton.performClick()
            compose.onNodeWithText("0.75×").assertIsDisplayed()
            screenshot("android-video-speed-menu")
            compose.onAllNodesWithText("1.25×").onLast().performClick()

            compose.onNodeWithContentDescription(context.getString(R.string.navigate_back)).performClick()
            awaitSpeed(1f)
            assertEquals(1.25f, runBlocking { repository.appSettings.first().videoPlaybackSpeed })
            compose.onNodeWithText("Open video").performClick()
            awaitSpeed(1.25f)
            instrumentation.runOnMainSync { manager.player.pause() }
            awaitControl(context.getString(R.string.media_play))
            speedButton.performClick()
            compose.onNodeWithText("0.75×").performClick()
            awaitSpeed(0.75f)
            compose.runOnIdle { assertEquals(false, manager.player.playWhenReady) }
            compose.onNodeWithContentDescription("Fixture video").performTouchInput { down(center) }
            awaitSpeed(2f)
            compose.onNodeWithContentDescription("Fixture video").performTouchInput { up() }
            awaitSpeed(0.75f)
            compose.runOnIdle { assertEquals(false, manager.player.playWhenReady) }
            assertEquals(0.75f, runBlocking { repository.appSettings.first().videoPlaybackSpeed })
            for (kind in listOf(UiMedia.Video.Kind.Gif, UiMedia.Video.Kind.LivePhoto)) {
                compose.runOnIdle { media = media.copy(kind = kind) }
                awaitSpeed(1f)
                instrumentation.runOnMainSync { manager.player.pause() }
                awaitControl(context.getString(R.string.media_play))
                speedButton.assertDoesNotExist()
                assertEquals(0.75f, runBlocking { repository.appSettings.first().videoPlaybackSpeed })
            }
        } finally {
            compose.runOnIdle { visible = false }
            runBlocking { repository.updateAppSettings { copy(videoPlaybackSpeed = previousSpeed) } }
        }
    }
}
