package com.bililive.danmaku.ui.danmaku

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One scope-owned coroutine handles presentation. A burst sends only its first dirty signal;
 * after the last rate-reset deadline there is no timer at all. Call methods on the scope's thread.
 */
internal class DanmakuPresentationPump(
    scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val publish: (Long) -> Long?
) {
    private val gate = DanmakuPresentationGate()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var rateResetDeadlineMs: Long? = null

    init {
        scope.launch {
            while (isActive) {
                val waitMs = gate.nextDelayMillis(nowMs(), rateResetDeadlineMs)
                when {
                    waitMs == null -> wake.receive()
                    waitMs > 0L -> withTimeoutOrNull(waitMs) { wake.receive() }
                    else -> {
                        val now = nowMs()
                        rateResetDeadlineMs = publish(now)
                        gate.didPublish(now)
                    }
                }
            }
        }
    }

    fun requestPresentation() {
        if (gate.invalidate()) wake.trySend(Unit)
    }

    fun setVisible(visible: Boolean) {
        if (gate.setVisible(visible)) wake.trySend(Unit)
    }

    fun setLowPowerMode(enabled: Boolean) {
        if (gate.setLowPowerMode(enabled)) wake.trySend(Unit)
    }
}
