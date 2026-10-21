package com.bililive.danmaku.data.remote

import okhttp3.HttpUrl
import okhttp3.Request

/** Build only TLS, official-domain endpoints before any handshake/auth material is transmitted. */
internal object DanmakuHostPolicy {
    fun requests(hosts: List<HostServer>, userAgent: String): List<Request> {
        val urls = hosts.mapNotNull { server ->
            val host = server.host.lowercase()
            if ((host != "bilibili.com" && !host.endsWith(".bilibili.com")) || server.wssPort !in 1..65535) return@mapNotNull null
            try {
                HttpUrl.Builder().scheme("https").host(host).port(server.wssPort).addPathSegment("sub").build()
            } catch (_: IllegalArgumentException) { null }
        }.distinct()
        require(urls.isNotEmpty()) { "No trusted danmaku hosts" }
        return urls.map { url ->
            Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Origin", "https://live.bilibili.com")
                .header("Referer", "https://live.bilibili.com/")
                // CookieJar is the sole cookie source; no manually overridden Cookie header.
                .build()
        }
    }
}
