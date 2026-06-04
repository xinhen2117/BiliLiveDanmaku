package com.bililive.danmaku.domain.model

import org.junit.Assert.*
import org.junit.Test

class DanmakuErrorTest {

    @Test
    fun `user message for known codes`() {
        assertEquals("请输入正确的直播间号", DanmakuError.userMessage("INVALID_ROOM_ID"))
        assertEquals("未找到该直播间，请检查房间号", DanmakuError.userMessage("ROOM_NOT_FOUND"))
        assertEquals("网络不可用，正在等待恢复", DanmakuError.userMessage("NETWORK_ERROR"))
    }

    @Test
    fun `user message for unknown code`() {
        assertEquals("发生未知错误，请稍后重试", DanmakuError.userMessage("SOME_NEW_ERROR"))
    }

    @Test
    fun `recoverable errors`() {
        val networkError = DanmakuError.fromCode("NETWORK_ERROR", "timeout")
        assertTrue(networkError.recoverable)

        val serverError = DanmakuError.fromCode("SERVER_ERROR", "500")
        assertTrue(serverError.recoverable)
    }

    @Test
    fun `non-recoverable errors`() {
        val authError = DanmakuError.fromCode("UNAUTHORIZED", "no auth")
        assertFalse(authError.recoverable)

        val notFound = DanmakuError.fromCode("ROOM_NOT_FOUND", "not found")
        assertFalse(notFound.recoverable)
    }
}
