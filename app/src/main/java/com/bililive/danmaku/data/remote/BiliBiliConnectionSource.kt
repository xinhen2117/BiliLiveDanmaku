package com.bililive.danmaku.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request

internal class BiliBiliConnectionSource(
    private val api: BiliBiliApi,
    private val cookieJar: BiliBiliCookieJar,
    private val loginManager: BiliBiliLoginManager
) : DanmakuConnectionSource {
    override suspend fun resolve(roomId: Long): DanmakuConnection = withContext(Dispatchers.IO) {
        cookieJar.initializeCookie()
        currentCoroutineContext().ensureActive()
        val realRoomId = api.getRealRoomId(roomId)
        currentCoroutineContext().ensureActive()
        val conf = api.getDanmuConf(realRoomId)
        currentCoroutineContext().ensureActive()
        // Only the official Bilibili domain may receive the handshake/auth material.
        require(conf.host == "bilibili.com" || conf.host.endsWith(".bilibili.com")) { "Untrusted danmaku host" }
        val url = HttpUrl.Builder().scheme("https").host(conf.host).port(conf.wssPort).addPathSegment("sub").build()
        DanmakuConnection(
            request = Request.Builder().url(url)
                .header("User-Agent", cookieJar.getUserAgent())
                .header("Origin", "https://live.bilibili.com")
                .header("Referer", "https://live.bilibili.com/")
                // CookieJar is the sole cookie source; no manually overridden Cookie header.
                .build(),
            realRoomId = realRoomId,
            token = conf.token,
            buvid = cookieJar.getBuvid3(),
            uid = loginManager.getLoginState().uid
        )
    }

    override fun close() {
        api.destroy()
        cookieJar.destroy()
    }
}
