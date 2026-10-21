package com.bililive.danmaku.ui.login

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QrLoginPollingTest {
    @Test
    fun `network failure exposes retry and stops further requests`() = assertTerminalResult(
        Result.failure(IOException("synthetic request failure")), QrLoginStatus.POLL_FAILED
    )

    @Test
    fun `expired challenge exposes refresh and stops polling`() = assertTerminalResult(
        Result.success(3), QrLoginStatus.EXPIRED
    )

    @Test
    fun `success is emitted once and stops polling`() = assertTerminalResult(
        Result.success(0), QrLoginStatus.SUCCESS
    )

    @Test
    fun `unknown response exposes retry instead of waiting forever`() = assertTerminalResult(
        Result.success(999), QrLoginStatus.UNKNOWN
    )

    @Test
    fun `thrown network exception also becomes a visible retry state`() = runTest {
        val states = mutableListOf<QrLoginStatus>()
        pollQrLogin(
            request = { throw IOException("synthetic request failure") },
            isCurrent = { true },
            onStatus = { states.add(it) }
        )
        assertEquals(listOf(QrLoginStatus.POLL_FAILED), states)
    }

    @Test
    fun `waiting and scanned responses keep polling until login succeeds`() = runTest {
        val responses = ArrayDeque(listOf(1, 2, 2, 0))
        val states = mutableListOf<QrLoginStatus>()
        pollQrLogin(
            request = { Result.success(responses.removeFirst()) },
            isCurrent = { true },
            onStatus = { states.add(it) }
        )
        assertEquals(
            listOf(QrLoginStatus.READY, QrLoginStatus.SCANNED, QrLoginStatus.SCANNED, QrLoginStatus.SUCCESS),
            states
        )
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test
    fun `network loss after scanning does not leave the UI in scanned state`() = runTest {
        val states = mutableListOf<QrLoginStatus>()
        var requests = 0
        pollQrLogin(
            request = {
                if (++requests == 1) Result.success(2) else Result.failure(IOException())
            },
            isCurrent = { true },
            onStatus = { states.add(it) }
        )
        assertEquals(listOf(QrLoginStatus.SCANNED, QrLoginStatus.POLL_FAILED), states)
        assertEquals(2, requests)
    }

    @Test
    fun `a fresh retry can succeed after an earlier request failed`() = runTest {
        val states = mutableListOf<QrLoginStatus>()
        pollQrLogin({ Result.failure(IOException()) }, { true }) { states.add(it) }
        pollQrLogin({ Result.success(0) }, { true }) { states.add(it) }
        assertEquals(listOf(QrLoginStatus.POLL_FAILED, QrLoginStatus.SUCCESS), states)
    }

    @Test
    fun `error message never includes transport exception details`() = runTest {
        val states = mutableListOf<QrLoginStatus>()
        pollQrLogin(
            { Result.failure(IOException("https://example.invalid/?qrcode_key=synthetic-secret")) },
            { true }
        ) { states.add(it) }
        assertEquals(listOf(QrLoginStatus.POLL_FAILED), states)
        assertFalse(states.single().message.contains("synthetic-secret"))
        assertFalse(states.single().message.contains("https://"))
    }

    @Test
    fun `cancel before first request emits neither success nor error`() = runTest {
        var requests = 0
        val states = mutableListOf<QrLoginStatus>()
        val job = launch {
            pollQrLogin({ requests++; Result.success(0) }, { true }) { states.add(it) }
        }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(20_000)
        assertEquals(0, requests)
        assertTrue(states.isEmpty())
    }

    @Test
    fun `returned and thrown cancellation are never shown as retryable failures`() = runTest {
        for (throwCancellation in listOf(false, true)) {
            val states = mutableListOf<QrLoginStatus>()
            val job = launch {
                pollQrLogin(
                    request = {
                        val cancellation = CancellationException("synthetic cancellation")
                        if (throwCancellation) throw cancellation else Result.failure(cancellation)
                    },
                    isCurrent = { true },
                    onStatus = { states.add(it) }
                )
            }
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(job.isCancelled)
            assertTrue(states.isEmpty())
        }
    }

    @Test
    fun `cancelled request cannot publish even if an uncooperative response arrives`() = runTest {
        val response = CompletableDeferred<Result<Int>>()
        val states = mutableListOf<QrLoginStatus>()
        val job = launch {
            pollQrLogin(
                request = { withContext(NonCancellable) { response.await() } },
                isCurrent = { true },
                onStatus = { states.add(it) }
            )
        }
        advanceTimeBy(2_000)
        runCurrent()
        job.cancel()
        response.complete(Result.success(0))
        job.join()
        assertTrue(states.isEmpty())
    }

    @Test
    fun `refresh or navigation invalidates both late success and late failure`() = runTest {
        for (lateResult in listOf(Result.success(0), Result.failure<Int>(IOException()))) {
            val response = CompletableDeferred<Result<Int>>()
            var current = true
            val states = mutableListOf<QrLoginStatus>()
            val job = launch {
                pollQrLogin({ response.await() }, { current }) { states.add(it) }
            }
            advanceTimeBy(2_000)
            runCurrent()
            current = false
            response.complete(lateResult)
            job.join()
            assertTrue(states.isEmpty())
        }
    }

    @Test
    fun `challenge invalidated during delay does not issue another request`() = runTest {
        var current = true
        var requests = 0
        val job = launch {
            pollQrLogin({ requests++; Result.success(1) }, { current }) { }
        }
        runCurrent()
        current = false
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(0, requests)
    }

    @Test
    fun `only active states poll and every unsuccessful terminal state offers refresh`() {
        for (state in QrLoginStatus.values()) {
            assertEquals(state == QrLoginStatus.READY || state == QrLoginStatus.SCANNED, state.canPoll)
            assertEquals(
                state in setOf(QrLoginStatus.EXPIRED, QrLoginStatus.GENERATION_FAILED,
                    QrLoginStatus.POLL_FAILED, QrLoginStatus.UNKNOWN),
                state.canRefresh
            )
            if (state.canRefresh) assertTrue(state.message.contains("刷新"))
        }
    }

    private fun assertTerminalResult(result: Result<Int>, expected: QrLoginStatus) = runTest {
        var requests = 0
        val states = mutableListOf<QrLoginStatus>()
        val job = launch {
            pollQrLogin({ requests++; result }, { true }) { states.add(it) }
        }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(expected), states)
        assertTrue(job.isCompleted)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, requests)
    }
}
