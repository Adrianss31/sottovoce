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
import java.time.LocalDate

private const val SESSIONS_TABLE = "CREATE TABLE sessions(book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE, day INTEGER NOT NULL, duration_ms INTEGER NOT NULL, PRIMARY KEY(book_id, day))"

class LibraryRepository(private val context: Context) {
    private val db = object : SQLiteOpenHelper(context, "library.db", null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY, data TEXT NOT NULL)")
            db.execSQL("CREATE TABLE bookmarks(id TEXT PRIMARY KEY, book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE, data TEXT NOT NULL)")
            db.execSQL(SESSIONS_TABLE)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 1 && newVersion == 2) db.execSQL(SESSIONS_TABLE)
            else error("Migrazione del database non disponibile: $oldVersion → $newVersion")
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
    /** Accumula il tempo di ascolto reale per libro e giorno corrente. Le statistiche restano sul dispositivo. */
    suspend fun recordListening(bookId: String, deltaMs: Long) = withContext(Dispatchers.IO) { mutex.withLock {
        if (deltaMs <= 0 || _books.value.none { it.id == bookId }) return@withLock
        val day = LocalDate.now().toEpochDay()
        val database = db.writableDatabase
        database.execSQL("UPDATE sessions SET duration_ms = duration_ms + ? WHERE book_id = ? AND day = ?", arrayOf(deltaMs, bookId, day))
        val exists = database.rawQuery("SELECT 1 FROM sessions WHERE book_id = ? AND day = ?", arrayOf(bookId, day.toString())).use { it.moveToFirst() }
        if (!exists) database.insertOrThrow("sessions", null, ContentValues().apply {
            put("book_id", bookId); put("day", day); put("duration_ms", deltaMs)
        })
    } }
    suspend fun listeningDays(): List<ListeningDay> = withContext(Dispatchers.IO) { mutex.withLock {
        db.readableDatabase.rawQuery("SELECT book_id, day, duration_ms FROM sessions", null).use { c ->
            buildList { while (c.moveToNext()) add(ListeningDay(c.getString(0), c.getLong(1), c.getLong(2))) }
        }
    } }
    private fun preferences(): Preferences {
        val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        return Preferences(
            theme = prefs.getString("theme", "system") ?: "system",
            skipBack = prefs.getInt("skipBack", 15),
            skipForward = prefs.getInt("skipForward", 30),
            smartRewind = prefs.getBoolean("smartRewind", true),
            nightTimerEnabled = prefs.getBoolean("nightTimerEnabled", false),
            nightTimerStartMinutes = prefs.getInt("nightTimerStartMinutes", 22 * 60),
            nightTimerDuration = prefs.getInt("nightTimerDuration", 30),
            timerFade = prefs.getBoolean("timerFade", true),
            timerShakeExtend = prefs.getBoolean("timerShakeExtend", false),
            libraryViewMode = prefs.getString("libraryViewMode", "grid") ?: "grid",
        )
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
            database.delete("bookmarks", null, null); database.delete("sessions", null, null); database.delete("books", null, null)
            safe.books.forEach(::write)
            safe.bookmarks.forEach { m -> database.insertOrThrow("bookmarks", null, ContentValues().apply {
                put("id", m.id); put("book_id", m.bookId); put("data", AppJson.encodeToString(m))
            }) }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit()
            .putString("theme", safe.preferences.theme).putInt("skipBack", safe.preferences.skipBack)
            .putInt("skipForward", safe.preferences.skipForward)
            .putBoolean("smartRewind", safe.preferences.smartRewind)
            .putBoolean("nightTimerEnabled", safe.preferences.nightTimerEnabled)
            .putInt("nightTimerStartMinutes", safe.preferences.nightTimerStartMinutes)
            .putInt("nightTimerDuration", safe.preferences.nightTimerDuration)
            .putBoolean("timerFade", safe.preferences.timerFade)
            .putBoolean("timerShakeExtend", safe.preferences.timerShakeExtend)
            .putString("libraryViewMode", safe.preferences.libraryViewMode).commit()
        refresh()
    } }
}
