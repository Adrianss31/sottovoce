package it.sottovoce.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Bounded Nero chpl reader. Unsupported QuickTime chapter tracks fall back to one track. */
object Mp4Chapters {
    fun parsePayload(bytes: ByteArray): List<Chapter> = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = b.get().toInt() and 255
        b.position(4)
        if (version != 0) b.int
        val count = b.get().toInt() and 255
        buildList {
            repeat(count) {
                val start = b.long / 10_000
                val length = b.get().toInt() and 255
                val name = ByteArray(length); b.get(name)
                if (start >= 0) add(Chapter(name.toString(Charsets.UTF_8).ifBlank { "Capitolo ${it + 1}" }, start))
            }
        }.sortedBy { it.startMs }.distinctBy { it.startMs }
    }.getOrDefault(emptyList())

    fun read(channel: FileChannel): List<Chapter> = runCatching {
        var visited = 0
        fun scan(start: Long, end: Long, depth: Int): List<Chapter> {
            if (depth > 6) return emptyList()
            var offset = start
            while (offset + 8 <= end && ++visited <= 20_000) {
                val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                channel.position(offset)
                header.limit(8)
                while (header.hasRemaining()) if (channel.read(header) < 0) return emptyList()
                header.flip()
                var size = header.int.toLong() and 0xffffffffL
                val typeBytes = ByteArray(4); header.get(typeBytes)
                val type = typeBytes.toString(Charsets.US_ASCII)
                var headerSize = 8L
                if (size == 1L) {
                    header.clear(); header.limit(8)
                    while (header.hasRemaining()) if (channel.read(header) < 0) return emptyList()
                    header.flip(); size = header.long; headerSize = 16
                } else if (size == 0L) size = end - offset
                if (size < headerSize || size > end - offset) return emptyList()
                if (type == "chpl" && size - headerSize <= 1024 * 1024) {
                    val payload = ByteBuffer.allocate((size - headerSize).toInt())
                    while (payload.hasRemaining()) if (channel.read(payload) < 0) return emptyList()
                    return parsePayload(payload.array())
                }
                if (type in setOf("moov", "udta")) {
                    val found = scan(offset + headerSize, offset + size, depth + 1)
                    if (found.isNotEmpty()) return found
                }
                offset += size
            }
            return emptyList()
        }
        scan(0, channel.size(), 0)
    }.getOrDefault(emptyList())
}
