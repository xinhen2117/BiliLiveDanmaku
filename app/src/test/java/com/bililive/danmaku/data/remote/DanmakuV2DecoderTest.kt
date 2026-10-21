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

/** Every fixture is synthesized here. No account, captured user messages, or network is used. */
class DanmakuV2DecoderTest {
    private fun varint(value: ULong): ByteArray = ByteArrayOutputStream().apply {
        var rest = value
        while (rest >= 128uL) {
            write(((rest and 127uL) or 128uL).toInt())
            rest = rest shr 7
        }
        write(rest.toInt())
    }.toByteArray()

    private fun number(field: Int, value: ULong) = varint((field.toULong() shl 3)) + varint(value)
    private fun bytes(field: Int, value: ByteArray) =
        varint((field.toULong() shl 3) or 2uL) + varint(value.size.toULong()) + value
    private fun string(field: Int, value: String) = bytes(field, value.toByteArray())
    private fun encoded(value: ByteArray) = Base64.getEncoder().encodeToString(value)
    private fun decode(value: ByteArray) = DanmakuV2Decoder.decode(encoded(value))
    private fun proto(content: String, uid: ULong = 5_000_000_001uL): ByteArray =
        string(6, content) + bytes(20, number(1, uid) + string(2, "合成用户") +
            bytes(11, number(1, 12uL) + string(2, "合成勋章")))

    private fun envelope(v2: String?, legacy: String? = null, command: String = "DANMU_MSG"): ByteArray =
        buildJsonObject {
            put("cmd", command)
            if (v2 != null) put("dm_v2", v2)
            if (legacy != null) putJsonArray("info") {
                add(JsonArray(emptyList()))
                add(legacy)
                add(JsonArray(listOf(JsonPrimitive(42), JsonPrimitive("旧格式用户"))))
            }
        }.toString().toByteArray()

    private fun packet(body: ByteArray, version: Int = 0): ByteArray =
        ByteBuffer.allocate(16 + body.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(capacity()); putShort(16); putShort(version.toShort()); putInt(5); putInt(1); put(body)
        }.array()

    @Test fun `v2-only message preserves complete unicode and large user id`() {
        val content = "完整正文 👩🏽‍💻\n" + "长消息".repeat(800)
        val message = BiliBiliProtocol.extractDanmaku(packet(envelope(encoded(proto(content))))).single()
        assertEquals(content, message.content)
        assertEquals(5_000_000_001L, message.uid)
        assertEquals("合成用户", message.username)
        assertEquals("合成勋章", message.badgeName)
        assertEquals(12, message.badgeLevel)
    }

    @Test fun `valid v2 wins over legacy text and identity`() {
        val message = BiliBiliProtocol.extractDanmaku(packet(envelope(encoded(proto("新版正文")), "旧正文"))).single()
        assertEquals("新版正文", message.content)
        assertEquals(5_000_000_001L, message.uid)
    }

    @Test fun `bad or absent v2 falls back to valid legacy and does not lose next packet`() {
        for (v2 in listOf(null, "", "%%%", encoded(byteArrayOf(0x32, 0x7f)), encoded(number(1, 3uL)))) {
            val frame = packet(envelope(v2, "旧正文")) + packet(envelope(encoded(proto("下一条"))))
            assertEquals(listOf("旧正文", "下一条"), BiliBiliProtocol.extractDanmaku(frame).map { it.content })
        }
    }

    @Test fun `mirror and suffixed commands share the verified message envelope`() {
        for (cmd in listOf("DANMU_MSG:4:0:2", "DANMU_MSG_MIRROR", "DANMU_MSG_MIRROR:4:0:2")) {
            assertEquals("v2", BiliBiliProtocol.extractDanmaku(packet(envelope(encoded(proto("v2")), command = cmd))).single().content)
            assertEquals("legacy", BiliBiliProtocol.extractDanmaku(packet(envelope(null, "legacy", cmd))).single().content)
        }
        assertTrue(BiliBiliProtocol.extractDanmaku(packet(envelope(encoded(proto("no")), command = "DANMU_MSG_OTHER"))).isEmpty())
    }

    @Test fun `v2-only compressed batch preserves every message in order`() {
        val inner = (0 until 300).map { packet(envelope(encoded(proto("message-$it")))) }
            .fold(ByteArray(0)) { a, b -> a + b }
        val compressed = ByteArrayOutputStream().apply { DeflaterOutputStream(this).use { it.write(inner) } }.toByteArray()
        assertEquals((0 until 300).map { "message-$it" }, BiliBiliProtocol.extractDanmaku(packet(compressed, 2)).map { it.content })
    }

    @Test fun `zero maximum signed and unsigned overflow uid never wrap or recover wrong legacy identity`() {
        assertEquals(0L, decode(proto("text", 0uL))!!.uid)
        assertEquals(Long.MAX_VALUE, decode(proto("text", Long.MAX_VALUE.toULong()))!!.uid)
        for (uid in listOf(Long.MAX_VALUE.toULong() + 1uL, ULong.MAX_VALUE)) {
            val message = BiliBiliProtocol.extractDanmaku(packet(envelope(encoded(proto("text", uid)), "legacy"))).single()
            assertEquals("text", message.content)
            assertNull(message.uid)
        }
    }

    @Test fun `missing optional metadata preserves body and empty content remains valid`() {
        val message = BiliBiliProtocol.extractDanmaku(packet(envelope(encoded(string(6, ""))))).single()
        assertEquals("", message.content)
        assertNull(message.uid)
        assertEquals("unknown", message.username)
        assertNull(message.badgeName)
    }

    @Test fun `unknown wire types that protobuf allows are skipped without interpreting nested bytes`() {
        val opaque = ByteArray(300) { 0xff.toByte() }
        val unknown = number(99, ULong.MAX_VALUE) + bytes(100, opaque) +
            varint((101uL shl 3) or 1uL) + ByteArray(8) + varint((102uL shl 3) or 5uL) + ByteArray(4)
        assertEquals("text", decode(unknown + proto("text"))!!.content)
    }

    @Test fun `repeated message fields merge and repeated strings use final value`() {
        val p = string(6, "first") + bytes(20, number(1, 9uL)) + string(6, "last") + bytes(20, string(2, "name"))
        assertEquals("last", decode(p)!!.content)
        assertEquals(9L, decode(p)!!.uid)
        assertEquals("name", decode(p)!!.username)
    }

    @Test fun `malformed tags lengths varints utf8 and known wire types fail closed`() {
        val invalid = listOf(
            byteArrayOf(0), byteArrayOf(0x0b, 0x0c), // field zero / unsupported groups
            number(6, 1uL), // content requires length-delimited encoding
            byteArrayOf(0x32, 0x7f), // truncated text
            bytes(6, byteArrayOf(0xc3.toByte(), 0x28)), // invalid UTF-8
            string(6, "text") + bytes(20, byteArrayOf(8) + ByteArray(10) { 0xff.toByte() }),
            string(6, "text") + varint(UInt.MAX_VALUE.toULong() + 1uL),
            string(6, "text") + byteArrayOf(0x12) + varint(ULong.MAX_VALUE),
            string(6, "text") + byteArrayOf(0x19, 1), // truncated fixed64
            string(6, "text") + byteArrayOf(0x1d, 1) // truncated fixed32
        )
        invalid.forEach { assertNull(decode(it)) }
    }

    @Test fun `encoded and field budgets are enforced`() {
        assertNull(DanmakuV2Decoder.decode("A".repeat(DanmakuV2Decoder.MAX_ENCODED_CHARS + 1)))
        val atLimit = string(6, "ok") + number(99, 0uL).let { field ->
            ByteArrayOutputStream().apply { repeat(DanmakuV2Decoder.MAX_FIELDS - 1) { write(field) } }.toByteArray()
        }
        assertNotNull(decode(atLimit))
        assertNull(decode(atLimit + number(99, 0uL)))
        val bodyLength = DanmakuV2Decoder.MAX_PROTO_BYTES - 4 // tag + three-byte varint length
        assertNotNull(decode(string(6, "a".repeat(bodyLength))))
        assertNull(decode(string(6, "a".repeat(bodyLength + 1))))
    }

    @Test(timeout = 5000) fun `arbitrary synthesized bytes cannot crash decoder`() {
        val random = Random(841)
        repeat(2000) {
            val bytes = ByteArray(random.nextInt(1024))
            random.nextBytes(bytes)
            decode(bytes)
        }
    }
}
