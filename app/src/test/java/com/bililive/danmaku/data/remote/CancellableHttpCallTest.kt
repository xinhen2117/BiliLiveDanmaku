package com.bililive.danmaku.data.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class CancellableHttpCallTest {
    @Test fun responseIsConsumedAndClosed() = runTest {
        val call = FakeLoginHttpCall()
        val pending = async { call.awaitResponseData() }
        runCurrent()
        val body = TrackingLoginResponseBody("{\"code\":0}")
        call.respond(body)
        val response = pending.await()
        assertEquals(200, response.code)
        assertEquals("{\"code\":0}", response.body)
        assertEquals("SESSDATA=test-only-not-a-real-session", response.headers["Set-Cookie"])
        assertTrue(body.closed)
        assertFalse(call.cancelled)
    }

    @Test fun cancellationCancelsCallAndClosesLateResponseWithoutReadingIt() = runTest {
        val call = FakeLoginHttpCall()
        var delivered = false
        val job = launch { call.awaitResponseData(); delivered = true }
        runCurrent()
        job.cancel()
        assertTrue(call.cancelled)
        val body = TrackingLoginResponseBody("late response")
        call.respond(body)
        runCurrent()
        assertTrue(body.closed)
        assertEquals(0, body.reads)
        assertFalse(delivered)
    }

    @Test fun cancellationWinsOverResponseQueuedForCoroutineResumption() = runTest {
        val call = FakeLoginHttpCall()
        var delivered = false
        val job = launch { call.awaitResponseData(); delivered = true }
        runCurrent()
        val body = TrackingLoginResponseBody("queued response")
        call.respond(body)
        job.cancel()
        runCurrent()
        assertTrue(body.closed)
        assertTrue(call.cancelled)
        assertFalse(delivered)
    }

    @Test fun networkFailureReachesCaller() = runTest {
        val call = FakeLoginHttpCall()
        val expected = IOException("test network failure")
        var actual: Exception? = null
        val job = launch {
            try { call.awaitResponseData() } catch (e: IOException) { actual = e }
        }
        runCurrent()
        call.fail(expected)
        job.join()
        assertSame(expected, actual)
    }

    @Test fun responseBodyIsClosedWhenReadFails() = runTest {
        val call = FakeLoginHttpCall()
        val expected = IOException("test body failure")
        var actual: Exception? = null
        val job = launch {
            try { call.awaitResponseData() } catch (e: IOException) { actual = e }
        }
        runCurrent()
        val body = TrackingLoginResponseBody("body") { throw expected }
        call.respond(body)
        job.join()
        assertSame(expected, actual)
        assertTrue(body.closed)
    }

    @Test fun cancellationDuringBodyReadCancelsCallAndClosesResponse() = runTest {
        val call = FakeLoginHttpCall()
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable?>()
        call.onCancel = { cancelled.countDown() }
        val body = TrackingLoginResponseBody("body") {
            reading.countDown()
            check(cancelled.await(5, TimeUnit.SECONDS)) { "Call was not cancelled" }
            throw IOException("cancelled read")
        }
        var delivered = false
        val job = launch { call.awaitResponseData(); delivered = true }
        runCurrent()
        val callbackThread = thread(name = "test-response-body") {
            try { call.respond(body) } catch (e: Throwable) { callbackFailure.set(e) }
        }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            job.cancel()
            callbackThread.join(5000)
            assertFalse(callbackThread.isAlive)
            assertNull(callbackFailure.get())
            assertTrue(body.closed)
            assertTrue(call.cancelled)
            assertFalse(delivered)
        } finally {
            cancelled.countDown()
            callbackThread.join(5000)
        }
    }
}
