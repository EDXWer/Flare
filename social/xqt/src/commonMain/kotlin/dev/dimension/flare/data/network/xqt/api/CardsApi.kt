package dev.dimension.flare.data.network.xqt.api

import de.jensklingenberg.ktorfit.Response
import de.jensklingenberg.ktorfit.http.Field
import de.jensklingenberg.ktorfit.http.FormUrlEncoded
import de.jensklingenberg.ktorfit.http.POST
import dev.dimension.flare.data.datasource.microblog.ComposeConfig
import dev.dimension.flare.data.datasource.microblog.ComposeData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

internal val XQT_POLL_CONFIG = ComposeConfig.Poll(maxOptions = 4, allowsMultiple = false, maxOptionLength = 25)

internal interface CardsApi {
    @FormUrlEncoded
    @POST("v2/cards/create.json")
    suspend fun createCard(@Field("card_data") cardData: String): Response<CreatedCard>
}

@Serializable
internal data class CreatedCard(
    @SerialName("card_uri")
    val cardUri: String? = null,
)

internal suspend fun CardsApi.createPoll(poll: ComposeData.Poll): String {
    require(!poll.multiple) { "X polls only allow one choice per vote." }
    require(XQT_POLL_CONFIG.areOptionsValid(poll.options)) { "X polls need 2 to 4 choices of up to 25 characters each." }
    val durationMinutes = poll.expiredAfter.milliseconds.inWholeMinutes
    require(durationMinutes in 5..10_080 && poll.expiredAfter % 60_000L == 0L) {
        "X polls must last between 5 minutes and 7 days, in whole minutes."
    }
    val choices = poll.options.map(String::trim).filter(String::isNotEmpty)
    val cardData =
        buildJsonObject {
            put("twitter:card", "poll${choices.size}choice_text_only")
            put("twitter:api:api:endpoint", "1")
            put("twitter:long:duration_minutes", durationMinutes)
            choices.forEachIndexed { index, choice ->
                put("twitter:string:choice${index + 1}_label", choice)
            }
        }
    val response = createCard(cardData.toString())
    check(response.isSuccessful) { "X could not create the poll (HTTP ${response.code})." }
    return checkNotNull(response.body()?.cardUri?.takeIf(String::isNotBlank)) { "X did not return a poll card." }
}
