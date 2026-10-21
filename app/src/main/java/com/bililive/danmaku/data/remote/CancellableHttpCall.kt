package com.bililive.danmaku.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A fully consumed response: no open socket/body is handed across a cancellation boundary. */
internal data class HttpResponseData(val code: Int, val headers: Headers, val body: String)

/** Cancelling the coroutine cancels both the request and a response body still being read. */
internal suspend fun Call.awaitResponseData(): HttpResponseData = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use {
                    if (!continuation.isActive) return
                    HttpResponseData(it.code, it.headers, it.body?.string().orEmpty())
                }
                continuation.resume(result)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    })
}
