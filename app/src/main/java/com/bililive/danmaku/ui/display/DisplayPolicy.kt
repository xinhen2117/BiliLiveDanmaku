package com.bililive.danmaku.ui.display

import com.bililive.danmaku.domain.model.DisplaySettings

/** Pure policy: one discrete move per minute, not a continuously running animation. */
object DisplayPolicy {
    const val SHIFT_INTERVAL_MS = 60_000L
    const val SHIFT_PADDING_DP = 12
    const val CONTROLS_TIMEOUT_MS = 8_000L
    private val offsets = listOf(
        0 to 0, -8 to -8, 0 to 8, 8 to -4, -4 to 4,
        8 to 8, -8 to 0, 4 to -8, -4 to 8
    )

    fun offset(step: Long): Pair<Int, Int> = offsets[Math.floorMod(step, offsets.size.toLong()).toInt()]

    fun brightness(settings: DisplaySettings, lowPower: Boolean, idleStage: Int): Float? {
        if (!lowPower) return null // Preserve the caller's original window brightness.
        val normal = settings.normalized().brightnessPercent / 100f
        return when (idleStage) {
            1 -> maxOf(0.05f, normal * 0.6f)
            2 -> minOf(normal, 0.05f)
            else -> normal
        }
    }

    fun contentAlpha(idleStage: Int): Float = when (idleStage) {
        1 -> 0.6f
        2 -> 0.25f
        else -> 1f
    }

    fun idleDelayMillis(settings: DisplaySettings): Long = settings.normalized().idleTimeoutSeconds * 1000L

    /** Based on available window size rather than device orientation alone (multi-window safe). */
    fun compactToolbar(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp || heightDp < 480
}

/** Narrow port so the ownership/restoration contract can be tested without Android or a device. */
interface DisplayWindowPort {
    fun snapshot(): DisplayWindowSnapshot
    fun apply(keepScreenOn: Boolean, brightness: Float, immersive: Boolean)
    fun restore(snapshot: DisplayWindowSnapshot)
}

data class DisplayWindowSnapshot(
    val keepScreenOn: Boolean,
    val brightness: Float,
    val statusBarVisible: Boolean,
    val navigationBarVisible: Boolean,
    val barsBehavior: Int
)

/** Owns only temporary window overrides. Pause, navigation, recreation and disposal must release it. */
class DisplayWindowLease(private val port: DisplayWindowPort) {
    private var original: DisplayWindowSnapshot? = null

    fun update(active: Boolean, keepScreenOn: Boolean, brightness: Float?, immersive: Boolean) {
        if (!active) {
            release()
            return
        }
        val saved = original ?: port.snapshot().also { original = it }
        port.apply(keepScreenOn || saved.keepScreenOn, brightness ?: saved.brightness, immersive)
    }

    fun release() {
        val saved = original ?: return
        original = null
        port.restore(saved)
    }
}
