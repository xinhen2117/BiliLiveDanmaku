package com.bililive.danmaku.ui.danmaku

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bililive.danmaku.domain.model.*
import com.bililive.danmaku.domain.repository.DanmakuRepository
import com.bililive.danmaku.domain.repository.SettingsRepository
import com.bililive.danmaku.domain.usecase.ConnectRoomUseCase
import com.bililive.danmaku.domain.usecase.DisconnectUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class DanmakuViewModel(
    private val roomId: Long,
    private val connectRoomUseCase: ConnectRoomUseCase,
    private val disconnectUseCase: DisconnectUseCase,
    private val danmakuRepository: DanmakuRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DanmakuUiState(roomId = roomId))
    val uiState: StateFlow<DanmakuUiState> = _uiState.asStateFlow()

    // High-performance: batch processing buffer
    private val danmakuBuffer = mutableListOf<DanmakuItem>()
    private val bufferLock = Any()

    // Duplicate fold tracking
    private val recentContentMap = LinkedHashMap<String, DanmakuItem>(256, 0.75f, true)

    // Rate calculation
    private val rateCounter = AtomicLong(0)
    private var lastRateTime = System.currentTimeMillis()

    private var settings = AppSettings()
    private var flushJob: Job? = null
    private var rateJob: Job? = null

    init {
        // Observe settings
        viewModelScope.launch {
            settingsRepository.observeSettings().collect {
                settings = it
            }
        }

        // Observe connection status
        viewModelScope.launch {
            danmakuRepository.observeStatus().collect { status ->
                _uiState.update { it.copy(status = status) }
            }
        }

        // Observe errors
        viewModelScope.launch {
            danmakuRepository.observeError().collect { error ->
                _uiState.update {
                    it.copy(errorMessage = DanmakuError.userMessage(error.code))
                }
            }
        }

        // Observe danmaku - lightweight enqueue only
        viewModelScope.launch {
            danmakuRepository.observeDanmaku().collect { item ->
                synchronized(bufferLock) {
                    danmakuBuffer.add(item)
                }
                rateCounter.incrementAndGet()
            }
        }

        // Start batch flush loop (every 33ms ≈ 30fps for list updates)
        flushJob = viewModelScope.launch {
            while (isActive) {
                delay(33)
                flushBuffer()
            }
        }

        // Rate calculation (every 1 second)
        rateJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val elapsed = now - lastRateTime
                val count = rateCounter.getAndSet(0)
                val rate = if (elapsed > 0) (count * 1000 / elapsed).toInt() else 0
                lastRateTime = now
                _uiState.update { it.copy(messagesPerSecond = rate) }
            }
        }

        // Auto-connect
        connect()
    }

    private fun flushBuffer() {
        val batch: List<DanmakuItem>
        synchronized(bufferLock) {
            if (danmakuBuffer.isEmpty()) return
            batch = danmakuBuffer.toList()
            danmakuBuffer.clear()
        }

        val filtered = applyFilters(batch)
        val folded = if (settings.duplicateFoldEnabled) applyDuplicateFold(filtered) else filtered

        _uiState.update { state ->
            val maxCount = settings.maxDanmakuCount
            val newMessages = state.messages + folded
            val trimmed = if (newMessages.size > maxCount) {
                newMessages.takeLast(maxCount)
            } else newMessages

            val highSpeed = state.messagesPerSecond > 100 || trimmed.size >= maxCount

            state.copy(
                messages = trimmed,
                totalCount = state.totalCount + batch.size,
                unreadCount = if (!state.autoScroll) state.unreadCount + folded.size else state.unreadCount,
                highSpeedMode = highSpeed
            )
        }
    }

    private fun applyFilters(items: List<DanmakuItem>): List<DanmakuItem> {
        if (!settings.keywordFilterEnabled || settings.keywordFilterList.isEmpty()) return items
        val keywords = settings.keywordFilterList
        return items.filter { item ->
            keywords.none { keyword -> item.content.contains(keyword, ignoreCase = true) }
        }
    }

    private fun applyDuplicateFold(items: List<DanmakuItem>): List<DanmakuItem> {
        val now = System.currentTimeMillis()
        val windowMs = settings.duplicateFoldWindowMs
        val result = mutableListOf<DanmakuItem>()

        for (item in items) {
            val key = "${item.roomId}:${item.content}"
            val existing = recentContentMap[key]

            if (existing != null && (now - existing.receiveTimeMillis) < windowMs) {
                // Fold: update count on existing in the result list
                val lastInResult = result.lastOrNull { it.id == existing.id }
                if (lastInResult != null) {
                    result.remove(lastInResult)
                    result.add(lastInResult.copy(repeatCount = lastInResult.repeatCount + 1))
                } else {
                    result.add(item.copy(repeatCount = existing.repeatCount + 1))
                }
            } else {
                result.add(item)
                recentContentMap[key] = item
                // Evict old entries
                if (recentContentMap.size > 1000) {
                    val iterator = recentContentMap.iterator()
                    var removed = 0
                    while (iterator.hasNext() && removed < 200) {
                        iterator.next()
                        iterator.remove()
                        removed++
                    }
                }
            }
        }
        return result
    }

    fun connect() {
        viewModelScope.launch {
            connectRoomUseCase(roomId)
        }
    }

    fun disconnect() {
        disconnectUseCase()
    }

    fun toggleAutoScroll() {
        _uiState.update {
            it.copy(
                autoScroll = !it.autoScroll,
                unreadCount = if (it.autoScroll) 0 else it.unreadCount
            )
        }
    }

    fun scrollToBottom() {
        _uiState.update { it.copy(autoScroll = true, unreadCount = 0) }
    }

    fun clearScreen() {
        _uiState.update { it.copy(messages = emptyList(), totalCount = 0) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectUseCase()
    }

    class Factory(
        private val roomId: Long,
        private val connectRoomUseCase: ConnectRoomUseCase,
        private val disconnectUseCase: DisconnectUseCase,
        private val danmakuRepository: DanmakuRepository,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DanmakuViewModel(
                roomId, connectRoomUseCase, disconnectUseCase,
                danmakuRepository, settingsRepository
            ) as T
        }
    }
}

private class AtomicLong(initial: Long) {
    private val value = java.util.concurrent.atomic.AtomicLong(initial)
    fun incrementAndGet() = value.incrementAndGet()
    fun getAndSet(newValue: Long) = value.getAndSet(newValue)
}
