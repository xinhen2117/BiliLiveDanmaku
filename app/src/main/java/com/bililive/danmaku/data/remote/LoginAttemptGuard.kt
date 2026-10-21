package com.bililive.danmaku.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Serializes login replacement, logout and credential/profile writes. A request cannot regain
 * authority merely by returning late. Network cancellation is best-effort; the attempt check is
 * what prevents a late callback from restoring an old account.
 */
internal class LoginAttemptGuard {
    enum class Kind { QR, SMS, COOKIE }

    class Attempt internal constructor(internal val kind: Kind) {
        internal var qrKey: String? = null
        internal val calls = mutableSetOf<Call>()
    }

    private val lock = Any()
    private var current: Attempt? = null

    fun begin(kind: Kind): Attempt {
        val attempt = Attempt(kind)
        val calls = synchronized(lock) {
            val oldCalls = current?.calls?.toList().orEmpty()
            current = attempt
            oldCalls
        }
        calls.forEach(Call::cancel)
        return attempt
    }

    fun bindQrKey(attempt: Attempt, key: String, context: CoroutineContext) {
        withCurrent(attempt, context) { attempt.qrKey = key }
    }

    fun qrAttempt(key: String): Attempt = synchronized(lock) {
        current?.takeIf { it.kind == Kind.QR && it.qrKey == key }
            ?: throw CancellationException("QR login is no longer active")
    }

    fun cancelQr() = invalidate { it.kind == Kind.QR }

    fun cancelInteractive() = invalidate { it.kind != Kind.COOKIE }

    fun cancel(attempt: Attempt) = invalidate { it === attempt }

    private fun invalidate(matches: (Attempt) -> Boolean) {
        val calls = synchronized(lock) {
            val attempt = current?.takeIf(matches) ?: return
            current = null
            attempt.calls.toList()
        }
        calls.forEach(Call::cancel)
    }

    /** Clearing storage shares the same lock as successful login/profile commits. */
    fun logout(clearStorage: () -> Unit) {
        val calls = synchronized(lock) {
            val oldCalls = current?.calls?.toList().orEmpty()
            current = null
            clearStorage()
            oldCalls
        }
        calls.forEach(Call::cancel)
    }

    fun <T> read(block: () -> T): T = synchronized(lock, block)

    fun <T> withCurrent(
        attempt: Attempt,
        context: CoroutineContext = EmptyCoroutineContext,
        block: () -> T
    ): T = synchronized(lock) {
        context.ensureActive()
        if (current !== attempt) throw CancellationException("Login attempt was replaced or cancelled")
        block()
    }

    suspend fun execute(attempt: Attempt, call: Call): HttpResponseData {
        val context = currentCoroutineContext()
        try {
            withCurrent(attempt, context) { attempt.calls.add(call) }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
        try {
            val response = call.awaitResponseData()
            return withCurrent(attempt, context) { response }
        } catch (e: Exception) {
            // Call.cancel() may report IOException. Preserve structured cancellation when the
            // caller or attempt has been cancelled instead of treating it as a retryable error.
            withCurrent(attempt, context) { throw e }
        } finally {
            synchronized(lock) { attempt.calls.remove(call) }
        }
    }
}
