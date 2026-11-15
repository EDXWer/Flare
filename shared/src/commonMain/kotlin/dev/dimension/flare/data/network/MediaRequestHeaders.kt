package dev.dimension.flare.data.network

import dev.dimension.flare.data.datasource.microblog.AccountHttpCapability
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.di.koinGet
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.native.HiddenFromObjC

public object MediaRequestHeaders {
    @Throws(Exception::class)
    public suspend fun accountHeaders(
        url: String,
        proxy: ProxyRef,
    ): Map<String, String> {
        if (proxy !is ProxyRef.Account) return emptyMap()
        val target = runCatching { Url(url) }.getOrNull() ?: return emptyMap()
        if (!target.isWeiboDirectMessageAttachment()) return emptyMap()
        val repository = koinGet<AccountRepository>()
        val account = repository.find(proxy.accountKey) ?: return emptyMap()
        return (repository.getOrCreateDataSource(account) as? AccountHttpCapability)?.authenticateMedia(url).orEmpty()
    }

    // Unknown names can contain credentials, including provider-specific token headers.
    public fun mayForwardAcrossOrigins(name: String): Boolean = name.lowercase() in publicHeaders

    private val publicHeaders = setOf("accept", "accept-language", "origin", "referer", "user-agent")
}

@HiddenFromObjC
public fun Url.isWeiboDirectMessageAttachment(): Boolean =
    protocol == URLProtocol.HTTPS && host == "attachment.api.weibo.com" && port == 443 && user == null && password == null &&
        (encodedPath == "/2/mss/msget" || encodedPath == "/2/mss/msget_thumbnail")
