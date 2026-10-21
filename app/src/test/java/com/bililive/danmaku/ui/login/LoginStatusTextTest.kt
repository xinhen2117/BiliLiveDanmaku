package com.bililive.danmaku.ui.login

import org.junit.Assert.*
import org.junit.Test

class LoginStatusTextTest {
    @Test
    fun `mock never asks for login regardless of saved account state`() {
        for (hasLocalLogin in listOf(false, true)) {
            assertTrue(accountLoginStatusText(true, hasLocalLogin).contains("Mock 模式无需登录"))
        }
        assertTrue(accountLoginStatusText(true, true).contains("已保存本地登录状态"))
        assertTrue(accountLoginStatusText(true, false).contains("不连接B站"))
    }

    @Test
    fun `real connection never promises a complete stream regardless of saved account state`() {
        for (hasLocalLogin in listOf(false, true)) {
            val text = accountLoginStatusText(false, hasLocalLogin)
            assertTrue(text.contains("实验性"))
            assertTrue(text.contains("不保证收齐弹幕"))
            assertFalse(text.contains("获取完整弹幕"))
        }
        assertTrue(accountLoginStatusText(false, true).contains("未验证会话有效性"))
        assertTrue(accountLoginStatusText(false, false).contains("未保存登录状态"))
    }

    @Test
    fun `login notice acknowledges experimental availability and completeness limits`() {
        assertTrue(EXPERIMENTAL_LOGIN_NOTICE.contains("实验性"))
        assertTrue(EXPERIMENTAL_LOGIN_NOTICE.contains("不能保证持续可用或收齐所有弹幕"))
    }
}
