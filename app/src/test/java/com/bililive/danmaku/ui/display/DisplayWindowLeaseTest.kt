package com.bililive.danmaku.ui.display

import org.junit.Assert.*
import org.junit.Test

class DisplayWindowLeaseTest {
    private class FakeWindow(var state: DisplayWindowSnapshot) : DisplayWindowPort {
        var captures = 0
        var restores = 0
        var lastImmersive = false
        override fun snapshot(): DisplayWindowSnapshot { captures++; return state }
        override fun apply(keepScreenOn: Boolean, brightness: Float, immersive: Boolean) {
            state = state.copy(keepScreenOn = keepScreenOn, brightness = brightness)
            lastImmersive = immersive
        }
        override fun restore(snapshot: DisplayWindowSnapshot) { restores++; state = snapshot; lastImmersive = false }
    }
    private val initial = DisplayWindowSnapshot(false, -1f, true, false, 1)

    @Test fun `pause restores exact original brightness and flags and release is idempotent`() {
        val port = FakeWindow(initial)
        val lease = DisplayWindowLease(port)
        lease.update(true, true, 0.2f, true)
        assertEquals(0.2f, port.state.brightness)
        assertTrue(port.state.keepScreenOn)
        assertTrue(port.lastImmersive)
        lease.update(false, true, 0.2f, true)
        assertEquals(initial, port.state)
        lease.release()
        assertEquals(1, port.restores)
    }

    @Test fun `repeated setting changes never replace original snapshot`() {
        val port = FakeWindow(initial)
        val lease = DisplayWindowLease(port)
        lease.update(true, true, 0.2f, true)
        lease.update(true, true, 0.05f, true)
        lease.update(true, false, null, false)
        assertEquals(initial.brightness, port.state.brightness)
        assertFalse(port.state.keepScreenOn)
        assertEquals(1, port.captures)
        lease.release()
        assertEquals(initial, port.state)
    }

    @Test fun `resume captures new outside preferences after previous session released`() {
        val port = FakeWindow(initial)
        val lease = DisplayWindowLease(port)
        lease.update(true, true, 0.2f, true)
        lease.release()
        val changed = initial.copy(brightness = 0.7f, keepScreenOn = true, barsBehavior = 2)
        port.state = changed
        lease.update(true, false, null, false)
        assertTrue(port.state.keepScreenOn)
        assertEquals(0.7f, port.state.brightness)
        lease.release()
        assertEquals(changed, port.state)
        assertEquals(2, port.captures)
    }

    @Test fun `inactive screen never takes ownership and recreation does not leak state`() {
        val oldPort = FakeWindow(initial)
        val old = DisplayWindowLease(oldPort)
        old.update(false, true, 0.2f, true)
        assertEquals(0, oldPort.captures)
        old.update(true, true, 0.2f, true)
        old.release()
        val newPort = FakeWindow(initial)
        val recreated = DisplayWindowLease(newPort)
        recreated.update(true, true, 0.2f, true)
        assertEquals(initial, oldPort.state)
        recreated.release()
        assertEquals(initial, newPort.state)
    }
}
