package com.bililive.danmaku.data.remote

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * B站 REST API，获取直播间信息和弹幕服务器配置
 */
class BiliBiliApi(
    private val cookieJar: BiliBiliCookieJar? = null
) {

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .apply {
            if (cookieJar != null) {
                cookieJar(cookieJar)
            }
        }
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val ROOM_INIT_URL = "https://api.live.bilibili.com/room/v1/Room/room_init"
        private const val ROOM_INFO_URL = "https://api.live.bilibili.com/room/v1/Room/get_info"
        private const val DANMU_CONF_URL = "https://api.live.bilibili.com/room/v1/Danmu/getConf"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    /**
     * 根据短房间号获取真实房间号
     */
    suspend fun getRealRoomId(shortRoomId: Long): Long {
        val url = "$ROOM_INIT_URL?id=$shortRoomId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://live.bilibili.com")
            .build()

        val response = client.newCall(request).awaitResponseData()
        if (response.code !in 200..299) throw IOException("Room lookup failed")
        val body = response.body

        val roomInit = json.decodeFromString<RoomInitResponse>(body)
        if (roomInit.code != 0) {
            throw Exception("获取房间信息失败: ${roomInit.message}")
        }
        return roomInit.data?.roomId?.takeIf { it > 0 } ?: throw IOException("Invalid room ID response")
    }

    /**
     * 获取直播间详细信息
     */
    suspend fun getRoomInfo(roomId: Long): RoomInfo {
        val url = "$ROOM_INFO_URL?room_id=$roomId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://live.bilibili.com")
            .build()

        val response = client.newCall(request).awaitResponseData()
        if (response.code !in 200..299) throw IOException("Room details lookup failed")
        val body = response.body

        val roomInfoResp = json.decodeFromString<RoomInfoResponse>(body)
        if (roomInfoResp.code != 0) {
            throw Exception("获取房间信息失败: ${roomInfoResp.message}")
        }
        val data = roomInfoResp.data ?: throw Exception("房间数据为空")
        return RoomInfo(
            roomId = roomId,
            shortRoomId = data.roomId,
            title = data.title,
            uname = data.uname ?: "",
            online = data.online ?: 0,
            liveStatus = data.liveStatus ?: 0
        )
    }

    /**
     * 获取弹幕服务器配置（含 WebSocket token）
     * 这是连接弹幕服务器的关键：需要 token 才能认证
     */
    suspend fun getDanmuConf(roomId: Long): DanmuConf {
        val url = "$DANMU_CONF_URL?room_id=$roomId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://live.bilibili.com")
            .build()

        val response = client.newCall(request).awaitResponseData()
        if (response.code !in 200..299) throw IOException("Danmaku configuration lookup failed")
        val body = response.body

        val confResp = json.decodeFromString<DanmuConfResponse>(body)
        if (confResp.code != 0) {
            throw Exception("获取弹幕配置失败: ${confResp.message}")
        }
        val data = confResp.data ?: throw Exception("弹幕配置为空")

        // Preserve all server-provided endpoints. Never invent an anonymous/tokenless fallback.
        if (data.token.isBlank() || data.hostServerList.isEmpty()) throw IOException("Incomplete danmaku configuration")
        return DanmuConf(token = data.token, hosts = data.hostServerList)
    }

    fun destroy() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
