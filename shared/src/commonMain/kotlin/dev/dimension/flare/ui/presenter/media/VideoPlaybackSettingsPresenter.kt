package dev.dimension.flare.ui.presenter.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.datastore.model.VideoPlaybackSpeed
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.presenter.PresenterBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

public class VideoPlaybackSettingsPresenter : PresenterBase<VideoPlaybackSettingsPresenter.State>() {
    private val store by koinInject<AppDataStore>()
    private val persistenceScope by koinInject<CoroutineScope>()

    public interface State {
        public val speed: VideoPlaybackSpeed?
        public val saveFailed: Boolean

        public fun speedFor(media: UiMedia.Video): VideoPlaybackSpeed? =
            if (media.kind == UiMedia.Video.Kind.Video) speed else VideoPlaybackSpeed.NORMAL

        public fun selectSpeed(speed: VideoPlaybackSpeed)

        public fun clearError()
    }

    @Composable
    override fun body(): State {
        val settings by store.appSettingsStore.data.collectAsState<AppSettings, AppSettings?>(null)
        var hasSaveError by remember { mutableStateOf(false) }
        return object : State {
            override val speed = settings?.let { VideoPlaybackSpeed.fromRate(it.videoPlaybackSpeed) }
            override val saveFailed = hasSaveError

            override fun selectSpeed(speed: VideoPlaybackSpeed) {
                hasSaveError = false
                // Queue writes in selection order and keep them alive after the viewer closes.
                persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        store.appSettingsStore.updateData { it.copy(videoPlaybackSpeed = speed.rate) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        hasSaveError = true
                    }
                }
            }

            override fun clearError() {
                hasSaveError = false
            }
        }
    }
}
