package it.sottovoce.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
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
    val series: String = "",
    val seriesPosition: Int? = null,
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
data class ChapterPlaybackStart(val itemIndex: Int, val positionMs: Long)
data class PlaybackSegment(
    val trackIndex: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val chapterOrdinal: Int,
    val chapterTotal: Int,
    val clipped: Boolean,
)

private const val SINGLE_SOURCE_PLAYBACK_THRESHOLD = 1024L * 1024L * 1024L

/**
 * A chaptered file normally becomes one clipped playlist item per chapter.
 * Reopening the same very large container for every clip duplicates its MP4
 * sample tables, so large files stay as one physical playback source instead.
 */
fun AudioTrack.usesSingleSourcePlayback(): Boolean =
    chapters.size > 1 && size >= SINGLE_SOURCE_PLAYBACK_THRESHOLD

fun AudioTrack.playbackItemCount(): Int =
    if (usesSingleSourcePlayback()) 1 else normalizedChapters().size

fun Book.playbackItemCount(): Int = tracks.sumOf { it.playbackItemCount() }

fun Book.playbackSegments(): List<PlaybackSegment> {
    val timeline = chapterTimeline()
    val byTrack = timeline.groupBy { it.trackIndex }
    return tracks.flatMapIndexed { trackIndex, track ->
        val chapters = byTrack[trackIndex].orEmpty()
        if (track.usesSingleSourcePlayback()) {
            listOf(PlaybackSegment(trackIndex, title, 0, track.durationMs,
                chapters.firstOrNull()?.ordinal ?: 1, timeline.size.coerceAtLeast(1), clipped = false))
        } else chapters.map { chapter ->
            PlaybackSegment(trackIndex, chapter.title, chapter.startMs, chapter.endMs,
                chapter.ordinal, chapter.total, clipped = true)
        }
    }
}

fun AudioTrack.normalizedChapters(): List<Chapter> {
    val points = chapters.filter { it.startMs >= 0 && (durationMs <= 0 || it.startMs < durationMs) }
        .sortedBy { it.startMs }.distinctBy { it.startMs }
    return if (points.firstOrNull()?.startMs == 0L) points else listOf(Chapter(if (points.isEmpty()) name else "Introduzione", 0)) + points
}

fun Book.positionAfterSkip(index: Int, position: Long, deltaMs: Long): Pair<Int, Long> {
    if (tracks.isEmpty()) return 0 to 0L
    var remaining = (tracks.take(index).sumOf { it.durationMs } + position + deltaMs).coerceIn(0, durationMs)
    tracks.forEachIndexed { i, track ->
        if (remaining < track.durationMs || i == tracks.lastIndex) return i to remaining
        remaining -= track.durationMs
    }
    return 0 to 0L
}

fun listeningTime(milliseconds: Long, speed: Float): Long = (milliseconds.coerceAtLeast(0) / speed.coerceIn(.5f, 3f)).toLong()

fun Book.chapterTimeline(): List<BookChapter> {
    val partial = tracks.flatMapIndexed { trackIndex, track ->
        val points = track.normalizedChapters()
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
    return when {
        completed || chapter.trackIndex < trackIndex -> ChapterStatus.COMPLETED
        chapter.trackIndex > trackIndex || chapter.startMs > positionMs -> ChapterStatus.UPCOMING
        chapter.endMs > chapter.startMs && positionMs >= chapter.endMs -> ChapterStatus.COMPLETED
        else -> ChapterStatus.CURRENT
    }
}

fun Book.chapterPlaybackStart(index: Int = trackIndex, position: Long = positionMs): ChapterPlaybackStart {
    if (tracks.isEmpty()) return ChapterPlaybackStart(0, position.coerceAtLeast(0))
    val safeTrackIndex = index.coerceIn(tracks.indices)
    val track = tracks[safeTrackIndex]
    val absolutePosition = position.coerceIn(0, track.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    val segments = playbackSegments()
    val itemIndex = segments.indexOfLast { it.trackIndex == safeTrackIndex && it.startMs <= absolutePosition }
        .takeIf { it >= 0 } ?: segments.indexOfFirst { it.trackIndex == safeTrackIndex }.coerceAtLeast(0)
    val segment = segments.getOrNull(itemIndex) ?: return ChapterPlaybackStart(0, absolutePosition)
    return ChapterPlaybackStart(itemIndex,
        (absolutePosition - segment.startMs).coerceIn(0, (segment.endMs - segment.startMs).takeIf { it > 0 } ?: Long.MAX_VALUE))
}
@Serializable data class Bookmark(
    val id: String = UUID.randomUUID().toString(), val bookId: String,
    val trackIndex: Int, val positionMs: Long, val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
@Serializable data class Preferences(
    val theme: String = "system",
    val skipBack: Int = 15,
    val skipForward: Int = 30,
    val smartRewind: Boolean = true,
    val nightTimerEnabled: Boolean = false,
    val nightTimerStartMinutes: Int = 22 * 60,
    val nightTimerDuration: Int = 30,
    val timerFade: Boolean = true,
    val timerShakeExtend: Boolean = false,
    val libraryViewMode: String = "grid",
)

@Serializable data class Backup(
    val format: String = "sottovoce", val version: Int = 2,
    val createdAt: Long = System.currentTimeMillis(),
    val books: List<Book>, val bookmarks: List<Bookmark>,
    val preferences: Preferences = Preferences(),
    val sessions: List<ListeningDay> = emptyList(),
)

fun validateBackup(backup: Backup): Backup {
    require(backup.format == "sottovoce" && backup.version in 1..2) { "Formato di backup non supportato." }
    require(backup.books.size <= 2000 && backup.bookmarks.size <= 50_000) { "Backup troppo grande." }
    require(backup.preferences.theme in setOf("system", "light", "dark"))
    require(backup.preferences.skipBack in 5..120 && backup.preferences.skipForward in 5..120)
    require(backup.preferences.nightTimerStartMinutes in 0 until 24 * 60)
    require(backup.preferences.nightTimerDuration in 5..180)
    require(backup.preferences.libraryViewMode in setOf("grid", "compact"))
    val ids = backup.books.map { it.id }
    require(ids.distinct().size == ids.size) { "Il backup contiene libri duplicati." }
    backup.books.forEach { b ->
        require(b.id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && b.title.length in 1..1000)
        require(b.author.length <= 1000 && b.narrator.length <= 1000 && b.series.length <= 1000)
        require(b.seriesPosition == null || b.seriesPosition in 1..999)
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
    require(backup.sessions.size <= 1_000_000) { "Troppi giorni di ascolto." }
    require(backup.sessions.map { it.bookId to it.day }.distinct().size == backup.sessions.size)
    backup.sessions.forEach { require(it.bookId in byId && it.durationMs in 0..86_400_000L && it.day in 0..365_000L) }
    // Backups carry listening data, never authority to read arbitrary local paths.
    return backup.copy(books = backup.books.map { b ->
        b.copy(coverPath = null, needsRelink = true, positionMs = b.positionMs.coerceAtMost(b.tracks[b.trackIndex].durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE), tracks = b.tracks.map { it.copy(uri = "", owned = false, chapters = it.normalizedChapters()) })
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

/** Una riga di ascolto registrata per libro e giorno (epoch day). Le statistiche restano locali. */
@Serializable data class ListeningDay(val bookId: String, val day: Long, val durationMs: Long)

data class MonthStat(val key: String, val label: String, val durationMs: Long)

data class DayStat(val day: Long, val label: String, val durationMs: Long)

data class ListeningStats(
    val totalMs: Long,
    val thisMonthMs: Long,
    val months: List<MonthStat>,
    val completedBooks: Int,
    val totalBooks: Int,
    val completedSeries: Int,
    val totalSeries: Int,
    val topBooks: List<Pair<String, Long>>,
    val todayMs: Long = 0,
    val weekMs: Long = 0,
    val activeDaysLast7: Int = 0,
    val currentStreak: Int = 0,
    val days: List<DayStat> = emptyList(),
)

fun computeStats(books: List<Book>, days: List<ListeningDay>, today: LocalDate): ListeningStats {
    val byMonth = days.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.day)) }
    val months = (0 until 6).map { offset ->
        val month = today.minusMonths((5 - offset).toLong()).withDayOfMonth(1)
        MonthStat("${month.year}-${month.monthValue}", "%02d/%d".format(month.monthValue, month.year % 100), 0)
    }.map { m ->
        val ym = YearMonth.of(m.key.substringBefore('-').toInt(), m.key.substringAfter('-').toInt())
        m.copy(durationMs = byMonth[ym]?.sumOf { it.durationMs } ?: 0)
    }
    val totalMs = days.sumOf { it.durationMs }
    val thisMonthMs = byMonth[YearMonth.from(today)]?.sumOf { it.durationMs } ?: 0
    val daily = (0 until 7).map { offset ->
        val date = today.minusDays((6 - offset).toLong())
        val day = date.toEpochDay()
        DayStat(day, "%02d/%02d".format(date.dayOfMonth, date.monthValue), days.filter { it.day == day }.sumOf { it.durationMs })
    }
    val weekMs = daily.sumOf { it.durationMs }
    val todayMs = daily.lastOrNull()?.durationMs ?: 0
    val activeDays = daily.count { it.durationMs > 0 }
    val daySet = days.filter { it.durationMs > 0 }.map { it.day }.toSet()
    var streak = 0
    while (daySet.contains(today.minusDays(streak.toLong()).toEpochDay())) streak++
    val series = books.filter { it.series.isNotBlank() }.groupBy { seriesKey(it.series) }
    val topBooks = days.groupBy { it.bookId }.mapValues { (_, list) -> list.sumOf { it.durationMs } }
        .entries.sortedByDescending { it.value }.take(5)
        .mapNotNull { (id, ms) -> books.firstOrNull { it.id == id }?.let { it.title to ms } }
    return ListeningStats(totalMs, thisMonthMs, months, books.count { it.completed }, books.size,
        series.count { (_, list) -> list.all { it.completed } }, series.size, topBooks,
        todayMs, weekMs, activeDays, streak, daily)
}

/** Elementi mostrati nella pagina principale: le serie diventano una sola card, i libri senza serie restano singoli. */
sealed interface LibraryEntry {
    data class Single(val book: Book) : LibraryEntry
    data class SeriesGroup(val name: String, val books: List<Book>, val totalCount: Int = books.size) : LibraryEntry {
        val key: String get() = seriesKey(name)
    }
}

/** Chiave stabile per raggruppare serie scritte con spazi o maiuscole diverse. */
fun seriesKey(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

fun groupForLibrary(books: List<Book>, allBooks: List<Book> = books): List<LibraryEntry> {
    val grouped = books.filter { it.series.isNotBlank() }.groupBy { seriesKey(it.series) }
    val allGrouped = allBooks.filter { it.series.isNotBlank() }.groupBy { seriesKey(it.series) }
    val used = mutableSetOf<String>()
    return buildList {
        books.forEach { book ->
            if (book.series.isBlank()) add(LibraryEntry.Single(book))
            else if (used.add(seriesKey(book.series))) {
                val key = seriesKey(book.series)
                val sorted = grouped.getValue(key).sortedWith(compareBy<Book> { it.seriesPosition ?: Int.MAX_VALUE }
                    .thenComparator { a, b -> NaturalOrder.compare(a.title, b.title) })
                add(LibraryEntry.SeriesGroup(book.series.trim().replace(Regex("\\s+"), " "), sorted,
                    allGrouped[key]?.size ?: sorted.size))
            }
        }
    }
}
