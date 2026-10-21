package com.bililive.danmaku.ui.danmaku

import com.bililive.danmaku.domain.model.DanmakuItem
import org.junit.Assert.*
import org.junit.Test

class DanmakuMessagePipelineTest {
    private fun item(id: Int, content: String = "message $id", room: Long = 1L) = DanmakuItem(
        id = id.toString(), roomId = room, uid = null, username = "viewer", content = content,
        timestamp = 0L, receiveTimeMillis = 0L
    )

    private fun pipeline(maxHistory: Int = 3, pendingCapacity: Int = 4, fold: Boolean = false) =
        DanmakuMessagePipeline(
            DanmakuPipelineConfig(maxHistory = maxHistory, duplicateFoldEnabled = fold), pendingCapacity
        )

    @Test fun `pending overflow retains newest arrivals in order and counts losses`() {
        val pipeline = pipeline()
        repeat(10) { assertTrue(pipeline.offer(item(it), it.toLong())) }
        assertEquals(4, pipeline.pendingCount)
        assertEquals(0, pipeline.historyCount)
        val state = pipeline.drain(10)
        assertEquals(listOf("7", "8", "9"), state.messages.map { it.id })
        assertEquals(6L, state.overflowCount)
        assertEquals(10L, state.totalCount)
        assertEquals(10L, state.receivedVersion)
        assertEquals(0, pipeline.pendingCount)
    }

    @Test fun `history expiry is bounded but is not incoming overflow`() {
        val pipeline = pipeline()
        repeat(50) { pipeline.offer(item(it), it.toLong()); pipeline.drain(it.toLong()) }
        val state = pipeline.drain(50)
        assertEquals(listOf("47", "48", "49"), state.messages.map { it.id })
        assertEquals(0L, state.overflowCount)
        assertEquals(50L, state.receivedVersion)
        assertTrue(state.historyFull)
    }

    @Test fun `filtered only traffic never requests presentation or activity`() {
        val pipeline = DanmakuMessagePipeline(DanmakuPipelineConfig(
            keywordFilterEnabled = true, keywords = listOf(" BLOCKED ", "", " ")
        ))
        repeat(10) { assertFalse(pipeline.offer(item(it, "a blocked word"), it.toLong())) }
        val state = pipeline.drain(10)
        assertTrue(state.messages.isEmpty())
        assertEquals(10L, state.totalCount)
        assertEquals(0L, state.receivedVersion)
        assertEquals(0, state.messagesPerSecond)
        assertNull(pipeline.rateExpiresAtMs)
        assertTrue(pipeline.offer(item(20, "visible"), 20))
    }

    @Test fun `folding updates existing retained row across batches without extra keys`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same"), 0)
        assertEquals(1, pipeline.drain(0).messages.single().repeatCount)
        pipeline.offer(item(2, "other"), 100)
        pipeline.offer(item(3, "same"), 200)
        pipeline.offer(item(4, "same"), 250)
        val state = pipeline.drain(250)
        assertEquals(listOf("1", "2"), state.messages.map { it.id })
        assertEquals(3, state.messages.first().repeatCount)
        assertEquals(4L, state.receivedVersion)
    }

    @Test fun `fold window boundary opens new group and uses monotonic receive time`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same"), 100)
        pipeline.offer(item(2, "same"), 3099)
        pipeline.drain(3099)
        pipeline.offer(item(3, "same"), 3100)
        val state = pipeline.drain(3100)
        assertEquals(listOf("1", "3"), state.messages.map { it.id })
        assertEquals(listOf(2, 1), state.messages.map { it.repeatCount })
    }

    @Test fun `folding isolates rooms and never folds against expired history`() {
        val pipeline = pipeline(maxHistory = 1, fold = true)
        pipeline.offer(item(1, "same", room = 1), 0)
        pipeline.drain(0)
        pipeline.offer(item(2, "same", room = 2), 1)
        assertEquals("2", pipeline.drain(1).messages.single().id)
        pipeline.offer(item(3, "same", room = 1), 2)
        val state = pipeline.drain(2)
        assertEquals("3", state.messages.single().id)
        assertEquals(1, state.messages.single().repeatCount)
        assertEquals(1, pipeline.foldTargetCount)
    }

    @Test fun `fold index remains bounded during long running traffic`() {
        val pipeline = pipeline(maxHistory = 3, fold = true)
        repeat(20_000) {
            pipeline.offer(item(it), it.toLong())
            pipeline.drain(it.toLong())
            assertTrue(pipeline.foldTargetCount <= 3)
        }
        assertEquals(3, pipeline.historyCount)
    }

    @Test fun `folded activity advances generation while row count stays unchanged`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same"), 0)
        val before = pipeline.drain(0)
        pipeline.offer(item(2, "same"), 1)
        val after = pipeline.drain(1)
        assertEquals(before.messages.size, after.messages.size)
        assertEquals(before.receivedVersion + 1L, after.receivedVersion)
    }

    @Test fun `clear removes pending history folds rate unread and overflow together`() {
        val pipeline = pipeline(pendingCapacity = 1, fold = true)
        pipeline.setAutoScroll(false)
        pipeline.offer(item(1, "same"), 0)
        pipeline.drain(0)
        pipeline.offer(item(2, "same"), 1)
        pipeline.offer(item(3, "same"), 2)
        pipeline.clear()
        val cleared = pipeline.drain(3)
        assertTrue(cleared.messages.isEmpty())
        assertEquals(0L, cleared.totalCount)
        assertEquals(0L, cleared.overflowCount)
        assertEquals(0, cleared.unreadCount)
        assertEquals(0, cleared.messagesPerSecond)
        assertEquals(0, pipeline.pendingCount)
        assertEquals(0, pipeline.foldTargetCount)
        assertNull(pipeline.rateExpiresAtMs)
        pipeline.offer(item(4, "same"), 4)
        val next = pipeline.drain(4)
        assertEquals(1, next.messages.single().repeatCount)
        assertEquals(cleared.receivedVersion + 1, next.receivedVersion)
        assertEquals(1L, next.totalCount)
    }

    @Test fun `resuming autoscroll clears unread while pausing does not clear unread`() {
        val pipeline = pipeline()
        pipeline.setAutoScroll(false)
        pipeline.offer(item(1), 0)
        pipeline.offer(item(2), 1)
        assertEquals(2, pipeline.drain(1).unreadCount)
        assertFalse(pipeline.setAutoScroll(false))
        assertEquals(2, pipeline.drain(1).unreadCount)
        assertTrue(pipeline.setAutoScroll(true))
        assertEquals(0, pipeline.drain(1).unreadCount)
        pipeline.offer(item(3), 2)
        assertEquals(0, pipeline.drain(2).unreadCount)
    }

    @Test fun `paused unread counts eligible arrivals including folds but excludes filters`() {
        val pipeline = DanmakuMessagePipeline(DanmakuPipelineConfig(
            keywordFilterEnabled = true, keywords = listOf("blocked")
        ))
        pipeline.setAutoScroll(false)
        pipeline.offer(item(1, "same"), 0)
        pipeline.offer(item(2, "same"), 1)
        pipeline.offer(item(3, "blocked"), 2)
        val state = pipeline.drain(2)
        assertEquals(1, state.messages.size)
        assertEquals(2, state.unreadCount)
    }

    @Test fun `rate expires exactly once and idle snapshots stay zero`() {
        val pipeline = pipeline()
        pipeline.offer(item(1), 100)
        pipeline.offer(item(2), 100)
        assertEquals(2, pipeline.drain(1099).messagesPerSecond)
        assertEquals(1100L, pipeline.rateExpiresAtMs)
        assertEquals(0, pipeline.drain(1100).messagesPerSecond)
        assertEquals(0, pipeline.drain(1_000_000).messagesPerSecond)
    }

    @Test fun `rate window is bounded even during sustained per millisecond traffic`() {
        val pipeline = pipeline()
        repeat(10_000) { pipeline.offer(item(it), it.toLong()) }
        assertEquals(1000, pipeline.rateBucketCount)
        assertEquals(1000, pipeline.drain(9999).messagesPerSecond)
        assertEquals(0, pipeline.drain(10_999).messagesPerSecond)
        assertEquals(0, pipeline.rateBucketCount)
    }

    @Test fun `changing settings trims immediately and removes newly filtered pending data`() {
        val pipeline = pipeline()
        pipeline.offer(item(1, "visible"), 0)
        pipeline.offer(item(2, "blocked"), 1)
        pipeline.drain(1)
        pipeline.offer(item(3, "blocked"), 2)
        pipeline.offer(item(4, "visible"), 3)
        assertTrue(pipeline.updateConfiguration(DanmakuPipelineConfig(
            maxHistory = 1, keywordFilterEnabled = true, keywords = listOf("blocked")
        )))
        val state = pipeline.drain(3)
        assertEquals(listOf("4"), state.messages.map { it.id })
        assertEquals(0L, state.overflowCount)
    }

    @Test fun `invalid history limits clamp safely and maximum remains bounded`() {
        val pipeline = pipeline(maxHistory = 0)
        pipeline.offer(item(1), 0)
        pipeline.offer(item(2), 1)
        assertEquals(listOf("2"), pipeline.drain(1).messages.map { it.id })
        pipeline.updateConfiguration(DanmakuPipelineConfig(maxHistory = Int.MAX_VALUE))
        repeat(6000) { pipeline.offer(item(it + 3), it.toLong() + 2); pipeline.drain(it.toLong() + 2) }
        assertEquals(DanmakuMessagePipeline.MAX_HISTORY_CAPACITY, pipeline.historyCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero pending capacity is rejected`() { pipeline(pendingCapacity = 0) }

    @Test fun `duplicate source ids retain a single key and clean stale fold anchors`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "old"), 0)
        pipeline.drain(0)
        pipeline.offer(item(1, "new"), 1)
        pipeline.offer(item(2, "old"), 2)
        val state = pipeline.drain(2)
        assertEquals(listOf("1", "2"), state.messages.map { it.id })
        assertEquals(listOf("new", "old"), state.messages.map { it.content })
        assertEquals(listOf(1, 1), state.messages.map { it.repeatCount })
    }

    @Test fun `repeat count saturates instead of wrapping`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same").copy(repeatCount = Int.MAX_VALUE), 0)
        pipeline.offer(item(2, "same"), 1)
        assertEquals(Int.MAX_VALUE, pipeline.drain(1).messages.single().repeatCount)
    }
    @Test fun `pending text budget drops oldest entries even below the count limit`() {
        val pipeline = DanmakuMessagePipeline(
            DanmakuPipelineConfig(duplicateFoldEnabled = false), pendingCapacity = 100,
            pendingTextCapacity = 10, historyTextCapacity = 100
        )
        repeat(3) { pipeline.offer(item(it, "1234").copy(username = ""), it.toLong()) }
        assertEquals(2, pipeline.pendingCount)
        assertEquals(10L, pipeline.pendingTextUnits)
        val state = pipeline.drain(3)
        assertEquals(listOf("1", "2"), state.messages.map { it.id })
        assertEquals(1L, state.overflowCount)
        assertEquals(0L, pipeline.pendingTextUnits)
    }

    @Test fun `history text budget evicts oldest entries and explicitly counts overflow`() {
        val pipeline = DanmakuMessagePipeline(
            DanmakuPipelineConfig(duplicateFoldEnabled = false),
            pendingTextCapacity = 100, historyTextCapacity = 10
        )
        repeat(3) { pipeline.offer(item(it, "1234").copy(username = ""), it.toLong()) }
        val state = pipeline.drain(3)
        assertEquals(listOf("1", "2"), state.messages.map { it.id })
        assertEquals(1L, state.overflowCount)
        assertEquals(10L, pipeline.historyTextUnits)
    }

    @Test fun `count retention expiry is not double counted as text overflow`() {
        val pipeline = DanmakuMessagePipeline(
            DanmakuPipelineConfig(maxHistory = 2, duplicateFoldEnabled = false),
            pendingTextCapacity = 100, historyTextCapacity = 10
        )
        repeat(3) { pipeline.offer(item(it, "1234").copy(username = ""), it.toLong()) }
        assertEquals(0L, pipeline.drain(3).overflowCount)
    }

    @Test fun `oversized arrival is counted and rejected without evicting good history`() {
        val pipeline = DanmakuMessagePipeline(pendingTextCapacity = 10, historyTextCapacity = 10)
        pipeline.offer(item(0, "1234").copy(username = ""), 0)
        pipeline.drain(0)
        assertTrue(pipeline.offer(item(1, "1234567890").copy(username = ""), 1))
        assertEquals(0, pipeline.pendingCount)
        val state = pipeline.drain(1)
        assertEquals(listOf("0"), state.messages.map { it.id })
        assertEquals(1L, state.overflowCount)
        assertEquals(2L, state.receivedVersion)
    }

    @Test fun `text budget includes usernames badges and source ids`() {
        val pipeline = DanmakuMessagePipeline(pendingTextCapacity = 10, historyTextCapacity = 10)
        pipeline.offer(item(1, "").copy(username = "12345", badgeName = "67890"), 0)
        assertEquals(1L, pipeline.drain(0).overflowCount)
        assertEquals(0L, pipeline.pendingTextUnits)
    }

    @Test fun `large payload traffic remains bounded without huge test allocations`() {
        val sharedText = "x".repeat(64 * 1024)
        val pipeline = DanmakuMessagePipeline(DanmakuPipelineConfig(duplicateFoldEnabled = false))
        repeat(1000) {
            pipeline.offer(item(it, sharedText), it.toLong())
            pipeline.drain(it.toLong())
        }
        assertTrue(pipeline.historyTextUnits <= DanmakuMessagePipeline.DEFAULT_HISTORY_TEXT_CAPACITY)
        assertTrue(pipeline.historyCount < 32)
        assertTrue(pipeline.drain(1000).overflowCount > 0)
        pipeline.clear()
        assertEquals(0L, pipeline.historyTextUnits)
        assertEquals(0L, pipeline.pendingTextUnits)
    }

    @Test fun `filter change and duplicate folds keep text accounting accurate`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same"), 0)
        val before = pipeline.drain(0)
        val textUnits = pipeline.historyTextUnits
        pipeline.offer(item(2, "same"), 1)
        pipeline.drain(1)
        assertEquals(textUnits, pipeline.historyTextUnits)
        pipeline.offer(item(3, "same"), 2)
        pipeline.updateConfiguration(DanmakuPipelineConfig(keywordFilterEnabled = true, keywords = listOf("same")))
        assertEquals(0L, pipeline.historyTextUnits)
        assertEquals(0L, pipeline.pendingTextUnits)
        assertEquals(1, before.messages.size)
    }

}
