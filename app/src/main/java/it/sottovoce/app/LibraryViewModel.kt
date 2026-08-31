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
    }
    private fun snapshot() {
        controller?.let { c -> now = NowPlaying(c.currentMediaItem?.mediaMetadata?.extras?.getString("bookId"),
            c.currentMediaItemIndex.coerceAtLeast(0), c.currentPosition.coerceAtLeast(0), c.duration.coerceAtLeast(0), c.isPlaying, c.playbackParameters.speed) }
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
        val take = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        uris.forEach { uri ->
            require(uri.scheme == "content") { "Scegli file presenti sul dispositivo." }
            try { app.contentResolver.takePersistableUriPermission(uri, take) }
            catch (_: SecurityException) { copyImports = true }
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
        val count = importer.commit(candidates, copyImports && replacing == null, replacing)
        candidates = emptyList(); relinkId = null; screen = "library"
        message = if (replacing != null) "File ricollegati. Progressi e segnalibri conservati." else "$count ${if (count == 1) "libro importato" else "libri importati"}."
    }
    fun playBook(book: Book, index: Int? = null, position: Long? = null) {
        if (book.needsRelink || book.tracks.any { !library.isSafeAudioUri(it.uri) }) { selectedId = book.id; screen = "detail"; message = "Ricollega i file prima di ascoltare."; return }
        val c = controller ?: run { message = "Il lettore si sta avviando. Riprova fra un istante."; return }
        PlaybackSignals.error.value = null
        if (now.bookId != book.id || index != null || c.playbackState == Player.STATE_ENDED) {
            val start = (index ?: book.trackIndex).coerceIn(book.tracks.indices)
            c.setMediaItems(book.mediaItems(), start, (position ?: book.positionMs).coerceAtLeast(0))
            c.setPlaybackSpeed(book.speed)
            c.prepare()
        } else if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play(); screen = "player"; snapshot()
    }
    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }; snapshot() }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)); snapshot() }
    fun skip(seconds: Int) {
        val c = controller ?: return
        val target = c.currentPosition + seconds * 1000L
        when {
            target < 0 && c.hasPreviousMediaItem() -> {
                val book = library.books.value.find { it.id == now.bookId }
                val previous = now.trackIndex - 1
                c.seekTo(previous, ((book?.tracks?.getOrNull(previous)?.durationMs ?: 0) + target).coerceAtLeast(0))
            }
            c.duration > 0 && target > c.duration && c.hasNextMediaItem() -> c.seekTo(c.currentMediaItemIndex + 1, target - c.duration)
            else -> c.seekTo(target.coerceIn(0, c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        }
        snapshot()
    }
    fun speed(value: Float) {
        controller?.setPlaybackSpeed(value)
        now.bookId?.let { id -> viewModelScope.launch { library.update(id) { it.copy(speed = value) } } }
        snapshot()
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
        stopCurrent(); library.restore(backup); pendingBackup = null; screen = "library"
        message = "Libreria ripristinata. Ricollega i file dalla scheda di ciascun libro."
    }
    fun checkUpdate() = task("Controllo aggiornamenti…") {
        updateFile = null
        val info = updater.check()
        updateChecked = true
        release = info.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        if (release == null) message = "Sei alla versione più recente."
    }
    fun downloadUpdate() = task("Scaricamento aggiornamento dell’app…") {
        val info = release ?: return@task
        updateProgress = 0f
        updateFile = updater.download(info) { updateProgress = it }
        message = "Aggiornamento verificato e pronto da installare."
    }
    fun installUpdate(onIntent: (Intent) -> Unit) = task("Verifica dell’APK…") {
        val file = updateFile ?: return@task
        val info = release ?: return@task
        withContext(Dispatchers.IO) { updater.verifyApk(file, info) }
        stopCurrent()
        onIntent(updater.install(file))
    }
    fun setTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun setSkips(back: Int, forward: Int) {
        skipBack = back; skipForward = forward
        prefs.edit().putInt("skipBack", back).putInt("skipForward", forward).apply()
    }
    override fun onCleared() { MediaController.releaseFuture(controllerFuture); super.onCleared() }
}

private suspend fun <T> ListenableFuture<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
    addListener({ try { if (continuation.isActive) continuation.resume(get()) }
        catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) } }, MoreExecutors.directExecutor())
}
private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= max) { "Backup troppo grande." }; output.write(buffer, 0, n) }
    return output.toByteArray()
}
