package com.bililive.danmaku.data.remote

import com.bililive.danmaku.domain.model.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
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
        override suspend fun resolve(roomId: Long): DanmakuConnection {
            rooms.add(roomId)
            resolveBlock?.invoke(roomId)
            if (fail) throw java.io.IOException("synthetic discovery failure")
            return DanmakuConnection(Request.Builder().url("http://localhost/socket").build(), roomId, "synthetic-token")
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
    private fun TestScope.client(source: Source, factory: Factory) = DanmakuWebSocketClient(
        source, factory,
        DanmakuWebSocketClient.ConnectionTiming(heartbeatInterval = 10, heartbeatTimeout = 15, dataTimeout = 100,
            authTimeout = 15, tickInterval = 1, retryDelays = listOf(10, 20)),
        nowMillis = { testScheduler.currentTime }
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
    fun `rejected or malformed auth never becomes connected or retries forever`() = runTest {
        for (reply in listOf(auth(-101), packet(8, "{}"), packet(8, "invalid"))) {
            val factory = Factory(); val client = client(Source(), factory)
            client.connect(1, backgroundScope, true); runCurrent()
            factory.sockets.single().open(); factory.sockets.single().binary(reply); runCurrent()
            assertEquals(ConnectionStatus.Failed, client.status.value)
            assertTrue(factory.sockets.single().cancelled)
            advanceTimeBy(100); runCurrent()
            assertEquals(1, factory.sockets.size)
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
