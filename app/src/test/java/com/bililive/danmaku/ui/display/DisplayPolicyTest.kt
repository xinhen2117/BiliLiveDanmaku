package com.bililive.danmaku.ui.display

import com.bililive.danmaku.domain.model.DisplaySettings
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DisplayPolicyTest {
    @Test fun `settings clamp corrupt stored and direct inputs`() {
        val low = DisplaySettings(brightnessPercent = Int.MIN_VALUE, idleTimeoutSeconds = -1, textSizeSp = 0).normalized()
        assertEquals(5, low.brightnessPercent)
        assertEquals(30, low.idleTimeoutSeconds)
        assertEquals(14, low.textSizeSp)
        val high = DisplaySettings(brightnessPercent = Int.MAX_VALUE, idleTimeoutSeconds = Int.MAX_VALUE, textSizeSp = 500).normalized()
        assertEquals(100, high.brightnessPercent)
        assertEquals(300, high.idleTimeoutSeconds)
        assertEquals(24, high.textSizeSp)
        assertEquals(high, high.normalized())
    }

    @Test fun `defaults keep viewing awake but opt out of dimming and immersion`() {
        val settings = DisplaySettings()
        assertTrue(settings.keepScreenOn)
        assertTrue(settings.pixelShiftEnabled)
        assertFalse(settings.lowPowerMode)
        assertFalse(settings.idleDimmingEnabled)
    }

    @Test fun `brightness is window-only nullable outside low power and bounded inside`() {
        for (percent in listOf(-100, 0, 5, 20, 100, 1000)) {
            val settings = DisplaySettings(brightnessPercent = percent)
            assertNull(DisplayPolicy.brightness(settings, false, 0))
            val stages = (0..2).map { requireNotNull(DisplayPolicy.brightness(settings, true, it)) }
            assertTrue(stages.all { it in 0.05f..1f })
            assertTrue(stages[0] >= stages[1] && stages[1] >= stages[2])
        }
    }

    @Test fun `all shifts stay inside reserved margin and include varied positions`() {
        val offsets = (0L..100L).map(DisplayPolicy::offset)
        assertTrue(offsets.distinct().size >= 8)
        assertTrue(offsets.all { (x, y) -> abs(x) < DisplayPolicy.SHIFT_PADDING_DP && abs(y) < DisplayPolicy.SHIFT_PADDING_DP })
        listOf(Long.MIN_VALUE, -1L, Long.MAX_VALUE).forEach {
            val (x, y) = DisplayPolicy.offset(it)
            assertTrue(abs(x) < DisplayPolicy.SHIFT_PADDING_DP && abs(y) < DisplayPolicy.SHIFT_PADDING_DP)
        }
        assertTrue(DisplayPolicy.SHIFT_INTERVAL_MS >= 60_000L)
    }

    @Test fun `idle timer is clamped and returns readable nonzero alpha`() {
        assertEquals(30_000L, DisplayPolicy.idleDelayMillis(DisplaySettings(idleTimeoutSeconds = -1)))
        assertEquals(300_000L, DisplayPolicy.idleDelayMillis(DisplaySettings(idleTimeoutSeconds = Int.MAX_VALUE)))
        assertEquals(1f, DisplayPolicy.contentAlpha(0))
        assertTrue(DisplayPolicy.contentAlpha(1) > DisplayPolicy.contentAlpha(2))
        assertTrue(DisplayPolicy.contentAlpha(2) > 0f)
    }

    @Test fun `toolbar adapts to landscape and short multiwindow without changing reading order`() {
        assertTrue(DisplayPolicy.compactToolbar(800, 360))
        assertTrue(DisplayPolicy.compactToolbar(320, 400))
        assertFalse(DisplayPolicy.compactToolbar(360, 800))
    }
}
