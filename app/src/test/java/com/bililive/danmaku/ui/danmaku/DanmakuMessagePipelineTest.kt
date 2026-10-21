package com.bililive.danmaku.ui.danmaku

import com.bililive.danmaku.domain.model.DanmakuItem
import org.junit.Assert.*
import org.junit.Test

class DanmakuMessagePipelineTest {
    private fun item(id: Int, content: String = "message $id", room: Long = 1L) = DanmakuItem(
        id = id.toString(), roomId = room, uid = null, username = "viewer", content = content,
        timestamp = 0L, receiveTimeMillis = 0L
    )

    private fun pipeline(maxHistory: Int = 3, fold: Boolean = false) =
        DanmakuMessagePipeline(
            DanmakuPipelineConfig(maxHistory = maxHistory, duplicateFoldEnabled = fold)
        )

    @Test fun `600 hidden arrivals fill available history before any snapshot without a 512 queue loss`() {
        val pipeline = pipeline(maxHistory = 1000)
        repeat(600) { assertTrue(pipeline.offer(item(it), it.toLong())) }
        assertEquals(600, pipeline.historyCount)
        val state = pipeline.snapshot(600)
        assertEquals((0..599).map(Int::toString), state.messages.map { it.id })
        assertEquals(0L, state.overflowCount)
        assertEquals(0L, state.diagnostics.historyEvictedRows)
        assertEquals(600L, state.totalCount)
        assertEquals(600L, state.receivedVersion)
    }

    @Test fun `history expiry is bounded but is not incoming overflow`() {
        val pipeline = pipeline()
        repeat(50) { pipeline.offer(item(it), it.toLong()); pipeline.snapshot(it.toLong()) }
        val state = pipeline.snapshot(50)
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
        val state = pipeline.snapshot(10)
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
        assertEquals(1, pipeline.snapshot(0).messages.single().repeatCount)
        pipeline.offer(item(2, "other"), 100)
        pipeline.offer(item(3, "same"), 200)
        pipeline.offer(item(4, "same"), 250)
        val state = pipeline.snapshot(250)
        assertEquals(listOf("1", "2"), state.messages.map { it.id })
        assertEquals(3, state.messages.first().repeatCount)
        assertEquals(4L, state.receivedVersion)
    }

    @Test fun `fold window boundary opens new group and uses monotonic receive time`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same"), 100)
        pipeline.offer(item(2, "same"), 3099)
        pipeline.snapshot(3099)
        pipeline.offer(item(3, "same"), 3100)
        val state = pipeline.snapshot(3100)
        assertEquals(listOf("1", "3"), state.messages.map { it.id })
        assertEquals(listOf(2, 1), state.messages.map { it.repeatCount })
    }

    @Test fun `folding isolates rooms and never folds against expired history`() {
        val pipeline = pipeline(maxHistory = 1, fold = true)
        pipeline.offer(item(1, "same", room = 1), 0)
        pipeline.snapshot(0)
        pipeline.offer(item(2, "same", room = 2), 1)
        assertEquals("2", pipeline.snapshot(1).messages.single().id)
        pipeline.offer(item(3, "same", room = 1), 2)
        val state = pipeline.snapshot(2)
        assertEquals("3", state.messages.single().id)
        assertEquals(1, state.messages.single().repeatCount)
        assertEquals(1, pipeline.foldTargetCount)
    }

    @Test fun `fold index remains bounded during long running traffic`() {
        val pipeline = pipeline(maxHistory = 3, fold = true)
        repeat(20_000) {
            pipeline.offer(item(it), it.toLong())
            pipeline.snapshot(it.toLong())
            assertTrue(pipeline.foldTargetCount <= 3)
        }
        assertEquals(3, pipeline.historyCount)
    }

    @Test fun `folded activity advances generation while row count stays unchanged`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same"), 0)
        val before = pipeline.snapshot(0)
        pipeline.offer(item(2, "same"), 1)
        val after = pipeline.snapshot(1)
        assertEquals(before.messages.size, after.messages.size)
        assertEquals(before.receivedVersion + 1L, after.receivedVersion)
    }

    @Test fun `clear removes history folds rate unread and diagnostics together`() {
        val pipeline = pipeline(fold = true)
        pipeline.setAutoScroll(false)
        pipeline.offer(item(1, "same"), 0)
        pipeline.snapshot(0)
        pipeline.offer(item(2, "same"), 1)
        pipeline.offer(item(3, "same"), 2)
        pipeline.clear()
        val cleared = pipeline.snapshot(3)
        assertTrue(cleared.messages.isEmpty())
        assertEquals(0L, cleared.totalCount)
        assertEquals(0L, cleared.overflowCount)
        assertEquals(0, cleared.unreadCount)
        assertEquals(0, cleared.messagesPerSecond)
        assertEquals(0, pipeline.foldTargetCount)
        assertNull(pipeline.rateExpiresAtMs)
        pipeline.offer(item(4, "same"), 4)
        val next = pipeline.snapshot(4)
        assertEquals(1, next.messages.single().repeatCount)
        assertEquals(cleared.receivedVersion + 1, next.receivedVersion)
        assertEquals(1L, next.totalCount)
    }

    @Test fun `resuming autoscroll clears unread while pausing does not clear unread`() {
        val pipeline = pipeline()
        pipeline.setAutoScroll(false)
        pipeline.offer(item(1), 0)
        pipeline.offer(item(2), 1)
        assertEquals(2, pipeline.snapshot(1).unreadCount)
        assertFalse(pipeline.setAutoScroll(false))
        assertEquals(2, pipeline.snapshot(1).unreadCount)
        assertTrue(pipeline.setAutoScroll(true))
        assertEquals(0, pipeline.snapshot(1).unreadCount)
        pipeline.offer(item(3), 2)
        assertEquals(0, pipeline.snapshot(2).unreadCount)
    }

    @Test fun `paused unread counts eligible arrivals including folds but excludes filters`() {
        val pipeline = DanmakuMessagePipeline(DanmakuPipelineConfig(
            keywordFilterEnabled = true, keywords = listOf("blocked")
        ))
        pipeline.setAutoScroll(false)
        pipeline.offer(item(1, "same"), 0)
        pipeline.offer(item(2, "same"), 1)
        pipeline.offer(item(3, "blocked"), 2)
        val state = pipeline.snapshot(2)
        assertEquals(1, state.messages.size)
        assertEquals(2, state.unreadCount)
    }

    @Test fun `rate expires exactly once and idle snapshots stay zero`() {
        val pipeline = pipeline()
        pipeline.offer(item(1), 100)
        pipeline.offer(item(2), 100)
        assertEquals(2, pipeline.snapshot(1099).messagesPerSecond)
        assertEquals(1100L, pipeline.rateExpiresAtMs)
        assertEquals(0, pipeline.snapshot(1100).messagesPerSecond)
        assertEquals(0, pipeline.snapshot(1_000_000).messagesPerSecond)
    }

    @Test fun `rate window is bounded even during sustained per millisecond traffic`() {
        val pipeline = pipeline()
        repeat(10_000) { pipeline.offer(item(it), it.toLong()) }
        assertEquals(1000, pipeline.rateBucketCount)
        assertEquals(1000, pipeline.snapshot(9999).messagesPerSecond)
        assertEquals(0, pipeline.snapshot(10_999).messagesPerSecond)
        assertEquals(0, pipeline.rateBucketCount)
    }

    @Test fun `changing settings trims immediately and counts filter pruned rows`() {
        val pipeline = pipeline()
        pipeline.offer(item(1, "visible"), 0)
        pipeline.offer(item(2, "blocked"), 1)
        pipeline.snapshot(1)
        pipeline.offer(item(3, "blocked"), 2)
        pipeline.offer(item(4, "visible"), 3)
        assertTrue(pipeline.updateConfiguration(DanmakuPipelineConfig(
            maxHistory = 1, keywordFilterEnabled = true, keywords = listOf("blocked")
        )))
        val state = pipeline.snapshot(3)
        assertEquals(listOf("4"), state.messages.map { it.id })
        assertEquals(0L, state.overflowCount)
        assertEquals(2L, state.diagnostics.filterPrunedRows)
        assertEquals(1L, state.diagnostics.historyCountEvictedRows)
    }

    @Test fun `invalid history limits clamp safely and maximum remains bounded`() {
        val pipeline = pipeline(maxHistory = 0)
        pipeline.offer(item(1), 0)
        pipeline.offer(item(2), 1)
        assertEquals(listOf("2"), pipeline.snapshot(1).messages.map { it.id })
        pipeline.updateConfiguration(DanmakuPipelineConfig(maxHistory = Int.MAX_VALUE))
        repeat(6000) { pipeline.offer(item(it + 3), it.toLong() + 2); pipeline.snapshot(it.toLong() + 2) }
        assertEquals(DanmakuMessagePipeline.MAX_HISTORY_CAPACITY, pipeline.historyCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero single item text capacity is rejected`() { DanmakuMessagePipeline(maxItemTextCapacity = 0) }

    @Test fun `duplicate source ids retain a single key and clean stale fold anchors`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "old"), 0)
        pipeline.snapshot(0)
        pipeline.offer(item(1, "new"), 1)
        pipeline.offer(item(2, "old"), 2)
        val state = pipeline.snapshot(2)
        assertEquals(listOf("1", "2"), state.messages.map { it.id })
        assertEquals(listOf("new", "old"), state.messages.map { it.content })
        assertEquals(listOf(1, 1), state.messages.map { it.repeatCount })
    }

    @Test fun `repeat count saturates instead of wrapping`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same").copy(repeatCount = Int.MAX_VALUE), 0)
        pipeline.offer(item(2, "same"), 1)
        assertEquals(Int.MAX_VALUE, pipeline.snapshot(1).messages.single().repeatCount)
    }
    @Test fun `history can retain aggregate text beyond former pending budget without snapshots`() {
        val pipeline = DanmakuMessagePipeline(DanmakuPipelineConfig(duplicateFoldEnabled = false),
            maxItemTextCapacity = 10, historyTextCapacity = 100)
        repeat(3) { pipeline.offer(item(it, "1234").copy(username = ""), it.toLong()) }
        assertEquals(3, pipeline.historyCount)
        assertEquals(15L, pipeline.historyTextUnits)
        val state = pipeline.snapshot(3)
        assertEquals(listOf("0", "1", "2"), state.messages.map { it.id })
        assertEquals(0L, state.overflowCount)
    }

    @Test fun `history text budget evicts oldest entries and explicitly counts overflow`() {
        val pipeline = DanmakuMessagePipeline(
            DanmakuPipelineConfig(duplicateFoldEnabled = false),
            maxItemTextCapacity = 100, historyTextCapacity = 10
        )
        repeat(3) { pipeline.offer(item(it, "1234").copy(username = ""), it.toLong()) }
        val state = pipeline.snapshot(3)
        assertEquals(listOf("1", "2"), state.messages.map { it.id })
        assertEquals(1L, state.overflowCount)
        assertEquals(1L, state.diagnostics.historyTextEvictedRows)
        assertEquals(0L, state.diagnostics.historyCountEvictedRows)
        assertEquals(10L, pipeline.historyTextUnits)
    }

    @Test fun `count retention expiry is not double counted as text overflow`() {
        val pipeline = DanmakuMessagePipeline(
            DanmakuPipelineConfig(maxHistory = 2, duplicateFoldEnabled = false),
            maxItemTextCapacity = 100, historyTextCapacity = 10
        )
        repeat(3) { pipeline.offer(item(it, "1234").copy(username = ""), it.toLong()) }
        assertEquals(0L, pipeline.snapshot(3).overflowCount)
    }

    @Test fun `oversized arrival is counted and rejected without evicting good history`() {
        val pipeline = DanmakuMessagePipeline(maxItemTextCapacity = 10, historyTextCapacity = 10)
        pipeline.offer(item(0, "1234").copy(username = ""), 0)
        pipeline.snapshot(0)
        assertTrue(pipeline.offer(item(1, "1234567890").copy(username = ""), 1))
        val state = pipeline.snapshot(1)
        assertEquals(listOf("0"), state.messages.map { it.id })
        assertEquals(1L, state.overflowCount)
        assertEquals(1L, state.receivedVersion)
        assertEquals(1L, state.diagnostics.oversizedMessages)
    }

    @Test fun `text budget includes usernames badges and source ids`() {
        val pipeline = DanmakuMessagePipeline(maxItemTextCapacity = 10, historyTextCapacity = 10)
        pipeline.offer(item(1, "").copy(username = "12345", badgeName = "67890"), 0)
        assertEquals(1L, pipeline.snapshot(0).overflowCount)
    }

    @Test fun `large payload traffic remains bounded without huge test allocations`() {
        val sharedText = "x".repeat(64 * 1024)
        val pipeline = DanmakuMessagePipeline(DanmakuPipelineConfig(duplicateFoldEnabled = false))
        repeat(1000) {
            pipeline.offer(item(it, sharedText), it.toLong())
            pipeline.snapshot(it.toLong())
        }
        assertTrue(pipeline.historyTextUnits <= DanmakuMessagePipeline.DEFAULT_HISTORY_TEXT_CAPACITY)
        assertTrue(pipeline.historyCount < 32)
        assertTrue(pipeline.snapshot(1000).overflowCount > 0)
        pipeline.clear()
        assertEquals(0L, pipeline.historyTextUnits)
    }

    @Test fun `filter change and duplicate folds keep text accounting accurate`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same"), 0)
        val before = pipeline.snapshot(0)
        val textUnits = pipeline.historyTextUnits
        pipeline.offer(item(2, "same"), 1)
        pipeline.snapshot(1)
        assertEquals(textUnits, pipeline.historyTextUnits)
        pipeline.offer(item(3, "same"), 2)
        pipeline.updateConfiguration(DanmakuPipelineConfig(keywordFilterEnabled = true, keywords = listOf("same")))
        assertEquals(0L, pipeline.historyTextUnits)
        assertEquals(1, before.messages.size)
    }

    @Test fun `5000 history capacity is used while hidden and only configured expiry removes rows`() {
        val pipeline = pipeline(maxHistory = 5000)
        repeat(5200) { pipeline.offer(item(it), it.toLong()) }
        assertEquals(5000, pipeline.historyCount)
        val state = pipeline.snapshot(5200)
        assertEquals((200..5199).map(Int::toString), state.messages.map { it.id })
        assertEquals(200L, state.diagnostics.historyCountEvictedRows)
        assertEquals(0L, state.diagnostics.historyTextEvictedRows)
        assertEquals(0L, state.overflowCount)
    }

    @Test fun `hidden duplicate traffic folds immediately without a pending cap`() {
        val pipeline = pipeline(maxHistory = 1000, fold = true)
        repeat(600) { pipeline.offer(item(it, "same"), it.toLong()) }
        assertEquals(1, pipeline.historyCount)
        val state = pipeline.snapshot(600)
        assertEquals(600, state.messages.single().repeatCount)
        assertEquals(599L, state.diagnostics.foldedMessages)
        assertEquals(0L, state.diagnostics.historyEvictedRows)
    }

    @Test fun `hidden and frequently published histories remain identical through mixed events`() {
        val config = DanmakuPipelineConfig(maxHistory = 20, keywordFilterEnabled = true,
            keywords = listOf("blocked"), duplicateFoldEnabled = true)
        val visible = DanmakuMessagePipeline(config, maxItemTextCapacity = 100, historyTextCapacity = 300)
        val hidden = DanmakuMessagePipeline(config, maxItemTextCapacity = 100, historyTextCapacity = 300)
        repeat(1200) { id ->
            val body = when {
                id % 19 == 0 -> "blocked"
                id % 23 == 0 -> "x".repeat(101)
                else -> "body ${id % 11}"
            }
            val incoming = item(id % 31, body)
            assertEquals(visible.offer(incoming, id.toLong()), hidden.offer(incoming, id.toLong()))
            visible.snapshot(id.toLong())
            if (id == 600) { visible.clear(); hidden.clear() }
            if (id == 900) {
                val changed = config.copy(maxHistory = 5, keywords = listOf("blocked", "body 1"))
                visible.updateConfiguration(changed); hidden.updateConfiguration(changed)
            }
        }
        assertEquals(visible.snapshot(1200), hidden.snapshot(1200))
        assertTrue(hidden.historyTextUnits <= 300)
    }

    @Test fun `filter fold budget expiry replacement and reconfiguration counters use distinct units`() {
        val pipeline = DanmakuMessagePipeline(DanmakuPipelineConfig(maxHistory = 2,
            keywordFilterEnabled = true, keywords = listOf("blocked"), duplicateFoldEnabled = true),
            maxItemTextCapacity = 100)
        pipeline.offer(item(0, "blocked"), 0)
        pipeline.offer(item(1, "same"), 1)
        pipeline.offer(item(2, "same"), 2)
        pipeline.offer(item(3, "other"), 3)
        pipeline.offer(item(4, "third"), 4)
        pipeline.offer(item(5, "x".repeat(101)), 5)
        pipeline.offer(item(4, "replacement"), 6)
        pipeline.updateConfiguration(DanmakuPipelineConfig(maxHistory = 2,
            keywordFilterEnabled = true, keywords = listOf("replacement")))
        val state = pipeline.snapshot(6)
        assertEquals(7L, state.totalCount)
        assertEquals(1L, state.diagnostics.filteredMessages)
        assertEquals(1L, state.diagnostics.foldedMessages)
        assertEquals(1L, state.diagnostics.oversizedMessages)
        assertEquals(1L, state.diagnostics.historyCountEvictedRows)
        assertEquals(0L, state.diagnostics.historyTextEvictedRows)
        assertEquals(1L, state.diagnostics.replacedIdRows)
        assertEquals(1L, state.diagnostics.filterPrunedRows)
        assertEquals(listOf("3"), state.messages.map { it.id })
    }

    @Test fun `published snapshot is immutable while hidden ingestion folds and replaces rows`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same"), 0)
        val previous = pipeline.snapshot(0)
        pipeline.offer(item(2, "same"), 1)
        pipeline.offer(item(3, "other"), 2)
        assertEquals(listOf("1"), previous.messages.map { it.id })
        assertEquals(1, previous.messages.single().repeatCount)
        assertEquals(0L, previous.diagnostics.foldedMessages)
        assertEquals(2, pipeline.snapshot(2).messages.first().repeatCount)
    }

    @Test fun `reused id is removed before folding into another existing row`() {
        val pipeline = pipeline(fold = true)
        pipeline.offer(item(1, "same"), 0)
        pipeline.offer(item(2, "old"), 1)
        pipeline.offer(item(2, "same"), 2)
        val state = pipeline.snapshot(2)
        assertEquals(listOf("1"), state.messages.map { it.id })
        assertEquals(2, state.messages.single().repeatCount)
        assertEquals(1L, state.diagnostics.replacedIdRows)
        assertEquals(1L, state.diagnostics.foldedMessages)
        assertEquals(1, pipeline.foldTargetCount)
        assertEquals(11L, pipeline.historyTextUnits)
    }

    @Test fun `oversized arrivals report loss but do not create displayed activity or unread`() {
        val pipeline = DanmakuMessagePipeline(maxItemTextCapacity = 10)
        pipeline.setAutoScroll(false)
        assertTrue(pipeline.offer(item(1, "x".repeat(11)), 0))
        val state = pipeline.snapshot(0)
        assertEquals(1L, state.totalCount)
        assertEquals(1L, state.diagnostics.oversizedMessages)
        assertEquals(0L, state.receivedVersion)
        assertEquals(0, state.unreadCount)
        assertEquals(0, state.messagesPerSecond)
        assertTrue(state.messages.isEmpty())
    }

}
