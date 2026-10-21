package com.bililive.danmaku.data.remote

import com.bililive.danmaku.domain.model.ConnectionStatus
import com.bililive.danmaku.domain.model.DanmakuError
import com.bililive.danmaku.domain.model.DanmakuItem
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One cancellable session owns discovery, socket callbacks, heartbeat and retry state.
 * Replacing/disconnecting a session makes all late callbacks inert.
 */
class DanmakuWebSocketClient internal constructor(
    private val connectionSource: DanmakuConnectionSource,
    private val socketFactory: WebSocket.Factory = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).build(),
    private val timing: ConnectionTiming = ConnectionTiming(),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    internal data class ConnectionTiming(
        val heartbeatInterval: Long = 10_000,
        val heartbeatTimeout: Long = 15_000,
        val dataTimeout: Long = 25_000,
        val authTimeout: Long = 15_000,
        val tickInterval: Long = 1_000,
        val retryDelays: List<Long> = listOf(1_000, 2_000, 3_000, 5_000, 8_000, 12_000, 20_000)
    ) {
        init {
            require(listOf(heartbeatInterval, heartbeatTimeout, dataTimeout, authTimeout, tickInterval).all { it > 0 })
            require(retryDelays.isNotEmpty() && retryDelays.all { it > 0 })
        }
    }

    private class Session(val roomId: Long, val autoReconnect: Boolean) {
        var job: Job? = null
        var socket: WebSocket? = null
    }
    private sealed interface Event {
        data object Open : Event
        data class Binary(val data: ByteArray) : Event
        data object Text : Event
        data object Closed : Event
        data object Failed : Event
        data object Tick : Event
    }
    private class ConnectionFailure(val errorCode: String, val retry: Boolean = true) : IOException(errorCode)

    private val lock = Any()
    private var session: Session? = null
    private var destroyed = false
    private val messageSequence = AtomicLong()
    private val _danmaku = Channel<DanmakuItem>(Channel.BUFFERED)
    val danmaku: Flow<DanmakuItem> = _danmaku.receiveAsFlow()
    private val _status = MutableStateFlow(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()
    private val _error = Channel<DanmakuError>(Channel.BUFFERED)
    val error: Flow<DanmakuError> = _error.receiveAsFlow()

    fun connect(roomId: Long, scope: CoroutineScope, autoReconnect: Boolean = true) = synchronized(lock) {
        check(!destroyed) { "Client has been destroyed" }
        require(roomId > 0)
        if (!scope.isActive) return@synchronized
        stopCurrentSession()
        while (_danmaku.tryReceive().isSuccess) { /* Do not deliver a previous room's buffered messages. */ }
        while (_error.tryReceive().isSuccess) { }
        val next = Session(roomId, autoReconnect)
        session = next
        _status.value = ConnectionStatus.Connecting
        next.job = scope.launch(start = CoroutineStart.LAZY) { runSession(next) }
        next.job!!.invokeOnCompletion { cause ->
            synchronized(lock) {
                if (session === next && cause is CancellationException) {
                    session = null
                    _status.value = ConnectionStatus.Disconnected
                }
            }
        }
        next.job!!.start()
        Unit
    }

    private fun stopCurrentSession() {
        val old = session
        session = null
        old?.job?.cancel()
        old?.socket?.cancel()
    }

    private fun isCurrent(value: Session): Boolean = synchronized(lock) { session === value }

    private suspend fun runSession(value: Session) {
        var retryAttempt = 0
        while (currentCoroutineContext().isActive && isCurrent(value)) {
            val failure = try {
                val connection = connectionSource.resolve(value.roomId)
                currentCoroutineContext().ensureActive()
                if (!isCurrent(value)) return
                runConnection(value, connection) { retryAttempt = 0 }
                ConnectionFailure("NETWORK_ERROR")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: ConnectionFailure) {
                failed
            } catch (_: Exception) {
                ConnectionFailure("NETWORK_ERROR")
            }
            synchronized(lock) {
                if (session !== value) return
                _error.trySend(DanmakuError.fromCode(failure.errorCode, DanmakuError.userMessage(failure.errorCode)))
                _status.value = if (value.autoReconnect && failure.retry) ConnectionStatus.Reconnecting else ConnectionStatus.Failed
            }
            if (!value.autoReconnect || !failure.retry) return
            delay(timing.retryDelays[retryAttempt.coerceAtMost(timing.retryDelays.lastIndex)])
            if (retryAttempt < timing.retryDelays.lastIndex) retryAttempt++
        }
    }

    private suspend fun runConnection(value: Session, connection: DanmakuConnection, onAuthenticated: () -> Unit) = coroutineScope {
        val events = Channel<Event>(64)
        val queuedFrames = PendingFrameBudget(8L * 1024 * 1024)
        fun offer(event: Event) {
            if (isCurrent(value) && !events.trySend(event).isSuccess) {
                // Bounded memory even if the receiver cannot keep up with server frames.
                events.close(ConnectionFailure("NETWORK_ERROR"))
            }
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = offer(Event.Open)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isCurrent(value) || bytes.size > BiliBiliProtocol.MAX_FRAME_BYTES) return
                if (!queuedFrames.tryReserve(bytes.size)) {
                    events.close(ConnectionFailure("NETWORK_ERROR"))
                    return
                }
                if (!events.trySend(Event.Binary(bytes.toByteArray())).isSuccess) {
                    queuedFrames.release(bytes.size)
                    events.close(ConnectionFailure("NETWORK_ERROR"))
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = offer(Event.Text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = offer(Event.Failed)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                offer(Event.Closed)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = offer(Event.Closed)
        }
        val socket = socketFactory.newWebSocket(connection.request, listener)
        synchronized(lock) {
            if (session !== value) {
                socket.cancel()
                throw CancellationException("Replaced connection")
            }
            value.socket = socket
        }
        val ticker = launch {
            while (isActive) {
                delay(timing.tickInterval)
                events.send(Event.Tick)
            }
        }
        var authenticated = false
        var opened = false
        var authStarted = nowMillis()
        var lastData = authStarted
        var lastHeartbeatSent = authStarted
        var awaitingHeartbeatSince: Long? = null
        try {
            for (event in events) {
                ensureActive()
                if (!isCurrent(value)) throw CancellationException("Replaced connection")
                when (event) {
                    Event.Open -> {
                        opened = true
                        authStarted = nowMillis()
                        lastData = authStarted
                        if (!socket.send(BiliBiliProtocol.buildAuthPacket(connection.realRoomId, connection.token, connection.buvid, connection.uid).toByteString())) {
                            throw ConnectionFailure("NETWORK_ERROR")
                        }
                    }
                    is Event.Binary -> {
                        queuedFrames.release(event.data.size)
                        lastData = nowMillis()
                        for (control in BiliBiliProtocol.extractControlMessages(event.data)) {
                            when (control) {
                                is BiliControlMessage.AuthReply -> {
                                    if (control.code != 0) throw ConnectionFailure("UNAUTHORIZED", retry = false)
                                    if (!authenticated) {
                                        authenticated = true
                                        lastHeartbeatSent = nowMillis()
                                        synchronized(lock) {
                                            if (session === value) _status.value = ConnectionStatus.Connected
                                        }
                                        onAuthenticated()
                                    }
                                }
                                BiliControlMessage.HeartbeatReply -> awaitingHeartbeatSince = null
                            }
                        }
                        if (authenticated) {
                            for (message in BiliBiliProtocol.extractDanmaku(event.data)) {
                                synchronized(lock) {
                                    if (session === value) _danmaku.trySend(DanmakuItem(
                                        id = "${value.roomId}_${messageSequence.incrementAndGet()}",
                                        roomId = value.roomId, uid = message.uid, username = message.username,
                                        content = message.content, timestamp = message.timestamp,
                                        badgeName = message.badgeName, badgeLevel = message.badgeLevel
                                    ))
                                }
                            }
                        }
                    }
                    Event.Text -> Unit // Text is not a valid binary auth/heartbeat response.
                    Event.Closed, Event.Failed -> throw ConnectionFailure("NETWORK_ERROR")
                    Event.Tick -> {
                        val now = nowMillis()
                        if (!authenticated && now - authStarted >= timing.authTimeout) throw ConnectionFailure("NETWORK_ERROR")
                        if (authenticated) {
                            if (now - lastData >= timing.dataTimeout ||
                                awaitingHeartbeatSince?.let { now - it >= timing.heartbeatTimeout } == true
                            ) throw ConnectionFailure("NETWORK_ERROR")
                            if (opened && now - lastHeartbeatSent >= timing.heartbeatInterval) {
                                if (!socket.send(BiliBiliProtocol.buildHeartbeatPacket().toByteString())) throw ConnectionFailure("NETWORK_ERROR")
                                lastHeartbeatSent = now
                                // Later heartbeats must not postpone the oldest unanswered heartbeat's deadline.
                                if (awaitingHeartbeatSince == null) awaitingHeartbeatSince = now
                            }
                        }
                    }
                }
            }
        } finally {
            ticker.cancel()
            events.cancel()
            socket.cancel()
            synchronized(lock) { if (value.socket === socket) value.socket = null }
        }
    }

    fun disconnect() = synchronized(lock) {
        stopCurrentSession()
        _status.value = ConnectionStatus.Disconnected
    }
    fun isConnected(): Boolean = _status.value == ConnectionStatus.Connected

    fun destroy() = synchronized(lock) {
        if (!destroyed) {
            destroyed = true
            disconnect()
            connectionSource.close()
            (socketFactory as? OkHttpClient)?.let {
                it.dispatcher.executorService.shutdown()
                it.connectionPool.evictAll()
            }
        }
    }
}
