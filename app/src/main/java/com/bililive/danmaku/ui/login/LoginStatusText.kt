package com.bililive.danmaku.ui.login

internal const val EXPERIMENTAL_LOGIN_NOTICE =
    "真实连接与登录为实验性功能，受B站接口、账号状态和服务端策略影响，不能保证持续可用或收齐所有弹幕。"

internal fun accountLoginStatusText(mockMode: Boolean, hasLocalLogin: Boolean): String = when {
    mockMode && hasLocalLogin -> "Mock 模式无需登录 · 已保存本地登录状态"
    mockMode -> "Mock 模式无需登录，不连接B站"
    hasLocalLogin -> "已保存本地登录状态，未验证会话有效性；真实连接（实验性）不保证收齐弹幕"
    else -> "未保存登录状态 · 真实连接（实验性）不保证收齐弹幕"
}
