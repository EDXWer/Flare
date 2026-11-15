package dev.dimension.flare.data.network.vvo.api

import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.Field
import de.jensklingenberg.ktorfit.http.FormUrlEncoded
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Header
import de.jensklingenberg.ktorfit.http.Multipart
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Query
import dev.dimension.flare.data.network.vvo.model.VVOResponse
import io.ktor.client.request.forms.MultiPartFormDataContent
import kotlinx.serialization.json.JsonElement

internal interface DirectMessageApi {
    @GET("message/msglist")
    suspend fun getDirectMessageRooms(
        @Query("page") page: Int,
    ): VVOResponse<JsonElement>

    @GET("api/chat/list")
    suspend fun getDirectMessages(
        @Query("uid") uid: String,
        @Query("count") count: Int,
        @Query("max_id") maxId: String? = null,
        @Query("since_id") sinceId: String? = null,
        @Query("unfollowing") unfollowing: Int = 0,
    ): VVOResponse<JsonElement>

    @POST("api/chat/send")
    @FormUrlEncoded
    suspend fun postDirectMessage(
        @Field("uid") uid: String,
        @Field("content") content: String,
        @Field("st") st: String,
        @Header("X-Xsrf-Token") xsrfToken: String = st,
        @Field("fids") fileId: String? = null,
        @Field("media_type") mediaType: Int? = null,
    ): VVOResponse<JsonElement>

    @POST("api/chat/send")
    @Multipart
    suspend fun uploadDirectMessageImage(
        @Body body: MultiPartFormDataContent,
        @Header("Referer") referer: String,
        @Header("X-Xsrf-Token") xsrfToken: String,
    ): VVOResponse<JsonElement>
}
