package it.sottovoce.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class AudioImporter(private val context: Context, private val library: LibraryRepository) {
    private val extensions = setOf("mp3", "m4b", "m4a", "mp4", "aac", "ogg", "opus", "flac", "wav")
    private fun supported(name: String) = name.substringAfterLast('.', "").lowercase() in extensions

    suspend fun files(uris: List<Uri>): List<Book> = withContext(Dispatchers.IO) {
        require(uris.size in 1..2000) { "Scegli da 1 a 2000 file per volta." }
        listOf(describe(uris, null))
    }
    suspend fun folder(uri: Uri): List<Book> = withContext(Dispatchers.IO) {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "Cartella non accessibile." }
        val children = root.listFiles().sortedWith { a, b -> NaturalOrder.compare(a.name.orEmpty(), b.name.orEmpty()) }
        var count = 0
        suspend fun collect(node: DocumentFile, depth: Int): List<Uri> {
            require(depth <= 12) { "La cartella contiene troppi livelli." }
            currentCoroutineContext().ensureActive()
            val result = mutableListOf<Uri>()
            for (file in node.listFiles().sortedWith { a, b -> NaturalOrder.compare(a.name.orEmpty(), b.name.orEmpty()) }) {
                currentCoroutineContext().ensureActive()
                require(++count <= 10_000) { "Cartella troppo grande: scegli una sottocartella." }
                if (file.isDirectory) result += collect(file, depth + 1)
                else if (supported(file.name.orEmpty())) result += file.uri
                require(result.size <= 2000) { "Troppi file per un solo libro." }
            }
            return result
        }
        val groups = mutableListOf<Book>()
        val direct = children.filter { it.isFile && supported(it.name.orEmpty()) }.map { it.uri }
        if (direct.isNotEmpty()) groups += describe(direct, root.name)
        for (child in children.filter { it.isDirectory }) {
            val tracks = collect(child, 1)
            if (tracks.isNotEmpty()) groups += describe(tracks, child.name)
            require(groups.size <= 100) { "Importa al massimo 100 libri per volta." }
        }
        require(groups.isNotEmpty()) { "Nessun file audio supportato in questa cartella." }
        groups
    }

    private suspend fun describe(uris: List<Uri>, folderName: String?): Book {
        var author = ""; var narrator = ""; var album = ""
        val tracks = uris.map { uri ->
            currentCoroutineContext().ensureActive()
            require(uri.scheme == "content") { "Sono supportati solo file scelti dal dispositivo." }
            var name = "Audiolibro"; var size = 0L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; size = if (c.isNull(1)) 0 else c.getLong(1).coerceAtLeast(0) }
            }
            require(supported(name)) { "Formato non supportato: $name" }
            var duration = 0L
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                if (album.isEmpty()) album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                if (author.isEmpty()) author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty()
                if (narrator.isEmpty()) narrator = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            } catch (_: Exception) {
                context.contentResolver.openInputStream(uri)?.use { require(it.read() >= 0) { "File vuoto: $name" } }
                    ?: error("File non accessibile: $name")
            } finally { retriever.release() }
            val chapters = if (name.substringAfterLast('.').lowercase() in setOf("m4b", "m4a", "mp4")) {
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { Mp4Chapters.read(it.channel) }
                    }.orEmpty()
                }.getOrDefault(emptyList())
            } else emptyList()
            AudioTrack(uri = uri.toString(), name = name, durationMs = duration.coerceAtLeast(0), size = size, chapters = chapters)
        }.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        return Book(title = album.ifBlank { folderName ?: tracks.first().name.substringBeforeLast('.') }, author = author, narrator = narrator, tracks = tracks)
    }

    suspend fun commit(candidates: List<Book>, copy: Boolean, relinkId: String? = null): Int = withContext(Dispatchers.IO) {
        require(candidates.isNotEmpty())
        candidates.forEach { require(it.title.isNotBlank()) { "Inserisci un titolo." } }
        if (relinkId != null) {
            require(candidates.size == 1) { "Per ricollegare scegli un solo libro." }
            val old = requireNotNull(library.books.value.find { it.id == relinkId })
            val replacement = candidates.single()
            require(old.tracks.size == replacement.tracks.size) { "Servono ${old.tracks.size} file, nello stesso ordine della registrazione originale." }
            library.update(old.id) { it.copy(tracks = replacement.tracks, needsRelink = false) }
            return@withContext 1
        }
        val existing = library.books.value.flatMap { it.tracks }.map { it.uri }.toSet()
        require(candidates.flatMap { it.tracks }.none { it.uri in existing }) { "Uno o più file sono già in libreria. Non sono stati importati duplicati." }
        if (copy) {
            val needed = candidates.sumOf { b -> b.tracks.sumOf { it.size } }
            require(StatFs(context.filesDir.path).availableBytes > needed + 32L * 1024 * 1024) { "Spazio insufficiente per copiare questi libri." }
        }
        val created = mutableListOf<File>()
        var committed = false
        try {
            val saved = candidates.map { book ->
                val directory = library.ownedDirectory(book.id).apply { mkdirs() }; created += directory
                val tracks = if (!copy) book.tracks else book.tracks.map { track ->
                    currentCoroutineContext().ensureActive()
                    val extension = track.name.substringAfterLast('.').lowercase().takeIf { it in extensions } ?: "audio"
                    val target = File(directory, "${track.id}.$extension")
                    val partial = File(directory, "${track.id}.part")
                    context.contentResolver.openInputStream(Uri.parse(track.uri)).use { input ->
                        requireNotNull(input) { "File non accessibile: ${track.name}" }
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                            output.fd.sync()
                        }
                    }
                    require(partial.length() > 0 && (track.size == 0L || partial.length() == track.size)) { "Copia incompleta: ${track.name}" }
                    require(partial.renameTo(target)) { "Impossibile completare la copia." }
                    track.copy(uri = Uri.fromFile(target).toString(), owned = true)
                }
                book.copy(tracks = tracks, coverPath = saveCover(Uri.parse(book.tracks.first().uri), directory))
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                library.add(saved)
                committed = true
            }
            saved.size
        } catch (e: Exception) {
            if (!committed) created.forEach { it.deleteRecursively() }
            throw e
        }
    }
    private fun saveCover(uri: Uri, directory: File): String? = runCatching {
        val r = MediaMetadataRetriever()
        val bytes = try { r.setDataSource(context, uri); r.embeddedPicture } finally { r.release() } ?: return null
        if (bytes.size > 10 * 1024 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 600).coerceAtLeast(1)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val target = File(directory, "cover.jpg")
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        target.absolutePath
    }.getOrNull()
}
