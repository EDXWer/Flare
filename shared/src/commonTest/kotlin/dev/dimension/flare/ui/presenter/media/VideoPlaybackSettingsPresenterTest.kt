package dev.dimension.flare.ui.presenter.media

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.VideoPlaybackSpeed
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.ui.model.UiMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlaybackSettingsPresenterTest : RobolectricTest() {
    @Test
    fun lastSelectionPersistsAfterViewerClosesAndIsRestoredByTheNextViewer() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val root = createTestRootPath()
            val store = AppDataStore(OkioFileStorage(createTestFileSystem(), root))
            val persistenceScope = CoroutineScope(dispatcher + SupervisorJob())
            val viewerScope = CoroutineScope(dispatcher + SupervisorJob())
            startKoin {
                modules(
                    module {
                        testSingle { store }
                        testSingle<CoroutineScope> { persistenceScope }
                    },
                )
            }
            try {
                store.appSettingsStore.updateData { it.copy(videoPlaybackSpeed = 3f) }
                val presenter = VideoPlaybackSettingsPresenter()
                val states = moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.stateIn(viewerScope)
                val initial = states.first { it.speed != null }
                assertEquals(VideoPlaybackSpeed.NORMAL, initial.speed)
                initial.selectSpeed(VideoPlaybackSpeed.FOUR_TIMES)
                initial.selectSpeed(VideoPlaybackSpeed.EIGHT_TIMES)
                initial.selectSpeed(VideoPlaybackSpeed.SIXTEEN_TIMES)
                viewerScope.cancel()

                store.appSettingsStore.data.first { it.videoPlaybackSpeed == 16f }
                val reopened = VideoPlaybackSettingsPresenter()
                val restored = moleculeFlow(RecompositionMode.Immediate) { reopened.body() }.first { it.speed != null }
                assertEquals(VideoPlaybackSpeed.SIXTEEN_TIMES, restored.speed)
                val video = UiMedia.Video("file:///clip.mp4", "", null, 1f, 1f)
                assertEquals(restored.speed, restored.speedFor(video))
                assertEquals(VideoPlaybackSpeed.NORMAL, restored.speedFor(video.copy(kind = UiMedia.Video.Kind.Gif)))
                assertEquals(VideoPlaybackSpeed.NORMAL, restored.speedFor(video.copy(kind = UiMedia.Video.Kind.LivePhoto)))
            } finally {
                viewerScope.cancel()
                persistenceScope.cancel()
                stopKoin()
                deleteTestRootPath(root)
                Dispatchers.resetMain()
            }
        }
}
