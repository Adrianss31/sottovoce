package it.sottovoce.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import it.sottovoce.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateManager(private val context: Context) {
    private fun connection(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            require(current.protocol == "https" && current.userInfo == null && current.port == -1) { "Indirizzo di aggiornamento non sicuro." }
            require(current.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Host di aggiornamento non autorizzato." }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Sottovoce/${BuildConfig.VERSION_NAME}")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("Reindirizzamento non valido.")
                connection.disconnect(); current = URL(current, location)
            } else {
                if (code != 200) {
                    connection.disconnect()
                    if (code == 404) error("Nessun aggiornamento pubblicato al momento.")
                    error("Server aggiornamenti non disponibile (HTTP $code).")
                }
                return connection
            }
        }
        error("Troppi reindirizzamenti.")
    }
    suspend fun check(): ReleaseInfo = withContext(Dispatchers.IO) {
        require(!BuildConfig.DEBUG) { "Usa l’APK release per gli aggiornamenti. La versione debug ha un’identità separata." }
        val c = connection(BuildConfig.UPDATE_MANIFEST_URL)
        val document = try {
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= 64 * 1024) { "Descrittore troppo grande." }; out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally { c.disconnect() }
        UpdateVerifier.verify(document, BuildConfig.UPDATE_PUBLIC_KEY)
    }
    suspend fun download(info: ReleaseInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        require(info.versionCode > BuildConfig.VERSION_CODE && info.minSdk <= Build.VERSION.SDK_INT) { "Versione non compatibile." }
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        require(folder.usableSpace > info.size + 20_000_000L) { "Spazio insufficiente per l’aggiornamento." }
        val file = File(folder, "update.apk")
        val partial = File(folder, "update.part")
        file.delete(); partial.delete()
        try {
            val c = connection(info.apkUrl)
            try {
                c.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024); var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        count += n; require(count <= info.size) { "Dimensione del pacchetto non valida." }
                        output.write(buffer, 0, n); progress(count.toFloat() / info.size)
                    }
                    output.fd.sync()
                } }
            } finally { c.disconnect() }
            verifyApk(partial, info)
            require(partial.renameTo(file)) { "Impossibile salvare l’aggiornamento." }
            file
        } catch (e: Exception) { partial.delete(); file.delete(); throw e }
    }
    @Suppress("DEPRECATION")
    fun verifyApk(file: File, info: ReleaseInfo) {
        require(file.length() == info.size) { "Aggiornamento incompleto." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(128 * 1024); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        require(digest.digest().joinToString("") { "%02x".format(it) }.equals(info.sha256, true)) { "Il pacchetto non supera la verifica di integrità." }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags)) { "APK non valido." }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "Il pacchetto appartiene a un’altra app." }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(code == info.versionCode && code > BuildConfig.VERSION_CODE) { "Versione del pacchetto non valida." }
        val incoming = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val current = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        require(!incoming.isNullOrEmpty() && !current.isNullOrEmpty() && incoming.map { it.toCharsString() }.toSet() == current.map { it.toCharsString() }.toSet()) { "Firma APK diversa da quella dell’app installata." }
    }
    fun install(file: File): Intent {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
