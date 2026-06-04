package com.bililive.danmaku.domain.model

enum class ConnectionStatus {
    Idle,
    Connecting,
    Connected,
    Reconnecting,
    Disconnected,
    Failed
}
