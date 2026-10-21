package com.bililive.danmaku.domain.model

/** Session totals, separate from intentional presentation filtering/folding/history expiry. */
data class TransportDiagnostics(
    val decodedMessages: Long = 0,
    val droppedMessages: Long = 0,
    // Raw frames rejected before decoding: their number of danmaku messages is unknown.
    val droppedFrames: Long = 0,
    val oversizedFrames: Long = 0
) {
    val hasLoss: Boolean get() = droppedMessages > 0 || droppedFrames > 0
}
