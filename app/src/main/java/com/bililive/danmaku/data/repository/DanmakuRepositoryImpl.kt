package com.bililive.danmaku.data.repository

import com.bililive.danmaku.data.remote.DanmakuWebSocketClient
import com.bililive.danmaku.data.remote.MockDanmakuDataSource
import com.bililive.danmaku.domain.model.*
import com.bililive.danmaku.domain.repository.DanmakuRepository
import com.bililive.danmaku.domain.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class DanmakuRepositoryImpl(
    private val webSocketClient: DanmakuWebSocketClient,
    private val mockDataSource: MockDanmakuDataSource,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope
) : DanmakuRepository {

    private val _filteredDanmaku = MutableSharedFlow<DanmakuItem>(extraBufferCapacity = 256)
    private val _diagnostics = MutableStateFlow(TransportDiagnostics())
    private val _status = MutableStateFlow(ConnectionStatus.Idle)
    private val _error = MutableSharedFlow<DanmakuError>(extraBufferCapacity = 16)

    private var collectJob: Job? = null

    override fun connect(roomId: Long) {
        disconnect()
        collectJob?.cancel()
        _diagnostics.value = TransportDiagnostics()

        collectJob = scope.launch {
            settingsRepository.observeSettings().first().let { settings ->
                if (settings.mockMode) {
                    mockDataSource.connect(roomId, settings.mockRate, scope)
                    launch { mockDataSource.danmaku.collect { _filteredDanmaku.emit(it) } }
                    launch { mockDataSource.status.collect { _status.value = it } }
                } else {
                    // 直连 B站
                    webSocketClient.connect(roomId, this, settings.autoReconnect)
                    launch { webSocketClient.danmaku.collect { _filteredDanmaku.emit(it) } }
                    launch { webSocketClient.status.collect { _status.value = it } }
                    launch { webSocketClient.error.collect { _error.emit(it) } }
                    launch { webSocketClient.diagnostics.collect { _diagnostics.value = it } }
                }
            }
        }
    }

    override fun disconnect() {
        collectJob?.cancel()
        collectJob = null
        webSocketClient.disconnect()
        mockDataSource.disconnect()
        _status.value = ConnectionStatus.Disconnected
    }

    override fun observeDanmaku(): Flow<DanmakuItem> = _filteredDanmaku.asSharedFlow()
    override fun observeStatus(): Flow<ConnectionStatus> = _status.asStateFlow()
    override fun observeTransportDiagnostics(): Flow<TransportDiagnostics> = _diagnostics.asStateFlow()
    override fun observeError(): Flow<DanmakuError> = _error.asSharedFlow()
    override fun isConnected(): Boolean = _status.value == ConnectionStatus.Connected
}
