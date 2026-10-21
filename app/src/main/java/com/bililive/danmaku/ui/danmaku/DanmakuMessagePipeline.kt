package com.bililive.danmaku.ui.danmaku

import com.bililive.danmaku.domain.model.DanmakuItem
import java.util.ArrayDeque

internal data class DanmakuPipelineConfig(
    val maxHistory: Int = 1000,
    val keywordFilterEnabled: Boolean = false,
    val keywords: List<String> = emptyList(),
    val duplicateFoldEnabled: Boolean = true,
    val duplicateFoldWindowMs: Long = 3000L
)

internal data class DanmakuPresentation(
    val messages: List<DanmakuItem>,
    val totalCount: Long,
    val receivedVersion: Long,
    val overflowCount: Long,
    val messagesPerSecond: Int,
    val unreadCount: Int,
    val autoScroll: Boolean,
    val historyFull: Boolean
)

/**
 * Main-thread-confined, bounded presentation storage. Filtered traffic is counted but never wakes
 * the UI. Both item counts and UTF-16 text budgets are bounded. Overflow drops the oldest
 * retained item and is explicitly reported; a single oversized item is rejected instead.
 * Normal history-count expiry is separate: the list deliberately retains only maxHistory.
 */
internal class DanmakuMessagePipeline(
    config: DanmakuPipelineConfig = DanmakuPipelineConfig(),
    private val pendingCapacity: Int = DEFAULT_PENDING_CAPACITY,
    private val pendingTextCapacity: Int = DEFAULT_PENDING_TEXT_CAPACITY,
    private val historyTextCapacity: Int = DEFAULT_HISTORY_TEXT_CAPACITY
) {
    init {
        require(pendingCapacity > 0)
        require(pendingTextCapacity > 0)
        require(historyTextCapacity > 0)
    }

    private data class Pending(val item: DanmakuItem, val receivedAtMs: Long)
    private data class ContentKey(val roomId: Long, val content: String)
    private data class FoldTarget(val id: String, val receivedAtMs: Long)
    private data class RateBucket(val timeMs: Long, var count: Int)

    private var config = config.normalized()
    private val pending = ArrayDeque<Pending>()
    private val history = LinkedHashMap<String, DanmakuItem>()
    internal var pendingTextUnits: Long = 0L
        private set
    internal var historyTextUnits: Long = 0L
        private set
    private val foldTargets = HashMap<ContentKey, FoldTarget>()
    // Equal-millisecond arrivals share a bucket. At most 1000 buckets survive a one-second window.
    private val rateBuckets = ArrayDeque<RateBucket>()
    private var lastClockMs: Long? = null
    private var lastAcceptedAtMs: Long? = null
    private var totalCount = 0L
    private var receivedVersion = 0L
    private var overflowCount = 0L
    private var unreadCount = 0
    var autoScroll: Boolean = true
        private set

    internal val pendingCount: Int get() = pending.size
    internal val historyCount: Int get() = history.size
    internal val foldTargetCount: Int get() = foldTargets.size
    internal val rateBucketCount: Int get() = rateBuckets.size
    val rateExpiresAtMs: Long? get() = lastAcceptedAtMs?.let { it + RATE_WINDOW_MS }

    /** Returns true only for display-eligible activity, including duplicates and queue overflow. */
    fun offer(item: DanmakuItem, nowMs: Long): Boolean {
        totalCount = increment(totalCount)
        if (isFiltered(item)) return false

        val now = monotonicTime(nowMs)
        receivedVersion = increment(receivedVersion)
        if (!autoScroll) unreadCount = increment(unreadCount)
        recordRate(now)
        val textUnits = item.textUnits()
        if (textUnits > pendingTextCapacity || textUnits > historyTextCapacity) {
            overflowCount = increment(overflowCount)
            return true
        }
        while (pending.size >= pendingCapacity || pendingTextUnits + textUnits > pendingTextCapacity) {
            pendingTextUnits -= pending.removeFirst().item.textUnits()
            overflowCount = increment(overflowCount)
        }
        pending.addLast(Pending(item, now))
        pendingTextUnits += textUnits
        return true
    }

    fun updateConfiguration(next: DanmakuPipelineConfig): Boolean {
        val normalized = next.normalized()
        if (config == normalized) return false
        config = normalized
        // Do not fold against an anchor from an earlier folding/filter policy.
        foldTargets.clear()
        pending.removeAll { isFiltered(it.item) }
        history.entries.removeAll { isFiltered(it.value) }
        pendingTextUnits = pending.sumOf { it.item.textUnits() }
        historyTextUnits = history.values.sumOf { it.textUnits() }
        trimHistory()
        return true
    }

    fun setAutoScroll(enabled: Boolean): Boolean {
        val changed = autoScroll != enabled || (enabled && unreadCount != 0)
        autoScroll = enabled
        if (enabled) unreadCount = 0
        return changed
    }

    fun drain(nowMs: Long): DanmakuPresentation {
        while (pending.isNotEmpty()) append(pending.removeFirst())
        pendingTextUnits = 0L
        val now = monotonicTime(nowMs)
        pruneRate(now)
        val rate = rateBuckets.sumOf { it.count.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return DanmakuPresentation(
            messages = history.values.toList(),
            totalCount = totalCount,
            receivedVersion = receivedVersion,
            overflowCount = overflowCount,
            messagesPerSecond = rate,
            unreadCount = unreadCount,
            autoScroll = autoScroll,
            historyFull = history.size >= config.maxHistory
        )
    }

    fun clear() {
        pending.clear()
        history.clear()
        pendingTextUnits = 0L
        historyTextUnits = 0L
        foldTargets.clear()
        rateBuckets.clear()
        lastAcceptedAtMs = null
        totalCount = 0L
        overflowCount = 0L
        unreadCount = 0
        // Keep activity generation monotonic, even after clear or a full-history rollover.
    }

    private fun append(incoming: Pending) {
        val item = incoming.item
        val key = item.key()
        val target = foldTargets[key]
        val existing = target?.let { history[it.id] }
        if (config.duplicateFoldEnabled && target != null && existing != null &&
            incoming.receivedAtMs - target.receivedAtMs < config.duplicateFoldWindowMs
        ) {
            history[target.id] = existing.copy(
                repeatCount = (existing.repeatCount.toLong() + item.repeatCount.coerceAtLeast(1))
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            return
        }

        // Reused source IDs must not create duplicate LazyColumn keys or stale fold references.
        history.remove(item.id)?.let {
            historyTextUnits -= it.textUnits()
            forgetFoldTarget(it)
        }
        history[item.id] = item
        historyTextUnits += item.textUnits()
        if (config.duplicateFoldEnabled) {
            foldTargets[key] = FoldTarget(item.id, incoming.receivedAtMs)
        }
        trimHistory()
    }

    private fun trimHistory() {
        while (history.size > config.maxHistory) removeOldestHistory()
        while (historyTextUnits > historyTextCapacity) {
            removeOldestHistory()
            overflowCount = increment(overflowCount)
        }
    }

    private fun removeOldestHistory() {
        val iterator = history.entries.iterator()
        val expired = iterator.next().value
        iterator.remove()
        historyTextUnits -= expired.textUnits()
        forgetFoldTarget(expired)
    }

    private fun forgetFoldTarget(item: DanmakuItem) {
        val key = item.key()
        if (foldTargets[key]?.id == item.id) foldTargets.remove(key)
    }

    private fun isFiltered(item: DanmakuItem): Boolean = config.keywordFilterEnabled &&
        config.keywords.any { item.content.contains(it, ignoreCase = true) }

    private fun recordRate(nowMs: Long) {
        pruneRate(nowMs)
        val last = rateBuckets.peekLast()
        if (last?.timeMs == nowMs) last.count = increment(last.count)
        else rateBuckets.addLast(RateBucket(nowMs, 1))
        lastAcceptedAtMs = nowMs
    }

    private fun pruneRate(nowMs: Long) {
        while (rateBuckets.isNotEmpty() && nowMs - rateBuckets.first.timeMs >= RATE_WINDOW_MS) {
            rateBuckets.removeFirst()
        }
    }

    private fun monotonicTime(nowMs: Long): Long = maxOf(nowMs, lastClockMs ?: nowMs).also {
        lastClockMs = it
    }

    private fun DanmakuItem.key() = ContentKey(roomId, content)

    // Count every retained string, not just body text. Long avoids length-sum overflow.
    private fun DanmakuItem.textUnits(): Long =
        id.length.toLong() + username.length + content.length + (badgeName?.length ?: 0)

    private fun DanmakuPipelineConfig.normalized() = copy(
        maxHistory = maxHistory.coerceIn(1, MAX_HISTORY_CAPACITY),
        keywords = keywords.map(String::trim).filter(String::isNotEmpty).distinct(),
        duplicateFoldWindowMs = duplicateFoldWindowMs.coerceAtLeast(0L)
    )

    private fun increment(value: Long) = if (value == Long.MAX_VALUE) value else value + 1L
    private fun increment(value: Int) = if (value == Int.MAX_VALUE) value else value + 1

    companion object {
        const val DEFAULT_PENDING_CAPACITY = 512
        const val MAX_HISTORY_CAPACITY = 5000
        // UTF-16 code units, approximately 0.5 MiB pending / 4 MiB history string payload.
        // Object/index overhead is additionally bounded by the item-count limits above.
        const val DEFAULT_PENDING_TEXT_CAPACITY = 256 * 1024
        const val DEFAULT_HISTORY_TEXT_CAPACITY = 2 * 1024 * 1024
        const val RATE_WINDOW_MS = 1000L
    }
}
