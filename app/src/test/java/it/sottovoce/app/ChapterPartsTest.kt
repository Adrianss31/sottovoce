package it.sottovoce.app

import it.sottovoce.app.data.AudioTrack
import it.sottovoce.app.data.Book
import it.sottovoce.app.data.Chapter
import it.sottovoce.app.data.chapterParts
import it.sottovoce.app.data.chapterTimeline
import it.sottovoce.app.data.label
import it.sottovoce.app.data.romanNumeral
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterPartsTest {
    private fun track(name: String, chapters: Int) = AudioTrack(uri = "file:///$name", name = name, durationMs = chapters * 60_000L,
        chapters = (0 until chapters).map { Chapter("Chapter ${it + 1}", it * 60_000L) })

    @Test fun singleFileBooksAreSplitIntoWholeGridRows() {
        val book = Book(title = "Saga", tracks = listOf(track("saga.m4b", 199)))
        val parts = book.chapterParts()
        assertEquals(listOf(0 to 39, 40 to 79, 80 to 119, 120 to 159, 160 to 198), parts.map { it.first to it.last })
        assertEquals("Capitoli 161–199", parts.last().label)
        assertEquals("161", parts.last().chip)
        assertEquals(8, Book(title = "Lungo", tracks = listOf(track("a.m4b", 1200))).chapterParts().size)
    }

    @Test fun multiFileBooksWithInnerChaptersGetOnePartPerFile() {
        val book = Book(title = "Saga", tracks = listOf(track("01 La pietra filosofale.m4b", 17), track("02 La camera dei segreti.m4b", 18)))
        val parts = book.chapterParts()
        assertEquals(2, parts.size)
        assertEquals("Parte I · 01 La pietra filosofale", parts[0].label)
        assertEquals(17 to 34, parts[1].first to parts[1].last)
        assertEquals("II", parts[1].chip)
    }

    @Test fun oneChapterPerFileIsTreatedAsASingleRun() {
        val book = Book(title = "Tracce", tracks = (1..30).map { AudioTrack(uri = "file:///$it", name = "Traccia $it.mp3", durationMs = 60_000) })
        assertEquals(listOf(0 to 29), book.chapterParts().map { it.first to it.last })
    }

    @Test fun genericTitlesAreNotNumberedTwice() {
        val timeline = Book(title = "B", tracks = listOf(AudioTrack(uri = "file:///b", name = "b", durationMs = 300_000,
            chapters = listOf(Chapter("Chapter 1", 0), Chapter("La colazione", 60_000), Chapter("Capitolo 3", 120_000))))).chapterTimeline()
        assertEquals(listOf("Chapter 1", "2. La colazione", "Capitolo 3"), timeline.map { it.label() })
        assertEquals("XIV", romanNumeral(14))
    }
}
