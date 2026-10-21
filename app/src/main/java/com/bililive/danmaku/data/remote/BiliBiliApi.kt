package com.bililive.danmaku.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
        return roomInit.data?.roomId ?: throw Exception("房间号为空")
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

        // 选择最佳服务器（优先选择 CDN 节点）
        val bestHost = data.hostServerList.firstOrNull()?.host ?: "broadcastlv.chat.bilibili.com"
        val wssPort = data.hostServerList.firstOrNull()?.wssPort ?: 443

        return DanmuConf(
            token = data.token,
            host = bestHost,
            wssPort = wssPort
        )
    }

    fun destroy() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

// ===== 数据类 =====

@Serializable
data class RoomInitResponse(
    val code: Int = -1,
    val message: String = "",
    val data: RoomInitData? = null
)

@Serializable
data class RoomInitData(
    @SerialName("room_id") val roomId: Long = 0,
    @SerialName("uid") val uid: Long = 0
)

@Serializable
data class RoomInfoResponse(
    val code: Int = -1,
    val message: String = "",
    val data: RoomInfoData? = null
)

@Serializable
data class RoomInfoData(
    @SerialName("room_id") val roomId: Long = 0,
    val title: String = "",
    val uname: String? = null,
    val online: Int? = null,
    @SerialName("live_status") val liveStatus: Int? = null
)

@Serializable
data class DanmuConfResponse(
    val code: Int = -1,
    val message: String = "",
    val data: DanmuConfData? = null
)

@Serializable
data class DanmuConfData(
    val token: String = "",
    @SerialName("host_server_list") val hostServerList: List<HostServer> = emptyList()
)

@Serializable
data class HostServer(
    val host: String = "",
    val port: Int = 2243,
    @SerialName("wss_port") val wssPort: Int = 443,
    @SerialName("ws_port") val wsPort: Int = 2244
)

data class DanmuConf(
    val token: String,
    val host: String,
    val wssPort: Int
)

data class RoomInfo(
    val roomId: Long,
    val shortRoomId: Long,
    val title: String,
    val uname: String,
    val online: Int,
    val liveStatus: Int  // 0=未开播, 1=直播中, 2=轮播
)
