package com.bililive.danmaku.data.remote

import java.util.concurrent.atomic.AtomicLong

/** Bounds queued payload bytes, not just event count; reserve before copying a network frame. */
internal class PendingFrameBudget(private val maximumBytes: Long) {
    init { require(maximumBytes > 0) }
    private val pending = AtomicLong()

    fun tryReserve(bytes: Int): Boolean {
        if (bytes < 0) return false
        while (true) {
            val current = pending.get()
            if (bytes.toLong() > maximumBytes - current) return false
            if (pending.compareAndSet(current, current + bytes)) return true
        }
    }

    fun release(bytes: Int) {
        require(bytes >= 0)
        check(pending.addAndGet(-bytes.toLong()) >= 0) { "Released an unreserved frame" }
    }
}
