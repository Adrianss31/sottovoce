package it.sottovoce.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable data class Chapter(val title: String, val startMs: Long)
@Serializable data class AudioTrack(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val name: String,
    val durationMs: Long = 0,
    val size: Long = 0,
    val owned: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val chapterParserVersion: Int = 0,
)
@Serializable data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String = "",
    val narrator: String = "",
    val tracks: List<AudioTrack>,
    val coverPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = 0,
    val trackIndex: Int = 0,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    val completed: Boolean = false,
    val needsRelink: Boolean = false,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs.coerceAtLeast(0) }
    val playedMs: Long get() = tracks.take(trackIndex).sumOf { it.durationMs } + positionMs
    val progress: Float get() = if (completed) 1f else if (durationMs > 0) (playedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

data class BookChapter(
    val ordinal: Int,
    val total: Int,
    val trackIndex: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    fun elapsedMs(positionMs: Long): Long = (positionMs - startMs).coerceIn(0, durationMs)
    fun remainingMs(positionMs: Long): Long = (durationMs - elapsedMs(positionMs)).coerceAtLeast(0)
    fun progress(positionMs: Long): Float = if (durationMs > 0) elapsedMs(positionMs).toFloat() / durationMs else 0f
}

enum class ChapterStatus { COMPLETED, CURRENT, UPCOMING }

fun Book.chapterTimeline(): List<BookChapter> {
    val partial = tracks.flatMapIndexed { trackIndex, track ->
        val points = track.chapters.sortedBy { it.startMs }.ifEmpty { listOf(Chapter(track.name, 0)) }
        points.mapIndexed { index, chapter ->
            val end = points.getOrNull(index + 1)?.startMs ?: track.durationMs
            BookChapter(0, 0, trackIndex, chapter.title.ifBlank { "Capitolo" },
                chapter.startMs.coerceAtLeast(0), end.coerceAtLeast(chapter.startMs))
        }
    }
    return partial.mapIndexed { index, chapter -> chapter.copy(ordinal = index + 1, total = partial.size) }
}

fun Book.currentChapter(index: Int = trackIndex, position: Long = positionMs): BookChapter? {
    val timeline = chapterTimeline()
    val trackChapters = timeline.filter { it.trackIndex == index }
    return trackChapters.lastOrNull { it.startMs <= position } ?: trackChapters.firstOrNull() ?: timeline.firstOrNull()
}

fun Book.chapterStatus(chapter: BookChapter): ChapterStatus {
    val current = currentChapter()
    return when {
        completed -> ChapterStatus.COMPLETED
        current == null || chapter.ordinal > current.ordinal -> ChapterStatus.UPCOMING
        chapter.ordinal == current.ordinal -> ChapterStatus.CURRENT
        else -> ChapterStatus.COMPLETED
    }
}
@Serializable data class Bookmark(
    val id: String = UUID.randomUUID().toString(), val bookId: String,
    val trackIndex: Int, val positionMs: Long, val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
@Serializable data class Preferences(val theme: String = "system", val skipBack: Int = 15, val skipForward: Int = 30)

@Serializable data class Backup(
    val format: String = "sottovoce", val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val books: List<Book>, val bookmarks: List<Bookmark>,
    val preferences: Preferences = Preferences(),
)

fun validateBackup(backup: Backup): Backup {
    require(backup.format == "sottovoce" && backup.version == 1) { "Formato di backup non supportato." }
    require(backup.books.size <= 2000 && backup.bookmarks.size <= 50_000) { "Backup troppo grande." }
    require(backup.preferences.theme in setOf("system", "light", "dark"))
    require(backup.preferences.skipBack in 5..120 && backup.preferences.skipForward in 5..120)
    val ids = backup.books.map { it.id }
    require(ids.distinct().size == ids.size) { "Il backup contiene libri duplicati." }
    backup.books.forEach { b ->
        require(b.id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && b.title.length in 1..1000)
        require(b.author.length <= 1000 && b.narrator.length <= 1000)
        require(b.tracks.size in 1..2000 && b.trackIndex in b.tracks.indices && b.positionMs >= 0)
        require(b.speed.isFinite() && b.speed in 0.5f..3f)
        require(b.tracks.map { it.id }.distinct().size == b.tracks.size)
        b.tracks.forEach { t ->
            require(t.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
            require(t.durationMs >= 0 && t.size >= 0 && t.name.length <= 1000 && t.chapters.size <= 5000)
            require(t.chapterParserVersion in 0..2)
            require(t.chapters.all { it.startMs >= 0 && it.title.length <= 1000 })
        }
    }
    require(backup.bookmarks.map { it.id }.distinct().size == backup.bookmarks.size)
    val byId = backup.books.associateBy { it.id }
    backup.bookmarks.forEach { m ->
        require(m.id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && m.note.length <= 10_000)
        val book = requireNotNull(byId[m.bookId]) { "Segnalibro senza libro." }
        require(m.trackIndex in book.tracks.indices && m.positionMs >= 0)
    }
    // Backups carry listening data, never authority to read arbitrary local paths.
    return backup.copy(books = backup.books.map { b ->
        b.copy(coverPath = null, needsRelink = true, tracks = b.tracks.map { it.copy(uri = "", owned = false) })
    })
}

object NaturalOrder : Comparator<String> {
    private val tokens = Regex("[0-9]+|[^0-9]+")
    override fun compare(a: String, b: String): Int {
        val aa = tokens.findAll(a.lowercase()).map { it.value }.toList()
        val bb = tokens.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(aa.size, bb.size)) {
            val x = aa[i]; val y = bb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
                nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return aa.size.compareTo(bb.size)
    }
}

fun timeLabel(milliseconds: Long): String {
    val total = milliseconds.coerceAtLeast(0) / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}
