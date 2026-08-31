package it.sottovoce.app.update

import it.sottovoce.app.data.AppJson
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Serializable data class SignedRelease(val payload: String, val signature: String)
@Serializable data class ReleaseInfo(
    val versionName: String, val versionCode: Long, val minSdk: Int,
    val apkUrl: String, val size: Long, val sha256: String, val notes: String,
)
object UpdateVerifier {
    fun verify(document: String, publicKey: String): ReleaseInfo {
        require(document.toByteArray().size <= 64 * 1024) { "Descrittore troppo grande." }
        val envelope = AppJson.decodeFromString<SignedRelease>(document)
        val data = Base64.getDecoder().decode(envelope.payload)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(key); update(data) }
        require(verifier.verify(Base64.getDecoder().decode(envelope.signature))) { "Firma dell’aggiornamento non valida." }
        val release = AppJson.decodeFromString<ReleaseInfo>(data.toString(Charsets.UTF_8))
        require(release.versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?")))
        require(release.versionCode > 0 && release.minSdk >= 26 && release.size in 1..150_000_000)
        require(release.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        val uri = URI(release.apkUrl)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null)
        require(uri.path.startsWith("/Adrianss31/sottovoce/releases/download/") && !uri.path.contains("..") && uri.query == null && uri.fragment == null)
        return release
    }
}
