package com.bililive.danmaku.data.remote

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

class BiliBiliProtocolTest {

    private fun makeBiliPacket(op: Int, body: ByteArray, protoVer: Int = 0): ByteArray {
        val pkt = ByteArray(16 + body.size)
        val buf = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(pkt.size)
        buf.putShort(16)
        buf.putShort(protoVer.toShort())
        buf.putInt(op)
        buf.putInt(1)
        buf.put(body)
        return pkt
    }

    private fun zlibCompress(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(data.size * 2)
        val size = deflater.deflate(buf)
        deflater.end()
        return buf.copyOfRange(0, size)
    }

    @Test
    fun `buildAuthPacket creates valid packet`() {
        val pkt = BiliBiliProtocol.buildAuthPacket(12345L, "token123", "buvid456")
        val buf = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        assertEquals(pkt.size, buf.getInt())
        assertEquals(16, buf.getShort().toInt() and 0xFFFF)
        assertEquals(1, buf.getShort().toInt() and 0xFFFF)
        assertEquals(7, buf.getInt())
        val body = pkt.copyOfRange(16, pkt.size).toString(Charsets.UTF_8)
        assertTrue(body.contains("12345"))
        assertTrue(body.contains("token123"))
    }

    @Test
    fun `buildHeartbeatPacket creates valid packet`() {
        val pkt = BiliBiliProtocol.buildHeartbeatPacket()
        assertEquals(16, pkt.size)
        val buf = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        assertEquals(16, buf.getInt())      // packet length
        assertEquals(16, buf.getShort().toInt())  // header length
        assertEquals(2, buf.getShort().toInt())   // protocol version
        assertEquals(2, buf.getInt())             // operation
    }

    @Test
    fun `extractDanmaku parses nested zlib structure`() {
        // 内层包：DANMU_MSG JSON
        val json = """{"cmd":"DANMU_MSG","info":[[0,"","",0,0,0,0],"测试弹幕",[100,"测试用户",0,0,0,1],["粉丝","主播",5]]}"""
        val innerBody = json.toByteArray(Charsets.UTF_8)
        val innerPkt = makeBiliPacket(5, innerBody)

        // 外层包：zlib 压缩内层包
        val compressed = zlibCompress(innerPkt)
        val outerPkt = makeBiliPacket(5, compressed)

        val result = BiliBiliProtocol.extractDanmaku(outerPkt)

        assertEquals(1, result.size)
        assertEquals("测试弹幕", result[0].content)
        assertEquals("测试用户", result[0].username)
        assertEquals(100L, result[0].uid)
        assertEquals("粉丝", result[0].badgeName)
        assertEquals(5, result[0].badgeLevel)
    }

    @Test
    fun `extractDanmaku handles multiple packets in one frame`() {
        // 两个内层弹幕包
        val json1 = """{"cmd":"DANMU_MSG","info":[[0,"","",0,0,0,0],"弹幕1",[1,"用户1"]]}"""
        val json2 = """{"cmd":"DANMU_MSG","info":[[0,"","",0,0,0,0],"弹幕2",[2,"用户2"]]}"""
        val inner1 = makeBiliPacket(5, json1.toByteArray())
        val inner2 = makeBiliPacket(5, json2.toByteArray())
        val combined = inner1 + inner2

        val compressed = zlibCompress(combined)
        val outerPkt = makeBiliPacket(5, compressed)

        val result = BiliBiliProtocol.extractDanmaku(outerPkt)

        assertEquals(2, result.size)
        assertEquals("弹幕1", result[0].content)
        assertEquals("弹幕2", result[1].content)
    }

    @Test
    fun `extractDanmaku ignores non-DANMU commands`() {
        val json = """{"cmd":"INTERACT_WORD_V2","data":{}}"""
        val inner = makeBiliPacket(5, json.toByteArray())
        val compressed = zlibCompress(inner)
        val outer = makeBiliPacket(5, compressed)

        val result = BiliBiliProtocol.extractDanmaku(outer)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractDanmaku handles malformed data`() {
        val result = BiliBiliProtocol.extractDanmaku(byteArrayOf(0x01, 0x02))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractDanmaku handles multiple outer packets in frame`() {
        val json1 = """{"cmd":"DANMU_MSG","info":[[0,"","",0,0,0,0],"A",[1,"U1"]]}"""
        val json2 = """{"cmd":"DANMU_MSG","info":[[0,"","",0,0,0,0],"B",[2,"U2"]]}"""
        val inner1 = zlibCompress(makeBiliPacket(5, json1.toByteArray()))
        val inner2 = zlibCompress(makeBiliPacket(5, json2.toByteArray()))
        val outer1 = makeBiliPacket(5, inner1)
        val outer2 = makeBiliPacket(5, inner2)
        val frame = outer1 + outer2

        val result = BiliBiliProtocol.extractDanmaku(frame)
        assertEquals(2, result.size)
    }
}
