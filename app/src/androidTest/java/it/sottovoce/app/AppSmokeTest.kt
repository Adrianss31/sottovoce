package it.sottovoce.app

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import it.sottovoce.app.data.*
import it.sottovoce.app.playback.PlaybackSignals
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
        compose.runOnIdle{vm.screen="library";vm.message=null;vm.changeTheme("light");vm.setSkips(15,30)}
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
        val book=Book(title="Audiolibro di prova",author="Test automatico",tracks=emptyList())
        val dir=app.library.ownedDirectory(book.id).apply{mkdirs()}
        val file=File(dir,"capitolo.wav").apply{writeBytes(wav())}
        val complete=book.copy(tracks=listOf(AudioTrack(uri=Uri.fromFile(file).toString(),name="file-audio.wav",durationMs=30_000,size=file.length(),owned=true,
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
    @Test fun emptyLibraryOffersOnlyLocalImport() {
        compose.onNodeWithTag("import_button").assertIsDisplayed()
        compose.onNodeWithText("Fonti",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("Download",useUnmergedTree=true).assertDoesNotExist()
        screenshot("01-library-empty")
        compose.onNodeWithTag("import_button").performClick()
        compose.onNodeWithText("Scegli file").assertIsDisplayed()
        compose.onNodeWithText("Scegli cartella").assertIsDisplayed()
    }
    @Test fun playerPersistsSeekSpeedAndBookmark() {
        val book=seed()
        screenshot("02-library")
        compose.onNodeWithTag("book_${book.id}").performClick()
        compose.onNodeWithText("Inizia l’ascolto").performClick()
        compose.waitUntil(10_000){vm.now.playing}
        compose.onNodeWithText("Capitolo introduttivo").assertIsDisplayed()
        assertEquals("Capitolo introduttivo",vm.controller?.mediaMetadata?.title?.toString())
        assertTrue(requireNotNull(vm.controller).duration in 14_000L..16_000L)
        compose.runOnIdle{vm.seek(20_000)}
        compose.waitUntil(5000){vm.controller?.mediaMetadata?.title?.toString()=="Seconda parte"&&vm.now.position>=19_000}
        compose.runOnIdle{vm.seek(10_000);vm.speed(1.5f)}
        compose.waitUntil(10_000){app.library.books.value.first().positionMs>=9000}
        compose.onNodeWithTag("play_pause").performClick()
        compose.waitUntil(5000){!vm.now.playing}
        screenshot("03-player")
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
        compose.waitUntil(5000){PlaybackSignals.timer.value=="30 minuti"}
        compose.runOnIdle{timerFuture=vm.controller!!.sendCustomCommand(timerCommand,Bundle.EMPTY)}
        timerFuture!!.get(5,TimeUnit.SECONDS)
        compose.waitUntil(5000){PlaybackSignals.timer.value.isEmpty()}
        runBlocking{app.library.load()}
        assertEquals("Un passaggio da ricordare",app.library.bookmarks.value.single().note)
    }
    @Test fun endOfTrackTimerStopsBeforeNextFile() {
        val book=seed()
        val second=book.tracks.single().copy(id=java.util.UUID.randomUUID().toString(),name="Capitolo 2")
        val multi=book.copy(tracks=book.tracks+second)
        runBlocking{app.library.update(book.id){multi}}
        compose.runOnIdle{vm.playBook(multi)}
        compose.waitUntil(10_000){vm.now.playing}
        compose.runOnIdle{vm.seek(28_000);vm.timer(-1)}
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
    @Test fun backupRoundTripPreservesDataButRequiresExplicitRelinking() {
        val book=seed()
        runBlocking {
            app.library.savePosition(book.id,0,5000,1.25f)
            app.library.bookmark(Bookmark(bookId=book.id,trackIndex=0,positionMs=4000,note="Nota locale"))
            context.getSharedPreferences("preferences",0).edit().putString("theme","dark").putInt("skipBack",30).commit()
            val backup=app.library.exportBackup()
            assertEquals("dark",backup.preferences.theme)
            context.getSharedPreferences("preferences",0).edit().putString("theme","light").commit()
            app.library.restore(backup)
            assertEquals("dark",context.getSharedPreferences("preferences",0).getString("theme",null))
            assertEquals(30,context.getSharedPreferences("preferences",0).getInt("skipBack",0))
            val restored=app.library.books.value.single()
            assertTrue(restored.needsRelink)
            assertEquals(5000L,restored.positionMs)
            assertEquals("",restored.tracks.single().uri)
            assertEquals("Nota locale",app.library.bookmarks.value.single().note)
            assertTrue(File(Uri.parse(book.tracks.single().uri).path!!).exists())
        }
    }
}
