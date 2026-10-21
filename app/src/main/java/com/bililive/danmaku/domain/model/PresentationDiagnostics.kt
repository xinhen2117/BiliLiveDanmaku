package com.bililive.danmaku.domain.model

/** Display-layer totals since clear, never estimates of missing upstream/network messages. */
data class PresentationDiagnostics(
    val filteredMessages: Long = 0,
    val foldedMessages: Long = 0,
    val oversizedMessages: Long = 0,
    val historyCountEvictedRows: Long = 0,
    val historyTextEvictedRows: Long = 0,
    val filterPrunedRows: Long = 0,
    val replacedIdRows: Long = 0
) {
    val historyEvictedRows: Long get() = saturatedAdd(historyCountEvictedRows, historyTextEvictedRows)
    // Compatibility total: explicit display budget losses, excluding configured count retention.
    val overflowCount: Long get() = saturatedAdd(oversizedMessages, historyTextEvictedRows)
    val hasAdjustments: Boolean get() = filteredMessages > 0 || foldedMessages > 0 || oversizedMessages > 0 ||
        historyEvictedRows > 0 || filterPrunedRows > 0 || replacedIdRows > 0

    private fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}
