package com.bililive.danmaku.domain.model

data class DanmakuError(
    val code: String,
    val message: String,
    val recoverable: Boolean
) {
    companion object {
        fun fromCode(code: String, message: String): DanmakuError {
            val recoverable = code !in setOf(
                "INVALID_ROOM_ID", "UNAUTHORIZED", "AUTH_EXPIRED", "ROOM_NOT_FOUND"
            )
            return DanmakuError(code, message, recoverable)
        }

        fun userMessage(code: String): String = when (code) {
            "INVALID_ROOM_ID" -> "请输入正确的直播间号"
            "ROOM_NOT_FOUND" -> "未找到该直播间，请检查房间号"
            "ROOM_NOT_LIVE" -> "直播间当前未开播，可保持等待或稍后重试"
            "UNAUTHORIZED" -> "该直播间尚未授权本工具接入，请主播完成授权"
            "AUTH_EXPIRED" -> "授权已过期，请重新获取身份码"
            "CONNECTION_LIMIT" -> "该直播间连接数已达上限，请稍后重试"
            "NETWORK_ERROR" -> "网络不可用，正在等待恢复"
            "SERVER_ERROR" -> "服务暂时不可用，请稍后再试"
            "PROTOCOL_ERROR" -> "弹幕协议解析异常，已尝试重新连接"
            else -> "发生未知错误，请稍后重试"
        }
    }
}
