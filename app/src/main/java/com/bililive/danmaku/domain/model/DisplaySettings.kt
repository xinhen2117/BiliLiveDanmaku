package com.bililive.danmaku.domain.model

/** App-local preferences. None of these writes to Android's global display settings. */
data class DisplaySettings(
    val keepScreenOn: Boolean = true,
    val lowPowerMode: Boolean = false,
    val brightnessPercent: Int = 20,
    val pixelShiftEnabled: Boolean = true,
    val idleDimmingEnabled: Boolean = false,
    val idleTimeoutSeconds: Int = 60,
    val textSizeSp: Int = 16,
    val compactRows: Boolean = false
) {
    fun normalized() = copy(
        brightnessPercent = brightnessPercent.coerceIn(5, 100),
        idleTimeoutSeconds = idleTimeoutSeconds.coerceIn(30, 300),
        textSizeSp = textSizeSp.coerceIn(14, 24)
    )
}
