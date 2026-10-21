package com.bililive.danmaku.data.remote

import org.junit.Assert.*
import org.junit.Test

class DanmakuHostPolicyTest {
    @Test fun `only explicit trusted tls hosts survive before credentials are sent`() {
        val requests = DanmakuHostPolicy.requests(listOf(
            HostServer("a.bilibili.com", wssPort = 443), HostServer("b.bilibili.com", wssPort = 2245),
            HostServer("bilibili.com.evil.test"), HostServer("evilbilibili.com"),
            HostServer("a.bilibili.com", wssPort = 0), HostServer("a.bilibili.com", wssPort = 443)
        ), "synthetic-agent")
        assertEquals(listOf("a.bilibili.com", "b.bilibili.com"), requests.map { it.url.host })
        assertTrue(requests.all { it.url.isHttps && it.url.encodedPath == "/sub" && it.header("Cookie") == null })
        val connection = DanmakuConnection(requests[0], 99, "synthetic", alternateRequests = requests.drop(1))
        assertEquals("b.bilibili.com", connection.forAttempt(1).request.url.host)
        assertEquals("a.bilibili.com", connection.forAttempt(2).request.url.host)
        assertEquals("synthetic", connection.forAttempt(1).token)
        assertEquals(99L, connection.forAttempt(1).realRoomId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `all invalid hosts fail instead of using anonymous fallback`() {
        DanmakuHostPolicy.requests(listOf(HostServer("untrusted.example")), "test")
    }
}
