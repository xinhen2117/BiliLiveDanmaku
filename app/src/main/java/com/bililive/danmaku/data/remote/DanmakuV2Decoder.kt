package com.bililive.danmaku.data.remote

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Minimal, bounded reader for the documented fields of DANMU_MSG.dm_v2, not a general protobuf
 * implementation. Field numbers/types follow Akegarasu/blivedm-go's MIT-licensed events.proto:
 * https://github.com/Akegarasu/blivedm-go/blob/957b543d2bfa8c2ac8a7af2fb42ae73eb92443b2/pb/events.proto
 * Copyright (c) 2022 秋葉杏. The upstream license is shipped in Blivedm-Go-MIT.txt assets.
 * This implementation is independently written; no upstream generated runtime is included.
 *
 * Only root -> user -> medal is traversed. Unknown fields are skipped without recursion or
 * allocation, groups are rejected, and all three readers share a field-count budget.
 * The enclosing protocol's JSON-body limit also applies, including base64 and metadata overhead.
 */
internal object DanmakuV2Decoder {
    internal const val MAX_PROTO_BYTES = 48 * 1024
    internal const val MAX_ENCODED_CHARS = (MAX_PROTO_BYTES + 2) / 3 * 4
    internal const val MAX_FIELDS = 4096

    internal data class Message(
        val content: String,
        val uid: Long?,
        val hasUid: Boolean,
        val username: String?,
        val badgeName: String?,
        val badgeLevel: Int?
    )

    private class Fields {
        var content: String? = null
        var uid: Long? = null
        var hasUid = false
        var username: String? = null
        var badgeName: String? = null
        var badgeLevel: Int? = null
    }
    private class Budget(var remaining: Int = MAX_FIELDS)
    private class InvalidProto : Exception()

    fun decode(encoded: String): Message? {
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_CHARS) return null
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            if (bytes.size > MAX_PROTO_BYTES) return null
            val reader = Reader(bytes, 0, bytes.size, Budget())
            val fields = Fields()
            while (reader.hasNext()) {
                val tag = reader.tag()
                when (tag.field) {
                    6 -> fields.content = reader.string(tag)
                    20 -> readUser(reader.message(tag), fields)
                    else -> reader.skip(tag)
                }
            }
            val content = fields.content ?: return null
            Message(content, fields.uid, fields.hasUid, fields.username, fields.badgeName, fields.badgeLevel)
        } catch (_: IllegalArgumentException) {
            null // Invalid base64, including non-ASCII characters.
        } catch (_: InvalidProto) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun readUser(reader: Reader, fields: Fields) {
        while (reader.hasNext()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> {
                    val uid = reader.number(tag)
                    fields.hasUid = true
                    // Preserve the text for an unsigned UID the application's Long cannot hold,
                    // but never wrap it negative or replace it with a different legacy identity.
                    fields.uid = if (uid <= Long.MAX_VALUE.toULong()) uid.toLong() else null
                }
                2 -> fields.username = reader.string(tag)
                11 -> readMedal(reader.message(tag), fields)
                else -> reader.skip(tag)
            }
        }
    }

    private fun readMedal(reader: Reader, fields: Fields) {
        while (reader.hasNext()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> fields.badgeLevel = reader.number(tag).let { value ->
                    if (value <= Int.MAX_VALUE.toULong()) value.toInt() else null
                }
                2 -> fields.badgeName = reader.string(tag)
                else -> reader.skip(tag)
            }
        }
    }

    private data class Tag(val field: Int, val wire: Int)

    private class Reader(
        private val data: ByteArray,
        private var position: Int,
        private val end: Int,
        private val budget: Budget
    ) {
        fun hasNext(): Boolean = position < end

        fun tag(): Tag {
            if (--budget.remaining < 0) throw InvalidProto()
            val value = varint()
            if (value > UInt.MAX_VALUE.toULong() || value shr 3 == 0uL) throw InvalidProto()
            return Tag((value shr 3).toInt(), (value and 7uL).toInt())
        }

        fun number(tag: Tag): ULong {
            if (tag.wire != 0) throw InvalidProto()
            return varint()
        }

        fun string(tag: Tag): String {
            val child = message(tag)
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, child.position, child.end - child.position)).toString()
        }

        fun message(tag: Tag): Reader {
            if (tag.wire != 2) throw InvalidProto()
            val length = varint()
            if (length > (end - position).toULong()) throw InvalidProto()
            val start = position
            position += length.toInt()
            return Reader(data, start, position, budget)
        }

        fun skip(tag: Tag) {
            when (tag.wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> message(tag)
                5 -> advance(4)
                else -> throw InvalidProto() // Deprecated groups are not in this schema.
            }
        }

        private fun advance(count: Int) {
            if (count > end - position) throw InvalidProto()
            position += count
        }

        private fun varint(): ULong {
            var value = 0uL
            for (index in 0 until 10) {
                if (position >= end) throw InvalidProto()
                val next = data[position++].toInt() and 0xff
                if (index == 9 && next > 1) throw InvalidProto()
                value = value or ((next and 0x7f).toULong() shl (index * 7))
                if (next and 0x80 == 0) return value
            }
            throw InvalidProto()
        }
    }
}
