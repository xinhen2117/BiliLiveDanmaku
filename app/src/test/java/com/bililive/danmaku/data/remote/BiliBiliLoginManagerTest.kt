package com.bililive.danmaku.data.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Exercises the real manager with fake transport/storage, never a live account or Android device. */
@OptIn(ExperimentalCoroutinesApi::class)
class BiliBiliLoginManagerTest {
    @Test fun cancelledPollCannotStoreLateSessionCookies() = runTest {
        val fixture = fixture()
        generate(fixture)
        val poll = launch { fixture.manager.pollQRCodeStatus("test-key") }
        runCurrent()
        val call = fixture.calls.last()
        poll.cancel()
        assertTrue(call.cancelled)
        call.respondJson(POLL_SUCCESS, TEST_COOKIES)
        poll.join()
        assertFalse(fixture.manager.isLoggedIn())
        assertEquals(0, fixture.prefs.writes)
        assertEquals(2, fixture.calls.size)
    }

    @Test fun refreshedQrCannotBeReplacedByOldPollResponse() = runTest {
        val fixture = fixture()
        generate(fixture, "old-key")
        val poll = launch { fixture.manager.pollQRCodeStatus("old-key") }
        runCurrent()
        val oldPoll = fixture.calls.last()
        generate(fixture, "new-key")
        assertTrue(oldPoll.cancelled)
        oldPoll.respondJson(POLL_SUCCESS, TEST_COOKIES)
        poll.join()
        assertFalse(fixture.manager.isLoggedIn())
        assertEquals(0, fixture.prefs.writes)
        val next = async { fixture.manager.pollQRCodeStatus("new-key") }
        runCurrent()
        fixture.calls.last().respondJson("""{"code":0,"data":{"code":86101}}""")
        assertEquals(1, next.await().getOrThrow())
    }

    @Test fun leavingQrDuringGenerationCancelsTheHttpRequest() = runTest {
        val fixture = fixture()
        val generation = launch { fixture.manager.generateQRCode() }
        runCurrent()
        val call = fixture.calls.last()
        fixture.manager.cancelQrLogin()
        assertTrue(call.cancelled)
        call.respondJson(qrJson("late-key"))
        generation.join()
        assertTrue(generation.isCancelled)
        assertFalse(fixture.manager.isLoggedIn())
    }

    @Test fun cancelledProfileLookupDoesNotLeavePartiallySavedLogin() = runTest {
        val fixture = fixture()
        generate(fixture)
        val poll = launch { fixture.manager.pollQRCodeStatus("test-key") }
        runCurrent()
        fixture.calls.last().respondJson(POLL_SUCCESS, TEST_COOKIES)
        runCurrent()
        val profile = fixture.calls.last()
        assertEquals("api.bilibili.com", profile.request().url.host)
        assertEquals(0, fixture.prefs.writes)
        poll.cancel()
        assertTrue(profile.cancelled)
        profile.respondJson(PROFILE)
        poll.join()
        assertFalse(fixture.manager.isLoggedIn())
        assertEquals(0, fixture.prefs.writes)
    }

    @Test fun logoutDuringProfileLookupCannotRestoreCookiesOrProfile() = runTest {
        val fixture = fixture()
        generate(fixture)
        val poll = launch { fixture.manager.pollQRCodeStatus("test-key") }
        runCurrent()
        fixture.calls.last().respondJson(POLL_SUCCESS, TEST_COOKIES)
        runCurrent()
        val profile = fixture.calls.last()
        fixture.manager.logout()
        assertTrue(profile.cancelled)
        profile.respondJson(PROFILE)
        poll.join()
        assertEquals(BiliBiliLoginManager.LoginState(), fixture.manager.getLoginState())
        assertEquals("", fixture.manager.getLoginCookieString())
    }

    @Test fun successfulQrCommitsCookieAndProfileInOneEdit() = runTest {
        val fixture = fixture()
        generate(fixture)
        val poll = async { fixture.manager.pollQRCodeStatus("test-key") }
        runCurrent()
        fixture.calls.last().respondJson(POLL_SUCCESS, TEST_COOKIES)
        runCurrent()
        assertEquals(0, fixture.prefs.writes)
        fixture.calls.last().respondJson(PROFILE)
        assertEquals(0, poll.await().getOrThrow())
        assertEquals(1, fixture.prefs.writes)
        assertEquals(BiliBiliLoginManager.LoginState(true, "Test account", 123), fixture.manager.getLoginState())
        assertEquals("SESSDATA=test-session; bili_jct=test-csrf; DedeUserID=123", fixture.manager.getLoginCookieString())
    }

    @Test fun logoutRejectsImportedCookieProfileArrivingLate() = runTest {
        val fixture = fixture()
        assertTrue(fixture.manager.loginWithCookieString("SESSDATA=test-import"))
        runCurrent()
        val profile = fixture.calls.last()
        fixture.manager.logout()
        assertTrue(profile.cancelled)
        profile.respondJson(PROFILE)
        runCurrent()
        assertEquals(BiliBiliLoginManager.LoginState(), fixture.manager.getLoginState())
        assertEquals(2, fixture.prefs.writes)
    }

    @Test fun importReplacementClearsOldProfileAndRejectsLateOldProfile() = runTest {
        val fixture = fixture()
        fixture.manager.loginWithCookieString("SESSDATA=first-test-session")
        runCurrent()
        val oldProfile = fixture.calls.last()
        fixture.manager.loginWithCookieString("SESSDATA=second-test-session")
        assertEquals(BiliBiliLoginManager.LoginState(true, "", 0), fixture.manager.getLoginState())
        runCurrent()
        val newProfile = fixture.calls.last()
        assertTrue(oldProfile.cancelled)
        newProfile.respondJson("""{"code":0,"data":{"uname":"New account","mid":456}}""")
        runCurrent()
        oldProfile.respondJson(PROFILE)
        runCurrent()
        assertEquals("second-test-session", fixture.manager.getSessdata())
        assertEquals(BiliBiliLoginManager.LoginState(true, "New account", 456), fixture.manager.getLoginState())
    }

    @Test fun topLevelApiFailureIsNotAcceptedAsQrLoginSuccess() = runTest {
        val fixture = fixture()
        generate(fixture)
        val poll = async { fixture.manager.pollQRCodeStatus("test-key") }
        runCurrent()
        fixture.calls.last().respondJson("""{"code":-1,"data":{"code":0}}""", TEST_COOKIES)
        assertTrue(poll.await().isFailure)
        assertFalse(fixture.manager.isLoggedIn())
        assertEquals(2, fixture.calls.size)
    }

    @Test fun profileFailureStillLogsInWithoutLoggingSensitiveExceptionMessage() = runTest {
        val fixture = fixture()
        generate(fixture)
        val poll = async { fixture.manager.pollQRCodeStatus("test-key") }
        runCurrent()
        fixture.calls.last().respondJson(POLL_SUCCESS, TEST_COOKIES)
        runCurrent()
        fixture.calls.last().fail(IOException("private URL qrcode_key=test-secret"))
        assertEquals(0, poll.await().getOrThrow())
        assertTrue(fixture.manager.isLoggedIn())
        assertEquals(listOf("获取用户信息失败"), fixture.logs)
    }

    @Test fun cancellingSmsLoginCancelsRequestAndDoesNotWriteCookies() = runTest {
        val fixture = fixture()
        val sms = launch { fixture.manager.loginWithSms("test-phone", "test-code") }
        runCurrent()
        val call = fixture.calls.last()
        sms.cancel()
        assertTrue(call.cancelled)
        call.respondJson("""{"code":0}""", TEST_COOKIES)
        sms.join()
        assertTrue(sms.isCancelled)
        assertFalse(fixture.manager.isLoggedIn())
        assertEquals(0, fixture.prefs.writes)
    }

    @Test fun changingLoginMethodCancelsSmsBeforeItsCoroutineIsDisposed() = runTest {
        val fixture = fixture()
        val sms = launch { fixture.manager.loginWithSms("test-phone", "test-code") }
        runCurrent()
        val call = fixture.calls.last()
        fixture.manager.cancelPendingLogin()
        assertTrue(call.cancelled)
        call.fail(IOException("cancelled request"))
        sms.join()
        assertTrue(sms.isCancelled)
        assertFalse(fixture.manager.isLoggedIn())
        assertEquals(0, fixture.prefs.writes)
    }

    @Test fun leavingLoginScreenDoesNotCancelAnAlreadyImportedAccountProfile() = runTest {
        val fixture = fixture()
        fixture.manager.loginWithCookieString("SESSDATA=test-import")
        runCurrent()
        val profile = fixture.calls.last()
        fixture.manager.cancelPendingLogin()
        assertFalse(profile.cancelled)
        profile.respondJson(PROFILE)
        runCurrent()
        assertEquals(BiliBiliLoginManager.LoginState(true, "Test account", 123), fixture.manager.getLoginState())
    }

    private class Fixture(val prefs: FakeLoginPreferences, val manager: BiliBiliLoginManager,
                          val calls: MutableList<FakeLoginHttpCall>, val logs: MutableList<String>)

    private fun TestScope.fixture(): Fixture {
        val prefs = FakeLoginPreferences()
        val calls = mutableListOf<FakeLoginHttpCall>()
        val logs = mutableListOf<String>()
        val factory = object : Call.Factory {
            override fun newCall(request: Request): Call = FakeLoginHttpCall(request).also(calls::add)
        }
        return Fixture(prefs, BiliBiliLoginManager(prefs, factory, StandardTestDispatcher(testScheduler), logs::add), calls, logs)
    }

    private suspend fun TestScope.generate(fixture: Fixture, key: String = "test-key") {
        val generation = async { fixture.manager.generateQRCode() }
        runCurrent()
        fixture.calls.last().respondJson(qrJson(key))
        assertEquals(key, generation.await().getOrThrow().qrcodeKey)
    }

    private fun qrJson(key: String) = """{"code":0,"data":{"url":"https://passport.bilibili.com/test-login","qrcode_key":"$key"}}"""

    companion object {
        private const val POLL_SUCCESS = """{"code":0,"data":{"code":0}}"""
        private const val PROFILE = """{"code":0,"data":{"uname":"Test account","mid":123}}"""
        private val TEST_COOKIES = listOf("SESSDATA=test-session; Path=/", "bili_jct=test-csrf; Path=/", "DedeUserID=123; Path=/")
    }
}
