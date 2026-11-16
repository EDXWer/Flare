package dev.dimension.flare.data.network.ai

import dev.dimension.flare.data.datastore.model.AiRequestHeader
import dev.dimension.flare.data.datastore.model.areValidAiRequestHeaders
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonPrimitive
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public fun openAIRequestHeaders(
    apiKey: String,
    customHeaders: List<AiRequestHeader>,
): Map<String, String> {
    require(areValidAiRequestHeaders(customHeaders)) { "Invalid custom AI headers" }
    return buildMap {
        if (apiKey.isNotBlank() && customHeaders.none { it.name.equals(HttpHeaders.Authorization, ignoreCase = true) }) {
            put(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        customHeaders.forEach { (name, value) -> put(name, value) }
    }
}

@HiddenFromObjC
public fun String.redactAiHeaderValues(headers: List<AiRequestHeader>): String =
    headers
        .map { it.value.trim(' ', '\t') }
        .filter(String::isNotEmpty)
        .flatMap { listOf(it, JsonPrimitive(it).toString().removeSurrounding("\"")) }
        .distinct()
        .sortedByDescending(String::length)
        .fold(this) { message, value -> message.replace(value, "[redacted]") }
