package dev.dimension.flare.data.datastore.model

import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.createSampleStatus
import dev.dimension.flare.ui.model.createSampleUser
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VideoPlaybackSpeedTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun videoKindsPreserveLegacyMediaAndUpdateRenderedContent() {
        val video =
            Json.decodeFromString<UiMedia.Video>(
                """{"url":"file:///clip.mov","thumbnailUrl":"","description":null,"height":1,"width":1}""",
            )
        assertEquals(UiMedia.Video.Kind.Video, video.kind)
        val post = createSampleStatus(createSampleUser()).copy(images = persistentListOf(video))
        for (kind in listOf(UiMedia.Video.Kind.Gif, UiMedia.Video.Kind.LivePhoto)) {
            val animation = video.copy(kind = kind)
            assertEquals(
                animation,
                ProtoBuf.decodeFromByteArray(UiMedia.Video.serializer(), ProtoBuf.encodeToByteArray(UiMedia.Video.serializer(), animation)),
            )
            assertEquals(animation, Json.decodeFromString<UiMedia.Video>(Json.encodeToString(UiMedia.Video.serializer(), animation)))
            assertNotEquals(post.renderHash, post.copy(images = persistentListOf(animation)).renderHash)
        }
    }

    @Test
    fun unsupportedAndNonFiniteSpeedsFallBackToNormal() {
        listOf(0f, -1f, 3f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertEquals(VideoPlaybackSpeed.NORMAL, VideoPlaybackSpeed.fromRate(it))
        }
        assertEquals(VideoPlaybackSpeed.ONE_AND_QUARTER, VideoPlaybackSpeed.fromRate(1.25f))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun oldSettingsDefaultToNormalAndProtobufPreservesSelectedSpeed() {
        assertEquals(1f, Json.decodeFromString<AppSettings>("""{"version":"old"}""").videoPlaybackSpeed)
        val settings = AppSettings(version = "test", videoPlaybackSpeed = 1.75f)
        val encoded = ProtoBuf.encodeToByteArray(AppSettings.serializer(), settings)
        assertEquals(settings, ProtoBuf.decodeFromByteArray(AppSettings.serializer(), encoded))
    }
}
