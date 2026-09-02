package it.sottovoce.app

import it.sottovoce.app.data.*
import it.sottovoce.app.update.*
import it.sottovoce.app.playback.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.LocalDate
import java.util.Base64

class CoreTest {
    @Test fun chapterFilesAreSortedNumericallyWithoutIntegerOverflow() {
        assertEquals(listOf("01.mp3","2.mp3","10.mp3","999999999999999999999999.mp3"),
            listOf("10.mp3","999999999999999999999999.mp3","2.mp3","01.mp3").sortedWith(NaturalOrder))
    }
    @Test fun progressUsesPreviousFilesAndNeverExceedsOne() {
        val b=Book(title="Libro",tracks=listOf(AudioTrack(uri="",name="1",durationMs=60_000),AudioTrack(uri="",name="2",durationMs=120_000)),trackIndex=1,positionMs=30_000)
        assertEquals(.5f,b.progress,.001f)
        assertEquals(1f,b.copy(positionMs=999999).progress,0f)
        assertEquals(0f,Book(title="Durata ignota",tracks=listOf(AudioTrack(uri="",name="x"))).progress,0f)
    }
    @Test fun chapterTimelineReportsCurrentProgressAndCompletion() {
        val track=AudioTrack(uri="",name="Libro.m4b",durationMs=90_000,chapters=listOf(Chapter("Uno",0),Chapter("Due",30_000)))
        val book=Book(title="Libro",tracks=listOf(track),positionMs=45_000)
        val chapters=book.chapterTimeline()
        assertEquals(2,chapters.size)
        assertEquals("Due",book.currentChapter()?.title)
        assertEquals(.25f,book.currentChapter()!!.progress(book.positionMs),.001f)
        assertEquals(45_000,book.currentChapter()!!.remainingMs(book.positionMs))
        assertEquals(ChapterStatus.COMPLETED,book.chapterStatus(chapters[0]))
        assertEquals(ChapterStatus.CURRENT,book.chapterStatus(chapters[1]))
        assertEquals(ChapterPlaybackStart(1,15_000),book.chapterPlaybackStart())
    }
    @Test fun largeChapteredFilesUseOneSourceAndAbsoluteChapterSeeks() {
        val chapters=listOf(Chapter("Uno",0),Chapter("Due",30_000),Chapter("Tre",60_000))
        val large=AudioTrack(uri="file:///large.m4b",name="large.m4b",durationMs=90_000,
            size=3_000_000_000L,chapters=chapters)
        val small=AudioTrack(uri="file:///small.m4b",name="small.m4b",durationMs=90_000,
            size=30_000_000L,chapters=chapters)
        val largeBook=Book(title="Grande",tracks=listOf(large),positionMs=45_000)
        assertTrue(large.usesSingleSourcePlayback())
        assertEquals(1,largeBook.playbackSegments().size)
        assertFalse(largeBook.playbackSegments().single().clipped)
        assertEquals(ChapterPlaybackStart(0,45_000),largeBook.chapterPlaybackStart())

        val mixed=Book(title="Misto",tracks=listOf(large,small))
        assertEquals(4,mixed.playbackItemCount())
        assertEquals(ChapterPlaybackStart(2,15_000),mixed.chapterPlaybackStart(index=1,position=45_000))
    }
    @Test fun smartRewindGrowsWithThePauseAndRemainsBounded() {
        assertEquals(0L, smartRewindForPause(29_999))
        assertEquals(5_000L, smartRewindForPause(30_000))
        assertEquals(10_000L, smartRewindForPause(10 * 60_000L))
        assertEquals(20_000L, smartRewindForPause(2 * 60 * 60_000L))
        assertEquals(30_000L, smartRewindForPause(24 * 60 * 60_000L))
    }
    @Test fun nightTimerUsesOneSessionAcrossMidnight() {
        assertTrue(isNightListeningTime(23 * 60, 22 * 60))
        assertTrue(isNightListeningTime(2 * 60, 22 * 60))
        assertFalse(isNightListeningTime(12 * 60, 22 * 60))
        assertFalse(isNightListeningTime(60, 2 * 60))
        assertTrue(isNightListeningTime(3 * 60, 2 * 60))
        assertEquals(100L, nightSessionKey(100, 23 * 60, 22 * 60))
        assertEquals(100L, nightSessionKey(101, 2 * 60, 22 * 60))
    }
    @Test fun restoreNeverTrustsPathsFromBackupAndKeepsListeningData() {
        val book=Book(title="Libro",series="Trilogia",seriesPosition=2,tracks=listOf(AudioTrack(uri="file:///data/private",name="audio",owned=true)),positionMs=12_000,coverPath="/etc/secret")
        val mark=Bookmark(bookId=book.id,trackIndex=0,positionMs=1000,note="Ricorda")
        val safe=validateBackup(Backup(books=listOf(book),bookmarks=listOf(mark)))
        assertEquals("",safe.books.single().tracks.single().uri)
        assertFalse(safe.books.single().tracks.single().owned)
        assertTrue(safe.books.single().needsRelink)
        assertNull(safe.books.single().coverPath)
        assertEquals(12_000L,safe.books.single().positionMs)
        assertEquals("Trilogia",safe.books.single().series)
        assertEquals(2,safe.books.single().seriesPosition)
        assertEquals(mark,safe.bookmarks.single())
    }
    @Test fun invalidRestoreIsRejectedBeforeDatabaseMutation() {
        val book=Book(title="Libro",tracks=listOf(AudioTrack(uri="",name="x")))
        assertThrows(IllegalArgumentException::class.java) { validateBackup(Backup(version=99,books=listOf(book),bookmarks=emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { validateBackup(Backup(books=listOf(book,book),bookmarks=emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { validateBackup(Backup(books=listOf(book),bookmarks=listOf(Bookmark(bookId=book.id,trackIndex=7,positionMs=0)))) }
        assertThrows(IllegalArgumentException::class.java) { validateBackup(Backup(books=listOf(book.copy(id="../other")),bookmarks=emptyList())) }
    }
    @Test fun neroChaptersParseTimingAndRejectTruncation() {
        val name="Primo capitolo".toByteArray()
        val bytes=ByteBuffer.allocate(9+9+name.size).putInt(0x01000000).putInt(0).put(1).putLong(125_000_000).put(name.size.toByte()).put(name).array()
        assertEquals(listOf(Chapter("Primo capitolo",12_500)),Mp4Chapters.parsePayload(bytes))
        assertTrue(Mp4Chapters.parsePayload(bytes.copyOf(7)).isEmpty())
    }
    @Test fun quickTimeChapterTrackReadsUtf8AndUtf16Titles() {
        fun box(type:String,vararg payload:ByteArray):ByteArray {
            val size=8+payload.sumOf{it.size}
            return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).putInt(size).put(type.toByteArray(Charsets.ISO_8859_1)).apply{payload.forEach{put(it)}}.array()
        }
        fun ints(vararg values:Int)=ByteBuffer.allocate(values.size*4).order(ByteOrder.BIG_ENDIAN).apply{values.forEach(::putInt)}.array()
        fun text(bytes:ByteArray)=ByteBuffer.allocate(2+bytes.size).order(ByteOrder.BIG_ENDIAN).putShort(bytes.size.toShort()).put(bytes).array()
        fun tkhd(id:Int)=box("tkhd",ints(0,0,0,id,0))
        fun mdhd(scale:Int)=box("mdhd",ints(0,0,0,scale,10_000))
        fun hdlr(type:String)=box("hdlr",ints(0,0),type.toByteArray(Charsets.ISO_8859_1))
        val first=text("Introduzione".toByteArray())
        val second=text(byteArrayOf(0xfe.toByte(),0xff.toByte())+"Capitolo due".toByteArray(Charsets.UTF_16BE))
        val mdat=box("mdat",first,second)
        val referenced=box("trak",tkhd(1),box("tref",box("chap",ints(2))),box("mdia",mdhd(1000),hdlr("soun")))
        val stsd=box("stsd",ints(0,1,8),"text".toByteArray())
        val stts=box("stts",ints(0,1,2,5000))
        val stsc=box("stsc",ints(0,1,1,2,1))
        val stsz=box("stsz",ints(0,0,2,first.size,second.size))
        val stco=box("stco",ints(0,1,8))
        val chapters=box("trak",tkhd(2),box("mdia",mdhd(1000),hdlr("text"),box("minf",box("stbl",stsd,stts,stsc,stsz,stco))))
        val file=kotlin.io.path.createTempFile("sottovoce-chapters", ".m4b").toFile()
        try {
            file.writeBytes(mdat+box("moov",referenced,chapters))
            FileChannel.open(file.toPath(),StandardOpenOption.READ).use { channel ->
                assertEquals(listOf(Chapter("Introduzione",0),Chapter("Capitolo due",5000)),Mp4Chapters.read(channel))
            }
        } finally {file.delete()}
    }
    private val keys=KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
    private val publicKey=Base64.getEncoder().encodeToString(keys.public.encoded)
    private fun envelope(info:ReleaseInfo):String {
        val data=AppJson.encodeToString(info).toByteArray()
        val sig=Signature.getInstance("SHA256withRSA").apply{initSign(keys.private);update(data)}.sign()
        return AppJson.encodeToString(SignedRelease(Base64.getEncoder().encodeToString(data),Base64.getEncoder().encodeToString(sig)))
    }
    private val release=ReleaseInfo("0.2.0",2,26,"https://github.com/Adrianss31/sottovoce/releases/download/v0.2.0/sottovoce-0.2.0.apk",123456,"a".repeat(64),"Novità")
    @Test fun signedReleaseIsAcceptedAndTamperingRejected() {
        val valid=envelope(release)
        assertEquals(release,UpdateVerifier.verify(valid,publicKey))
        val original=AppJson.decodeFromString<SignedRelease>(valid)
        val tampered=original.copy(payload=Base64.getEncoder().encodeToString(AppJson.encodeToString(release.copy(versionCode=999)).toByteArray()))
        assertThrows(IllegalArgumentException::class.java){UpdateVerifier.verify(AppJson.encodeToString(tampered),publicKey)}
    }
    @Test fun signedManifestCannotPointAtAnotherHostOrRepository() {
        assertThrows(IllegalArgumentException::class.java){UpdateVerifier.verify(envelope(release.copy(apkUrl="https://example.org/app.apk")),publicKey)}
        assertThrows(IllegalArgumentException::class.java){UpdateVerifier.verify(envelope(release.copy(apkUrl="https://github.com/other/app/releases/download/v1/app.apk")),publicKey)}
        assertThrows(IllegalArgumentException::class.java){UpdateVerifier.verify(envelope(release.copy(size=999_999_999)),publicKey)}
    }
    @Test fun seriesAreGroupedUnderOneEntryKeepingOthersSeparate() {
        fun book(id:String,title:String,series:String="",position:Int?=null)=Book(id=id,title=title,series=series,seriesPosition=position,
            tracks=listOf(AudioTrack(id="t$id",uri="",name=title)))
        val first=book("a","Primo","Trilogia",1); val second=book("b","Secondo","Trilogia",2)
        val third=book("c","Terzo","Trilogia",3); val alone=book("d","Romanzo")
        val entries=groupForLibrary(listOf(alone,third,first,second))
        assertEquals(2,entries.size)
        assertTrue(entries[0] is LibraryEntry.Single)
        assertEquals(alone,(entries[0] as LibraryEntry.Single).book)
        val series=entries[1] as LibraryEntry.SeriesGroup
        assertEquals("Trilogia",series.name)
        assertEquals(listOf(first,second,third),series.books)
        // La serie raggruppata ha una sola occorrenza anche con più libri consecutivi.
        assertEquals(1,entries.count { it is LibraryEntry.SeriesGroup })
    }
    @Test fun seriesGroupingNormalizesCaseAndWhitespace() {
        fun book(id:String, series:String)=Book(id=id,title=id,series=series,
            tracks=listOf(AudioTrack(id="t$id",uri="",name=id)))
        val entries=groupForLibrary(listOf(book("a", "  Saga   del   Nord  "), book("b", "saga del nord")))
        assertEquals(1, entries.size)
        val group=entries.single() as LibraryEntry.SeriesGroup
        assertEquals("saga del nord", group.key)
        assertEquals(2, group.books.size)
    }
    @Test fun listeningStatsAggregateTotalsMonthsAndCompletion() {
        val today=LocalDate.of(2026,9,10)
        fun book(id:String,title:String,series:String="",position:Int?=null,completed:Boolean=false)=Book(id=id,title=title,
            series=series,seriesPosition=position,completed=completed,tracks=listOf(AudioTrack(id="t$id",uri="",name=title)))
        val books=listOf(book("a","Libro A","Saga",1,completed=true),book("b","Libro B","Saga",2,completed=true),book("c","Libro C"))
        val days=listOf(
            ListeningDay("a",LocalDate.of(2026,9,3).toEpochDay(),30*60_000L),
            ListeningDay("a",LocalDate.of(2026,8,20).toEpochDay(),60*60_000L),
            ListeningDay("b",LocalDate.of(2026,9,5).toEpochDay(),15*60_000L),
            ListeningDay("missing",LocalDate.of(2026,9,6).toEpochDay(),5*60_000L))
        val s=computeStats(books,days,today)
        assertEquals(110*60_000L,s.totalMs)
        assertEquals(50*60_000L,s.thisMonthMs)
        assertEquals(2,s.completedBooks); assertEquals(3,s.totalBooks)
        assertEquals(1,s.completedSeries); assertEquals(1,s.totalSeries)
        assertEquals(listOf("Libro A" to 90*60_000L,"Libro B" to 15*60_000L),s.topBooks)
        assertEquals(6,s.months.size)
        assertEquals("09/26",s.months[5].label); assertEquals(50*60_000L,s.months[5].durationMs)
        assertEquals("08/26",s.months[4].label); assertEquals(60*60_000L,s.months[4].durationMs)
        assertEquals(0L,s.months[3].durationMs)
        assertEquals(0L, s.todayMs)
        assertEquals(20*60_000L, s.weekMs)
        assertEquals(2, s.activeDaysLast7)
        assertEquals(0, s.currentStreak)
        assertEquals(7, s.days.size)
    }
}
