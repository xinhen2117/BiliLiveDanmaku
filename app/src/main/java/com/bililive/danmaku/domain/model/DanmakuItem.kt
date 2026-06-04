package com.bililive.danmaku.domain.model

data class DanmakuItem(
    val id: String,
    val roomId: Long,
    val uid: Long?,
    val username: String,
    val content: String,
    val timestamp: Long,
    val badgeName: String? = null,
    val badgeLevel: Int? = null,
    val repeatCount: Int = 1,
    val receiveTimeMillis: Long = System.currentTimeMillis()
)
