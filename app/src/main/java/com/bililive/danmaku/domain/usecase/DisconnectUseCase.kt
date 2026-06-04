package com.bililive.danmaku.domain.usecase

import com.bililive.danmaku.domain.repository.DanmakuRepository

class DisconnectUseCase(
    private val repository: DanmakuRepository
) {
    operator fun invoke() {
        repository.disconnect()
    }
}
