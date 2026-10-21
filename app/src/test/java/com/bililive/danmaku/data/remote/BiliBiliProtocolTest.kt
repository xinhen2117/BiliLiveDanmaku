package com.bililive.danmaku.data.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Random
import java.util.zip.DeflaterOutputStream

class BiliBiliProtocolTest {
    private fun packet(op: Int, body: ByteArray, version: Int = 0, headerSize: Int = 16): ByteArray =
        ByteBuffer.allocate(headerSize + body.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(capacity())
            putShort(headerSize.toShort())
            putShort(version.toShort())
            putInt(op)
            putInt(1)
            position(headerSize)
            put(body)
        }.array()

    private fun message(content: String = "测试弹幕", command: String = "DANMU_MSG"): ByteArray =
        """{"cmd":"$command","info":[[],"$content",[100,"测试用户"],[5,"粉丝","主播",12345]]}""".toByteArray()

    private fun compressed(data: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DeflaterOutputStream(output).use { it.write(data) }
    }.toByteArray()

    private fun zlibPacket(data: ByteArray): ByteArray = packet(5, compressed(data), version = 2)

    @Test
    fun `auth packet includes correct header and escaped credentials`() {
        val auth = BiliBiliProtocol.buildAuthPacket(12345, "a\"b", "buvid", 42)
        val header = ByteBuffer.wrap(auth).order(ByteOrder.BIG_ENDIAN)
        assertEquals(auth.size, header.int)
        assertEquals(16, header.short.toInt())
        assertEquals(1, header.short.toInt())
        assertEquals(7, header.int)
        assertEquals(1, header.int)
        val body = Json.parseToJsonElement(auth.copyOfRange(16, auth.size).decodeToString()).jsonObject
        assertEquals(12345L, body.getValue("roomid").jsonPrimitive.long)
        assertEquals(42L, body.getValue("uid").jsonPrimitive.long)
        assertEquals(2, body.getValue("protover").jsonPrimitive.int)
        assertEquals("a\"b", body.getValue("key").jsonPrimitive.content)
        assertEquals("buvid", body.getValue("buvid").jsonPrimitive.content)
    }

    @Test
    fun `auth packet omits absent credentials`() {
        val auth = BiliBiliProtocol.buildAuthPacket(1)
        val body = Json.parseToJsonElement(auth.copyOfRange(16, auth.size).decodeToString()).jsonObject
        assertFalse(body.containsKey("key"))
        assertFalse(body.containsKey("buvid"))
        assertEquals(0L, body.getValue("uid").jsonPrimitive.long)
    }

    @Test
    fun `heartbeat uses normal body version and heartbeat operation`() {
        val heartbeat = BiliBiliProtocol.buildHeartbeatPacket()
        assertEquals(16, heartbeat.size)
        val header = ByteBuffer.wrap(heartbeat).order(ByteOrder.BIG_ENDIAN)
        assertEquals(16, header.int)
        assertEquals(16, header.short.toInt())
        assertEquals(1, header.short.toInt())
        assertEquals(2, header.int)
        assertEquals(1, header.int)
    }

    @Test
    fun `uncompressed message uses actual medal field order`() {
        val result = BiliBiliProtocol.extractDanmaku(packet(5, message())).single()
        assertEquals("测试弹幕", result.content)
        assertEquals("测试用户", result.username)
        assertEquals(100L, result.uid)
        assertEquals("粉丝", result.badgeName)
        assertEquals(5, result.badgeLevel)
        assertTrue(result.timestamp > 0)
    }

    @Test
    fun `version one JSON and extended headers are supported`() {
        assertEquals("测试弹幕", BiliBiliProtocol.extractDanmaku(packet(5, message(), 1, 20)).single().content)
    }

    @Test
    fun `suffixed DANMU command is supported`() {
        assertEquals(1, BiliBiliProtocol.extractDanmaku(packet(5, message(command = "DANMU_MSG:4:0:2:2:2:0"))).size)
        assertTrue(BiliBiliProtocol.extractDanmaku(packet(5, message(command = "DANMU_MSG_OTHER"))).isEmpty())
    }

    @Test
    fun `zlib contains multiple inner messages`() {
        val frame = zlibPacket(packet(5, message("A")) + packet(5, message("B")))
        assertEquals(listOf("A", "B"), BiliBiliProtocol.extractDanmaku(frame).map { it.content })
    }

    @Test
    fun `multiple compressed outer packets preserve order`() {
        val frame = zlibPacket(packet(5, message("A"))) + zlibPacket(packet(5, message("B")))
        assertEquals(listOf("A", "B"), BiliBiliProtocol.extractDanmaku(frame).map { it.content })
    }

    @Test
    fun `brotli packet is decoded`() {
        // Generated from a synthetic version-0 packet using Node's zlib.brotliCompressSync.
        val body = Base64.getDecoder().decode("CyKAAAAARQAQAAAAAAAFAAAAAXsiY21kIjoiREFOTVVfTVNHIiwiaW5mbyI6W1tdLCJCcm90bGkiLFs3LCJ0ZXN0ZXIiXV19Aw==")
        val result = BiliBiliProtocol.extractDanmaku(packet(5, body, 3)).single()
        assertEquals("Brotli", result.content)
        assertEquals(7L, result.uid)
    }

    @Test
    fun `mixed control and danmaku packets are decoded`() {
        val frame = packet(3, byteArrayOf(0, 0, 0, 1), 1) + packet(8, "{\"code\":0}".toByteArray(), 1) + packet(5, message())
        assertEquals(1, BiliBiliProtocol.extractDanmaku(frame).size)
    }

    @Test
    fun `non danmaku commands and operations are ignored`() {
        assertTrue(BiliBiliProtocol.extractDanmaku(packet(5, "{\"cmd\":\"INTERACT_WORD_V2\",\"data\":{}}".toByteArray())).isEmpty())
        assertTrue(BiliBiliProtocol.extractDanmaku(packet(8, message())).isEmpty())
    }

    @Test
    fun `unknown version does not guess body format`() {
        val frame = packet(5, message("ignored"), 99) + packet(5, message("valid"))
        assertEquals(listOf("valid"), BiliBiliProtocol.extractDanmaku(frame).map { it.content })
        assertTrue(BiliBiliProtocol.extractDanmaku(packet(5, compressed(packet(5, message())), 0)).isEmpty())
    }

    @Test
    fun `malformed JSON does not prevent next valid message`() {
        val invalid = listOf("{", "null", "[]", "{\"cmd\":{}}", "{\"cmd\":\"DANMU_MSG\",\"info\":{}}", "{\"cmd\":\"DANMU_MSG\",\"info\":[[],null]}")
        for (body in invalid) {
            val result = BiliBiliProtocol.extractDanmaku(packet(5, body.toByteArray()) + packet(5, message()))
            assertEquals("Invalid body: $body", 1, result.size)
        }
    }

    @Test
    fun `missing optional fields do not discard content`() {
        val result = BiliBiliProtocol.extractDanmaku(packet(5, """{"cmd":"DANMU_MSG","info":[[],"hello"]}""".toByteArray())).single()
        assertEquals("unknown", result.username)
        assertNull(result.uid)
        assertNull(result.badgeName)
        assertNull(result.badgeLevel)
    }

    @Test
    fun `short empty and truncated frames are ignored`() {
        assertTrue(BiliBiliProtocol.extractDanmaku(byteArrayOf()).isEmpty())
        assertTrue(BiliBiliProtocol.extractDanmaku(byteArrayOf(1, 2)).isEmpty())
        val good = packet(5, message())
        for (length in 0 until good.size) assertTrue(BiliBiliProtocol.extractDanmaku(good.copyOf(length)).isEmpty())
    }

    @Test
    fun `invalid header lengths cannot slice outside packet`() {
        for (headerSize in listOf(0, 15, 17, 65535)) {
            val broken = packet(5, byteArrayOf())
            ByteBuffer.wrap(broken).putShort(4, headerSize.toShort())
            assertTrue("Header size $headerSize", BiliBiliProtocol.extractDanmaku(broken).isEmpty())
        }
    }

    @Test
    fun `invalid packet lengths including overflow are rejected`() {
        for (length in listOf(-1, 0, 15, Int.MAX_VALUE)) {
            val broken = packet(5, byteArrayOf())
            ByteBuffer.wrap(broken).putInt(0, length)
            val result = BiliBiliProtocol.extractDanmaku(packet(5, message()) + broken)
            assertEquals(1, result.size)
        }
    }

    @Test
    fun `malformed compressed packet does not swallow later outer packet`() {
        for (version in listOf(2, 3)) {
            val frame = packet(5, byteArrayOf(1, 2, 3), version) + packet(5, message())
            assertEquals(1, BiliBiliProtocol.extractDanmaku(frame).size)
        }
    }

    @Test
    fun `truncated zlib stream is not accepted as partial success`() {
        val compressed = compressed(packet(5, message()))
        assertTrue(BiliBiliProtocol.extractDanmaku(packet(5, compressed.copyOf(compressed.size - 2), 2)).isEmpty())
    }

    @Test
    fun `nested compression is supported within depth limit`() {
        var frame = packet(5, message())
        repeat(BiliBiliProtocol.MAX_COMPRESSION_DEPTH) { frame = zlibPacket(frame) }
        assertEquals(1, BiliBiliProtocol.extractDanmaku(frame).size)
        assertTrue(BiliBiliProtocol.extractDanmaku(zlibPacket(frame)).isEmpty())
    }

    @Test(timeout = 5000)
    fun `decompression output is bounded`() {
        val frame = zlibPacket(ByteArray(BiliBiliProtocol.MAX_DECOMPRESSED_BYTES + 1))
        assertTrue(BiliBiliProtocol.extractDanmaku(frame).isEmpty())
    }

    @Test(timeout = 5000)
    fun `decompression budget is shared across outer packets`() {
        val padding = ByteArray(BiliBiliProtocol.MAX_DECOMPRESSED_BYTES / 2 + 1)
        val frame = zlibPacket(padding) + zlibPacket(packet(5, message()) + padding)
        assertTrue(BiliBiliProtocol.extractDanmaku(frame).isEmpty())
    }

    @Test
    fun `oversize frame is rejected before parsing`() {
        val frame = packet(5, message()) + ByteArray(BiliBiliProtocol.MAX_FRAME_BYTES)
        assertTrue(BiliBiliProtocol.extractDanmaku(frame).isEmpty())
    }

    @Test(timeout = 5000)
    fun `deep JSON cannot overflow stack or swallow next packet`() {
        val nested = "[".repeat(10000) + "0" + "]".repeat(10000)
        val body = """{"cmd":"DANMU_MSG","info":[[],"ignored"],"extra":$nested}""".toByteArray()
        val result = BiliBiliProtocol.extractDanmaku(packet(5, body) + packet(5, message("valid")))
        assertEquals(listOf("valid"), result.map { it.content })
    }

    @Test
    fun `JSON structural brackets inside escaped strings do not count as nesting`() {
        val content = "[".repeat(1000) + "\\\"quoted\\\"" + "]".repeat(1000)
        val body = buildJsonObject {
            put("cmd", "DANMU_MSG")
            putJsonArray("info") { add(JsonArray(emptyList())); add(content) }
        }.toString().toByteArray()
        assertEquals(content, BiliBiliProtocol.extractDanmaku(packet(5, body)).single().content)
    }

    @Test
    fun `JSON at depth limit is accepted`() {
        val nested = "[".repeat(BiliBiliProtocol.MAX_JSON_DEPTH - 1) + "0" + "]".repeat(BiliBiliProtocol.MAX_JSON_DEPTH - 1)
        val body = """{"cmd":"DANMU_MSG","info":[[],"valid"],"extra":$nested}""".toByteArray()
        assertEquals("valid", BiliBiliProtocol.extractDanmaku(packet(5, body)).single().content)
    }

    @Test
    fun `individual JSON byte budget accepts boundary and skips oversize body`() {
        val base = message("valid")
        val atLimit = base + ByteArray(BiliBiliProtocol.MAX_JSON_BODY_BYTES - base.size) { ' '.code.toByte() }
        assertEquals(1, BiliBiliProtocol.extractDanmaku(packet(5, atLimit)).size)
        val frame = packet(5, atLimit + byteArrayOf(' '.code.toByte())) + packet(5, base)
        assertEquals(listOf("valid"), BiliBiliProtocol.extractDanmaku(frame).map { it.content })
    }

    @Test(timeout = 5000)
    fun `deterministic arbitrary input does not crash parser`() {
        val random = Random(20260604)
        repeat(1000) {
            val bytes = ByteArray(random.nextInt(1024))
            random.nextBytes(bytes)
            BiliBiliProtocol.extractDanmaku(bytes)
        }
    }
}
