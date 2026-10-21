package com.bililive.danmaku.data.remote

import okhttp3.Request

/** Separates room discovery from transport so connection races can be tested without Android/accounts. */
internal interface DanmakuConnectionSource {
    suspend fun resolve(roomId: Long): DanmakuConnection
    fun close() = Unit
}

internal data class DanmakuConnection(
    val request: Request,
    val realRoomId: Long,
    val token: String = "",
    val buvid: String = "",
    val uid: Long = 0
)
