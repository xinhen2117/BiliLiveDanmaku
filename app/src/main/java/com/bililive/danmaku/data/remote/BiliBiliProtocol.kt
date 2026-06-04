package com.bililive.danmaku.data.remote

import android.util.Log
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * B站弹幕协议解析器
 *
 * 数据流：WebSocket帧 → BiliBili包(op=5) → zlib解压 → 嵌套BiliBili包 → JSON弹幕
 */
object BiliBiliProtocol {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun buildAuthPacket(roomId: Long, token: String = "", buvid: String = "", uid: Long = 0): ByteArray {
        val body = buildJsonObject {
            put("uid", uid)
            put("roomid", roomId)
            put("protover", 2)
            put("platform", "web")
            put("type", 2)
            if (token.isNotEmpty()) put("key", token)
            if (buvid.isNotEmpty()) put("buvid", buvid)
        }.toString().toByteArray(Charsets.UTF_8)
        return buildPacket(1, 7, body)
    }

    fun buildHeartbeatPacket(): ByteArray = buildPacket(2, 2, ByteArray(0))

    /**
     * 从原始 WebSocket 帧数据中提取所有弹幕
     *
     * 处理流程：
     * 1. 遍历帧内所有 BiliBili 外层包（粘包处理）
     * 2. 对每个 op=5 包的 body 做 zlib 解压
     * 3. 解压后是内层 BiliBili 包，遍历提取 JSON
     * 4. 从 DANMU_MSG JSON 中解析弹幕
     */
    fun extractDanmaku(data: ByteArray): List<DanmakuMessage> {
        val result = mutableListOf<DanmakuMessage>()
        var pos = 0

        while (pos + 16 <= data.size) {
            val packetLength = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).getInt()
            val headerLength = ByteBuffer.wrap(data, pos + 4, 2).order(ByteOrder.BIG_ENDIAN).getShort().toInt() and 0xFFFF
            val op = ByteBuffer.wrap(data, pos + 8, 4).order(ByteOrder.BIG_ENDIAN).getInt()

            if (packetLength < 16 || pos + packetLength > data.size) break

            if (op == 5) {
                val body = data.copyOfRange(pos + headerLength, pos + packetLength)
                // zlib 解压
                val decompressed = tryZlibDecompress(body)
                if (decompressed != null) {
                    // 解压后是内层包，遍历提取弹幕
                    extractFromInnerPackets(decompressed, result)
                } else {
                    // 没压缩，直接解析 JSON
                    extractFromJson(body, result)
                }
            }

            pos += packetLength
        }
        return result
    }

    /**
     * 从内层 BiliBili 包中提取弹幕 JSON
     */
    private fun extractFromInnerPackets(data: ByteArray, result: MutableList<DanmakuMessage>) {
        var pos = 0
        while (pos + 16 <= data.size) {
            val packetLength = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).getInt()
            val headerLength = ByteBuffer.wrap(data, pos + 4, 2).order(ByteOrder.BIG_ENDIAN).getShort().toInt() and 0xFFFF
            val op = ByteBuffer.wrap(data, pos + 8, 4).order(ByteOrder.BIG_ENDIAN).getInt()

            if (packetLength < 16 || pos + packetLength > data.size) break

            if (op == 5) {
                val body = data.copyOfRange(pos + headerLength, pos + packetLength)
                // 冎层包的 body 可能也是 zlib（极少情况）
                val decompressed = tryZlibDecompress(body)
                if (decompressed != null) {
                    extractFromInnerPackets(decompressed, result)
                } else {
                    extractFromJson(body, result)
                }
            }

            pos += packetLength
        }
    }

    /**
     * 从 JSON body 中提取 DANMU_MSG
     */
    private fun extractFromJson(body: ByteArray, result: MutableList<DanmakuMessage>) {
        try {
            val obj = json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
            val cmd = obj["cmd"]?.jsonPrimitive?.content ?: return
            if (cmd == "DANMU_MSG") {
                val info = obj["info"]?.jsonArray ?: return
                val content = info.getOrNull(1)?.jsonPrimitive?.content ?: return
                val userInfo = info.getOrNull(2)?.jsonArray
                val uid = userInfo?.getOrNull(0)?.jsonPrimitive?.longOrNull
                val username = userInfo?.getOrNull(1)?.jsonPrimitive?.content ?: "unknown"
                val badgeArr = info.getOrNull(3)?.jsonArray
                val badgeName = badgeArr?.getOrNull(0)?.jsonPrimitive?.contentOrNull
                val badgeLevel = badgeArr?.getOrNull(2)?.jsonPrimitive?.intOrNull
                result.add(DanmakuMessage(uid, username, content, badgeName, badgeLevel, System.currentTimeMillis()))
            }
        } catch (e: Exception) {
            Log.w("BiliProtocol", "JSON解析失败: ${e.message}, body=${String(body, Charsets.UTF_8).take(100)}")
        }
    }

    /**
     * zlib 解压
     */
    private fun tryZlibDecompress(data: ByteArray): ByteArray? {
        if (data.size < 2) return null
        if (data[0] != 0x78.toByte()) return null
        if (data[1] !in listOf(0x01.toByte(), 0x5E.toByte(), 0x9C.toByte(), 0xDA.toByte())) return null
        return try {
            val inflater = Inflater()
            inflater.setInput(data)
            val output = java.io.ByteArrayOutputStream(data.size * 2)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                output.write(buf, 0, n)
            }
            inflater.end()
            output.toByteArray()
        } catch (_: Exception) { null }
    }

    private fun buildPacket(protoVer: Int, op: Int, body: ByteArray): ByteArray {
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
}

data class DanmakuMessage(
    val uid: Long?, val username: String, val content: String,
    val badgeName: String? = null, val badgeLevel: Int? = null, val timestamp: Long
)
