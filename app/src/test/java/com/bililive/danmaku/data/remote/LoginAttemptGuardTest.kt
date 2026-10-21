package com.bililive.danmaku.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginAttemptGuardTest {
    @Test fun refreshCancelsInFlightPollAndRejectsLateCookieWrite() = runTest {
        val guard = LoginAttemptGuard()
        val old = guard.begin(LoginAttemptGuard.Kind.QR)
        guard.bindQrKey(old, "old-key", currentCoroutineContext())
        val call = FakeLoginHttpCall()
        var storedCookie = ""
        val poll = launch {
            guard.execute(old, call)
            guard.withCurrent(old, currentCoroutineContext()) { storedCookie = "old-cookie" }
        }
        runCurrent()
        val replacement = guard.begin(LoginAttemptGuard.Kind.QR)
        guard.bindQrKey(replacement, "new-key", currentCoroutineContext())
        assertTrue(call.cancelled)
        call.respond()
        poll.join()
        assertEquals("", storedCookie)
        assertSame(replacement, guard.qrAttempt("new-key"))
        assertCancelled { guard.qrAttempt("old-key") }
    }

    @Test fun logoutCancelsProfileRequestAndPreventsRestoringOldAccount() = runTest {
        val guard = LoginAttemptGuard()
        val attempt = guard.begin(LoginAttemptGuard.Kind.COOKIE)
        var cookie = "imported-cookie"
        var username = ""
        val profileCall = FakeLoginHttpCall()
        val profile = launch {
            guard.execute(attempt, profileCall)
            guard.withCurrent(attempt, currentCoroutineContext()) { username = "Old account" }
        }
        runCurrent()
        guard.logout { cookie = ""; username = "" }
        assertTrue(profileCall.cancelled)
        profileCall.respond()
        profile.join()
        assertEquals("", cookie)
        assertEquals("", username)
        assertCancelled { guard.withCurrent(attempt) { cookie = "restored" } }
    }

    @Test fun changingAccountsRejectsOldProfileEvenIfResponseAlreadyFinished() = runTest {
        val guard = LoginAttemptGuard()
        val old = guard.begin(LoginAttemptGuard.Kind.COOKIE)
        var cookie = "old-cookie"
        var username = "Old account"
        val newer = guard.begin(LoginAttemptGuard.Kind.COOKIE)
        guard.withCurrent(newer) { cookie = "new-cookie"; username = "New account" }
        assertCancelled { guard.withCurrent(old) { username = "Late old profile" } }
        assertEquals("new-cookie", cookie)
        assertEquals("New account", username)
    }

    @Test fun leavingQrScreenCancelsGenerationAndRejectsItsLateKey() = runTest {
        val guard = LoginAttemptGuard()
        val attempt = guard.begin(LoginAttemptGuard.Kind.QR)
        val call = FakeLoginHttpCall()
        val generation = launch {
            guard.execute(attempt, call)
            guard.bindQrKey(attempt, "late-key", currentCoroutineContext())
        }
        runCurrent()
        guard.cancelQr()
        assertTrue(call.cancelled)
        call.respond()
        generation.join()
        assertCancelled { guard.qrAttempt("late-key") }
    }

    @Test fun cancelledCoroutineCannotCommitEvenWhileAttemptIsCurrent() {
        val guard = LoginAttemptGuard()
        val attempt = guard.begin(LoginAttemptGuard.Kind.QR)
        val job = Job().apply { cancel() }
        var saved = false
        assertCancelled { guard.withCurrent(attempt, job) { saved = true } }
        assertFalse(saved)
    }

    @Test fun cancelledOrStaleAttemptNeverStartsAnotherNetworkCall() = runTest {
        val guard = LoginAttemptGuard()
        val old = guard.begin(LoginAttemptGuard.Kind.QR)
        guard.cancelQr()
        val call = FakeLoginHttpCall()
        try {
            guard.execute(old, call)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(call.cancelled)
        assertFalse(call.isExecuted())
    }

    @Test fun qrDisposalDoesNotCancelReplacementCookieLogin() {
        val guard = LoginAttemptGuard()
        val old = guard.begin(LoginAttemptGuard.Kind.QR)
        val imported = guard.begin(LoginAttemptGuard.Kind.COOKIE)
        guard.cancelQr()
        guard.cancel(old)
        var saved = false
        guard.withCurrent(imported) { saved = true }
        assertTrue(saved)
    }

    @Test fun staleQrFailureCannotCancelReplacementQrAttempt() = runTest {
        val guard = LoginAttemptGuard()
        val old = guard.begin(LoginAttemptGuard.Kind.QR)
        val newer = guard.begin(LoginAttemptGuard.Kind.QR)
        guard.bindQrKey(newer, "new-key", currentCoroutineContext())
        guard.cancel(old)
        assertSame(newer, guard.qrAttempt("new-key"))
    }

    @Test fun profileLookupMustFinishBeforeAtomicLoginCommit() = runTest {
        val guard = LoginAttemptGuard()
        val attempt = guard.begin(LoginAttemptGuard.Kind.QR)
        val pollCall = FakeLoginHttpCall()
        val profileCall = FakeLoginHttpCall()
        var cookie = ""
        var username = ""
        val login = launch {
            guard.execute(attempt, pollCall)
            guard.execute(attempt, profileCall)
            guard.withCurrent(attempt, currentCoroutineContext()) {
                cookie = "test-cookie"
                username = "Test account"
            }
        }
        runCurrent()
        pollCall.respond()
        runCurrent()
        assertEquals("", cookie)
        guard.cancelQr()
        assertTrue(profileCall.cancelled)
        profileCall.respond()
        login.join()
        assertEquals("", cookie)
        assertEquals("", username)
    }

    @Test fun currentAttemptCanCommitCookiesAndProfileTogether() = runTest {
        val guard = LoginAttemptGuard()
        val attempt = guard.begin(LoginAttemptGuard.Kind.QR)
        val call = FakeLoginHttpCall()
        var account = ""
        val login = launch {
            guard.execute(attempt, call)
            guard.withCurrent(attempt, currentCoroutineContext()) { account = "cookie:username" }
        }
        runCurrent()
        call.respond()
        login.join()
        assertEquals("cookie:username", account)
    }

    private fun assertCancelled(block: () -> Unit) {
        try {
            block()
            fail("Expected CancellationException")
        } catch (_: CancellationException) { }
    }
}
