package fr.astragames.app.core.metadata

import fr.astragames.app.core.search.levenshtein
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.text.Normalizer
import kotlin.math.max

data class VndbMetadata(
    val id: String,
    val title: String,
    val originalTitle: String?,
    val description: String?,
    val developers: List<String>,
    val coverUrl: String?,
    val f95Url: String?
)

class VndbProvider {
    suspend fun search(gameTitle: String): VndbMetadata? = withContext(Dispatchers.IO) {
        if (gameTitle.isBlank()) return@withContext null
        val request = JSONObject()
            .put("filters", JSONArray().put("search").put("=").put(gameTitle.trim()))
            .put("fields", "title,alttitle,aliases,description,image{url,thumbnail},developers{name},extlinks{url,label,name}")
            .put("sort", "searchrank")
            .put("results", 5)
        var body: String? = null
        var attempts = 0
        while (body == null && attempts < 2) {
            attempts++
            val connection = (URI("https://api.vndb.org/kana/vn").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AstraGames/1.0 (Android; metadata enrichment)")
            }
            try {
                connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                when {
                    status in 200..299 -> body = connection.inputStream.bufferedReader().use { it.readText() }
                    status == 429 -> if (attempts < 2) delay(2_000) else Log.w("AstraMetadata", "VNDB limite de requêtes atteinte pour « $gameTitle »")
                    else -> Log.w("AstraMetadata", "VNDB HTTP $status pour « $gameTitle »")
                }
            } catch (error: Exception) {
                Log.w("AstraMetadata", "VNDB inaccessible pour « $gameTitle »", error)
            } finally {
                connection.disconnect()
            }
        }
        body?.let { parseResponse(it, gameTitle) }
    }

    internal fun parseResponse(json: String, requestedTitle: String): VndbMetadata? {
        val results = runCatching { JSONObject(json).getJSONArray("results") }.getOrNull() ?: return null
        val candidates = (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.let { it to matchScore(requestedTitle, it) }
        }
        val selected = candidates.maxByOrNull { it.second }?.takeIf { it.second >= MIN_MATCH_SCORE }?.first ?: return null
        val image = selected.optJSONObject("image")
        val developers = selected.optJSONArray("developers").objects()
            .mapNotNull { it.optString("name").trim().ifBlank { null } }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        val f95 = selected.optJSONArray("extlinks").objects()
            .asSequence().map { it.optString("url") }
            .plus(sequenceOf(selected.optString("description")))
            .mapNotNull(::findF95InText)
            .firstOrNull()
        return VndbMetadata(
            id = selected.optString("id"),
            title = selected.optString("title").trim(),
            originalTitle = selected.optNullableString("alttitle"),
            description = selected.optNullableString("description")?.let(::cleanDescription),
            developers = developers,
            coverUrl = image?.optNullableString("url") ?: image?.optNullableString("thumbnail"),
            f95Url = f95
        )
    }

    private fun matchScore(requested: String, item: JSONObject): Double {
        val names = buildList {
            add(item.optString("title"))
            item.optNullableString("alttitle")?.let(::add)
            val aliases = item.optJSONArray("aliases")
            if (aliases != null) for (index in 0 until aliases.length()) aliases.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
        return names.maxOfOrNull { similarity(normalize(requested), normalize(it)) } ?: 0.0
    }

    private fun similarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (left.length >= 5 && (left in right || right in left)) return .9
        val leftTokens = left.split(' ').filter(String::isNotBlank).toSet()
        val rightTokens = right.split(' ').filter(String::isNotBlank).toSet()
        val union = leftTokens union rightTokens
        val tokenScore = if (union.isEmpty()) 0.0 else (leftTokens intersect rightTokens).size.toDouble() / union.size
        val editScore = 1.0 - levenshtein(left, right).toDouble() / max(left.length, right.length)
        return max(tokenScore, editScore)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("\\[[^\\]]+\\]"), " ")
        .replace(Regex("\\b(v(?:er(?:sion)?)?\\s*)?\\d+(?:[._-]\\d+)+\\b"), " ")
        // Keep every Unicode letter/number so Cyrillic, kana and Han titles remain searchable.
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()


    private fun cleanDescription(value: String): String = value
        .replace(Regex("\\[url=[^\\]]+\\]([^\\[]*)\\[/url\\]", RegexOption.IGNORE_CASE), "\$1")
        .replace(Regex("\\[/?[a-z]+(?:=[^\\]]+)?\\]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun findF95InText(value: String): String? = URL_PATTERN.findAll(value)
        .mapNotNull { canonicalF95ThreadUrl(it.value.trimEnd('.', ',', ';', ')', ']')) }
        .firstOrNull()

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).trim().ifBlank { null }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).mapNotNull(::optJSONObject)

    private companion object {
        const val MIN_MATCH_SCORE = .82
        val URL_PATTERN = Regex("https?://[^\\s<>\\]]+", RegexOption.IGNORE_CASE)
    }
}
