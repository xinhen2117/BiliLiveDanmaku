package com.bililive.danmaku.data.remote

import com.bililive.danmaku.domain.model.DanmakuItem
import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

/** Non-blocking batch producer. All queued and currently emitted messages remain in the budget. */
internal class PendingDanmakuBuffer(
    private val maxBatches: Int,
    private val maxMessages: Int,
    private val maxBytes: Long
) {
    init { require(maxBatches > 0 && maxMessages > 0 && maxBytes > 0) }
    internal class Batch(val generation: Long, items: List<DanmakuItem>) {
        val items = ArrayDeque(items)
        var remaining = items.size
    }
    internal class Delivery(val batch: Batch, val item: DanmakuItem, val bytes: Long)
    private val lock = Any()
    private val queue = ArrayDeque<Batch>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    private var batches = 0
    private var messages = 0
    private var bytes = 0L

    fun offer(generation: Long, items: List<DanmakuItem>): Boolean {
        if (items.isEmpty()) return true
        val size = items.sumOf(::estimatedBytes)
        synchronized(lock) {
            if (batches >= maxBatches || items.size > maxMessages - messages || size > maxBytes - bytes) return false
            queue.addLast(Batch(generation, items))
            batches++; messages += items.size; bytes += size
        }
        available.trySend(Unit)
        return true
    }

    suspend fun take(): Delivery {
        while (true) {
            synchronized(lock) {
                if (queue.isNotEmpty()) {
                    val batch = queue.first
                    val item = batch.items.removeFirst()
                    if (batch.items.isEmpty()) queue.removeFirst()
                    return Delivery(batch, item, estimatedBytes(item))
                }
            }
            available.receive()
        }
    }

    fun release(delivery: Delivery) = synchronized(lock) {
        messages--; bytes -= delivery.bytes
        if (--delivery.batch.remaining == 0) batches--
        check(batches >= 0 && messages >= 0 && bytes >= 0)
    }

    fun clear() = synchronized(lock) {
        while (queue.isNotEmpty()) {
            val batch = queue.removeFirst()
            while (batch.items.isNotEmpty()) {
                val item = batch.items.removeFirst()
                release(Delivery(batch, item, estimatedBytes(item)))
            }
        }
    }

    // Conservative UTF-16 payload estimate plus per-message/list/object overhead; counts also cap
    // fixed JVM allocations. This is a budget, not a claim to measure the exact heap size.
    private fun estimatedBytes(item: DanmakuItem): Long = 256L + 2L *
        (item.id.length.toLong() + item.username.length + item.content.length + (item.badgeName?.length ?: 0))
}
