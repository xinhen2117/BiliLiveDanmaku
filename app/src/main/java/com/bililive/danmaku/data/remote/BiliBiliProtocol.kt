package com.bililive.danmaku.data.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

/** B站 WebSocket 二进制协议。输入不可信，按包头版本解码，并限制解压大小和嵌套深度。 */
object BiliBiliProtocol {
    private const val HEADER_SIZE = 16
    internal const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    internal const val MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024
    internal const val MAX_COMPRESSION_DEPTH = 4
    internal const val MAX_JSON_DEPTH = 64
    internal const val MAX_JSON_BODY_BYTES = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun buildAuthPacket(roomId: Long, token: String = "", buvid: String = "", uid: Long = 0): ByteArray {
        val body = buildJsonObject {
            put("uid", uid)
            put("roomid", roomId)
            put("protover", 3)
            put("platform", "web")
            put("type", 2)
            if (token.isNotEmpty()) put("key", token)
            if (buvid.isNotEmpty()) put("buvid", buvid)
        }.toString().toByteArray(Charsets.UTF_8)
        return buildPacket(1, 7, body)
    }

    fun buildHeartbeatPacket(): ByteArray = buildPacket(1, 2, ByteArray(0))

    /**
     * 遍历粘包，跳过非弹幕和未知版本；损坏的包头终止当前层，避免猜测下一包的位置。
     * 解压预算由整帧共享，防止多个压缩包分别耗尽内存。错误 JSON 不影响后续合法包。
     */
    fun extractDanmaku(data: ByteArray): List<DanmakuMessage> {
        if (data.size > MAX_FRAME_BYTES) return emptyList()
        val result = mutableListOf<DanmakuMessage>()
        extractPackets(data, result, 0, DecodeBudget())
        return result
    }

    /** Header-only scan for callback-side liveness; never decompresses or parses JSON here. */
    internal fun hasHeartbeatReply(data: ByteArray): Boolean {
        if (data.size > MAX_FRAME_BYTES) return false
        var pos = 0
        while (data.size - pos >= HEADER_SIZE) {
            val header = ByteBuffer.wrap(data, pos, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            val length = header.int
            val headerLength = header.short.toInt() and 0xFFFF
            val version = header.short.toInt() and 0xFFFF
            val operation = header.int
            if (length < HEADER_SIZE || length > data.size - pos || headerLength < HEADER_SIZE || headerLength > length) return false
            if (version in 0..1 && operation == 3 && length - headerLength >= 4) return true
            pos += length
        }
        return false
    }

    /** Control packets may occur anywhere in a frame, not only at offset zero. */
    internal fun extractControlMessages(data: ByteArray): List<BiliControlMessage> {
        if (data.size > MAX_FRAME_BYTES) return emptyList()
        val result = mutableListOf<BiliControlMessage>()
        var pos = 0
        while (data.size - pos >= HEADER_SIZE) {
            val header = ByteBuffer.wrap(data, pos, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            val length = header.int
            val headerLength = header.short.toInt() and 0xFFFF
            val version = header.short.toInt() and 0xFFFF
            val operation = header.int
            if (length < HEADER_SIZE || length > data.size - pos || headerLength < HEADER_SIZE || headerLength > length) break
            if (version in 0..1) {
                if (operation == 3 && length - headerLength >= 4) result.add(BiliControlMessage.HeartbeatReply)
                if (operation == 8) {
                    val body = data.copyOfRange(pos + headerLength, pos + length)
                    val text = if (body.size <= MAX_JSON_BODY_BYTES) body.toString(Charsets.UTF_8) else ""
                    val code = if (hasSafeJsonDepth(text)) try {
                        ((json.parseToJsonElement(text) as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
                    } catch (_: SerializationException) { null } else null
                    result.add(BiliControlMessage.AuthReply(code))
                }
            }
            pos += length
        }
        return result
    }

    private class DecodeBudget(var remaining: Int = MAX_DECOMPRESSED_BYTES)

    private fun extractPackets(
        data: ByteArray,
        result: MutableList<DanmakuMessage>,
        depth: Int,
        budget: DecodeBudget
    ) {
        var pos = 0
        while (data.size - pos >= HEADER_SIZE) {
            val header = ByteBuffer.wrap(data, pos, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            val packetLength = header.int
            val headerLength = header.short.toInt() and 0xFFFF
            val version = header.short.toInt() and 0xFFFF
            val op = header.int

            // 使用剩余长度比较，避免 pos + packetLength 整数溢出。
            if (packetLength < HEADER_SIZE || packetLength > data.size - pos ||
                headerLength < HEADER_SIZE || headerLength > packetLength
            ) return

            if (op == 5) {
                val body = data.copyOfRange(pos + headerLength, pos + packetLength)
                when (version) {
                    0, 1 -> extractFromJson(body, result)
                    2, 3 -> if (depth < MAX_COMPRESSION_DEPTH && budget.remaining > 0) {
                        val decoded = decompress(body, version, budget)
                        if (decoded != null) extractPackets(decoded, result, depth + 1, budget)
                    }
                }
            }
            pos += packetLength
        }
    }

    private fun extractFromJson(body: ByteArray, result: MutableList<DanmakuMessage>) {
        // 大量短 JSON 值会膨胀为大量树节点；单条消息另设限制，不影响批量压缩包。
        if (body.size > MAX_JSON_BODY_BYTES) return
        val text = body.toString(Charsets.UTF_8)
        // JSON 树解析器会递归处理数组/对象；帧大小限制本身不能防止深层输入耗尽栈。
        if (!hasSafeJsonDepth(text)) return
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            // 不记录原始弹幕/用户信息，也不依赖 Android Log，便于纯 JVM 回归测试。
            null
        } ?: return
        val cmd = (obj["cmd"] as? JsonPrimitive)?.contentOrNull ?: return
        val command = cmd.substringBefore(':')
        if (command != "DANMU_MSG" && command != "DANMU_MSG_MIRROR") return
        val info = obj["info"] as? JsonArray
        // Both formats occur in the same envelope. A malformed optional v2 payload must not
        // discard a usable legacy message; v2-only messages must not require the old array.
        val v2 = (obj["dm_v2"] as? JsonPrimitive)?.takeIf { it.isString }
            ?.content?.let(DanmakuV2Decoder::decode)
        val content = v2?.content
            ?: (info?.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val userInfo = info?.getOrNull(2) as? JsonArray
        val uid = if (v2?.hasUid == true) v2.uid else
            (userInfo?.getOrNull(0) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        val username = v2?.username ?: (userInfo?.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: "unknown"
        // B站勋章数组顺序为 [等级, 勋章名, 主播名, 房间号, ...]。
        val badge = info?.getOrNull(3) as? JsonArray
        val badgeLevel = v2?.badgeLevel ?: (badge?.getOrNull(0) as? JsonPrimitive)?.intOrNull
        val badgeName = v2?.badgeName ?: (badge?.getOrNull(1) as? JsonPrimitive)?.contentOrNull
        result.add(DanmakuMessage(uid, username, content, badgeName, badgeLevel, System.currentTimeMillis()))
    }

    private fun hasSafeJsonDepth(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > MAX_JSON_DEPTH) return false
                    '}', ']' -> if (--depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString
    }

    private fun decompress(data: ByteArray, version: Int, budget: DecodeBudget): ByteArray? = try {
        val input = ByteArrayInputStream(data)
        val decoder: InputStream = if (version == 2) InflaterInputStream(input) else BrotliInputStream(input)
        decoder.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                if (count > budget.remaining) {
                    budget.remaining = 0
                    return@use null
                }
                budget.remaining -= count
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (_: IOException) {
        // 截断、字典缺失或无效压缩流不能作为有效的部分消息返回。
        null
    }

    private fun buildPacket(protoVer: Int, op: Int, body: ByteArray): ByteArray {
        val packet = ByteArray(HEADER_SIZE + body.size)
        ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(packet.size)
            putShort(HEADER_SIZE.toShort())
            putShort(protoVer.toShort())
            putInt(op)
            putInt(1)
            put(body)
        }
        return packet
    }
}

data class DanmakuMessage(
    val uid: Long?, val username: String, val content: String,
    val badgeName: String? = null, val badgeLevel: Int? = null, val timestamp: Long
)

internal sealed interface BiliControlMessage {
    data class AuthReply(val code: Int?) : BiliControlMessage
    data object HeartbeatReply : BiliControlMessage
}
