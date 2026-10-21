package com.bililive.danmaku.data.remote

import com.bililive.danmaku.domain.model.DanmakuItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PendingDanmakuBufferTest {
    private fun item(id: Int, content: String = "message") = DanmakuItem(
        id = "$id", roomId = 1, uid = 1, username = "test", content = content, timestamp = 0)

    @Test fun `one burst preserves all messages in order`() = runTest {
        val buffer = PendingDanmakuBuffer(2, 300, 1024 * 1024)
        assertTrue(buffer.offer(1, (0 until 200).map { item(it) }))
        repeat(200) { index ->
            val next = buffer.take()
            assertEquals("$index", next.item.id)
            buffer.release(next)
        }
        assertTrue(buffer.offer(2, listOf(item(201))))
    }

    @Test fun `in flight messages still consume batch message and byte budgets`() = runTest {
        val buffer = PendingDanmakuBuffer(1, 1, 300)
        assertTrue(buffer.offer(1, listOf(item(1))))
        val delivery = buffer.take()
        assertFalse(buffer.offer(1, listOf(item(2))))
        buffer.release(delivery)
        assertTrue(buffer.offer(1, listOf(item(2))))
    }

    @Test fun `message and byte budgets reject a whole batch instead of partial delivery`() = runTest {
        assertFalse(PendingDanmakuBuffer(10, 2, 10000).offer(1, (0..2).map { item(it) }))
        val bytes = PendingDanmakuBuffer(10, 10, 600)
        assertFalse(bytes.offer(1, listOf(item(1, "x".repeat(200)))))
        assertTrue(bytes.offer(1, listOf(item(2))))
        assertEquals("2", bytes.take().item.id)
    }

    @Test fun `clear removes queued generations but leaves in flight accounting valid`() = runTest {
        val buffer = PendingDanmakuBuffer(2, 3, 10000)
        assertTrue(buffer.offer(1, listOf(item(1), item(2))))
        val first = buffer.take()
        buffer.clear()
        assertTrue(buffer.offer(2, listOf(item(3), item(4))))
        assertFalse(buffer.offer(2, listOf(item(5))))
        buffer.release(first)
        assertTrue(buffer.offer(2, listOf(item(5))))
        repeat(3) { index ->
            val next = buffer.take()
            assertEquals(2L, next.batch.generation)
            assertEquals("${index + 3}", next.item.id)
            buffer.release(next)
        }
    }
}
