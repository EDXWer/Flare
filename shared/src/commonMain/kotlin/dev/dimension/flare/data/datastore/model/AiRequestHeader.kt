package dev.dimension.flare.data.datastore.model

import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable

@Serializable
public data class AiRequestHeader(
    val name: String,
    val value: String,
) {
    override fun toString(): String = "AiRequestHeader(name=$name, value=***)"
}

public fun areValidAiRequestHeaders(headers: List<AiRequestHeader>): Boolean {
    val names = mutableSetOf<String>()
    return headers.all { (name, value) ->
        val normalized = name.lowercase()
        headerName.matches(name) &&
            normalized !in managedHeaders && !HttpHeaders.isUnsafe(name) && names.add(normalized) &&
            value.all { it == '\t' || it in ' '..'~' }
    }
}

private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
private val managedHeaders = setOf("host", "content-type", "content-length")
