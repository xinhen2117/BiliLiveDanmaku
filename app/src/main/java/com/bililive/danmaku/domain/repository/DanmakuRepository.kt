package com.bililive.danmaku.domain.repository

import com.bililive.danmaku.domain.model.DisplaySettings
import com.bililive.danmaku.domain.model.AppSettings
import com.bililive.danmaku.domain.model.DanmakuError
import com.bililive.danmaku.domain.model.DanmakuItem
import com.bililive.danmaku.domain.model.ConnectionStatus
import com.bililive.danmaku.domain.model.TransportDiagnostics
import kotlinx.coroutines.flow.Flow

interface DanmakuRepository {
    fun connect(roomId: Long)
    fun disconnect()
    fun observeDanmaku(): Flow<DanmakuItem>
    fun observeStatus(): Flow<ConnectionStatus>
    fun observeError(): Flow<DanmakuError>
    fun observeTransportDiagnostics(): Flow<TransportDiagnostics> = kotlinx.coroutines.flow.flowOf(TransportDiagnostics())
    fun isConnected(): Boolean
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun updateMockMode(enabled: Boolean)
    suspend fun updateMockRate(rate: Int)
    suspend fun updateKeywordFilterEnabled(enabled: Boolean)
    suspend fun updateKeywordFilterList(keywords: List<String>)
    suspend fun updateDuplicateFoldEnabled(enabled: Boolean)
    suspend fun updateMaxDanmakuCount(count: Int)
    suspend fun updateHighSpeedMode(enabled: Boolean)
    suspend fun updateAutoReconnect(enabled: Boolean)
    suspend fun updateDarkMode(dark: Boolean)
    suspend fun updateFollowSystemDarkMode(follow: Boolean)
    suspend fun updateDisplaySettings(update: (DisplaySettings) -> DisplaySettings)
    suspend fun getRecentRooms(): List<Long>
    suspend fun addRecentRoom(roomId: Long)
    suspend fun clearRecentRooms()
}
