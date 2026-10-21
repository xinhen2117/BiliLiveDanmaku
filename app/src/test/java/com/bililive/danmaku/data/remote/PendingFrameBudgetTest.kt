package com.bililive.danmaku.data.remote

import org.junit.Assert.*
import org.junit.Test

class PendingFrameBudgetTest {
    @Test
    fun `maximum size frame bursts are bounded before allocation`() {
        val budget = PendingFrameBudget(8L * 1024 * 1024)
        val frame = 4 * 1024 * 1024
        assertTrue(budget.tryReserve(frame))
        assertTrue(budget.tryReserve(frame))
        repeat(1000) { assertFalse(budget.tryReserve(frame)) }
        budget.release(frame)
        assertTrue(budget.tryReserve(frame))
    }

    @Test
    fun `invalid oversized or overflow input cannot reserve`() {
        val budget = PendingFrameBudget(16)
        assertFalse(budget.tryReserve(-1))
        assertFalse(budget.tryReserve(Int.MAX_VALUE))
        assertTrue(budget.tryReserve(16))
        assertFalse(budget.tryReserve(1))
        budget.release(16)
        assertTrue(budget.tryReserve(1))
    }
}
