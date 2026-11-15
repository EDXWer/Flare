package dev.dimension.flare.data.network.vvo.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class DirectMessageRoomData(
    val user: User? = null,
    val scheme: String? = null,
    val text: String? = null,
    val unread: Long? = null,
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
)

@Serializable
internal data class DirectMessageData(
    val msgs: List<DirectMessageEntry>? = null,
    val users: Map<String, User>? = null,
)

@Serializable
internal data class DirectMessageEntry(
    val id: JsonPrimitive? = null,
    val idstr: String? = null,
    val text: String? = null,
    @SerialName("sender_id") val senderId: JsonPrimitive? = null,
    @SerialName("sender_screen_name") val senderName: String? = null,
    @SerialName("recipient_id") val recipientId: JsonPrimitive? = null,
    @SerialName("created_at") val createdAt: JsonPrimitive? = null,
    @SerialName("media_type") val mediaType: JsonPrimitive? = null,
    val attachment: JsonElement? = null,
)

@Serializable
internal data class DirectMessageAttachment(
    val fid: JsonPrimitive? = null,
    val fidstr: JsonPrimitive? = null,
    @SerialName("original_image") val originalImage: DirectMessageImage? = null,
    val thumbnail: DirectMessageImage? = null,
)

@Serializable
internal data class DirectMessageImage(
    val url: String? = null,
    val width: Float = 0f,
    val height: Float = 0f,
)
