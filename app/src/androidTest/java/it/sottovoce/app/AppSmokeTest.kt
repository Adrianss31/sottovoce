package it.sottovoce.app

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.session.MediaController as PlatformMediaController
import android.media.session.MediaSession as PlatformMediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import it.sottovoce.app.data.*
import it.sottovoce.app.playback.PlaybackSignals
import it.sottovoce.app.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule(order=0) val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order=1) val compose = createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=context.applicationContext as SottovoceApp
    private val vm get()=ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
    @Before fun reset() {
        compose.waitUntil(10_000){vm.controller!=null}
        var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>?=null
        compose.runOnIdle{future=vm.controller?.sendCustomCommand(SessionCommand("it.sottovoce.STOP_AND_SAVE",Bundle.EMPTY),Bundle.EMPTY)}
        future?.get(10,TimeUnit.SECONDS)
        runBlocking {app.library.load();app.library.books.value.toList().forEach{app.library.removeBook(it.id)}}
        compose.runOnIdle{
            vm.screen="library";vm.message=null;vm.changeTheme("light");vm.setSkips(15,30)
            vm.changeSmartRewind(true);vm.changeNightTimerEnabled(false);vm.changeTimerFade(true);vm.changeTimerShakeExtend(false);vm.changeLibraryViewMode("grid")
        }
    }
    private fun wav():ByteArray {
        val samples=16_000*30
        val buffer=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
        repeat(samples){i->buffer.putShort((kotlin.math.sin(i*2.0*Math.PI*220/16000)*1200).toInt().toShort())}
        return buffer.array()
    }
    private fun seed():Book {
        val book=Book(title="Audiolibro di prova",author="Test automatico",tracks=listOf(AudioTrack(uri="", name="Audio da ricollegare", durationMs=30_000)), needsRelink=true)
        val dir=app.library.ownedDirectory(book.id).apply{mkdirs()}
        val file=File(dir,"capitolo.wav").apply{writeBytes(wav())}
        val cover = File(dir, "cover.jpg")
        val artwork = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(artwork)
        canvas.drawColor(android.graphics.Color.rgb(42, 84, 92))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(240, 178, 101) }
        canvas.drawCircle(170f, 50f, 55f, paint)
        paint.color = android.graphics.Color.WHITE; paint.textSize = 25f
        canvas.drawText("SOTTOVOCE", 15f, 125f, paint)
        cover.outputStream().use { artwork.compress(Bitmap.CompressFormat.JPEG, 95, it) }; artwork.recycle()
        val complete=book.copy(needsRelink=false, coverPath=cover.absolutePath, tracks=listOf(AudioTrack(uri=Uri.fromFile(file).toString(),name="file-audio.wav",durationMs=30_000,size=file.length(),owned=true,
            chapters=listOf(Chapter("Capitolo introduttivo",0),Chapter("Seconda parte",15_000)))))
        runBlocking{app.library.add(listOf(complete))}
        compose.waitUntil(10_000){compose.onAllNodesWithText(complete.title).fetchSemanticsNodes().isNotEmpty()}
        return complete
    }
    private fun screenshot(name:String) {
        val image=compose.onNodeWithTag("app_scaffold").captureToImage().asAndroidBitmap()
        val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir=(output?.let{File(it)}?:requireNotNull(context.getExternalFilesDir("screenshots"))).apply{mkdirs()}
        File(dir,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun waitOnMain(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var matched = false
        while (!matched && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { matched = condition() }
            if (!matched) SystemClock.sleep(100)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", matched)
    }
    private fun activeMediaNotification(): StatusBarNotification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { status ->
            status.packageName == context.packageName && platformToken(status) != null
        }
    @Suppress("DEPRECATION")
    private fun platformToken(status: StatusBarNotification): PlatformMediaSession.Token? =
        status.notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? PlatformMediaSession.Token
    private fun stopAndSave() {
        var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>? = null
        compose.runOnIdle {
            future = vm.controller?.sendCustomCommand(
                SessionCommand("it.sottovoce.STOP_AND_SAVE", Bundle.EMPTY),
                Bundle.EMPTY,
            )
        }
        future?.get(10, TimeUnit.SECONDS)
    }
    @Suppress("DEPRECATION")
    @Test fun playingServicePublishesForegroundMediaNotificationWithSystemControls() {
        val book = seed()
        compose.runOnIdle { vm.playBook(book) }
        compose.waitUntil(10_000) { vm.now.playing }

        waitOnMain { activeMediaNotification() != null }
        val status = requireNotNull(activeMediaNotification())
        val token = requireNotNull(platformToken(status))
        val controller = PlatformMediaController(context, token)
        val actions = requireNotNull(controller.playbackState).actions

        assertTrue(status.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue(status.notification.actions.orEmpty().isNotEmpty())
        assertTrue(actions and PlaybackState.ACTION_PAUSE != 0L)
        assertTrue(actions and PlaybackState.ACTION_SEEK_TO != 0L)
        assertTrue(actions and PlaybackState.ACTION_REWIND != 0L)
        val running = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == PlaybackService::class.java.name }
        assertTrue("PlaybackService is not running in the foreground", running?.foreground == true)
    }
    @Test fun playbackContinuesAndSystemControlsWorkWhileActivityIsStopped() {
        val book = seed()
        compose.runOnIdle { vm.playBook(book) }
        compose.waitUntil(10_000) { vm.now.playing }
        waitOnMain { activeMediaNotification() != null }
        val controller = PlatformMediaController(context, requireNotNull(platformToken(requireNotNull(activeMediaNotification()))))
        val media3Controller = requireNotNull(vm.controller)
        val activity = compose.activity

        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            waitOnMain { activity.lifecycle.currentState == Lifecycle.State.CREATED }
            waitOnMain {
                controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    media3Controller.isPlaying &&
                    media3Controller.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            }
            var position = 0L
            InstrumentationRegistry.getInstrumentation().runOnMainSync { position = media3Controller.currentPosition }
            waitOnMain { media3Controller.currentPosition >= position + 500L }

            controller.transportControls.pause()
            waitOnMain { controller.playbackState?.state == PlaybackState.STATE_PAUSED && !media3Controller.isPlaying }
            controller.transportControls.play()
            waitOnMain {
                controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    media3Controller.isPlaying &&
                    media3Controller.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            }
            var resumedAt = 0L
            InstrumentationRegistry.getInstrumentation().runOnMainSync { resumedAt = media3Controller.currentPosition }
            waitOnMain { media3Controller.currentPosition >= resumedAt + 500L }
        } finally {
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
                waitOnMain { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            } finally {
                stopAndSave()
            }
        }
    }
    @Test fun listeningPanelPinsOnlyDuringPlaybackAndExpandsAtTop() {
        val book = seed()
        val others = (1..12).map { Book(title = "Scaffale $it", tracks = listOf(AudioTrack(uri="", name="Audio da ricollegare", durationMs=30_000)), needsRelink = true, createdAt = it.toLong()) }
        runBlocking { app.library.add(others) }
        compose.runOnIdle { vm.playBook(book) }
        compose.waitUntil(10_000) { vm.now.playing }
        compose.onNodeWithTag("pinned_listening").assertIsDisplayed()
        compose.onNodeWithTag("library").performScrollToIndex(8)
        compose.onNodeWithTag("listening_compact").assertIsDisplayed()
        screenshot("05-listening-pinned")
        compose.onNodeWithTag("library").performScrollToIndex(0)
        compose.onNodeWithTag("listening_expanded").assertIsDisplayed()
        compose.runOnIdle { vm.togglePlay() }
        compose.waitUntil(5_000) { !vm.now.playing }
        compose.onNodeWithTag("pinned_listening").assertDoesNotExist()
        compose.onNodeWithTag("library").performScrollToIndex(8)
        compose.onNodeWithTag("listening_expanded").assertIsNotDisplayed()
    }
    @Test fun homeStartsWithShelfInsteadOfRedundantLibraryHeading() {
        seed()
        compose.onNodeWithText("Scaffale").assertIsDisplayed()
        compose.onNodeWithText("La tua libreria").assertDoesNotExist()
        compose.onNodeWithText("1 audiolibro · 0 in ascolto").assertDoesNotExist()
    }
    @Test fun shelfBooksDoNotAnimateWhenPlaybackPinsOrUnpinsThePanel() {
        val book = seed()
        compose.runOnIdle { vm.playBook(book) }
        compose.waitUntil(10_000) { vm.now.playing }
        compose.runOnIdle { vm.togglePlay() }
        compose.waitUntil(5_000) { !vm.now.playing }
        compose.waitForIdle()

        fun bookOffset(): Float {
            val grid = compose.onNodeWithTag("library").fetchSemanticsNode().boundsInRoot
            val cover = compose.onNodeWithTag("book_${book.id}").fetchSemanticsNode().boundsInRoot
            return cover.top - grid.top
        }
        fun assertNoPlacementAnimation(toggle: () -> Unit) {
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle(toggle)
                compose.mainClock.advanceTimeByFrame()
                val firstFrame = bookOffset()
                compose.mainClock.advanceTimeBy(120)
                val middleFrame = bookOffset()
                compose.mainClock.advanceTimeBy(800)
                val settled = bookOffset()
                assertEquals("Shelf book moved after the playback layout changed", settled, firstFrame, 1f)
                assertEquals("Shelf book animated during playback change", settled, middleFrame, 1f)
            } finally { compose.mainClock.autoAdvance = true }
            compose.waitForIdle()
        }

        assertNoPlacementAnimation { vm.togglePlay() }
        compose.waitUntil(5_000) { vm.now.playing }
        assertNoPlacementAnimation { vm.togglePlay() }
        compose.waitUntil(5_000) { !vm.now.playing }
    }
    @Test fun playbackDoesNotMoveTheHomeViewport() {
        val book = seed()
        compose.runOnIdle { vm.playBook(book) }
        compose.waitUntil(10_000) { vm.now.playing }
        compose.runOnIdle { vm.togglePlay() }
        compose.waitUntil(5_000) { !vm.now.playing }
        compose.waitForIdle()
        val gridTop = compose.onNodeWithTag("library").fetchSemanticsNode().boundsInRoot.top
        val shelfTop = compose.onNodeWithText("Scaffale").fetchSemanticsNode().boundsInRoot.top

        compose.runOnIdle { vm.togglePlay() }
        compose.waitUntil(5_000) { vm.now.playing }
        compose.waitForIdle()
        assertEquals(gridTop, compose.onNodeWithTag("library").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(shelfTop, compose.onNodeWithText("Scaffale").fetchSemanticsNode().boundsInRoot.top, 2f)

        compose.runOnIdle { vm.togglePlay() }
        compose.waitUntil(5_000) { !vm.now.playing }
        compose.waitForIdle()
        assertEquals(gridTop, compose.onNodeWithTag("library").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(shelfTop, compose.onNodeWithText("Scaffale").fetchSemanticsNode().boundsInRoot.top, 2f)
    }
    @Test fun listeningPanelSizeFollowsSmallScrollsInBothDirections() {
        val book = seed()
        val others = (1..12).map { Book(title = "Scaffale $it", tracks = listOf(AudioTrack(uri="", name="Audio da ricollegare", durationMs=30_000)), needsRelink = true, createdAt = it.toLong()) }
        runBlocking { app.library.add(others) }
        compose.runOnIdle { vm.playBook(book) }
        compose.waitUntil(10_000) { vm.now.playing }
        fun height() = compose.onNodeWithTag("listening_panel").fetchSemanticsNode().boundsInRoot.height
        fun drag(up: Boolean) {
            compose.onNodeWithTag("library").performTouchInput {
                val from = bottom * .7f
                if (up) swipeUp(startY = from, endY = from - 50f, durationMillis = 500)
                else swipeDown(startY = from, endY = from + 50f, durationMillis = 500)
            }
            compose.waitForIdle()
        }
        val expanded = height()
        drag(true)
        val first = height()
        drag(true)
        val second = height()
        drag(false)
        val returning = height()
        assertTrue("The panel did not begin shrinking with the first drag", first < expanded - 2f)
        assertTrue("The panel did not keep shrinking with the second drag", second < first - 2f)
        assertTrue("The panel did not grow with the reverse drag", returning > second + 2f)
    }
    @Test fun longBookTitleHeaderTracksScrollWithoutReverseJump() {
        val book = seed()
        runBlocking { app.library.update(book.id) { it.copy(title = "Un audiolibro con un titolo abbastanza lungo da occupare più righe nell’intestazione") } }
        compose.onNodeWithTag("library").performScrollToNode(hasTestTag("book_${book.id}"))
        compose.onNodeWithTag("book_${book.id}").performClick()
        compose.onNodeWithTag("book_detail").assertIsDisplayed()
        fun width() = compose.onNodeWithTag("detail_cover").fetchSemanticsNode().boundsInRoot.width
        fun drag(up: Boolean) {
            compose.onNodeWithTag("book_detail").performTouchInput {
                val from = bottom * .7f
                if (up) swipeUp(startY = from, endY = from - 50f, durationMillis = 500)
                else swipeDown(startY = from, endY = from + 50f, durationMillis = 500)
            }
            compose.waitForIdle()
        }
        val expanded = width()
        drag(true)
        val first = width()
        drag(true)
        val second = width()
        drag(false)
        val returning = width()
        assertTrue("Cover should respond to a small upward drag", first < expanded - 2f)
        assertTrue("Cover should keep shrinking before the compact state", second < first - 2f)
        assertTrue("Cover should grow during a small reverse drag", returning > second + 2f)
    }
    @Test fun chapterPairsAreSideBySideAndOddLastChapterIsReachable() {
        val original = seed()
        val book = original.copy(tracks = listOf(original.tracks.single().copy(
            chapters = (1..21).map { Chapter("Capitolo $it", (it - 1) * 1_000L) })))
        runBlocking { app.library.update(book.id) { book } }
        compose.onNodeWithTag("library").performScrollToNode(hasTestTag("book_${book.id}"))
        compose.onNodeWithTag("book_${book.id}").performClick()
        compose.onNodeWithTag("book_detail").performScrollToNode(hasTestTag("chapter_1"))
        val first = compose.onNodeWithTag("chapter_1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("chapter_2").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left >= first.right)
        compose.onNodeWithTag("book_detail").performScrollToNode(hasTestTag("chapter_9"))
        screenshot("06-chapter-columns")
        compose.onNodeWithTag("book_detail").performScrollToNode(hasTestTag("chapter_21"))
        compose.onNodeWithTag("chapter_21").assertIsDisplayed()
        compose.onNodeWithText("Capitolo 21").performClick()
        compose.waitUntil(10_000) { vm.now.playing && vm.now.position >= 20_000 }
    }
    @Test fun resetStopsActiveBookAndPersistsUnstartedWithoutLosingBookmarks() {
        val book = seed()
        runBlocking { app.library.bookmark(Bookmark(bookId = book.id, trackIndex = 0, positionMs = 4_000, note = "Conserva")) }
        compose.onNodeWithTag("library").performScrollToNode(hasTestTag("book_${book.id}"))
        compose.onNodeWithTag("book_${book.id}").performClick()
        compose.runOnIdle { vm.playBook(book, 0, 16_000) }
        compose.waitUntil(10_000) { vm.now.playing && vm.now.position >= 16_000 }
        compose.onNodeWithTag("reset_book").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.busy == null && vm.now.bookId == null && app.library.books.value.single().lastPlayedAt == 0L }
        runBlocking { app.library.load() }
        val reset = app.library.books.value.single()
        assertEquals(0L, reset.positionMs)
        assertEquals(0, reset.trackIndex)
        assertFalse(reset.completed)
        assertEquals("Conserva", app.library.bookmarks.value.single().note)
        compose.onNodeWithContentDescription("Torna indietro").performClick()
        compose.onNodeWithTag("library").performScrollToIndex(0)
        compose.onNodeWithTag("listening_expanded").assertDoesNotExist()
        compose.runOnIdle { vm.playBook(reset) }
        compose.waitUntil(10_000) { vm.now.playing }
        assertTrue(vm.now.position < 5_000)
        compose.runOnIdle { vm.markNotStarted(reset) }
        compose.waitUntil(10_000) { vm.busy == null && vm.now.bookId == null }
        runBlocking { app.library.update(book.id) { it.copy(completed = true, lastPlayedAt = 1, positionMs = 25_000) } }
        compose.runOnIdle { vm.markNotStarted(app.library.books.value.single()) }
        compose.waitUntil(5_000) { !app.library.books.value.single().completed }
        assertEquals(0L, app.library.books.value.single().lastPlayedAt)
    }
    @Test fun coverNavigationHasReversibleIntermediateFrames() {
        val book = seed()
        compose.onNodeWithTag("library").performScrollToNode(hasTestTag("book_${book.id}"))
        screenshot("07-cover-origin")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("book_${book.id}").performClick()
            compose.mainClock.advanceTimeBy(160)
            screenshot("08-cover-opening")
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        screenshot("09-cover-destination")
        compose.onNodeWithTag("book_detail").performScrollToNode(hasText("Gestione del libro"))
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Torna indietro").performClick()
            compose.mainClock.advanceTimeBy(160)
            screenshot("10-cover-returning")
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("book_${book.id}").assertIsDisplayed()
        compose.runOnIdle { vm.changeTheme("dark") }
        screenshot("11-library-dark")
    }
    @Test fun emptyLibraryOffersOnlyLocalImport() {
        compose.onNodeWithTag("import_button").assertIsDisplayed()
        compose.onNodeWithText("Fonti",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("Download",useUnmergedTree=true).assertDoesNotExist()
        screenshot("01-library-empty")
        compose.onNodeWithTag("import_button").performClick()
        compose.onNodeWithText("Scegli file").assertIsDisplayed()
        compose.onNodeWithText("Scegli cartella").assertIsDisplayed()
    }
    @Test fun seriesAreGroupedUnderOneCardAndLibraryCanBecomeCompact() {
        val book=seed()
        runBlocking{app.library.update(book.id){it.copy(series="Trilogia di prova",seriesPosition=1)}}
        compose.waitUntil(5000){compose.onAllNodesWithText("Trilogia di prova",substring=true).fetchSemanticsNodes().isNotEmpty()}
        // In home il libro in serie non è più una card singola: è raggruppato sotto la card della serie.
        compose.onNodeWithTag("book_${book.id}").assertDoesNotExist()
        compose.onNodeWithTag("library").performScrollToNode(hasTestTag("series_card_Trilogia di prova"))
        compose.onNodeWithTag("series_card_Trilogia di prova").assertIsDisplayed()
        // Anche in vista compatta la serie resta raggruppata.
        compose.onNodeWithContentDescription("Vista compatta").performScrollTo().performClick()
        compose.onNodeWithTag("series_card_Trilogia di prova").assertIsDisplayed()
        // Un tocco apre la serie e mostra i suoi libri.
        compose.onNodeWithTag("series_card_Trilogia di prova").performClick()
        compose.onNodeWithTag("series_view").assertIsDisplayed()
        compose.onNodeWithTag("book_${book.id}").assertIsDisplayed()
        screenshot("03-series")
    }
    @Test fun backFromScrolledBookReturnsToItsLibraryPosition() {
        val target = Book(title="Libro in fondo", tracks=listOf(AudioTrack(uri="", name="Audio da ricollegare", durationMs=30_000)), needsRelink=true, createdAt=1)
        val newer = (1..14).map { index ->
            Book(title="Libro recente ${index.toString().padStart(2, '0')}", tracks=listOf(AudioTrack(uri="", name="Audio da ricollegare", durationMs=30_000)), needsRelink=true, createdAt=100L + index)
        }
        runBlocking { app.library.add(newer + target) }
        compose.waitUntil(10_000) { app.library.books.value.size == newer.size + 1 }
        compose.onNodeWithTag("library").performScrollToNode(hasTestTag("book_${target.id}"))
        compose.onNodeWithTag("book_${target.id}").performClick()
        compose.onNodeWithTag("book_detail").performScrollToNode(hasText("Gestione del libro"))
        compose.onNodeWithContentDescription("Torna indietro").performClick()
        compose.onNodeWithTag("book_${target.id}").assertIsDisplayed()
        compose.onNodeWithTag("book_${newer.last().id}").assertDoesNotExist()
    }
    @Test fun playerPersistsSeekSpeedAndBookmark() {
        val book=seed()
        screenshot("02-library")
        compose.onNodeWithTag("book_${book.id}").performScrollTo()
        screenshot("02-library-books")
        compose.onNodeWithTag("book_${book.id}").performClick()
        compose.onNodeWithTag("book_detail").assertIsDisplayed()
        compose.onNodeWithText("Inizia l’ascolto").performScrollTo().performClick()
        compose.waitUntil(10_000){vm.now.playing}
        compose.onNodeWithTag("book_detail").assertIsDisplayed()
        compose.onNodeWithTag("player").assertDoesNotExist()
        compose.onAllNodesWithText("Capitolo introduttivo").onFirst().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Capitolo introduttivo",vm.controller?.mediaMetadata?.title?.toString())
            assertTrue(requireNotNull(vm.controller).duration in 14_000L..16_000L)
        }
        compose.runOnIdle{vm.seek(20_000)}
        compose.waitUntil(5000){vm.now.position>=19_000}
        compose.runOnIdle{assertEquals("Seconda parte",vm.controller?.mediaMetadata?.title?.toString())}
        compose.runOnIdle{vm.seek(10_000);vm.speed(1.5f)}
        compose.waitUntil(10_000){app.library.books.value.first().positionMs>=9000}
        compose.onNodeWithTag("play_pause").performClick()
        compose.waitUntil(5000){!vm.now.playing}
        screenshot("03-player")
        compose.onNodeWithTag("book_detail").performScrollToNode(hasText("Seconda parte"))
        screenshot("04-chapters")
        compose.onNodeWithText("Segnalibro",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Nota facoltativa").performTextInput("Un passaggio da ricordare")
        compose.onNodeWithText("Salva",substring=false).performClick()
        compose.waitUntil(5000){app.library.bookmarks.value.isNotEmpty()}
        runBlocking {app.library.update(book.id){it.copy(title="Titolo corretto")}}
        assertEquals(1,app.library.bookmarks.value.size)
        assertEquals(1.5f,app.library.books.value.first().speed,0f)
        assertTrue(app.library.books.value.first().positionMs>=9000)
        val timerCommand=SessionCommand(PlaybackSignals.TOGGLE_TIMER_COMMAND,Bundle.EMPTY)
        var timerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>?=null
        compose.runOnIdle{timerFuture=vm.controller!!.sendCustomCommand(timerCommand,Bundle.EMPTY)}
        timerFuture!!.get(5,TimeUnit.SECONDS)
        compose.waitUntil(5000){PlaybackSignals.timer.value.startsWith("30")}
        compose.runOnIdle{timerFuture=vm.controller!!.sendCustomCommand(timerCommand,Bundle.EMPTY)}
        timerFuture!!.get(5,TimeUnit.SECONDS)
        compose.waitUntil(5000){PlaybackSignals.timer.value.startsWith("40")}
        compose.runOnIdle{vm.timer(0)}
        compose.waitUntil(5000){PlaybackSignals.timer.value.isEmpty()}
        runBlocking{app.library.load()}
        assertEquals("Un passaggio da ricordare",app.library.bookmarks.value.single().note)
    }
    @Test fun largeChapteredFileKeepsOneSourceWhenChangingChapter() {
        val original=seed()
        val large=original.copy(tracks=listOf(original.tracks.single().copy(size=3_000_000_000L)))
        runBlocking { app.library.update(original.id) { large } }
        compose.runOnIdle { vm.playBook(large,0,1_000) }
        compose.waitUntil(10_000) { vm.now.playing }
        var mediaId:String?=null
        compose.runOnIdle {
            assertEquals(1,vm.controller?.mediaItemCount)
            mediaId=vm.controller?.currentMediaItem?.mediaId
        }
        compose.runOnIdle { vm.playBook(large,0,16_000) }
        compose.waitUntil(5_000) { vm.now.playing && vm.now.position >= 15_000 }
        compose.runOnIdle {
            assertEquals(1,vm.controller?.mediaItemCount)
            assertEquals(mediaId,vm.controller?.currentMediaItem?.mediaId)
            assertEquals("Seconda parte",large.currentChapter(0,vm.now.position)?.title)
        }
    }
    @Test fun endOfTrackTimerStopsBeforeNextFile() {
        val book=seed()
        val second=book.tracks.single().copy(id=java.util.UUID.randomUUID().toString(),name="Capitolo 2")
        val multi=book.copy(tracks=book.tracks+second)
        runBlocking{app.library.update(book.id){multi}}
        compose.runOnIdle{vm.playBook(multi)}
        compose.waitUntil(10_000){vm.now.playing}
        compose.runOnIdle{vm.seek(28_000)}
        compose.waitUntil(5_000){vm.now.position>=27_500}
        compose.runOnIdle{vm.timer(-1)}
        compose.waitUntil(5_000){PlaybackSignals.timer.value=="Fine capitolo"}
        compose.waitUntil(10_000){!vm.now.playing && vm.now.position>=29_000}
        assertEquals(0,vm.now.trackIndex)
        assertTrue(it.sottovoce.app.playback.PlaybackSignals.timer.value.isEmpty())
    }
    @Test fun importingCopiesAndDeletingNeverTouchesTheOriginal() {
        val resolver=context.contentResolver
        val source=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{
            put(MediaStore.MediaColumns.DISPLAY_NAME,"sottovoce-test-${System.nanoTime()}.wav")
            put(MediaStore.MediaColumns.MIME_TYPE,"audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/SottovoceTests")
        }))
        try {
            resolver.openOutputStream(source)!!.use{it.write(wav())}
            runBlocking {
                val importer=AudioImporter(context,app.library)
                val candidates=importer.files(listOf(source))
                assertTrue(candidates.single().durationMs>0)
                importer.commit(candidates,copy=true)
                val imported=app.library.books.value.single()
                assertTrue(imported.tracks.single().owned)
                val copy=File(Uri.parse(imported.tracks.single().uri).path!!)
                assertTrue(copy.exists())
                app.library.removeBook(imported.id)
                assertFalse(copy.exists())
                assertTrue(resolver.openInputStream(source)!!.use{it.read()>=0})
            }
        } finally {resolver.delete(source,null,null)}
    }
    @Test fun backupRoundTripPreservesStatisticsAndRecoversLocalCopies() {
        val book=seed()
        runBlocking {
            app.library.savePosition(book.id,0,5000,1.25f)
            app.library.bookmark(Bookmark(bookId=book.id,trackIndex=0,positionMs=4000,note="Nota locale"))
            context.getSharedPreferences("preferences",0).edit().putString("theme","dark").putInt("skipBack",30).commit()
            app.library.recordListening(book.id, 60_000)
            val backup=app.library.exportBackup()
            assertEquals(60_000L, backup.sessions.sumOf { it.durationMs })
            assertEquals("dark",backup.preferences.theme)
            context.getSharedPreferences("preferences",0).edit().putString("theme","light").commit()
            app.library.restore(backup)
            assertEquals("dark",context.getSharedPreferences("preferences",0).getString("theme",null))
            assertEquals(30,context.getSharedPreferences("preferences",0).getInt("skipBack",0))
            val restored=app.library.books.value.single()
            assertFalse(restored.needsRelink)
            assertEquals(5000L,restored.positionMs)
            assertEquals(book.tracks.single().uri,restored.tracks.single().uri)
            assertEquals(60_000L, app.library.listeningDays().sumOf { it.durationMs })
            assertTrue(app.library.hasRecovery())
            assertEquals("Nota locale",app.library.bookmarks.value.single().note)
            assertTrue(File(Uri.parse(book.tracks.single().uri).path!!).exists())
        }
    }
}
