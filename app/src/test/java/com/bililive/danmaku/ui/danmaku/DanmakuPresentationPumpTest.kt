package com.bililive.danmaku.ui.danmaku

import com.bililive.danmaku.domain.model.DanmakuItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DanmakuPresentationPumpTest {
    private fun item(id: Int, content: String = "message $id") = DanmakuItem(
        id.toString(), 1L, null, "viewer", content, 0L
    )

    @Test fun `idle timer resets rate once then stops completely`() = runTest {
        val pipeline = DanmakuMessagePipeline()
        val published = mutableListOf<Pair<Long, Int>>()
        val pump = DanmakuPresentationPump(backgroundScope, { testScheduler.currentTime }) { now ->
            val snapshot = pipeline.drain(now)
            published.add(now to snapshot.messagesPerSecond)
            if (snapshot.messagesPerSecond > 0) pipeline.rateExpiresAtMs else null
        }
        pipeline.offer(item(1), 0)
        pump.requestPresentation()
        pump.setVisible(true)
        runCurrent()
        assertEquals(listOf(0L to 1), published)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf(0L to 1, 1000L to 0), published)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(2, published.size)
    }

    @Test fun `coroutine batches sustained low power traffic no more than four times per second`() = runTest {
        val pipeline = DanmakuMessagePipeline()
        val times = mutableListOf<Long>()
        val pump = DanmakuPresentationPump(backgroundScope, { testScheduler.currentTime }) { now ->
            pipeline.drain(now)
            times.add(now)
            pipeline.rateExpiresAtMs
        }
        pump.setLowPowerMode(true)
        pump.setVisible(true)
        repeat(1000) {
            pipeline.offer(item(it), testScheduler.currentTime)
            pump.requestPresentation()
            runCurrent()
            advanceTimeBy(1)
        }
        assertEquals(listOf(0L, 250L, 500L, 750L), times)
        assertEquals(249, pipeline.pendingCount)
    }

    @Test fun `hidden screen interrupts timer and resumes latest backlog without intermediate publishes`() = runTest {
        val pipeline = DanmakuMessagePipeline(pendingCapacity = 3)
        val snapshots = mutableListOf<DanmakuPresentation>()
        val pump = DanmakuPresentationPump(backgroundScope, { testScheduler.currentTime }) { now ->
            val snapshot = pipeline.drain(now)
            snapshots.add(snapshot)
            if (snapshot.messagesPerSecond > 0) pipeline.rateExpiresAtMs else null
        }
        pump.setVisible(true)
        runCurrent()
        pump.setVisible(false)
        runCurrent()
        repeat(20) {
            pipeline.offer(item(it), testScheduler.currentTime)
            pump.requestPresentation()
            advanceTimeBy(100)
        }
        runCurrent()
        assertEquals(1, snapshots.size)
        assertEquals(3, pipeline.pendingCount)
        pump.setVisible(true)
        runCurrent()
        assertEquals(2, snapshots.size)
        assertEquals(listOf("17", "18", "19"), snapshots.last().messages.map { it.id })
        assertEquals(17L, snapshots.last().overflowCount)
    }

    @Test fun `filtered only traffic does not wake or publish a visible idle screen`() = runTest {
        val pipeline = DanmakuMessagePipeline(DanmakuPipelineConfig(
            keywordFilterEnabled = true, keywords = listOf("blocked")
        ))
        var publicationCount = 0
        val pump = DanmakuPresentationPump(backgroundScope, { testScheduler.currentTime }) { now ->
            pipeline.drain(now)
            publicationCount++
            null
        }
        pump.setVisible(true)
        runCurrent()
        repeat(1000) {
            if (pipeline.offer(item(it, "blocked"), testScheduler.currentTime)) pump.requestPresentation()
            advanceTimeBy(10)
        }
        runCurrent()
        assertEquals(1, publicationCount)
        assertEquals(0, pipeline.pendingCount)
    }

    @Test fun `mode changes interrupt pending wait and use the new interval`() = runTest {
        val times = mutableListOf<Long>()
        val pump = DanmakuPresentationPump(backgroundScope, { testScheduler.currentTime }) { now ->
            times.add(now)
            null
        }
        pump.setLowPowerMode(true)
        pump.setVisible(true)
        runCurrent()
        advanceTimeBy(10)
        pump.requestPresentation()
        runCurrent()
        advanceTimeBy(10)
        pump.setLowPowerMode(false)
        runCurrent()
        advanceTimeBy(30)
        runCurrent()
        assertEquals(listOf(0L, 50L), times)
    }

    @Test fun `clear during pending batch cannot resurrect old messages`() = runTest {
        val pipeline = DanmakuMessagePipeline()
        val snapshots = mutableListOf<DanmakuPresentation>()
        val pump = DanmakuPresentationPump(backgroundScope, { testScheduler.currentTime }) { now ->
            val snapshot = pipeline.drain(now)
            snapshots.add(snapshot)
            if (snapshot.messagesPerSecond > 0) pipeline.rateExpiresAtMs else null
        }
        pump.setVisible(true)
        runCurrent()
        advanceTimeBy(1)
        pipeline.offer(item(1), 1)
        pump.requestPresentation()
        runCurrent()
        pipeline.clear()
        pump.requestPresentation()
        advanceTimeBy(49)
        runCurrent()
        assertTrue(snapshots.last().messages.isEmpty())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, snapshots.size)
    }
}
