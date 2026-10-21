package com.bililive.danmaku.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


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
    val hosts: List<HostServer>
)

data class RoomInfo(
    val roomId: Long,
    val shortRoomId: Long,
    val title: String,
    val uname: String,
    val online: Int,
    val liveStatus: Int  // 0=未开播, 1=直播中, 2=轮播
)
