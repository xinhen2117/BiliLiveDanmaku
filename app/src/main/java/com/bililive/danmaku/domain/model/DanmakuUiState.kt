package com.bililive.danmaku.domain.model

data class DanmakuUiState(
    val roomId: Long? = null,
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val messages: List<DanmakuItem> = emptyList(),
    val totalCount: Long = 0,
    val messagesPerSecond: Int = 0,
    val unreadCount: Int = 0,
    val autoScroll: Boolean = true,
    val highSpeedMode: Boolean = false,
    val errorMessage: String? = null,
    // Accepted arrival generation, independent of retained list size and duplicate folding.
    val receivedVersion: Long = 0L,
    // Display text-budget losses only; transport loss and normal history expiry are separate.
    val overflowCount: Long = 0L,
    val presentationDiagnostics: PresentationDiagnostics = PresentationDiagnostics(),
    val transportDiagnostics: TransportDiagnostics = TransportDiagnostics()
)
