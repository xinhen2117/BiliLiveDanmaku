package com.bililive.danmaku.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
        val requests = DanmakuHostPolicy.requests(conf.hosts, cookieJar.getUserAgent())
        DanmakuConnection(
            request = requests.first(),
            alternateRequests = requests.drop(1),
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
