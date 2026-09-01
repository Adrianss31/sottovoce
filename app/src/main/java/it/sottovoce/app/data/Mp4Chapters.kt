package it.sottovoce.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Bounded reader for Nero `chpl` and QuickTime referenced text chapter tracks. */
object Mp4Chapters {
    private const val MAX_BOXES = 20_000
    private const val MAX_CHAPTERS = 5_000
    private const val MAX_TABLE_BYTES = 1024 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "tref")

    private data class Box(val type: String, val payload: Long, val end: Long)
    private data class Stsc(val firstChunk: Int, val samplesPerChunk: Int)
    private data class Track(
        val id: Int, val timescale: Long, val handler: String, val description: String,
        val chapterReferences: Set<Int>, val durations: List<Long>, val sizes: List<Long>,
        val chunks: List<Long>, val sampleToChunk: List<Stsc>,
    )

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
                if (start >= 0) add(Chapter(decodeText(name).ifBlank { "Capitolo ${it + 1}" }, start))
            }
        }.sortedBy { it.startMs }.distinctBy { it.startMs }
    }.getOrDefault(emptyList())

    fun read(channel: FileChannel): List<Chapter> = runCatching {
        val fileSize = channel.size()
        require(fileSize >= 8)
        var boxesVisited = 0
        fun children(start: Long, end: Long): List<Box> {
            val result = mutableListOf<Box>()
            var offset = start
            while (offset + 8 <= end && ++boxesVisited <= MAX_BOXES) {
                val head = read(channel, offset, 8)
                var size = head.int.toLong() and 0xffffffffL
                val typeBytes = ByteArray(4); head.get(typeBytes)
                val type = typeBytes.toString(Charsets.ISO_8859_1)
                var header = 8L
                if (size == 1L) { size = read(channel, offset + 8, 8).long; header = 16 }
                else if (size == 0L) size = end - offset
                if (size < header || size > end - offset) break
                result += Box(type, offset + header, offset + size)
                offset += size
            }
            return result
        }
        fun descendants(box: Box): List<Box> {
            val result = mutableListOf<Box>()
            fun walk(parent: Box) {
                for (child in children(parent.payload, parent.end)) {
                    result += child
                    if (child.type in containers) walk(child)
                }
            }
            walk(box)
            return result
        }

        val moov = children(0, fileSize).firstOrNull { it.type == "moov" } ?: return@runCatching emptyList()
        val nero = descendants(moov).firstNotNullOfOrNull { box ->
            if (box.type != "chpl" || box.end - box.payload !in 1..MAX_TABLE_BYTES.toLong()) null
            else parsePayload(read(channel, box.payload, (box.end - box.payload).toInt()).array()).takeIf { it.isNotEmpty() }
        }
        if (!nero.isNullOrEmpty()) return@runCatching nero

        val tracks = children(moov.payload, moov.end).filter { it.type == "trak" }.mapNotNull { trak ->
            parseTrack(channel, trak, ::children, ::descendants)
        }
        val referenced = tracks.flatMap { it.chapterReferences }.toSet()
        val chapterTrack = tracks.firstOrNull { it.id in referenced && it.handler in setOf("text", "sbtl", "subt") }
            ?: tracks.firstOrNull { it.id in referenced && it.description in setOf("text", "tx3g") }
            ?: return@runCatching emptyList()
        readQuickTimeChapters(channel, chapterTrack)
    }.getOrDefault(emptyList())

    private fun parseTrack(channel: FileChannel, trak: Box, children: (Long, Long) -> List<Box>, descendants: (Box) -> List<Box>): Track? = runCatching {
        val all = descendants(trak)
        fun one(type: String) = all.firstOrNull { it.type == type }
        val tkhd = one("tkhd") ?: return null
        val tkhdBytes = read(channel, tkhd.payload, minOf(32L, tkhd.end - tkhd.payload).toInt())
        val idOffset = if ((tkhdBytes.get(0).toInt() and 255) == 1) 20 else 12
        require(tkhdBytes.limit() >= idOffset + 4)
        val id = tkhdBytes.getInt(idOffset); require(id > 0)

        val mdhd = one("mdhd") ?: return null
        val mdhdBytes = read(channel, mdhd.payload, minOf(36L, mdhd.end - mdhd.payload).toInt())
        val scaleOffset = if ((mdhdBytes.get(0).toInt() and 255) == 1) 20 else 12
        require(mdhdBytes.limit() >= scaleOffset + 4)
        val timescale = mdhdBytes.getInt(scaleOffset).toLong() and 0xffffffffL; require(timescale > 0)

        val handler = one("hdlr")?.let {
            val b = read(channel, it.payload, minOf(12L, it.end - it.payload).toInt())
            if (b.limit() >= 12) fourCc(b, 8) else ""
        }.orEmpty()
        val description = one("stsd")?.let {
            val b = read(channel, it.payload, minOf(16L, it.end - it.payload).toInt())
            if (b.limit() >= 16 && b.getInt(4) > 0) fourCc(b, 12) else ""
        }.orEmpty()
        val references = all.filter { it.type == "tref" }.flatMap { tref ->
            children(tref.payload, tref.end).filter { it.type == "chap" }.flatMap { chap ->
                val length = chap.end - chap.payload
                if (length < 4 || length > MAX_TABLE_BYTES || length % 4 != 0L) emptyList()
                else read(channel, chap.payload, length.toInt()).let { b -> buildList { while (b.remaining() >= 4) add(b.int) } }
            }
        }.filter { it > 0 }.toSet()
        Track(id, timescale, handler, description, references, parseStts(channel, one("stts")),
            parseStsz(channel, one("stsz")), parseOffsets(channel, one("stco"), one("co64")), parseStsc(channel, one("stsc")))
    }.getOrNull()

    private fun readQuickTimeChapters(channel: FileChannel, track: Track): List<Chapter> = runCatching {
        val count = track.sizes.size
        require(count in 1..MAX_CHAPTERS && track.durations.size == count && track.chunks.isNotEmpty() && track.sampleToChunk.isNotEmpty())
        val offsets = LongArray(count)
        var sample = 0
        for (chunkIndex in track.chunks.indices) {
            val mapping = track.sampleToChunk.lastOrNull { it.firstChunk <= chunkIndex + 1 } ?: return emptyList()
            var offset = track.chunks[chunkIndex]
            repeat(mapping.samplesPerChunk) {
                if (sample < count) { offsets[sample] = offset; offset = Math.addExact(offset, track.sizes[sample]); sample++ }
            }
        }
        require(sample == count)
        var time = 0L
        buildList {
            for (i in 0 until count) {
                val size = track.sizes[i]; require(size in 2..MAX_TEXT_BYTES.toLong())
                val data = read(channel, offsets[i], size.toInt())
                val length = data.short.toInt() and 0xffff; require(length <= data.remaining())
                val text = ByteArray(length); data.get(text)
                add(Chapter(decodeText(text).ifBlank { "Capitolo ${i + 1}" }, Math.multiplyExact(time, 1000L) / track.timescale))
                time = Math.addExact(time, track.durations[i])
            }
        }.sortedBy { it.startMs }.distinctBy { it.startMs }
    }.getOrDefault(emptyList())

    private fun parseStts(channel: FileChannel, box: Box?): List<Long> = runCatching {
        parseTable(channel, box, 8) { b, entries ->
            buildList {
                repeat(entries) {
                    val count = b.int.toLong() and 0xffffffffL; val duration = b.int.toLong() and 0xffffffffL
                    require(count <= MAX_CHAPTERS && size + count <= MAX_CHAPTERS)
                    repeat(count.toInt()) { add(duration) }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun parseStsz(channel: FileChannel, box: Box?): List<Long> = runCatching {
        box ?: return emptyList()
        val length = box.end - box.payload; require(length in 12..MAX_TABLE_BYTES.toLong())
        val b = read(channel, box.payload, length.toInt()); b.int
        val fixed = b.int.toLong() and 0xffffffffL; val count = b.int.toLong() and 0xffffffffL
        require(count in 1..MAX_CHAPTERS.toLong())
        if (fixed != 0L) List(count.toInt()) { fixed }
        else List(count.toInt()) { require(b.remaining() >= 4); b.int.toLong() and 0xffffffffL }
    }.getOrDefault(emptyList())

    private fun parseOffsets(channel: FileChannel, stco: Box?, co64: Box?): List<Long> {
        val box = co64 ?: stco ?: return emptyList(); val width = if (box.type == "co64") 8 else 4
        return runCatching { parseTable(channel, box, width) { b, entries ->
            List(entries) { if (width == 8) b.long.also { require(it >= 0) } else b.int.toLong() and 0xffffffffL }
        } }.getOrDefault(emptyList())
    }

    private fun parseStsc(channel: FileChannel, box: Box?): List<Stsc> = runCatching {
        parseTable(channel, box, 12) { b, entries ->
            List(entries) {
                val first = b.int.toLong() and 0xffffffffL; val samples = b.int.toLong() and 0xffffffffL; b.int
                require(first in 1..Int.MAX_VALUE.toLong() && samples in 1..MAX_CHAPTERS.toLong()); Stsc(first.toInt(), samples.toInt())
            }.also { require(it.zipWithNext().all { (a, next) -> a.firstChunk < next.firstChunk }) }
        }
    }.getOrDefault(emptyList())

    private fun <T> parseTable(channel: FileChannel, box: Box?, entryWidth: Int, block: (ByteBuffer, Int) -> T): T {
        requireNotNull(box)
        val length = box.end - box.payload; require(length in 8..MAX_TABLE_BYTES.toLong())
        val b = read(channel, box.payload, length.toInt()); b.int
        val entries = b.int.toLong() and 0xffffffffL
        require(entries <= MAX_CHAPTERS && entries * entryWidth <= b.remaining())
        return block(b, entries.toInt())
    }

    private fun read(channel: FileChannel, offset: Long, length: Int): ByteBuffer {
        require(offset >= 0 && length >= 0 && offset <= channel.size() - length)
        val b = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN); channel.position(offset)
        while (b.hasRemaining()) require(channel.read(b) >= 0)
        return b.flip() as ByteBuffer
    }

    private fun fourCc(b: ByteBuffer, offset: Int): String = ByteArray(4) { b.get(offset + it) }.toString(Charsets.ISO_8859_1)

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            else -> bytes.toString(Charsets.UTF_8)
        }
        return text.replace('\u0000', ' ').trim()
    }
}
