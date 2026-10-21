package com.bililive.danmaku.ui.login

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal enum class QrLoginStatus(val message: String) {
    LOADING("正在生成二维码..."),
    READY("请用B站App扫描二维码"),
    SCANNED("已扫码，请在B站App中确认"),
    SUCCESS("✅ 登录成功！"),
    EXPIRED("二维码已过期，请刷新重试"),
    GENERATION_FAILED("生成二维码失败，请检查网络后刷新重试"),
    POLL_FAILED("二维码状态获取失败，请检查网络后刷新重试"),
    UNKNOWN("登录返回了未知状态，请刷新二维码重试");

    val canPoll: Boolean get() = this == READY || this == SCANNED
    val isError: Boolean get() = this == GENERATION_FAILED || this == POLL_FAILED || this == UNKNOWN
    val canRefresh: Boolean get() = this == EXPIRED || isError
}

/** Stops at the first terminal result. Cancellation and superseded challenges never report errors. */
internal suspend fun pollQrLogin(
    request: suspend () -> Result<Int>,
    isCurrent: () -> Boolean,
    onStatus: (QrLoginStatus) -> Unit
) {
    while (isCurrent()) {
        delay(2_000)
        if (!isCurrent()) return
        val result = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return
        val status = result.fold(
            onSuccess = { code ->
                when (code) {
                    0 -> QrLoginStatus.SUCCESS
                    1 -> QrLoginStatus.READY
                    2 -> QrLoginStatus.SCANNED
                    3 -> QrLoginStatus.EXPIRED
                    else -> QrLoginStatus.UNKNOWN
                }
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                // Do not expose exception text, which may contain a request URL or challenge.
                QrLoginStatus.POLL_FAILED
            }
        )
        onStatus(status)
        if (!status.canPoll) return
    }
}
