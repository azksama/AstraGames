package fr.astragames.app.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI

data class JoiPlayCatalogEntry(
    val id: Int,
    val title: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val date: String,
    val badge: String?
)

class JoiPlayCatalogProvider {
    suspend fun fetchRaw(): String = withContext(Dispatchers.IO) {
        val connection = (URI(CATALOG_URL).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AstraGames/1.0 (Android; JoiPlay update check)")
        }
        check(connection.responseCode in 200..299) { "Catalogue JoiPlay inaccessible (${connection.responseCode})." }
        connection.inputStream.bufferedReader().use { it.readText() }.also(::parse)
    }

    fun parse(raw: String): List<JoiPlayCatalogEntry> {
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").trim()
            val version = item.optString("version").trim()
            val link = item.optString("link").trim()
            if (title.isBlank() || version.isBlank() || !link.startsWith("https://")) return@mapNotNull null
            JoiPlayCatalogEntry(
                id = item.optInt("id", index + 1),
                title = title,
                version = version,
                description = item.optString("description").trim(),
                downloadUrl = link,
                date = item.optString("date").trim(),
                badge = item.optString("badge").trim().ifBlank { null }
            )
        }
    }

    companion object {
        const val CATALOG_URL = "https://joiplay.net/assets/json/downloads.json"
        const val CACHE_DURATION_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
