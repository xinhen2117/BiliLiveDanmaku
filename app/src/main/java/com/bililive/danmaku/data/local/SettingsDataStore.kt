package com.bililive.danmaku.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.bililive.danmaku.domain.model.AppSettings
import com.bililive.danmaku.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) : SettingsRepository {

    companion object {
        val MOCK_MODE = booleanPreferencesKey("mock_mode")
        val MOCK_RATE = intPreferencesKey("mock_rate")
        val KEYWORD_FILTER_ENABLED = booleanPreferencesKey("keyword_filter_enabled")
        val KEYWORD_FILTER_LIST = stringPreferencesKey("keyword_filter_list")
        val DUPLICATE_FOLD_ENABLED = booleanPreferencesKey("duplicate_fold_enabled")
        val MAX_DANMAKU_COUNT = intPreferencesKey("max_danmaku_count")
        val HIGH_SPEED_MODE = booleanPreferencesKey("high_speed_mode")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val FOLLOW_SYSTEM_DARK = booleanPreferencesKey("follow_system_dark")
        val RECENT_ROOMS = stringPreferencesKey("recent_rooms")
    }

    override fun observeSettings(): Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            mockMode = prefs[MOCK_MODE] ?: true,
            mockRate = prefs[MOCK_RATE] ?: 10,
            keywordFilterEnabled = prefs[KEYWORD_FILTER_ENABLED] ?: false,
            keywordFilterList = try {
                Json.decodeFromString(prefs[KEYWORD_FILTER_LIST] ?: "[]")
            } catch (_: Exception) { emptyList() },
            duplicateFoldEnabled = prefs[DUPLICATE_FOLD_ENABLED] ?: true,
            maxDanmakuCount = prefs[MAX_DANMAKU_COUNT] ?: 1000,
            highSpeedMode = prefs[HIGH_SPEED_MODE] ?: false,
            autoReconnect = prefs[AUTO_RECONNECT] ?: true,
            darkMode = prefs[DARK_MODE] ?: false,
            followSystemDarkMode = prefs[FOLLOW_SYSTEM_DARK] ?: true
        )
    }

    override suspend fun updateMockMode(enabled: Boolean) {
        context.dataStore.edit { it[MOCK_MODE] = enabled }
    }

    override suspend fun updateMockRate(rate: Int) {
        context.dataStore.edit { it[MOCK_RATE] = rate }
    }

    override suspend fun updateKeywordFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEYWORD_FILTER_ENABLED] = enabled }
    }

    override suspend fun updateKeywordFilterList(keywords: List<String>) {
        context.dataStore.edit { it[KEYWORD_FILTER_LIST] = Json.encodeToString(keywords) }
    }

    override suspend fun updateDuplicateFoldEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DUPLICATE_FOLD_ENABLED] = enabled }
    }

    override suspend fun updateMaxDanmakuCount(count: Int) {
        context.dataStore.edit { it[MAX_DANMAKU_COUNT] = count }
    }

    override suspend fun updateHighSpeedMode(enabled: Boolean) {
        context.dataStore.edit { it[HIGH_SPEED_MODE] = enabled }
    }

    override suspend fun updateAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_RECONNECT] = enabled }
    }

    override suspend fun updateDarkMode(dark: Boolean) {
        context.dataStore.edit { it[DARK_MODE] = dark }
    }

    override suspend fun updateFollowSystemDarkMode(follow: Boolean) {
        context.dataStore.edit { it[FOLLOW_SYSTEM_DARK] = follow }
    }

    override suspend fun getRecentRooms(): List<Long> {
        val prefs = context.dataStore.data.first()
        val str = prefs[RECENT_ROOMS] ?: return emptyList()
        return try {
            Json.decodeFromString<List<Long>>(str).take(20)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun addRecentRoom(roomId: Long) {
        val current = getRecentRooms().toMutableList()
        current.remove(roomId)
        current.add(0, roomId)
        val trimmed = current.take(20)
        context.dataStore.edit { it[RECENT_ROOMS] = Json.encodeToString(trimmed) }
    }

    override suspend fun clearRecentRooms() {
        context.dataStore.edit { it.remove(RECENT_ROOMS) }
    }
}
