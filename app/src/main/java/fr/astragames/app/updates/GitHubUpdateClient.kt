package fr.astragames.app.updates

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

internal class GitHubUpdateClient {
    suspend fun latest(debug: Boolean): GitHubRelease {
        val connection = connect("$UPDATE_API/releases/latest", "application/vnd.github+json")
        try {
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024) { "Réponse GitHub trop volumineuse." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return GitHubRelease.parse(JSONObject(String(bytes, Charsets.UTF_8)), debug)
        } finally { connection.disconnect() }
    }

    suspend fun download(release: GitHubRelease, target: File, progress: (Int) -> Unit) {
        val connection = connect("$UPDATE_API/releases/assets/${release.assetId}", "application/octet-stream")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var previous = -1
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= release.bytes && total <= MAX_APK_BYTES) { "Taille de téléchargement inattendue." }
                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    val percent = (total * 100 / release.bytes).toInt()
                    if (percent != previous) { previous = percent; progress(percent) }
                }
                output.fd.sync()
            } }
            require(total == release.bytes && digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "APK incomplet ou empreinte SHA-256 incorrecte." }
        } finally { connection.disconnect() }
    }

    private suspend fun connect(initial: String, accept: String): HttpURLConnection {
        var url = initial
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(allowedUpdateUrl(url)) { "Adresse de téléchargement refusée." }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "AstraGames-Android")
                if (URI(url).host == "api.github.com") {
                    setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
                }
            }
            val status = try { connection.responseCode } catch (error: Exception) { connection.disconnect(); throw error }
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(location != null) { "Redirection GitHub invalide." }
                url = URI(url).resolve(location).toString()
            } else {
                if (status !in 200..299) {
                    connection.disconnect()
                    error(when (status) {
                        401, 403 -> "Accès GitHub temporairement refusé. Réessayez plus tard."
                        404 -> "Aucune release publique disponible pour le moment."
                        429 -> "GitHub limite les requêtes. Réessayez plus tard."
                        else -> "GitHub est indisponible. Réessayez plus tard."
                    })
                }
                return connection
            }
        }
        error("Trop de redirections GitHub.")
    }
}
