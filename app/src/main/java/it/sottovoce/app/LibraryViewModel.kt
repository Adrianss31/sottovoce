package it.sottovoce.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import it.sottovoce.app.data.*
import it.sottovoce.app.playback.*
import it.sottovoce.app.update.ReleaseInfo
import it.sottovoce.app.update.UpdateManager
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NowPlaying(val bookId: String? = null, val trackIndex: Int = 0, val position: Long = 0,
    val duration: Long = 0, val playing: Boolean = false, val speed: Float = 1f)

@UnstableApi
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as SottovoceApp
    val library = app.library
    private val importer = AudioImporter(app, library)
    val updater = UpdateManager(app)
    private val prefs = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set
    var skipBack by mutableStateOf(prefs.getInt("skipBack", 15))
        private set
    var skipForward by mutableStateOf(prefs.getInt("skipForward", 30))
        private set
    var screen by mutableStateOf("library")
    var selectedId by mutableStateOf<String?>(null)
    var now by mutableStateOf(NowPlaying())
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var candidates by mutableStateOf<List<Book>>(emptyList())
    var mustCopyImports by mutableStateOf(false)
        private set
    var copyImports by mutableStateOf(false)
    var relinkId by mutableStateOf<String?>(null)
    var pendingBackup by mutableStateOf<Backup?>(null)
    var release by mutableStateOf<ReleaseInfo?>(null)
        private set
    var updateProgress by mutableStateOf(0f)
        private set
    var updateFile by mutableStateOf<File?>(null)
        private set
    var updateChecked by mutableStateOf(false)
        private set
    var updateInProgress by mutableStateOf(false)
        private set
    var controller by mutableStateOf<MediaController?>(null)
        private set
    private var operation: Job? = null
    private val controllerFuture = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    init {
        viewModelScope.launch {
            try {
                library.load()
                controller = controllerFuture.awaitValue()
                controller?.addListener(object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { snapshot() } })
                while (isActive) { snapshot(); delay(500) }
            } catch (e: Exception) { if (e !is CancellationException) message = "Impossibile avviare il lettore: ${e.message}" }
        }
        viewModelScope.launch { PlaybackSignals.error.collect { if (it != null) { message = it; PlaybackSignals.error.value = null } } }
        viewModelScope.launch {
            if (!BuildConfig.DEBUG) runCatching { refreshRelease() }
        }
    }
    private fun snapshot() {
        controller?.let { c ->
            val extras = c.currentMediaItem?.mediaMetadata?.extras
            val bookId = extras?.getString("bookId")
            val trackIndex = extras?.getInt("trackIndex", 0) ?: 0
            val chapterStart = extras?.getLong("chapterStartMs", 0) ?: 0
            val trackDuration = library.books.value.find { it.id == bookId }?.tracks?.getOrNull(trackIndex)?.durationMs
                ?: (chapterStart + c.duration.coerceAtLeast(0))
            now = NowPlaying(bookId, trackIndex, chapterStart + c.currentPosition.coerceAtLeast(0),
                trackDuration, c.isPlaying, c.playbackParameters.speed)
        }
    }
    fun task(label: String, action: suspend () -> Unit) {
        if (busy != null) return
        operation = viewModelScope.launch {
            busy = label
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Operazione non riuscita." }
            finally { busy = null }
        }
    }
    fun cancelTask() { operation?.cancel() }
    fun importFiles(uris: List<Uri>, flags: Int) {
        task("Lettura dei file…") {
            persist(uris, flags)
            candidates = importer.files(uris); screen = "import"
        }
    }
    fun importFolder(uri: Uri, flags: Int) {
        task("Lettura della cartella…") {
            persist(listOf(uri), flags)
            candidates = importer.folder(uri); screen = "import"
        }
    }
    private fun persist(uris: List<Uri>, flags: Int) {
        mustCopyImports = false
        val take = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        uris.forEach { uri ->
            require(uri.scheme == "content") { "Scegli file presenti sul dispositivo." }
            try { app.contentResolver.takePersistableUriPermission(uri, take) }
            catch (_: SecurityException) {
                require(relinkId == null) { "Questo archivio non concede accesso permanente. Per ricollegare, scegli i file dalla memoria del telefono." }
                mustCopyImports = true; copyImports = true
            }
        }
    }
    fun changeCandidate(id: String, title: String? = null, author: String? = null) {
        candidates = candidates.map { if (it.id != id) it else it.copy(title = title ?: it.title, author = author ?: it.author) }
    }
    fun moveTrack(bookId: String, from: Int, to: Int) {
        candidates = candidates.map { b -> if (b.id != bookId || from !in b.tracks.indices || to !in b.tracks.indices) b else {
            val tracks = b.tracks.toMutableList(); tracks.add(to, tracks.removeAt(from)); b.copy(tracks = tracks)
        } }
    }
    fun confirmImport() = task(if (copyImports) "Copia dei file…" else "Importazione…") {
        val replacing = relinkId
        if (replacing != null && now.bookId == replacing) stopCurrent()
        val count = importer.commit(candidates, (copyImports || mustCopyImports) && replacing == null, replacing)
        candidates = emptyList(); relinkId = null; screen = "library"
        message = if (replacing != null) "File ricollegati. Progressi e segnalibri conservati." else "$count ${if (count == 1) "libro importato" else "libri importati"}."
    }
    fun playBook(book: Book, index: Int? = null, position: Long? = null) {
        if (book.needsRelink || book.tracks.any { !library.isSafeAudioUri(it.uri) }) { selectedId = book.id; screen = "detail"; message = "Ricollega i file prima di ascoltare."; return }
        if (book.tracks.any { !it.owned && it.size > LARGE_DOCUMENT_THRESHOLD }) {
            task("Preparazione del file grande…") {
                val local = importer.ensureSeekableCopy(book)
                playBook(local, index, position)
            }
            return
        }
        val c = controller ?: run { message = "Il lettore si sta avviando. Riprova fra un istante."; return }
        PlaybackSignals.error.value = null
        if (now.bookId != book.id || index != null || c.playbackState == Player.STATE_ENDED) {
            val track = (index ?: book.trackIndex).coerceIn(book.tracks.indices)
            val start = book.chapterPlaybackStart(track, (position ?: book.positionMs).coerceAtLeast(0))
            c.setMediaItems(book.mediaItems(), start.itemIndex, start.positionMs)
            c.setPlaybackSpeed(book.speed)
            c.prepare()
        } else if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play(); snapshot()
    }
    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }; snapshot() }
    fun seek(position: Long) {
        val c = controller ?: return
        val book = library.books.value.find { it.id == now.bookId }
        if (book == null) c.seekTo(position.coerceAtLeast(0)) else {
            val start = book.chapterPlaybackStart(now.trackIndex, position.coerceAtLeast(0))
            c.seekTo(start.itemIndex, start.positionMs)
        }
        snapshot()
    }
    fun skip(seconds: Int) {
        val book = library.books.value.find { it.id == now.bookId } ?: return
        var track = now.trackIndex
        var target = now.position + seconds * 1000L
        when {
            target < 0 && track > 0 -> {
                track -= 1
                target = (book.tracks[track].durationMs + target).coerceAtLeast(0)
            }
            book.tracks[track].durationMs > 0 && target > book.tracks[track].durationMs && track < book.tracks.lastIndex -> {
                target -= book.tracks[track].durationMs
                track += 1
            }
        }
        val start = book.chapterPlaybackStart(track, target.coerceIn(0, book.tracks[track].durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
        controller?.seekTo(start.itemIndex, start.positionMs)
        snapshot()
    }
    fun speed(value: Float) {
        controller?.setPlaybackSpeed(value)
        now.bookId?.let { id -> viewModelScope.launch { library.update(id) { it.copy(speed = value) } } }
        snapshot()
    }
    fun speed(book: Book, value: Float) {
        if (now.bookId == book.id) {
            controller?.setPlaybackSpeed(value)
            snapshot()
        }
        viewModelScope.launch { library.update(book.id) { it.copy(speed = value) } }
    }
    fun timer(minutes: Int) { controller?.sendCustomCommand(SessionCommand(PlaybackSignals.TIMER_COMMAND, Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) }) }
    fun addBookmark(note: String) { now.bookId?.let { id -> task("Salvataggio…") {
        library.bookmark(Bookmark(bookId = id, trackIndex = now.trackIndex, positionMs = now.position, note = note.take(10_000)))
        message = "Segnalibro salvato."
    } } }
    fun saveMetadata(book: Book, title: String, author: String, narrator: String) = task("Salvataggio…") {
        require(title.isNotBlank()) { "Il titolo non può essere vuoto." }
        library.update(book.id) { it.copy(title = title.trim().take(1000), author = author.trim().take(1000), narrator = narrator.trim().take(1000)) }
    }
    fun markCompleted(book: Book) = task("Salvataggio…") { library.update(book.id) { it.copy(completed = !it.completed) } }
    fun removeBook(book: Book, copiesOnly: Boolean) = task("Rimozione…") {
        if (now.bookId == book.id) stopCurrent()
        if (copiesOnly) library.removeCopies(book.id) else library.removeBook(book.id)
        screen = "library"
    }
    private suspend fun stopCurrent() {
        val result = controller?.sendCustomCommand(SessionCommand("it.sottovoce.STOP_AND_SAVE", Bundle.EMPTY), Bundle.EMPTY)
        result?.awaitValue(); snapshot()
    }
    fun exportBackup(uri: Uri) = task("Esportazione del backup…") {
        val backup = library.exportBackup()
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(AppJson.encodeToString(backup).toByteArray()) }
                ?: error("Impossibile scrivere il backup.")
        }
        message = "Backup salvato. Conserva gli audio separatamente."
    }
    fun readBackup(uri: Uri) = task("Lettura del backup…") {
        pendingBackup = withContext(Dispatchers.IO) {
            val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(16 * 1024 * 1024) } ?: error("File non accessibile.")
            validateBackup(AppJson.decodeFromString<Backup>(bytes.toString(Charsets.UTF_8)))
        }
    }
    fun restoreBackup() = task("Ripristino…") {
        val backup = pendingBackup ?: return@task
        stopCurrent(); library.restore(backup)
        theme = backup.preferences.theme; skipBack = backup.preferences.skipBack; skipForward = backup.preferences.skipForward
        pendingBackup = null; screen = "library"
        message = "Libreria ripristinata. Ricollega i file dalla scheda di ciascun libro."
    }
    private suspend fun refreshRelease() {
        val info = updater.check()
        updateChecked = true
        release = info.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        if (release == null) updateFile = null
    }
    fun checkUpdate() = task("Controllo aggiornamenti…") {
        refreshRelease()
        if (release == null) message = "Sei alla versione più recente."
    }
    fun updateAndInstall(onIntent: (Intent) -> Unit) {
        if (updateInProgress) return
        viewModelScope.launch {
            updateInProgress = true
            try {
                val info = release ?: return@launch
                val file = updateFile ?: run {
                    updateProgress = 0f
                    updater.download(info) { updateProgress = it }.also { updateFile = it }
                }
                withContext(Dispatchers.IO) { updater.verifyApk(file, info) }
                stopCurrent()
                onIntent(updater.install(file))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Aggiornamento non riuscito." }
            finally { updateInProgress = false }
        }
    }
    fun changeTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun setSkips(back: Int, forward: Int) {
        skipBack = back; skipForward = forward
        prefs.edit().putInt("skipBack", back).putInt("skipForward", forward).apply()
    }
    override fun onCleared() { MediaController.releaseFuture(controllerFuture); super.onCleared() }
}

private const val LARGE_DOCUMENT_THRESHOLD = 2_000_000_000L

private suspend fun <T> ListenableFuture<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
    addListener({ try { if (continuation.isActive) continuation.resume(get()) }
        catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) } }, MoreExecutors.directExecutor())
}
private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= max) { "Backup troppo grande." }; output.write(buffer, 0, n) }
    return output.toByteArray()
}
