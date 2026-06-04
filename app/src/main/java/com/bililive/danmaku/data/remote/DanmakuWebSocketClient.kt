package com.bililive.danmaku.data.remote

import android.util.Log
import com.bililive.danmaku.domain.model.ConnectionStatus
import com.bililive.danmaku.domain.model.DanmakuError
import com.bililive.danmaku.domain.model.DanmakuItem
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * B站弹幕 WebSocket 客户端 v8
 *
 * 关键改进：
 * - 数据看门狗：20秒没收到任何服务器数据 → 强制重连
 * - 心跳响应监控：发心跳后检查是否收到人气值回复
 * - 异常不再静默吞掉，记录日志
 * - 更激进的重连策略
 */
class DanmakuWebSocketClient(
    private val bilibiliApi: BiliBiliApi,
    private val cookieJar: BiliBiliCookieJar? = null,
    private val loginManager: BiliBiliLoginManager? = null
) {
    companion object {
        private const val TAG = "DanmakuWS"
        private const val HEARTBEAT_INTERVAL = 10_000L          // 10秒心跳
        private const val DATA_TIMEOUT = 25_000L                // 25秒无数据 → 重连
        private const val HEARTBEAT_RESPONSE_TIMEOUT = 15_000L  // 发心跳后15秒内没收到回复 → 重连
    }

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)  // 缩短到30秒
        .writeTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)  // 不用 OkHttp 自带 ping
        .apply { if (cookieJar != null) cookieJar(cookieJar) }
        .build()

    private val _danmaku = Channel<DanmakuItem>(Channel.BUFFERED)
    val danmaku: Flow<DanmakuItem> = _danmaku.receiveAsFlow()

    private val _status = MutableStateFlow(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _error = Channel<DanmakuError>(Channel.BUFFERED)
    val error: Flow<DanmakuError> = _error.receiveAsFlow()

    private val userDisconnected = AtomicBoolean(false)
    private val reconnectAttempt = AtomicLong(0)
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    private var currentRoomId: Long = 0
    private var realRoomId: Long = 0
    private var danmuConf: DanmuConf? = null

    // 上次收到服务器数据的时间戳（包括心跳回复、弹幕等任何数据）
    @Volatile private var lastDataReceivedTime = 0L
    // 上次发送心跳的时间
    @Volatile private var lastHeartbeatSentTime = 0L

    // 重连延迟：1s, 2s, 3s, 5s, 8s, 12s, 20s
    private val reconnectDelays = longArrayOf(1000, 2000, 3000, 5000, 8000, 12000, 20000)

    fun connect(roomId: Long, scope: CoroutineScope) {
        currentRoomId = roomId
        userDisconnected.set(false)
        reconnectAttempt.set(0)
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        _status.value = ConnectionStatus.Connecting

        scope.launch {
            try {
                cookieJar?.initializeCookie(scope)
                realRoomId = bilibiliApi.getRealRoomId(roomId)
                danmuConf = bilibiliApi.getDanmuConf(realRoomId)
                doConnect(scope)
            } catch (e: Exception) {
                Log.e(TAG, "获取房间信息失败", e)
                _error.trySend(DanmakuError.fromCode("ROOM_NOT_FOUND", "获取房间信息失败: ${e.message}"))
                _status.value = ConnectionStatus.Failed
                scheduleReconnect(scope)
            }
        }
    }

    private fun doConnect(scope: CoroutineScope) {
        val conf = danmuConf ?: run { _status.value = ConnectionStatus.Failed; return }
        val wsUrl = "wss://${conf.host}/sub"
        val cookieString = cookieJar?.getCookieString() ?: ""

        Log.d(TAG, "连接 $wsUrl (room=$realRoomId)")

        val requestBuilder = Request.Builder().url(wsUrl)
            .header("User-Agent", cookieJar?.getUserAgent() ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .header("Origin", "https://live.bilibili.com")
            .header("Referer", "https://live.bilibili.com/")
        if (cookieString.isNotEmpty()) requestBuilder.header("Cookie", cookieString)

        webSocket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 已连接，发送认证...")
                lastDataReceivedTime = System.currentTimeMillis()  // 连接成功也算收到数据
                val uid = loginManager?.getLoginState()?.uid ?: 0
                val authPacket = BiliBiliProtocol.buildAuthPacket(
                    roomId = realRoomId, token = conf.token,
                    buvid = cookieJar?.getBuvid3() ?: "",
                    uid = uid
                )
                webSocket.send(authPacket.toByteString(0, authPacket.size))
                _status.value = ConnectionStatus.Connected
                reconnectAttempt.set(0)
                startHeartbeat(scope)
                startDataWatchdog(scope)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastDataReceivedTime = System.currentTimeMillis()  // 任何数据都刷新时间
                try {
                    val data = bytes.toByteArray()
                    if (data.size < 16) return

                    // 检查认证响应 (op=8) 和心跳响应 (op=3)
                    val firstOp = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.BIG_ENDIAN).getInt()
                    if (firstOp == 8) {
                        Log.d(TAG, "认证成功")
                        _status.value = ConnectionStatus.Connected
                        reconnectAttempt.set(0)
                        return
                    }
                    if (firstOp == 3) {
                        // 心跳回复（人气值），说明连接正常
                        return
                    }

                    // 提取所有弹幕
                    val danmakuMessages = BiliBiliProtocol.extractDanmaku(data)
                    if (danmakuMessages.isNotEmpty()) {
                        Log.d(TAG, "收到 ${danmakuMessages.size} 条弹幕")
                    }
                    for (msg in danmakuMessages) {
                        _danmaku.trySend(
                            DanmakuItem(
                                id = "${currentRoomId}_${msg.timestamp}_${msg.uid ?: 0}_${msg.content.hashCode()}",
                                roomId = currentRoomId,
                                uid = msg.uid,
                                username = msg.username,
                                content = msg.content,
                                timestamp = msg.timestamp,
                                badgeName = msg.badgeName,
                                badgeLevel = msg.badgeLevel
                            )
                        )
                    }
                } catch (e: Exception) {
                    // 不再静默吞掉！记录日志
                    Log.e(TAG, "解析消息异常: ${e.message}", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastDataReceivedTime = System.currentTimeMillis()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket 异常: ${t.message}")
                if (!userDisconnected.get()) {
                    _status.value = ConnectionStatus.Reconnecting
                    val attempt = reconnectAttempt.get()
                    if (attempt >= 2) {
                        _error.trySend(DanmakuError.fromCode("NETWORK_ERROR", "网络不稳定，正在重连..."))
                    }
                    scheduleReconnect(scope)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 关闭: code=$code reason=$reason")
                if (!userDisconnected.get()) {
                    _status.value = ConnectionStatus.Reconnecting
                    scheduleReconnect(scope)
                }
            }
        })
    }

    /**
     * 心跳：每10秒发一次
     */
    private fun startHeartbeat(scope: CoroutineScope) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay(1000) // 连接后等1秒再发第一个心跳
            while (isActive) {
                try {
                    val heartbeat = BiliBiliProtocol.buildHeartbeatPacket()
                    val sent = webSocket?.send(heartbeat.toByteString(0, heartbeat.size))
                    lastHeartbeatSentTime = System.currentTimeMillis()
                    if (sent != true) {
                        Log.w(TAG, "心跳发送失败")
                        if (!userDisconnected.get()) {
                            forceReconnect(scope, "心跳发送失败")
                        }
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "心跳异常: ${e.message}")
                    if (!userDisconnected.get()) {
                        forceReconnect(scope, "心跳异常: ${e.message}")
                    }
                    break
                }
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }

    /**
     * 数据看门狗：每5秒检查一次，如果超过25秒没收到任何服务器数据，强制重连
     * 这是解决"WebSocket假死"的核心——连接看起来活着但实际收不到数据
     */
    private fun startDataWatchdog(scope: CoroutineScope) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(5000) // 给连接5秒启动时间
            while (isActive) {
                delay(5000) // 每5秒检查一次
                val now = System.currentTimeMillis()
                val silenceDuration = now - lastDataReceivedTime

                if (silenceDuration > DATA_TIMEOUT) {
                    Log.w(TAG, "数据超时！${silenceDuration/1000}秒没收到服务器数据，强制重连")
                    if (!userDisconnected.get()) {
                        forceReconnect(scope, "数据超时${silenceDuration/1000}秒")
                    }
                    break
                }

                // 额外检查：如果发了心跳但15秒内没收到回复，也重连
                if (lastHeartbeatSentTime > 0) {
                    val hbSilence = now - lastHeartbeatSentTime
                    if (hbSilence > HEARTBEAT_RESPONSE_TIMEOUT && silenceDuration > HEARTBEAT_RESPONSE_TIMEOUT) {
                        Log.w(TAG, "心跳无响应！${hbSilence/1000}秒，强制重连")
                        if (!userDisconnected.get()) {
                            forceReconnect(scope, "心跳无响应${hbSilence/1000}秒")
                        }
                        break
                    }
                }
            }
        }
    }

    /**
     * 强制重连：立即关闭当前连接并触发重连
     */
    private fun forceReconnect(scope: CoroutineScope, reason: String) {
        Log.w(TAG, "强制重连: $reason")
        try { webSocket?.close(1001, reason) } catch (_: Exception) {}
        webSocket = null
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        if (!userDisconnected.get()) {
            _status.value = ConnectionStatus.Reconnecting
            scheduleReconnect(scope)
        }
    }

    /**
     * 重连：首次快速重连，后续递增延迟
     */
    private fun scheduleReconnect(scope: CoroutineScope) {
        if (userDisconnected.get()) return
        reconnectJob?.cancel()

        // 先关闭旧连接
        try { webSocket?.close(1000, "reconnect") } catch (_: Exception) {}
        webSocket = null
        heartbeatJob?.cancel()
        watchdogJob?.cancel()

        reconnectJob = scope.launch {
            _status.value = ConnectionStatus.Reconnecting
            val attempt = reconnectAttempt.getAndIncrement()
            val delayMs = reconnectDelays.getOrElse(attempt.toInt()) { 20000 }
            Log.d(TAG, "第${attempt+1}次重连，等待${delayMs}ms")
            delay(delayMs)
            if (!userDisconnected.get()) {
                // 重新获取 token（可能过期）
                try { danmuConf = bilibiliApi.getDanmuConf(realRoomId) } catch (_: Exception) {}
                doConnect(scope)
            }
        }
    }

    fun disconnect() {
        userDisconnected.set(true)
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        try { webSocket?.close(1000, "User disconnect") } catch (_: Exception) {}
        webSocket = null
        _status.value = ConnectionStatus.Disconnected
    }

    fun isConnected(): Boolean = _status.value == ConnectionStatus.Connected

    fun destroy() {
        disconnect()
        bilibiliApi.destroy()
        cookieJar?.destroy()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
