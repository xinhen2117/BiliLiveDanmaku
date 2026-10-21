package com.bililive.danmaku.ui.danmaku

/** Pure scheduling policy: data-driven batches, no repeated wakeups while idle or not visible. */
internal class DanmakuPresentationGate {
    var isVisible: Boolean = false
        private set
    var lowPowerMode: Boolean = false
        private set
    private var dirty = false
    private var lastPublishedAtMs: Long? = null

    /** Only the transition to dirty needs a wake signal; the rest of the burst coalesces. */
    fun invalidate(): Boolean {
        val shouldWake = !dirty && isVisible
        dirty = true
        return shouldWake
    }

    fun setVisible(visible: Boolean): Boolean {
        if (visible == isVisible) return false
        isVisible = visible
        if (visible) dirty = true
        return true
    }

    fun setLowPowerMode(enabled: Boolean): Boolean {
        if (enabled == lowPowerMode) return false
        lowPowerMode = enabled
        return true
    }

    /** Null means wait indefinitely for an event. idleRateDeadline is a single rate-reset wake. */
    fun nextDelayMillis(nowMs: Long, idleRateDeadline: Long?): Long? {
        if (!isVisible) return null
        val requestedAt = if (dirty) nowMs else idleRateDeadline ?: return null
        val interval = if (lowPowerMode) LOW_POWER_INTERVAL_MS else NORMAL_INTERVAL_MS
        val allowedAt = lastPublishedAtMs?.let { it + interval } ?: nowMs
        return (maxOf(requestedAt, allowedAt) - nowMs).coerceAtLeast(0L)
    }

    fun didPublish(nowMs: Long) {
        lastPublishedAtMs = nowMs
        dirty = false
    }

    companion object {
        const val NORMAL_INTERVAL_MS = 50L
        const val LOW_POWER_INTERVAL_MS = 250L
    }
}
