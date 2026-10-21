package com.bililive.danmaku.data.remote

import com.bililive.danmaku.domain.model.ConnectionStatus
import com.bililive.danmaku.domain.model.DanmakuError
import com.bililive.danmaku.domain.model.DanmakuItem
import com.bililive.danmaku.domain.model.TransportDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

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
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ingestionLimits: IngestionLimits = IngestionLimits()
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

    internal data class IngestionLimits(
        val pendingBatches: Int = 64,
        val pendingMessages: Int = 8192,
        val pendingMessageBytes: Long = 8L * 1024 * 1024,
        val pendingFrames: Int = 64,
        val pendingFrameBytes: Long = 8L * 1024 * 1024
    ) {
        init { require(pendingBatches > 0 && pendingMessages > 0 && pendingMessageBytes > 0 &&
            pendingFrames > 0 && pendingFrameBytes > 0) }
    }

    private class Session(val generation: Long, val roomId: Long, val autoReconnect: Boolean) {
        var job: Job? = null
        var socket: WebSocket? = null
    }
    private sealed interface Event {
        data object Open : Event
        data class Binary(val data: ByteArray, val receivedAt: Long) : Event
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
    private val sessionSequence = AtomicLong()
    private val pendingDanmaku = PendingDanmakuBuffer(ingestionLimits.pendingBatches,
        ingestionLimits.pendingMessages, ingestionLimits.pendingMessageBytes)
    val danmaku: Flow<DanmakuItem> = flow {
        while (true) {
            val delivery = pendingDanmaku.take()
            try {
                if (synchronized(lock) { session?.generation == delivery.batch.generation }) emit(delivery.item)
            } finally {
                pendingDanmaku.release(delivery)
            }
        }
    }
    private val _diagnostics = MutableStateFlow(TransportDiagnostics())
    val diagnostics: StateFlow<TransportDiagnostics> = _diagnostics.asStateFlow()
    private val _status = MutableStateFlow(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()
    private val _error = Channel<DanmakuError>(Channel.BUFFERED)
    val error: Flow<DanmakuError> = _error.receiveAsFlow()

    fun connect(roomId: Long, scope: CoroutineScope, autoReconnect: Boolean = true) = synchronized(lock) {
        check(!destroyed) { "Client has been destroyed" }
        require(roomId > 0)
        if (!scope.isActive) return@synchronized
        stopCurrentSession()
        pendingDanmaku.clear()
        _diagnostics.value = TransportDiagnostics()
        while (_error.tryReceive().isSuccess) { }
        val next = Session(sessionSequence.incrementAndGet(), roomId, autoReconnect)
        session = next
        _status.value = ConnectionStatus.Connecting
        next.job = scope.launch(dispatcher, start = CoroutineStart.LAZY) { runSession(next) }
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
        var endpointAttempt = 0L
        var authRefreshUsed = false
        while (currentCoroutineContext().isActive && isCurrent(value)) {
            val failure = try {
                val connection = connectionSource.resolve(value.roomId)
                currentCoroutineContext().ensureActive()
                if (!isCurrent(value)) return
                val selected = connection.forAttempt(endpointAttempt)
                endpointAttempt = if (endpointAttempt == Long.MAX_VALUE) 0 else endpointAttempt + 1
                runConnection(value, selected) { retryAttempt = 0; authRefreshUsed = false }
                ConnectionFailure("NETWORK_ERROR")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: ConnectionFailure) {
                // A stale token gets one fresh discovery/handshake, not endless auth retries or
                // an anonymous/risk-control bypass. With autoReconnect off, never retry.
                if (failed.errorCode == "UNAUTHORIZED" && value.autoReconnect && !authRefreshUsed) {
                    authRefreshUsed = true
                    ConnectionFailure("AUTH_REFRESHING")
                } else failed
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

    private fun frameRejected(value: Session, oversized: Boolean = false) = synchronized(lock) {
        if (session !== value) return@synchronized
        val old = _diagnostics.value
        _diagnostics.value = old.copy(droppedFrames = old.droppedFrames + 1,
            oversizedFrames = old.oversizedFrames + if (oversized) 1 else 0)
        if (!old.hasLoss) _error.trySend(DanmakuError.fromCode("TRANSPORT_OVERFLOW",
            DanmakuError.userMessage("TRANSPORT_OVERFLOW")))
    }

    private suspend fun runConnection(value: Session, connection: DanmakuConnection, onAuthenticated: () -> Unit) = coroutineScope {
        val queuedFrames = PendingFrameBudget(ingestionLimits.pendingFrameBytes)
        val accepting = AtomicBoolean(true)
        val lastFrameReceivedAt = AtomicLong(nowMillis())
        val lastHeartbeatReplyAt = AtomicLong(Long.MIN_VALUE)
        val events = Channel<Event>(ingestionLimits.pendingFrames, onUndeliveredElement = { event ->
            if (event is Event.Binary) {
                queuedFrames.release(event.data.size)
                frameRejected(value)
            }
        })
        // A full data queue never blocks timer delivery or an OkHttp callback. One coalesced tick
        // is enough: deadlines use the monotonic clock, not a count of received tick messages.
        val ticks = Channel<Event.Tick>(Channel.CONFLATED)
        fun offer(event: Event) {
            if (accepting.get() && isCurrent(value) && !events.trySend(event).isSuccess) {
                // Bounded memory even if the receiver cannot keep up with server frames.
                events.close(ConnectionFailure("TRANSPORT_OVERFLOW"))
            }
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = offer(Event.Open)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!accepting.get() || !isCurrent(value)) return
                if (bytes.size > BiliBiliProtocol.MAX_FRAME_BYTES) {
                    frameRejected(value, oversized = true)
                    return
                }
                if (!queuedFrames.tryReserve(bytes.size)) {
                    frameRejected(value)
                    events.close(ConnectionFailure("TRANSPORT_OVERFLOW"))
                    return
                }
                val data = bytes.toByteArray()
                val receivedAt = nowMillis()
                lastFrameReceivedAt.set(receivedAt)
                // Only inspect packet headers in the callback; all decompression/JSON stays on
                // the parser dispatcher. An already-arrived acknowledgement must not time out
                // just because a large data frame is waiting for CPU time.
                if (BiliBiliProtocol.hasHeartbeatReply(data)) lastHeartbeatReplyAt.set(receivedAt)
                if (!events.trySend(Event.Binary(data, receivedAt)).isSuccess) {
                    queuedFrames.release(bytes.size)
                    frameRejected(value)
                    events.close(ConnectionFailure("TRANSPORT_OVERFLOW"))
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
                ticks.trySend(Event.Tick)
            }
        }
        var authenticated = false
        var opened = false
        var authStarted = nowMillis()
        var lastHeartbeatSent = authStarted
        var awaitingHeartbeatSince: Long? = null
        try {
            while (true) {
                // Biased select gives the heartbeat deadline priority over a busy data queue.
                val event = select<Event> {
                    ticks.onReceive { it }
                    events.onReceive { it }
                }
                ensureActive()
                if (!isCurrent(value)) throw CancellationException("Replaced connection")
                when (event) {
                    Event.Open -> {
                        opened = true
                        authStarted = nowMillis()
                        if (!socket.send(BiliBiliProtocol.buildAuthPacket(connection.realRoomId, connection.token, connection.buvid, connection.uid).toByteString())) {
                            throw ConnectionFailure("NETWORK_ERROR")
                        }
                    }
                    is Event.Binary -> {
                        queuedFrames.release(event.data.size)
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
                                BiliControlMessage.HeartbeatReply -> {
                                    if (awaitingHeartbeatSince?.let { event.receivedAt >= it } == true) awaitingHeartbeatSince = null
                                }
                            }
                        }
                        if (authenticated) {
                            val messages = BiliBiliProtocol.extractDanmaku(event.data).map { message ->
                                DanmakuItem(id = "${value.roomId}_${messageSequence.incrementAndGet()}",
                                    roomId = value.roomId, uid = message.uid, username = message.username,
                                    content = message.content, timestamp = message.timestamp,
                                    badgeName = message.badgeName, badgeLevel = message.badgeLevel)
                            }
                            synchronized(lock) {
                                if (session === value && messages.isNotEmpty()) {
                                    // A very large server frame may exceed the queue's message budget.
                                    // Retain bounded chunks that fit, preserving their order.
                                    var dropped = 0L
                                    for (batch in messages.chunked(256)) {
                                        if (!pendingDanmaku.offer(value.generation, batch)) dropped += batch.size
                                    }
                                    val old = _diagnostics.value
                                    _diagnostics.value = old.copy(
                                        decodedMessages = old.decodedMessages + messages.size,
                                        droppedMessages = old.droppedMessages + dropped)
                                    if (dropped > 0 && !old.hasLoss) _error.trySend(DanmakuError.fromCode(
                                        "TRANSPORT_OVERFLOW", DanmakuError.userMessage("TRANSPORT_OVERFLOW")))
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
                            if (awaitingHeartbeatSince?.let { lastHeartbeatReplyAt.get() >= it } == true) awaitingHeartbeatSince = null
                            if (now - lastFrameReceivedAt.get() >= timing.dataTimeout ||
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
            accepting.set(false)
            ticker.cancel()
            ticks.cancel()
            events.cancel() // onUndeliveredElement releases/counts every remaining raw frame.

            socket.cancel()
            synchronized(lock) { if (value.socket === socket) value.socket = null }
        }
    }

    fun disconnect() = synchronized(lock) {
        stopCurrentSession()
        pendingDanmaku.clear()
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
