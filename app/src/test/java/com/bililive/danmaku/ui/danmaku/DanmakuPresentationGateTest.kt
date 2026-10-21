package com.bililive.danmaku.ui.danmaku

import com.bililive.danmaku.domain.model.DanmakuItem
import org.junit.Assert.*
import org.junit.Test

class DanmakuPresentationGateTest {
    @Test fun `hidden screen has no scheduled wake even with dirty data and expired rate`() {
        val gate = DanmakuPresentationGate()
        assertFalse(gate.invalidate())
        assertNull(gate.nextDelayMillis(10_000, 1000))
        assertTrue(gate.setVisible(true))
        assertEquals(0L, gate.nextDelayMillis(10_000, 1000))
    }

    @Test fun `an idle screen schedules no repeated timer`() {
        val gate = DanmakuPresentationGate()
        gate.setVisible(true)
        gate.didPublish(0)
        assertNull(gate.nextDelayMillis(1000, null))
        assertNull(gate.nextDelayMillis(1_000_000, null))
    }

    @Test fun `one dirty signal coalesces subsequent arrivals`() {
        val gate = DanmakuPresentationGate()
        gate.setVisible(true)
        gate.didPublish(0)
        assertTrue(gate.invalidate())
        repeat(10_000) { assertFalse(gate.invalidate()) }
        assertEquals(49L, gate.nextDelayMillis(1, null))
        assertEquals(0L, gate.nextDelayMillis(50, null))
    }

    @Test fun `normal mode publishes at most twenty times per second`() {
        assertEquals((0L..950L step 50).toList(), publications(lowPower = false))
    }

    @Test fun `low power mode publishes at most four times per second`() {
        assertEquals(listOf(0L, 250L, 500L, 750L), publications(lowPower = true))
    }

    @Test fun `mode switching recomputes deadline from last publication`() {
        val gate = DanmakuPresentationGate()
        gate.setVisible(true)
        gate.didPublish(0)
        gate.invalidate()
        assertEquals(40L, gate.nextDelayMillis(10, null))
        assertTrue(gate.setLowPowerMode(true))
        assertEquals(240L, gate.nextDelayMillis(10, null))
        assertTrue(gate.setLowPowerMode(false))
        assertEquals(40L, gate.nextDelayMillis(10, null))
        assertFalse(gate.setLowPowerMode(false))
    }

    @Test fun `resume keeps rate cap even when stopped between nearby batches`() {
        val gate = DanmakuPresentationGate()
        gate.setLowPowerMode(true)
        gate.setVisible(true)
        gate.didPublish(0)
        gate.setVisible(false)
        gate.invalidate()
        assertNull(gate.nextDelayMillis(100, null))
        gate.setVisible(true)
        assertEquals(150L, gate.nextDelayMillis(100, null))
        assertFalse(gate.setVisible(true))
    }

    @Test fun `single idle rate reset also obeys publication interval`() {
        val gate = DanmakuPresentationGate()
        gate.setLowPowerMode(true)
        gate.setVisible(true)
        gate.didPublish(900)
        assertEquals(250L, gate.nextDelayMillis(900, 1000))
        assertEquals(0L, gate.nextDelayMillis(1150, 1000))
        gate.didPublish(1150)
        assertNull(gate.nextDelayMillis(1150, null))
    }

    @Test fun `new data can wake before the idle rate reset`() {
        val gate = DanmakuPresentationGate()
        gate.setVisible(true)
        gate.didPublish(0)
        assertEquals(900L, gate.nextDelayMillis(100, 1000))
        assertTrue(gate.invalidate())
        assertEquals(0L, gate.nextDelayMillis(100, 1000))
    }

    @Test fun `hidden arrivals stay bounded and resume latest retained data once`() {
        val gate = DanmakuPresentationGate()
        val pipeline = DanmakuMessagePipeline(
            DanmakuPipelineConfig(maxHistory = 2, duplicateFoldEnabled = false)
        )
        repeat(20) {
            val item = DanmakuItem(it.toString(), 1L, null, "viewer", "text $it", 0L)
            if (pipeline.offer(item, it.toLong())) assertFalse(gate.invalidate())
            assertNull(gate.nextDelayMillis(it.toLong(), null))
        }
        assertEquals(2, pipeline.historyCount)
        gate.setVisible(true)
        assertEquals(0L, gate.nextDelayMillis(20, null))
        val snapshot = pipeline.snapshot(20)
        gate.didPublish(20)
        assertEquals(listOf("18", "19"), snapshot.messages.map { it.id })
        assertEquals(0L, snapshot.overflowCount)
        assertEquals(18L, snapshot.diagnostics.historyCountEvictedRows)
        assertEquals(20L, snapshot.receivedVersion)
        assertNull(gate.nextDelayMillis(20, null))
    }

    private fun publications(lowPower: Boolean): List<Long> {
        val gate = DanmakuPresentationGate()
        gate.setLowPowerMode(lowPower)
        gate.setVisible(true)
        val result = mutableListOf<Long>()
        for (now in 0L..999L) {
            gate.invalidate()
            if (gate.nextDelayMillis(now, null) == 0L) {
                result.add(now)
                gate.didPublish(now)
            }
        }
        return result
    }
}
