package com.bililive.danmaku.ui.danmaku

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bililive.danmaku.domain.model.*
import com.bililive.danmaku.domain.repository.DanmakuRepository
import com.bililive.danmaku.domain.repository.SettingsRepository
import com.bililive.danmaku.domain.usecase.ConnectRoomUseCase
import com.bililive.danmaku.domain.usecase.DisconnectUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DanmakuViewModel(
    private val roomId: Long,
    private val connectRoomUseCase: ConnectRoomUseCase,
    private val disconnectUseCase: DisconnectUseCase,
    private val danmakuRepository: DanmakuRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DanmakuUiState(roomId = roomId))
    val uiState: StateFlow<DanmakuUiState> = _uiState.asStateFlow()

    // All collectors and screen callbacks are confined to Main. The socket remains independent
    // of presentation visibility; rotation / STOP never starts a second connection.
    private val pipeline = DanmakuMessagePipeline()
    private var settings = AppSettings()
    private var latestStatus = ConnectionStatus.Idle
    private var latestError: String? = null
    private var latestTransportDiagnostics = TransportDiagnostics()
    private val presentation = DanmakuPresentationPump(
        scope = viewModelScope,
        nowMs = SystemClock::elapsedRealtime,
        publish = ::publishPresentation
    )

    init {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { next ->
                val historyChanged = pipeline.updateConfiguration(
                    DanmakuPipelineConfig(
                        maxHistory = next.maxDanmakuCount,
                        keywordFilterEnabled = next.keywordFilterEnabled,
                        keywords = next.keywordFilterList,
                        duplicateFoldEnabled = next.duplicateFoldEnabled,
                        duplicateFoldWindowMs = next.duplicateFoldWindowMs
                    )
                )
                val speedModeChanged = settings.highSpeedMode != next.highSpeedMode
                settings = next
                if (historyChanged || speedModeChanged) requestPresentation()
            }
        }
        viewModelScope.launch {
            danmakuRepository.observeStatus().collect { status ->
                if (latestStatus != status) {
                    latestStatus = status
                    requestPresentation()
                }
            }
        }
        viewModelScope.launch {
            danmakuRepository.observeError().collect { error ->
                latestError = DanmakuError.userMessage(error.code)
                requestPresentation()
            }
        }
        viewModelScope.launch {
            danmakuRepository.observeDanmaku().collect { item ->
                if (pipeline.offer(item, SystemClock.elapsedRealtime())) requestPresentation()
            }
        }
        viewModelScope.launch {
            danmakuRepository.observeTransportDiagnostics().collect { diagnostics ->
                if (latestTransportDiagnostics != diagnostics) {
                    val lossChanged = latestTransportDiagnostics.droppedMessages != diagnostics.droppedMessages ||
                        latestTransportDiagnostics.droppedFrames != diagnostics.droppedFrames ||
                        latestTransportDiagnostics.oversizedFrames != diagnostics.oversizedFrames
                    latestTransportDiagnostics = diagnostics
                    // Decoded-count-only changes piggyback on existing snapshots; filtered-only
                    // traffic must not wake the display. New loss diagnostics are shown promptly.
                    if (lossChanged) requestPresentation()
                }
            }
        }
        connect()
    }

    private fun requestPresentation() {
        presentation.requestPresentation()
    }

    private fun publishPresentation(now: Long): Long? {
        val snapshot = pipeline.snapshot(now)
        _uiState.value = DanmakuUiState(
            roomId = roomId,
            status = latestStatus,
            messages = snapshot.messages,
            totalCount = snapshot.totalCount,
            messagesPerSecond = snapshot.messagesPerSecond,
            unreadCount = snapshot.unreadCount,
            autoScroll = snapshot.autoScroll,
            highSpeedMode = settings.highSpeedMode || snapshot.messagesPerSecond > 100 || snapshot.historyFull,
            errorMessage = latestError,
            receivedVersion = snapshot.receivedVersion,
            overflowCount = snapshot.overflowCount,
            presentationDiagnostics = snapshot.diagnostics,
            transportDiagnostics = latestTransportDiagnostics
        )
        // At most one future idle reset; no one-second idle loop. New eligible activity postpones
        // this deadline on its next batch. Filtered-only traffic never schedules presentation.
        return if (snapshot.messagesPerSecond > 0) pipeline.rateExpiresAtMs else null
    }

    /** Called from the screen's lifecycle observer on Main, not from socket callbacks. */
    fun setUiVisible(visible: Boolean) {
        presentation.setVisible(visible)
    }

    /** Session presentation mode only. Does not reconnect, change filters, or persist settings. */
    fun setLowPowerMode(enabled: Boolean) {
        presentation.setLowPowerMode(enabled)
    }

    fun connect() {
        viewModelScope.launch { connectRoomUseCase(roomId) }
    }

    fun disconnect() {
        disconnectUseCase()
    }

    fun toggleAutoScroll() {
        setAutoScroll(!pipeline.autoScroll)
    }

    fun setAutoScroll(enabled: Boolean) {
        if (pipeline.setAutoScroll(enabled)) requestPresentation()
    }

    fun scrollToBottom() {
        setAutoScroll(true)
    }

    fun clearScreen() {
        // Clear display history/counters only. Session transport-loss counters remain available.
        pipeline.clear()
        requestPresentation()
    }

    fun dismissError() {
        latestError = null
        requestPresentation()
    }

    override fun onCleared() {
        disconnectUseCase()
        super.onCleared()
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
