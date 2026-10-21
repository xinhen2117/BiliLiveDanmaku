package com.bililive.danmaku.data.remote

import com.bililive.danmaku.domain.model.ConnectionStatus
import com.bililive.danmaku.domain.model.DanmakuItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DanmakuWebSocketClientTest {
    private fun packet(op: Int, body: String = "", version: Int = 1): ByteArray {
        val bytes = body.toByteArray()
        return ByteBuffer.allocate(16 + bytes.size).apply {
            putInt(capacity()); putShort(16); putShort(version.toShort()); putInt(op); putInt(1); put(bytes)
        }.array()
    }
    private fun auth(code: Int = 0) = packet(8, "{\"code\":$code}")
    private fun heartbeat() = packet(3, "0001")
    private fun message() = packet(5, """{"cmd":"DANMU_MSG","info":[[],"hello",[1,"test"]]}""", 0)

    private class Source : DanmakuConnectionSource {
        val rooms = mutableListOf<Long>()
        var resolveBlock: (suspend (Long) -> Unit)? = null
        var fail = false
        var token = "synthetic-token"
        var alternates: List<Request> = emptyList()
        override suspend fun resolve(roomId: Long): DanmakuConnection {
            rooms.add(roomId)
            resolveBlock?.invoke(roomId)
            if (fail) throw java.io.IOException("synthetic discovery failure")
            return DanmakuConnection(Request.Builder().url("http://localhost/socket").build(), roomId, token, alternateRequests = alternates)
        }
    }
    private class Socket(private val requestValue: Request, val listener: WebSocketListener) : WebSocket {
        var cancelled = false
        var sendAccepted = true
        val sent = mutableListOf<ByteString>()
        override fun request(): Request = requestValue
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = sendAccepted
        override fun send(bytes: ByteString): Boolean { sent.add(bytes); return sendAccepted }
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() { cancelled = true }
        fun open() = listener.onOpen(this, Response.Builder().request(requestValue).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun binary(data: ByteArray) = listener.onMessage(this, data.toByteString())
        fun fail() = listener.onFailure(this, java.io.IOException("synthetic failure"), null)
        fun closed() = listener.onClosed(this, 1000, "test")
    }
    private class Factory : WebSocket.Factory {
        val sockets = mutableListOf<Socket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket = Socket(request, listener).also { sockets.add(it) }
    }
    private fun TestScope.client(source: Source, factory: Factory,
        limits: DanmakuWebSocketClient.IngestionLimits = DanmakuWebSocketClient.IngestionLimits()) = DanmakuWebSocketClient(
        source, factory,
        DanmakuWebSocketClient.ConnectionTiming(heartbeatInterval = 10, heartbeatTimeout = 15, dataTimeout = 100,
            authTimeout = 15, tickInterval = 1, retryDelays = listOf(10, 20)),
        nowMillis = { testScheduler.currentTime },
        dispatcher = StandardTestDispatcher(testScheduler), ingestionLimits = limits
    )

    @Test
    fun `upgrade alone is not authenticated connection`() = runTest {
        val source = Source(); val factory = Factory(); val client = client(source, factory)
        client.connect(1, backgroundScope, false); runCurrent()
        factory.sockets.single().open(); runCurrent()
        assertEquals(ConnectionStatus.Connecting, client.status.value)
        assertEquals(7, ByteBuffer.wrap(factory.sockets.single().sent.single().toByteArray()).getInt(8))
        factory.sockets.single().binary(auth()); runCurrent()
        assertTrue(client.isConnected())
        client.disconnect()
    }

    @Test
    fun `rejected or malformed auth refreshes once then stops`() = runTest {
        for (reply in listOf(auth(-101), packet(8, "{}"), packet(8, "invalid"))) {
            val factory = Factory(); val client = client(Source(), factory)
            client.connect(1, backgroundScope, true); runCurrent()
            factory.sockets.single().open(); factory.sockets.single().binary(reply); runCurrent()
            assertEquals(ConnectionStatus.Reconnecting, client.status.value)
            assertTrue(factory.sockets.single().cancelled)
            advanceTimeBy(10); runCurrent()
            val retried = factory.sockets.last()
            retried.open(); retried.binary(reply); runCurrent()
            assertEquals(ConnectionStatus.Failed, client.status.value)
            advanceTimeBy(100); runCurrent()
            assertEquals(2, factory.sockets.size)
            client.disconnect()
        }
    }

    @Test
    fun `auth outside first packet and mixed messages are delivered`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        client.connect(9, backgroundScope, false); runCurrent()
        val received = async(UnconfinedTestDispatcher(testScheduler)) { client.danmaku.first() }
        factory.sockets.single().open()
        factory.sockets.single().binary(heartbeat() + auth() + message()); runCurrent()
        assertTrue(client.isConnected())
        assertEquals("hello", received.await().content)
        assertEquals(9L, received.await().roomId)
        client.disconnect()
    }

    @Test
    fun `disconnect cancels discovery and blocks late noncancellable completion`() = runTest {
        val source = Source().apply { resolveBlock = { withContext(NonCancellable) { delay(20) } } }
        val factory = Factory(); val client = client(source, factory)
        client.connect(1, backgroundScope); runCurrent()
        client.disconnect(); advanceTimeBy(30); runCurrent()
        assertEquals(ConnectionStatus.Disconnected, client.status.value)
        assertTrue(factory.sockets.isEmpty())
    }

    @Test
    fun `old callbacks cannot close or change replacement room`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        client.connect(1, backgroundScope); runCurrent()
        val old = factory.sockets.single()
        client.connect(2, backgroundScope); runCurrent()
        val current = factory.sockets.last()
        current.open(); current.binary(auth()); runCurrent()
        old.open(); old.binary(auth(-101)); old.fail(); old.closed(); runCurrent()
        assertTrue(old.cancelled)
        assertFalse(current.cancelled)
        assertTrue(client.isConnected())
        assertEquals(2, factory.sockets.size)
        client.disconnect()
    }

    @Test
    fun `auto reconnect false stops after failure`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        client.connect(1, backgroundScope, false); runCurrent()
        factory.sockets.single().fail(); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(ConnectionStatus.Failed, client.status.value)
        assertEquals(1, factory.sockets.size)
        client.disconnect()
    }

    @Test
    fun `auto reconnect resolves room again after retry delay`() = runTest {
        val factory = Factory(); val source = Source(); val client = client(source, factory)
        client.connect(8, backgroundScope, true); runCurrent()
        factory.sockets.single().fail(); runCurrent()
        assertEquals(ConnectionStatus.Reconnecting, client.status.value)
        advanceTimeBy(9); runCurrent(); assertEquals(1, factory.sockets.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, factory.sockets.size)
        assertEquals(listOf(8L, 8L), source.rooms)
        client.disconnect()
    }

    @Test
    fun `disconnect cancels scheduled reconnect`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        client.connect(1, backgroundScope, true); runCurrent()
        factory.sockets.single().fail(); runCurrent()
        client.disconnect(); advanceTimeBy(100); runCurrent()
        assertEquals(1, factory.sockets.size)
        assertEquals(ConnectionStatus.Disconnected, client.status.value)
    }

    @Test
    fun `discovery failure respects reconnect setting`() = runTest {
        val source = Source().apply { fail = true }; val factory = Factory(); val client = client(source, factory)
        client.connect(1, backgroundScope, false); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(ConnectionStatus.Failed, client.status.value)
        assertEquals(1, source.rooms.size)
        assertTrue(factory.sockets.isEmpty())
        client.disconnect()
    }

    @Test
    fun `missing authentication times out`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        client.connect(1, backgroundScope, false); runCurrent()
        factory.sockets.single().open(); runCurrent()
        advanceTimeBy(15); runCurrent()
        assertEquals(ConnectionStatus.Failed, client.status.value)
        assertTrue(factory.sockets.single().cancelled)
        client.disconnect()
    }

    @Test
    fun `silent authentication timeout retries when enabled`() = runTest {
        val factory = Factory(); val source = Source(); val client = client(source, factory)
        client.connect(1, backgroundScope, true); runCurrent()
        factory.sockets.single().open(); runCurrent()
        advanceTimeBy(15); runCurrent()
        assertEquals(ConnectionStatus.Reconnecting, client.status.value)
        advanceTimeBy(10); runCurrent()
        assertEquals(2, factory.sockets.size)
        client.disconnect()
    }

    @Test
    fun `later heartbeats do not postpone oldest unacknowledged deadline`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        client.connect(1, backgroundScope, false); runCurrent()
        val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
        advanceTimeBy(20); runCurrent()
        socket.binary(message()); runCurrent() // Normal traffic is not a heartbeat acknowledgement.
        assertEquals(3, socket.sent.size) // Auth + heartbeats at 10 and 20.
        advanceTimeBy(5); runCurrent()
        assertEquals(ConnectionStatus.Failed, client.status.value)
        client.disconnect()
    }

    @Test
    fun `heartbeat acknowledgement clears deadline`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        client.connect(1, backgroundScope, false); runCurrent()
        val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
        advanceTimeBy(12); runCurrent(); socket.binary(heartbeat()); runCurrent()
        advanceTimeBy(13); runCurrent()
        assertTrue(client.isConnected())
        client.disconnect()
    }

    @Test
    fun `parent scope cancellation disconnects session`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        client.connect(1, owner); runCurrent()
        owner.cancel(); runCurrent()
        assertTrue(factory.sockets.single().cancelled)
        assertEquals(ConnectionStatus.Disconnected, client.status.value)
    }

    @Test
    fun `cancelled owner cannot replace an active session`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        client.connect(1, backgroundScope); runCurrent()
        val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
        val cancelled = CoroutineScope(Job().apply { cancel() } + StandardTestDispatcher(testScheduler))
        client.connect(2, cancelled); runCurrent()
        assertTrue(client.isConnected())
        assertFalse(socket.cancelled)
        assertEquals(1, factory.sockets.size)
        client.disconnect()
    }

    @Test
    fun `queued frame byte overflow fails safely without unlimited allocation`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        client.connect(1, backgroundScope, false); runCurrent()
        val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
        val largeFrame = ByteArray(BiliBiliProtocol.MAX_FRAME_BYTES).toByteString()
        repeat(3) { socket.listener.onMessage(socket, largeFrame) }
        runCurrent()
        assertEquals(ConnectionStatus.Failed, client.status.value)
        assertTrue(socket.cancelled)
        client.disconnect()
    }


    // Synthetic offline fixtures. No login, live room, cookie, or personal message data.
    private fun binaryPacket(body: ByteArray, version: Int): ByteArray =
        ByteBuffer.allocate(16 + body.size).apply {
            putInt(capacity()); putShort(16); putShort(version.toShort())
            putInt(5); putInt(1); put(body)
        }.array()

    private fun burst(count: Int = 200, compressed: Boolean = false): ByteArray {
        val raw = ByteArrayOutputStream().also { output ->
            repeat(count) { index ->
                output.write(packet(5,
                    """{"cmd":"DANMU_MSG","info":[[],"burst-$index",[1,"synthetic-viewer"]]}""", 0))
            }
        }.toByteArray()
        if (!compressed) return raw
        val encoded = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(raw) }
        }.toByteArray()
        return binaryPacket(encoded, 2)
    }

    @Test
    fun `one frame delivers all 200 ordinary or zlib messages in order`() = runTest {
        // StandardTestDispatcher is intentional: a ready receiver must not be relied on to run
        // between non-suspending sends in the decoder's per-frame loop.
        for (compressed in listOf(false, true)) {
            val factory = Factory(); val client = client(Source(), factory)
            val received = mutableListOf<DanmakuItem>()
            client.connect(1, backgroundScope, false); runCurrent()
            val collector = backgroundScope.launch { client.danmaku.collect { received.add(it) } }
            runCurrent()
            val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
            socket.binary(burst(compressed = compressed)); runCurrent()
            assertTrue("Burst must not disconnect the socket", client.isConnected())
            assertEquals("compressed=$compressed", (0 until 200).map { "burst-$it" }, received.map { it.content })
            assertEquals(200, received.map { it.id }.distinct().size)
            collector.cancel(); client.disconnect(); runCurrent()
        }
    }

    @Test
    fun `concatenated brotli packets do not lose or content deduplicate messages`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        val received = mutableListOf<DanmakuItem>()
        client.connect(1, backgroundScope, false); runCurrent()
        val collector = backgroundScope.launch { client.danmaku.collect { received.add(it) } }
        runCurrent()
        val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
        // Same verified synthetic Brotli fixture as BiliBiliProtocolTest. Repeated equal text
        // represents separate real arrivals, not a transport-level duplicate.
        val body = Base64.getDecoder().decode("CyKAAAAARQAQAAAAAAAFAAAAAXsiY21kIjoiREFOTVVfTVNHIiwiaW5mbyI6W1tdLCJCcm90bGkiLFs3LCJ0ZXN0ZXIiXV19Aw==")
        val frame = ByteArrayOutputStream().also { output ->
            repeat(200) { output.write(binaryPacket(body, 3)) }
        }.toByteArray()
        socket.binary(frame); runCurrent()
        assertEquals(200, received.size)
        assertTrue(received.all { it.content == "Brotli" })
        assertEquals(200, received.map { it.id }.distinct().size)
        collector.cancel(); client.disconnect()
    }

    @Test
    fun `small burst remains available when collector attaches after decoding`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        val received = mutableListOf<DanmakuItem>()
        client.connect(1, backgroundScope, false); runCurrent()
        val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
        // Well below the bounded batch memory budget, so absence of a collector is not overload.
        socket.binary(burst(compressed = true)); runCurrent()
        val collector = backgroundScope.launch { client.danmaku.collect { received.add(it) } }
        runCurrent()
        assertEquals((0 until 200).map { "burst-$it" }, received.map { it.content })
        collector.cancel(); client.disconnect()
    }

    @Test
    fun `slow consumer cannot block auth heartbeat control processing`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        val firstDelivery = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        var delivered = 0
        client.connect(1, backgroundScope, false); runCurrent()
        val collector = backgroundScope.launch {
            client.danmaku.collect {
                delivered++
                firstDelivery.complete(Unit)
                releaseConsumer.await()
            }
        }
        runCurrent()
        val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
        socket.binary(burst(compressed = true)); runCurrent()
        assertTrue(firstDelivery.isCompleted)
        assertEquals(1, delivered)
        // Holding the consumer past heartbeatTimeout must not hold the session's actor.
        repeat(5) {
            advanceTimeBy(10); runCurrent()
            socket.binary(heartbeat()); runCurrent()
            assertTrue("Connected despite held consumer, tick=$it", client.isConnected())
        }
        assertEquals(5, socket.sent.count { ByteBuffer.wrap(it.toByteArray()).getInt(8) == 2 })
        releaseConsumer.complete(Unit); runCurrent()
        assertEquals(200, delivered)
        collector.cancel(); client.disconnect()
    }

    @Test
    fun `room replacement cancels queued and in flight old batch tail`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        val received = mutableListOf<DanmakuItem>()
        val releaseFirst = CompletableDeferred<Unit>()
        client.connect(1, backgroundScope, false); runCurrent()
        val collector = backgroundScope.launch {
            client.danmaku.collect {
                received.add(it)
                if (received.size == 1) releaseFirst.await()
            }
        }
        runCurrent()
        val old = factory.sockets.single(); old.open(); old.binary(auth()); runCurrent()
        old.binary(burst(compressed = true)); runCurrent()
        assertEquals(1, received.size)
        client.connect(2, backgroundScope, false); runCurrent()
        val current = factory.sockets.last(); current.open(); current.binary(auth()); runCurrent()
        current.binary(message()); runCurrent()
        // Also reject a late callback from the old transport while its first item is displayed.
        old.binary(message()); runCurrent()
        releaseFirst.complete(Unit); runCurrent()
        assertEquals(listOf(1L, 2L), received.map { it.roomId })
        assertEquals("hello", received.last().content)
        assertTrue(client.isConnected())
        collector.cancel(); client.disconnect()
    }

    @Test
    fun `decoded batch overflow is explicit and does not block later heartbeat`() = runTest {
        val factory = Factory()
        val client = client(Source(), factory, DanmakuWebSocketClient.IngestionLimits(pendingMessages = 1))
        client.connect(1, backgroundScope, false); runCurrent()
        val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
        socket.binary(message() + message()); runCurrent()
        assertEquals(2L, client.diagnostics.value.decodedMessages)
        assertEquals(2L, client.diagnostics.value.droppedMessages)
        assertEquals(0L, client.diagnostics.value.droppedFrames)
        repeat(3) { advanceTimeBy(10); runCurrent(); socket.binary(heartbeat()); runCurrent() }
        assertTrue(client.isConnected())
        assertEquals("TRANSPORT_OVERFLOW", client.error.first().code)
        client.disconnect()
    }

    @Test
    fun `oversized raw frames are counted without inventing message counts`() = runTest {
        val factory = Factory(); val client = client(Source(), factory)
        client.connect(1, backgroundScope, false); runCurrent()
        val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
        socket.binary(ByteArray(BiliBiliProtocol.MAX_FRAME_BYTES + 1)); runCurrent()
        assertEquals(1L, client.diagnostics.value.droppedFrames)
        assertEquals(1L, client.diagnostics.value.oversizedFrames)
        assertEquals(0L, client.diagnostics.value.droppedMessages)
        assertTrue(client.isConnected())
        client.connect(2, backgroundScope, false); runCurrent()
        assertEquals(0L, client.diagnostics.value.droppedFrames)
        client.disconnect()
    }

    @Test
    fun `auth rejection does not refresh when automatic reconnect is disabled`() = runTest {
        val factory = Factory(); val source = Source(); val client = client(source, factory)
        client.connect(1, backgroundScope, false); runCurrent()
        factory.sockets.single().open(); factory.sockets.single().binary(auth(-101)); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(ConnectionStatus.Failed, client.status.value)
        assertEquals(1, source.rooms.size)
        client.disconnect()
    }

    @Test
    fun `queued heartbeat acknowledgement survives a delayed parser and priority tick`() = runTest {
        var clockOffset = 0L
        val factory = Factory()
        val client = DanmakuWebSocketClient(Source(), factory,
            DanmakuWebSocketClient.ConnectionTiming(heartbeatInterval = 10, heartbeatTimeout = 15,
                dataTimeout = 100, authTimeout = 15, tickInterval = 1, retryDelays = listOf(10)),
            nowMillis = { testScheduler.currentTime + clockOffset }, dispatcher = StandardTestDispatcher(testScheduler))
        client.connect(1, backgroundScope, false); runCurrent()
        val socket = factory.sockets.single(); socket.open(); socket.binary(auth()); runCurrent()
        advanceTimeBy(10); runCurrent()
        advanceTimeBy(1)
        socket.binary(message())
        socket.binary(heartbeat()) // Received on time, but still behind data in the actor queue.
        clockOffset = 14 // Simulate a GC/CPU pause until the oldest heartbeat deadline.
        runCurrent()
        assertTrue(client.isConnected())
        assertFalse(socket.cancelled)
        client.disconnect()
    }

    @Test
    fun `reconnect rotates trusted supplied endpoints and refreshes credentials`() = runTest {
        val source = Source().apply {
            alternates = listOf(Request.Builder().url("http://localhost/second").build())
        }
        val factory = Factory(); val client = client(source, factory)
        client.connect(1, backgroundScope, true); runCurrent()
        factory.sockets.single().open(); factory.sockets.single().binary(auth(-101)); runCurrent()
        source.token = "fresh-synthetic-token"
        advanceTimeBy(10); runCurrent()
        val socket = factory.sockets.last()
        assertEquals("/second", socket.request().url.encodedPath)
        socket.open(); runCurrent()
        assertTrue(socket.sent.single().utf8().contains("fresh-synthetic-token"))
        socket.binary(auth()); runCurrent()
        assertTrue(client.isConnected())
        assertEquals(2, source.rooms.size)
        client.disconnect()
    }

    @Test(timeout = 15000)
    fun `default transport never follows redirect before sending auth token`() = runBlocking {
        val sourceServer = MockWebServer()
        val redirectTarget = MockWebServer()
        sourceServer.start(); redirectTarget.start()
        sourceServer.enqueue(MockResponse().setResponseCode(302).addHeader("Location", redirectTarget.url("/untrusted")))
        val source = object : DanmakuConnectionSource {
            override suspend fun resolve(roomId: Long) = DanmakuConnection(
                Request.Builder().url(sourceServer.url("/sub")).build(), roomId, "synthetic-secret"
            )
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = DanmakuWebSocketClient(source)
        try {
            client.connect(1, owner, false)
            withTimeout(5000) { client.status.first { it == ConnectionStatus.Failed } }
            assertEquals(1, sourceServer.requestCount)
            assertEquals(0, redirectTarget.requestCount)
        } finally {
            client.destroy(); owner.cancel(); sourceServer.shutdown(); redirectTarget.shutdown()
        }
    }

    @Test(timeout = 15000)
    fun `real OkHttp WebSocket handles auth mixed frames and server close`() = runBlocking {
        val server = MockWebServer()
        val receivedAuth = CompletableDeferred<ByteString>()
        val accepted = CompletableDeferred<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { accepted.complete(webSocket) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                receivedAuth.complete(bytes)
                webSocket.send((heartbeat() + auth() + message()).toByteString())
            }
        }))
        server.start()
        val transport = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val source = object : DanmakuConnectionSource {
            override suspend fun resolve(roomId: Long) = DanmakuConnection(Request.Builder().url(server.url("/sub")).build(), roomId)
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = DanmakuWebSocketClient(source, transport)
        try {
            val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { client.danmaku.first() } }
            client.connect(123, owner, false)
            withTimeout(5000) { client.status.first { it == ConnectionStatus.Connected } }
            assertEquals(7, ByteBuffer.wrap(withTimeout(5000) { receivedAuth.await() }.toByteArray()).getInt(8))
            assertEquals("hello", message.await().content)
            withTimeout(5000) { accepted.await() }.close(1000, "done")
            withTimeout(5000) { client.status.first { it == ConnectionStatus.Failed } }
            assertEquals(1, server.requestCount)
        } finally {
            client.destroy(); owner.cancel(); server.shutdown()
        }
    }
}
