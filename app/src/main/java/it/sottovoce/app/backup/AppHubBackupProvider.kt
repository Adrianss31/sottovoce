package it.sottovoce.app.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Bridge locale per esportare e ripristinare i dati di Sottovoce verso AppHub,
 * includendo anche tutti gli audiolibri e riallineando i percorsi locali al ripristino.
 */
class AppHubBackupProvider : ContentProvider() {

    private data class ExternalTrackEntry(
        val contentUri: Uri,
        val zipPath: String,
        val sizeBytes: Long,
    )

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = "application/zip"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: throw IllegalStateException("Context not available")
        enforceTrustedCaller(ctx)
        return when (method) {
            METHOD_PING -> handlePing(ctx)
            METHOD_COMMIT_RESTORE -> handleCommitRestore(ctx)
            else -> errorBundle("Metodo non supportato: $method")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Context not available")
        enforceTrustedCaller(ctx)
        val segment = uri.lastPathSegment
        return when {
            segment == PATH_EXPORT && mode.contains("r") -> createExportFileDescriptor(ctx)
            segment == PATH_IMPORT && mode.contains("w") -> createImportFileDescriptor(ctx)
            else -> throw FileNotFoundException("URI o modalità non supportati: $uri ($mode)")
        }
    }

    private fun handlePing(ctx: Context): Bundle {
        val pkgInfo = getOwnPackageInfo(ctx)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            pkgInfo?.versionCode?.toLong() ?: 0L
        }
        val localBytes = collectBackupEntries(ctx).sumOf { (_, file) -> file.length() }
        val externalAudioBytes = collectExternalAudioTracks(ctx).sumOf { it.sizeBytes.coerceAtLeast(0L) }
        return Bundle().apply {
            putBoolean(KEY_OK, true)
            putInt(KEY_PROTOCOL_VERSION, PROTOCOL_VERSION)
            putString(KEY_PACKAGE_NAME, ctx.packageName)
            putString(KEY_VERSION_NAME, pkgInfo?.versionName.orEmpty())
            putLong(KEY_VERSION_CODE, versionCode)
            putLong(KEY_ESTIMATED_BYTES, localBytes + externalAudioBytes)
        }
    }

    private fun createExportFileDescriptor(ctx: Context): ParcelFileDescriptor {
        if ("robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) {
            val exportFile = File(ctx.cacheDir, EXPORT_ZIP_NAME)
            val tmpFile = File(ctx.cacheDir, "$EXPORT_ZIP_NAME.tmp")
            exportFile.parentFile?.mkdirs()
            tmpFile.delete()
            FileOutputStream(tmpFile).use { out ->
                writeExportZip(ctx, BufferedOutputStream(out, 64 * 1024))
            }
            if (exportFile.exists()) exportFile.delete()
            if (!tmpFile.renameTo(exportFile)) {
                tmpFile.copyTo(exportFile, overwrite = true)
                tmpFile.delete()
            }
            return ParcelFileDescriptor.open(exportFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { rawOut ->
                    writeExportZip(ctx, BufferedOutputStream(rawOut, 64 * 1024))
                }
            } catch (_: Throwable) {
                runCatching { writeSide.close() }
            }
        }, "AppHubBackupExport").apply {
            isDaemon = true
            start()
        }
        return readSide
    }

    private fun writeExportZip(ctx: Context, rawOut: BufferedOutputStream) {
        checkpointWalDatabases(ctx)
        val entries = collectBackupEntries(ctx)
        val existingPaths = entries.map { it.first }.toHashSet()
        val externalTracks = collectExternalAudioTracks(ctx)

        ZipOutputStream(rawOut).use { zos ->
            val buffer = ByteArray(128 * 1024)
            for ((relPath, file) in entries) {
                if (!file.isFile) continue
                zos.setLevel(compressionLevelFor(relPath))
                val entry = ZipEntry(relPath).apply { time = file.lastModified() }
                zos.putNextEntry(entry)
                FileInputStream(file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        zos.write(buffer, 0, read)
                    }
                }
                zos.closeEntry()
            }

            for (track in externalTracks) {
                if (track.zipPath in existingPaths) continue
                runCatching {
                    ctx.contentResolver.openInputStream(track.contentUri)?.use { input ->
                        zos.setLevel(Deflater.NO_COMPRESSION)
                        zos.putNextEntry(ZipEntry(track.zipPath))
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zos.write(buffer, 0, read)
                        }
                        zos.closeEntry()
                        existingPaths += track.zipPath
                    }
                }
            }
        }
    }

    private fun collectExternalAudioTracks(ctx: Context): List<ExternalTrackEntry> {
        val dbFile = File(dataRootDir(ctx), "databases/library.db")
        val list = mutableListOf<ExternalTrackEntry>()
        if (!dbFile.isFile) return list

        runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT id, data FROM books", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val bookId = cursor.getString(0).orEmpty()
                        val data = cursor.getString(1).orEmpty()
                        if (bookId.isBlank() || data.isBlank()) continue
                        val bookObj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                        val tracks = bookObj.optJSONArray("tracks") ?: continue
                        val bookDir = File(ctx.filesDir, "books/$bookId")
                        for (i in 0 until tracks.length()) {
                            val t = tracks.optJSONObject(i) ?: continue
                            val trackId = t.optString("id", "")
                            val uriStr = t.optString("uri", "")
                            if (trackId.isBlank() || !uriStr.startsWith("content://")) continue

                            val alreadyOwned = bookDir.listFiles()?.any {
                                it.isFile &&
                                    it.name.substringBeforeLast('.') == trackId &&
                                    !it.name.endsWith(".part") &&
                                    it.name != "cover.jpg"
                            } == true
                            if (alreadyOwned) continue

                            val name = t.optString("name", "")
                            val ext = name.substringAfterLast('.', "")
                                .lowercase()
                                .takeIf { it in AUDIO_EXTENSIONS }
                                ?: "audio"
                            val recordedSize = t.optLong("size", 0L)
                            val parsed = Uri.parse(uriStr)
                            val size = if (recordedSize > 0L) recordedSize else queryUriSize(ctx, parsed)
                            if (size >= 0L || isUriReadable(ctx, parsed)) {
                                list += ExternalTrackEntry(
                                    contentUri = parsed,
                                    zipPath = "files/books/$bookId/$trackId.$ext",
                                    sizeBytes = size.coerceAtLeast(0L),
                                )
                            }
                        }
                    }
                }
            }
        }
        return list
    }

    private fun queryUriSize(ctx: Context, uri: Uri): Long {
        val fromCursor = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst() && idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        if (fromCursor >= 0L) return fromCursor
        return runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun isUriReadable(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun createImportFileDescriptor(ctx: Context): ParcelFileDescriptor {
        val staging = File(ctx.cacheDir, STAGING_ZIP_NAME)
        staging.parentFile?.mkdirs()
        staging.delete()
        staging.createNewFile()
        return ParcelFileDescriptor.open(
            staging,
            ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_CREATE,
        )
    }

    private fun handleCommitRestore(ctx: Context): Bundle {
        val stagingFile = File(ctx.cacheDir, STAGING_ZIP_NAME)
        if (!stagingFile.isFile || stagingFile.length() == 0L) {
            return errorBundle("Archivio di ripristino vuoto o mancante")
        }

        val dataRoot = dataRootDir(ctx)
        val allowedRoots = setOf("databases", "shared_prefs", "files")

        return try {
            ZipInputStream(BufferedInputStream(FileInputStream(stagingFile))).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (!entry.isDirectory) {
                        val rootFolder = name.substringBefore('/', "")
                        require(rootFolder in allowedRoots) { "Percorso non consentito: $name" }
                        resolveSafeTarget(dataRoot, name)
                    }
                    zis.closeEntry()
                }
            }

            val restoredDatabases = mutableSetOf<String>()
            ZipInputStream(BufferedInputStream(FileInputStream(stagingFile))).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("databases/") && !entry.isDirectory) {
                        val dbName = name.removePrefix("databases/")
                        if (!dbName.contains('/') &&
                            !dbName.endsWith("-wal") &&
                            !dbName.endsWith("-shm") &&
                            !dbName.endsWith("-journal")
                        ) {
                            restoredDatabases += dbName
                        }
                    }
                    zis.closeEntry()
                }
            }
            val dbDir = File(dataRoot, "databases")
            for (dbName in restoredDatabases) {
                File(dbDir, "$dbName-wal").delete()
                File(dbDir, "$dbName-shm").delete()
                File(dbDir, "$dbName-journal").delete()
            }

            var filesRestored = 0
            val buffer = ByteArray(64 * 1024)
            ZipInputStream(BufferedInputStream(FileInputStream(stagingFile))).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (!entry.isDirectory) {
                        val target = resolveSafeTarget(dataRoot, name)
                        target.parentFile?.mkdirs()
                        val tmp = File(target.parentFile, "${target.name}.apphub_tmp")
                        FileOutputStream(tmp).use { out ->
                            while (true) {
                                val read = zis.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                            }
                            out.fd.sync()
                        }
                        if (target.exists()) target.delete()
                        if (!tmp.renameTo(target)) {
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                        filesRestored++
                    }
                    zis.closeEntry()
                }
            }

            relinkRestoredBooks(ctx, dataRoot)

            stagingFile.delete()
            File(ctx.cacheDir, EXPORT_ZIP_NAME).delete()

            Handler(Looper.getMainLooper()).postDelayed({
                Process.killProcess(Process.myPid())
            }, 300L)

            Bundle().apply {
                putBoolean(KEY_OK, true)
                putInt(KEY_FILES_RESTORED, filesRestored)
            }
        } catch (t: Throwable) {
            runCatching { stagingFile.delete() }
            errorBundle(t.message ?: "Errore durante il ripristino")
        }
    }

    private fun relinkRestoredBooks(ctx: Context, dataRoot: File) {
        val dbFile = File(dataRoot, "databases/library.db")
        if (!dbFile.isFile) return

        runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.beginTransaction()
                try {
                    val updates = mutableListOf<Pair<String, String>>()
                    db.rawQuery("SELECT id, data FROM books", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val bookId = cursor.getString(0).orEmpty()
                            val raw = cursor.getString(1).orEmpty()
                            if (bookId.isBlank() || raw.isBlank()) continue
                            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                            val bookDir = File(ctx.filesDir, "books/$bookId")
                            val cover = File(bookDir, "cover.jpg")
                            if (cover.isFile) {
                                obj.put("coverPath", cover.absolutePath)
                            } else if (obj.has("coverPath")) {
                                obj.put("coverPath", JSONObject.NULL)
                            }

                            val dirFiles = bookDir.listFiles()?.filter {
                                it.isFile && !it.name.endsWith(".part") && it.name != "cover.jpg"
                            }.orEmpty()
                            val tracks = obj.optJSONArray("tracks")
                            var allReady = tracks != null && tracks.length() > 0
                            if (tracks != null) {
                                for (i in 0 until tracks.length()) {
                                    val t = tracks.optJSONObject(i) ?: continue
                                    val trackId = t.optString("id", "")
                                    val matched = dirFiles.firstOrNull {
                                        it.name.substringBeforeLast('.') == trackId
                                    }
                                    if (matched != null && matched.isFile) {
                                        t.put("uri", Uri.fromFile(matched).toString())
                                        t.put("owned", true)
                                    } else {
                                        val uriStr = t.optString("uri", "")
                                        val validLocal = uriStr.startsWith("file:") &&
                                            Uri.parse(uriStr).path?.let { File(it).isFile } == true
                                        if (!validLocal) {
                                            allReady = false
                                            t.put("uri", "")
                                            t.put("owned", false)
                                        }
                                    }
                                }
                            }
                            obj.put("needsRelink", !allReady)
                            updates += bookId to obj.toString()
                        }
                    }
                    for ((bookId, updatedJson) in updates) {
                        db.execSQL("UPDATE books SET data = ? WHERE id = ?", arrayOf(updatedJson, bookId))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    private fun errorBundle(message: String): Bundle = Bundle().apply {
        putBoolean(KEY_OK, false)
        putString(KEY_ERROR, message)
    }

    private fun dataRootDir(ctx: Context): File =
        ctx.filesDir.parentFile ?: ctx.dataDir

    private fun resolveSafeTarget(root: File, relativePath: String): File {
        require(!relativePath.startsWith("/") && !relativePath.contains("..")) {
            "Percorso ZIP non sicuro: $relativePath"
        }
        val rootCanonical = root.canonicalPath + File.separator
        val target = File(root, relativePath)
        require(target.canonicalPath.startsWith(rootCanonical)) {
            "Zip Slip rilevato: $relativePath"
        }
        return target
    }

    private fun collectBackupEntries(ctx: Context): List<Pair<String, File>> {
        val root = dataRootDir(ctx)
        val result = mutableListOf<Pair<String, File>>()
        for (folder in listOf("databases", "shared_prefs", "files")) {
            val dir = File(root, folder)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relPath = file.relativeTo(root).path.replace('\\', '/')
                    if (!isDefaultExcluded(relPath, file)) {
                        result += relPath to file
                    }
                }
        }
        return result
    }

    private fun isDefaultExcluded(relPath: String, file: File): Boolean {
        val name = file.name
        if (name == STAGING_ZIP_NAME ||
            name == EXPORT_ZIP_NAME ||
            name.startsWith("$EXPORT_ZIP_NAME.") ||
            name.endsWith(".apphub_tmp") ||
            name.endsWith(".part")
        ) {
            return true
        }
        if (relPath.startsWith("databases/")) {
            if (name.startsWith("androidx.work.workdb") ||
                name.startsWith("com.google.android.datatransport") ||
                name.startsWith("com.google.firebase") ||
                name.endsWith("-journal")
            ) {
                return true
            }
        }
        if (relPath.startsWith("shared_prefs/")) {
            if (name.startsWith("androidx.work.util.") ||
                name.startsWith("com.google.android.gms.") ||
                name == "WebViewChromiumPrefs.xml" ||
                name.endsWith(".bak")
            ) {
                return true
            }
        }
        if (relPath.startsWith("files/")) {
            if (name == "generatefid.lock" ||
                name.startsWith("PersistedInstallation") ||
                name == "profileInstalled" ||
                name == "rList" ||
                name == "before-restore.json" ||
                name.endsWith(".lock")
            ) {
                return true
            }
        }
        return false
    }

    private fun compressionLevelFor(relPath: String): Int {
        val ext = relPath.substringAfterLast('.', "").lowercase()
        return if (ext in PRECOMPRESSED_EXTENSIONS) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED
    }

    private fun checkpointWalDatabases(ctx: Context) {
        val dbDir = File(dataRootDir(ctx), "databases")
        if (!dbDir.isDirectory) return
        dbDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith("-journal")) return@forEach
            if (name.startsWith("androidx.work.workdb")) return@forEach
            if (!hasSqliteHeader(file)) return@forEach
            runCatching {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                        cursor.moveToFirst()
                    }
                }
            }
        }
    }

    private fun hasSqliteHeader(file: File): Boolean {
        if (file.length() < 16) return false
        return runCatching {
            val header = ByteArray(16)
            FileInputStream(file).use { it.read(header) }
            String(header, Charsets.US_ASCII) == "SQLite format 3\u0000"
        }.getOrDefault(false)
    }

    private fun enforceTrustedCaller(ctx: Context) {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return

        val pm = ctx.packageManager
        val packages = pm.getPackagesForUid(callingUid)
        if (packages.isNullOrEmpty()) {
            throw SecurityException("Unauthorized backup caller (uid=$callingUid)")
        }

        for (pkg in packages) {
            val certs = getPackageSigningCertSha256s(pm, pkg)
            if (certs.any { it == APPHUB_RELEASE_CERT_SHA256 }) return
        }

        throw SecurityException("Unauthorized backup caller: ${packages.joinToString()}")
    }

    private fun getPackageSigningCertSha256s(pm: PackageManager, packageName: String): List<String> {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }
                val signers = info.signingInfo?.apkContentsSigners.orEmpty()
                signers.map { sha256Hex(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures.orEmpty().map { sha256Hex(it.toByteArray()) }
            }
        }.getOrDefault(emptyList())
    }

    private fun getOwnPackageInfo(ctx: Context): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getPackageInfo(
                ctx.packageName,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }
    }.getOrNull()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val PATH_EXPORT = "export"
        private const val PATH_IMPORT = "import"
        private const val METHOD_PING = "ping"
        private const val METHOD_COMMIT_RESTORE = "commit_restore"
        private const val KEY_PROTOCOL_VERSION = "protocolVersion"
        private const val KEY_PACKAGE_NAME = "packageName"
        private const val KEY_VERSION_NAME = "versionName"
        private const val KEY_VERSION_CODE = "versionCode"
        private const val KEY_ESTIMATED_BYTES = "estimatedBytes"
        private const val KEY_OK = "ok"
        private const val KEY_ERROR = "error"
        private const val KEY_FILES_RESTORED = "filesRestored"
        private const val STAGING_ZIP_NAME = "apphub_restore_staging.zip"
        private const val EXPORT_ZIP_NAME = "apphub_export_staging.zip"
        private const val APPHUB_RELEASE_CERT_SHA256 =
            "f23b8a1655c118054bae73beb0a6aad36b82370c4febdc063d4f720a05e447a4"
        private val AUDIO_EXTENSIONS = setOf("m4b", "m4a", "mp3", "aac", "ogg", "opus", "flac", "wav", "mp4")
        private val PRECOMPRESSED_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi",
            "mp3", "m4a", "m4b", "ogg", "opus", "aac", "flac", "wav", "audio",
            "jpg", "jpeg", "png", "webp", "gif", "zip", "gz",
        )
    }
}
