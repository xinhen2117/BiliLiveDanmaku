package com.bililive.danmaku.domain.usecase

import com.bililive.danmaku.domain.repository.DanmakuRepository
import com.bililive.danmaku.domain.repository.SettingsRepository

class ConnectRoomUseCase(
    private val danmakuRepository: DanmakuRepository,
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(roomId: Long): Result<Unit> {
        return try {
            settingsRepository.addRecentRoom(roomId)
            danmakuRepository.connect(roomId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
