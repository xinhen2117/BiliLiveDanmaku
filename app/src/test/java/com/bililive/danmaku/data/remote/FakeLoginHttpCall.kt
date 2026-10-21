package com.bililive.danmaku.data.remote

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import java.io.IOException

/** Allows a response to arrive even after cancel(), as real queued callbacks can. */
internal class FakeLoginHttpCall(
    private val originalRequest: Request = Request.Builder().url("https://example.invalid/login-test").build()
) : Call {
    private lateinit var callback: Callback
    private var executed = false
    var cancelled = false
        private set
    var onCancel: () -> Unit = {}

    override fun request(): Request = originalRequest
    override fun execute(): Response = error("Only asynchronous calls are allowed")
    override fun enqueue(responseCallback: Callback) {
        check(!executed)
        executed = true
        callback = responseCallback
    }
    override fun cancel() { cancelled = true; onCancel() }
    override fun isExecuted(): Boolean = executed
    override fun isCanceled(): Boolean = cancelled
    override fun timeout(): Timeout = Timeout.NONE
    override fun clone(): Call = FakeLoginHttpCall()

    fun respond(
        body: ResponseBody = TrackingLoginResponseBody("{}"),
        cookies: List<String> = listOf("SESSDATA=test-only-not-a-real-session"),
        code: Int = 200
    ) {
        val response = Response.Builder().request(originalRequest)
            .protocol(Protocol.HTTP_1_1).code(code).message("Test response").body(body)
        cookies.forEach { response.addHeader("Set-Cookie", it) }
        callback.onResponse(this, response.build())
    }

    fun respondJson(json: String, cookies: List<String> = emptyList(), code: Int = 200) =
        respond(TrackingLoginResponseBody(json), cookies, code)

    fun fail(e: IOException) = callback.onFailure(this, e)
}

internal class TrackingLoginResponseBody(
    content: String,
    private val beforeRead: () -> Unit = {}
) : ResponseBody() {
    var closed = false
        private set
    var reads = 0
        private set
    private val bytes = content.toByteArray()
    private val buffered = object : ForwardingSource(Buffer().write(bytes)) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            reads++
            beforeRead()
            return super.read(sink, byteCount)
        }
        override fun close() { closed = true; super.close() }
    }.buffer()

    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = bytes.size.toLong()
    override fun source(): BufferedSource = buffered
}
