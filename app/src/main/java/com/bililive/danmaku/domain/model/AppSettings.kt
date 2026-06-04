package com.bililive.danmaku.domain.model

data class AppSettings(
    val mockMode: Boolean = true,
    val mockRate: Int = 10,
    val keywordFilterEnabled: Boolean = false,
    val keywordFilterList: List<String> = emptyList(),
    val duplicateFoldEnabled: Boolean = true,
    val duplicateFoldWindowMs: Long = 3000L,
    val maxDanmakuCount: Int = 1000,
    val highSpeedMode: Boolean = false,
    val autoReconnect: Boolean = true,
    val followSystemDarkMode: Boolean = true,
    val darkMode: Boolean = false
)
