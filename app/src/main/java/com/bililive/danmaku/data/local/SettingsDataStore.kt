package com.bililive.danmaku.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.bililive.danmaku.domain.model.DisplaySettings
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
        val KEEP_SCREEN_ON = booleanPreferencesKey("display_keep_screen_on")
        val LOW_POWER_DISPLAY = booleanPreferencesKey("display_low_power")
        val DISPLAY_BRIGHTNESS = intPreferencesKey("display_brightness")
        val PIXEL_SHIFT = booleanPreferencesKey("display_pixel_shift")
        val IDLE_DIMMING = booleanPreferencesKey("display_idle_dimming")
        val IDLE_TIMEOUT = intPreferencesKey("display_idle_timeout")
        val DISPLAY_TEXT_SIZE = intPreferencesKey("display_text_size")
        val COMPACT_ROWS = booleanPreferencesKey("display_compact_rows")
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
            followSystemDarkMode = prefs[FOLLOW_SYSTEM_DARK] ?: true,
            display = prefs.readDisplaySettings()
        )
    }

    private fun Preferences.readDisplaySettings() = DisplaySettings(
        keepScreenOn = this[KEEP_SCREEN_ON] ?: true,
        lowPowerMode = this[LOW_POWER_DISPLAY] ?: false,
        brightnessPercent = this[DISPLAY_BRIGHTNESS] ?: 20,
        pixelShiftEnabled = this[PIXEL_SHIFT] ?: true,
        idleDimmingEnabled = this[IDLE_DIMMING] ?: false,
        idleTimeoutSeconds = this[IDLE_TIMEOUT] ?: 60,
        textSizeSp = this[DISPLAY_TEXT_SIZE] ?: 16,
        compactRows = this[COMPACT_ROWS] ?: false
    ).normalized()

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

    override suspend fun updateDisplaySettings(update: (DisplaySettings) -> DisplaySettings) {
        context.dataStore.edit {
            // Read-modify-write inside one DataStore transaction: fast controls cannot overwrite
            // unrelated settings using an old Compose snapshot.
            val safe = update(it.readDisplaySettings()).normalized()
            it[KEEP_SCREEN_ON] = safe.keepScreenOn
            it[LOW_POWER_DISPLAY] = safe.lowPowerMode
            it[DISPLAY_BRIGHTNESS] = safe.brightnessPercent
            it[PIXEL_SHIFT] = safe.pixelShiftEnabled
            it[IDLE_DIMMING] = safe.idleDimmingEnabled
            it[IDLE_TIMEOUT] = safe.idleTimeoutSeconds
            it[DISPLAY_TEXT_SIZE] = safe.textSizeSp
            it[COMPACT_ROWS] = safe.compactRows
        }
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
