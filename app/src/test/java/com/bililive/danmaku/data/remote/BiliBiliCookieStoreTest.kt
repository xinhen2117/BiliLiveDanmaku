package com.bililive.danmaku.data.remote

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.http.BridgeInterceptor
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class BiliBiliCookieStoreTest {
    private val origin = "https://api.bilibili.com/login".toHttpUrl()
    private val now = 1_700_000_000_000L

    @Test
    fun `login and browser cookies share one request snapshot`() {
        val store = store { loginSnapshot("account-a") }
        store.saveFromResponse(origin, listOf(cookie("buvid3", "device-only")))

        val cookies = store.loadForRequest("https://api.bilibili.com/x/web-interface/nav".toHttpUrl())

        assertEquals(setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "buvid3"),
            cookies.map { it.name }.toSet())
        assertEquals("account-a", cookies.single { it.name == "SESSDATA" }.value)
        assertTrue(cookies.filter { it.name != "buvid3" }.all { it.secure && it.httpOnly })
    }

    @Test
    fun `account cookies reach exact domain and real subdomains only`() {
        val store = store { loginSnapshot("synthetic-session") }
        listOf("bilibili.com", "api.bilibili.com", "broadcast.chat.bilibili.com").forEach { host ->
            assertEquals(host, 4, store.loadForRequest("https://$host/".toHttpUrl()).size)
        }
        listOf("evilbilibili.com", "bilibili.com.evil.test", "bilibili.example", "example.com",
            "127.0.0.1").forEach { host ->
            assertTrue(host, store.loadForRequest("https://$host/".toHttpUrl()).isEmpty())
        }
    }

    @Test
    fun `account cookies never travel over cleartext HTTP`() {
        val store = store { loginSnapshot("synthetic-session") }
        listOf("bilibili.com", "api.bilibili.com").forEach { host ->
            assertTrue(store.loadForRequest("http://$host/".toHttpUrl()).isEmpty())
        }
    }

    @Test
    fun `login provider is read once per eligible request and not read for other origins`() {
        val reads = AtomicInteger()
        val store = store {
            loginSnapshot("account-${reads.incrementAndGet()}")
        }

        store.loadForRequest("https://evilbilibili.com/".toHttpUrl())
        store.loadForRequest("http://bilibili.com/".toHttpUrl())
        assertEquals(0, reads.get())
        val first = store.loadForRequest(origin).associate { it.name to it.value }
        assertEquals(1, reads.get())
        assertEquals("account-1", first["SESSDATA"])
        assertEquals("account-1-csrf", first["bili_jct"])
        val second = store.loadForRequest(origin).associate { it.name to it.value }
        assertEquals(2, reads.get())
        assertEquals("account-2", second["SESSDATA"])
    }

    @Test
    fun `account switch and logout take effect without cached account cookies`() {
        val login = AtomicReference(loginSnapshot("account-a"))
        val store = store(login::get)
        store.saveFromResponse(origin, listOf(cookie("buvid3", "device-only")))
        assertEquals("account-a", store.loadForRequest(origin).single { it.name == "SESSDATA" }.value)

        login.set(loginSnapshot("account-b"))
        assertEquals("account-b", store.loadForRequest(origin).single { it.name == "SESSDATA" }.value)

        login.set("")
        assertEquals(listOf("buvid3"), store.loadForRequest(origin).map { it.name })
    }

    @Test
    fun `server cookies cannot replace account cookies or restore logout`() {
        val login = AtomicReference(loginSnapshot("current-account"))
        val store = store(login::get)
        val stale = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
            .map { cookie(it, "stale-response", path = "/login") }
        store.saveFromResponse(origin, stale)
        val current = store.loadForRequest(origin)
        assertEquals(4, current.size)
        assertFalse(current.any { it.value == "stale-response" })

        login.set("")
        store.saveFromResponse(origin, stale) // An old in-flight request finishes after logout.
        assertTrue(store.loadForRequest(origin).isEmpty())
    }

    @Test
    fun `auxiliary account cookies without a session are not sent`() {
        val store = store { "bili_jct=orphan; DedeUserID=123; DedeUserID__ckMd5=orphan" }
        assertTrue(store.loadForRequest(origin).isEmpty())
    }

    @Test
    fun `malformed session cannot crash requests or inject headers`() {
        val store = store { "SESSDATA=synthetic\r\nX-Injected: true; bili_jct=orphan" }
        assertTrue(store.loadForRequest(origin).isEmpty())
    }

    @Test
    fun `only supported login names are synthesized and values preserve equals`() {
        val store = store { "SESSDATA=synthetic==; unexpected=ignored; Path=/; bili_jct=test-csrf" }
        assertEquals(mapOf("SESSDATA" to "synthetic==", "bili_jct" to "test-csrf"),
            store.loadForRequest(origin).associate { it.name to it.value })
    }

    @Test
    fun `domain browser cookie never matches a suffix spoof`() {
        val store = store()
        store.saveFromResponse(origin, listOf(cookie("buvid3", "device-only")))
        assertTrue(store.loadForRequest("https://evilbilibili.com/".toHttpUrl()).isEmpty())
        assertTrue(store.loadForRequest("https://bilibili.com.evil.test/".toHttpUrl()).isEmpty())
        assertEquals(1, store.loadForRequest("https://live.bilibili.com/".toHttpUrl()).size)
    }

    @Test
    fun `host only cookie stays on the originating host`() {
        val store = store()
        val hostCookie = Cookie.Builder().name("host-cookie").value("synthetic")
            .hostOnlyDomain("api.bilibili.com").path("/").build()
        store.saveFromResponse(origin, listOf(hostCookie))

        assertEquals(listOf(hostCookie), store.loadForRequest(origin))
        assertTrue(store.loadForRequest("https://sub.api.bilibili.com/".toHttpUrl()).isEmpty())
        assertTrue(store.loadForRequest("https://live.bilibili.com/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `secure browser cookie is withheld from HTTP`() {
        val store = store()
        store.saveFromResponse(origin, listOf(cookie("secure", "yes", secure = true),
            cookie("ordinary", "yes")))
        assertEquals(setOf("secure", "ordinary"), store.loadForRequest(origin).map { it.name }.toSet())
        assertEquals(listOf("ordinary"), store.loadForRequest("http://api.bilibili.com/".toHttpUrl()).map { it.name })
    }

    @Test
    fun `path cookies use path segment boundaries`() {
        val store = store()
        store.saveFromResponse(origin, listOf(cookie("scoped", "yes", path = "/live")))
        listOf("/live", "/live/", "/live/room").forEach { path ->
            assertEquals(path, 1, store.loadForRequest("https://api.bilibili.com$path".toHttpUrl()).size)
        }
        listOf("/", "/lively", "/other/live").forEach { path ->
            assertTrue(path, store.loadForRequest("https://api.bilibili.com$path".toHttpUrl()).isEmpty())
        }
    }

    @Test
    fun `expired cookie is removed at expiry not just at insertion`() {
        val clock = AtomicLong(now)
        val store = BiliBiliCookieStore(currentTimeMillis = clock::get)
        store.saveFromResponse(origin, listOf(cookie("temporary", "yes", expiresAt = now + 1)))
        assertEquals(1, store.loadForRequest(origin).size)
        clock.incrementAndGet()
        assertTrue(store.loadForRequest(origin).isEmpty())
        clock.decrementAndGet()
        assertTrue(store.loadForRequest(origin).isEmpty())
    }

    @Test
    fun `already expired cookie is not stored`() {
        val store = store()
        store.saveFromResponse(origin, listOf(cookie("expired", "no", expiresAt = now - 1)))
        assertTrue(store.loadForRequest(origin).isEmpty())
    }

    @Test
    fun `replacement and deletion use name domain and path identity`() {
        val store = store()
        store.saveFromResponse(origin, listOf(cookie("same", "root"), cookie("same", "scoped", path = "/login")))
        assertEquals(listOf("scoped", "root"), store.loadForRequest(origin).map { it.value })
        store.saveFromResponse(origin, listOf(cookie("same", "replacement", path = "/login")))
        assertEquals(listOf("replacement", "root"), store.loadForRequest(origin).map { it.value })
        store.saveFromResponse(origin, listOf(cookie("same", "deleted", path = "/login", expiresAt = now)))
        assertEquals(listOf("root"), store.loadForRequest(origin).map { it.value })
    }

    @Test
    fun `same cookie name in different domains remains separate`() {
        val store = store()
        store.saveFromResponse(origin, listOf(cookie("same", "parent"),
            cookie("same", "subdomain", domain = "api.bilibili.com")))
        assertEquals(setOf("parent", "subdomain"), store.loadForRequest(origin).map { it.value }.toSet())
        assertEquals(listOf("parent"), store.loadForRequest("https://live.bilibili.com/".toHttpUrl()).map { it.value })
    }

    @Test
    fun `unrelated response origin cannot set bilibili browser cookies`() {
        val store = store()
        store.saveFromResponse("https://evilbilibili.com/".toHttpUrl(), listOf(cookie("buvid3", "spoofed")))
        assertTrue(store.loadForRequest(origin).isEmpty())
    }

    @Test
    fun `public suffix cookies cannot broaden the bilibili boundary`() {
        val store = store()
        store.saveFromResponse(origin, listOf(cookie("tracking", "synthetic", domain = "com")))
        assertTrue(store.loadForRequest(origin).isEmpty())
        assertTrue(store.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `application cookie jar does not collect third party cookies`() {
        val store = store()
        val thirdParty = "https://example.com/".toHttpUrl()
        store.saveFromResponse(thirdParty, listOf(cookie("unknown-session", "synthetic", domain = "example.com")))
        assertTrue(store.loadForRequest(thirdParty).isEmpty())
        assertTrue(store.loadForRequest(origin).isEmpty())
    }

    @Test
    fun `insecure response cannot inject a secure browser cookie`() {
        val store = store()
        store.saveFromResponse("http://api.bilibili.com/".toHttpUrl(),
            listOf(cookie("secure", "synthetic", secure = true)))
        assertTrue(store.loadForRequest(origin).isEmpty())
    }

    @Test
    fun `buvid lookup respects matching and expiry without reading login`() {
        val clock = AtomicLong(now)
        val store = BiliBiliCookieStore(loginCookieProvider = { error("No account lookup needed") },
            currentTimeMillis = clock::get)
        store.saveFromResponse(origin, listOf(cookie("buvid3", "device-only", expiresAt = now + 1)))
        val live = "https://live.bilibili.com/".toHttpUrl()
        assertEquals("device-only", store.browserCookieValue("buvid3", live))
        assertEquals("", store.browserCookieValue("buvid3", "https://evilbilibili.com/".toHttpUrl()))
        clock.incrementAndGet()
        assertEquals("", store.browserCookieValue("buvid3", live))
    }

    @Test
    fun `clearing browser cookies leaves current account under login manager control`() {
        val store = store { loginSnapshot("current-account") }
        store.saveFromResponse(origin, listOf(cookie("buvid3", "device-only")))
        store.clear()
        assertEquals(4, store.loadForRequest(origin).size)
        assertFalse(store.loadForRequest(origin).any { it.name == "buvid3" })
    }

    @Test
    fun `simultaneous writes reads and clears do not mutate shared lists`() {
        val store = store()
        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = (0 until 4).map { worker ->
                workers.submit {
                    start.await()
                    repeat(300) { iteration ->
                        store.saveFromResponse(origin, listOf(cookie("worker-$worker", "value-$iteration")))
                        val snapshot = store.loadForRequest(origin)
                        assertEquals(snapshot.size, snapshot.map { Triple(it.name, it.domain, it.path) }.toSet().size)
                        if (iteration % 31 == 0) store.clear()
                    }
                }
            }
            start.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `OkHttp bridge retains both login and browser cookies for WebSocket handshake`() {
        val store = store { loginSnapshot("synthetic-session") }
        store.saveFromResponse(origin, listOf(cookie("buvid3", "device-only")))
        // OkHttp normalizes wss to https before consulting the CookieJar.
        val request = Request.Builder().url("wss://broadcast.chat.bilibili.com/sub").build()
        val chain = RecordingChain(request)

        BridgeInterceptor(store).intercept(chain).close()

        val header = requireNotNull(chain.sentRequest).header("Cookie").orEmpty()
        assertTrue(header.contains("SESSDATA=synthetic-session"))
        assertTrue(header.contains("buvid3=device-only"))
        assertEquals(1, header.split("; ").count { it.startsWith("SESSDATA=") })
    }

    @Test
    fun `OkHttp bridge sends no account credentials on untrusted WebSocket host`() {
        val store = store { loginSnapshot("synthetic-session") }
        store.saveFromResponse(origin, listOf(cookie("buvid3", "device-only")))
        val chain = RecordingChain(Request.Builder().url("wss://evilbilibili.com/sub").build())

        BridgeInterceptor(store).intercept(chain).close()

        assertNull(requireNotNull(chain.sentRequest).header("Cookie"))
    }

    private fun store(provider: () -> String = { "" }) =
        BiliBiliCookieStore(loginCookieProvider = provider, currentTimeMillis = { now })

    private fun loginSnapshot(session: String) =
        "SESSDATA=$session; bili_jct=$session-csrf; DedeUserID=123; DedeUserID__ckMd5=$session-check"

    private fun cookie(
        name: String,
        value: String,
        domain: String = "bilibili.com",
        path: String = "/",
        secure: Boolean = false,
        expiresAt: Long? = null
    ): Cookie = Cookie.Builder().name(name).value(value).domain(domain).path(path).apply {
        if (secure) secure()
        if (expiresAt != null) expiresAt(expiresAt)
    }.build()

    private class RecordingChain(private val original: Request) : Interceptor.Chain {
        var sentRequest: Request? = null
        override fun request() = original
        override fun proceed(request: Request): Response {
            sentRequest = request
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("synthetic response").body("".toResponseBody()).build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = error("No real network call in this test")
        override fun connectTimeoutMillis() = 1000
        override fun readTimeoutMillis() = 1000
        override fun writeTimeoutMillis() = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }
}
