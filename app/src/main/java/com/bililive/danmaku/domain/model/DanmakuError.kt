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
            "UNAUTHORIZED" -> "连接认证被拒绝，请检查登录状态后重试"
            "AUTH_REFRESHING" -> "连接认证未通过，正在重新获取连接凭据；再次失败后将停止重试"
            "AUTH_EXPIRED" -> "连接认证已过期，请重新登录后重试"
            "CONNECTION_LIMIT" -> "该直播间连接数已达上限，请稍后重试"
            "TRANSPORT_OVERFLOW" -> "接收缓冲超限，部分弹幕或数据帧未能保留；详见接收丢弃计数"
            "NETWORK_ERROR" -> "网络连接中断，请检查网络后重试"
            "SERVER_ERROR" -> "服务暂时不可用，请稍后再试"
            "PROTOCOL_ERROR" -> "弹幕协议解析异常，已尝试重新连接"
            else -> "发生未知错误，请稍后重试"
        }
    }
}
