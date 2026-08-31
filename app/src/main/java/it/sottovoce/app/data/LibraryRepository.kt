package it.sottovoce.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

class LibraryRepository(private val context: Context) {
    private val db = object : SQLiteOpenHelper(context, "library.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY, data TEXT NOT NULL)")
            db.execSQL("CREATE TABLE bookmarks(id TEXT PRIMARY KEY, book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE, data TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Migrazione del database non disponibile: $oldVersion → $newVersion")
        }
    }.apply { setWriteAheadLoggingEnabled(true) }
    private val mutex = Mutex()
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { refresh() } }
    private fun refresh() {
        _books.value = db.readableDatabase.rawQuery("SELECT data FROM books", null).use { c ->
            buildList { while (c.moveToNext()) add(AppJson.decodeFromString<Book>(c.getString(0))) }
        }.sortedByDescending { maxOf(it.lastPlayedAt, it.createdAt) }
        _bookmarks.value = db.readableDatabase.rawQuery("SELECT data FROM bookmarks", null).use { c ->
            buildList { while (c.moveToNext()) add(AppJson.decodeFromString<Bookmark>(c.getString(0))) }
        }.sortedBy { it.createdAt }
    }
    private fun write(book: Book) {
        val values = ContentValues().apply { put("id", book.id); put("data", AppJson.encodeToString(book)) }
        // UPDATE then INSERT avoids REPLACE deleting foreign-key bookmark rows.
        if (db.writableDatabase.update("books", values, "id=?", arrayOf(book.id)) == 0)
            db.writableDatabase.insertOrThrow("books", null, values)
    }
    suspend fun add(books: List<Book>) = withContext(Dispatchers.IO) { mutex.withLock {
        val database = db.writableDatabase
        database.beginTransaction()
        try { books.forEach(::write); database.setTransactionSuccessful() } finally { database.endTransaction() }
        refresh()
    } }
    suspend fun update(id: String, transform: (Book) -> Book) = withContext(Dispatchers.IO) { mutex.withLock {
        _books.value.firstOrNull { it.id == id }?.let { write(transform(it)); refresh() }
    } }
    suspend fun savePosition(id: String, index: Int, position: Long, speed: Float, finished: Boolean = false) = update(id) { b ->
        if (index !in b.tracks.indices) b else b.copy(
            trackIndex = index, positionMs = position.coerceAtLeast(0), speed = speed,
            lastPlayedAt = System.currentTimeMillis(), completed = finished || b.completed,
        )
    }
    suspend fun bookmark(mark: Bookmark) = withContext(Dispatchers.IO) { mutex.withLock {
        if (_books.value.none { it.id == mark.bookId }) return@withLock
        db.writableDatabase.insertOrThrow("bookmarks", null, ContentValues().apply {
            put("id", mark.id); put("book_id", mark.bookId); put("data", AppJson.encodeToString(mark))
        }); refresh()
    } }
    suspend fun removeBookmark(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        db.writableDatabase.delete("bookmarks", "id=?", arrayOf(id)); refresh()
    } }
    suspend fun removeBook(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        db.writableDatabase.delete("books", "id=?", arrayOf(id)); refresh()
        ownedDirectory(id).deleteRecursively()
    } }
    suspend fun removeCopies(id: String) = withContext(Dispatchers.IO) {
        update(id) { b -> b.copy(needsRelink = true, tracks = b.tracks.map { if (it.owned) it.copy(uri = "", owned = false) else it }) }
        ownedDirectory(id).listFiles()?.filter { it.name != "cover.jpg" }?.forEach { it.delete() }
    }
    fun ownedDirectory(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        return File(context.filesDir, "books/$id")
    }
    fun isSafeAudioUri(value: String): Boolean {
        val uri = Uri.parse(value)
        return when (uri.scheme) {
            "content" -> true
            "file" -> runCatching {
                val base = File(context.filesDir, "books").canonicalPath + File.separator
                File(requireNotNull(uri.path)).canonicalPath.startsWith(base)
            }.getOrDefault(false)
            else -> false
        }
    }
    private fun preferences(): Preferences {
        val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        return Preferences(prefs.getString("theme", "system") ?: "system", prefs.getInt("skipBack", 15), prefs.getInt("skipForward", 30))
    }
    suspend fun exportBackup(): Backup = withContext(Dispatchers.IO) { mutex.withLock {
        Backup(books = _books.value.map { b ->
            b.copy(coverPath = null, needsRelink = true, tracks = b.tracks.map { it.copy(uri = "", owned = false) })
        }, bookmarks = _bookmarks.value, preferences = preferences())
    } }
    suspend fun restore(backup: Backup) = withContext(Dispatchers.IO) { mutex.withLock {
        val safe = validateBackup(backup)
        // Recovery snapshot is local and excludes audio. Never delete audio during restore.
        val recovery = File(context.filesDir, "before-restore.json")
        val atomic = android.util.AtomicFile(recovery)
        val stream = atomic.startWrite()
        try {
            stream.write(AppJson.encodeToString(Backup(books = _books.value, bookmarks = _bookmarks.value, preferences = preferences())).toByteArray())
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.delete("bookmarks", null, null); database.delete("books", null, null)
            safe.books.forEach(::write)
            safe.bookmarks.forEach { m -> database.insertOrThrow("bookmarks", null, ContentValues().apply {
                put("id", m.id); put("book_id", m.bookId); put("data", AppJson.encodeToString(m))
            }) }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit()
            .putString("theme", safe.preferences.theme).putInt("skipBack", safe.preferences.skipBack)
            .putInt("skipForward", safe.preferences.skipForward).commit()
        refresh()
    } }
}
