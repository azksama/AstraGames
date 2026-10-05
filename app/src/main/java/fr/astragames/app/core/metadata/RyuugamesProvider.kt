package fr.astragames.app.core.metadata

import fr.astragames.app.settings.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

enum class MetadataSource(val label: String, val domain: String) {
    F95ZONE("F95Zone", "f95zone.to"), RYUUGAMES("Ryuugames", "ryuugames.com");
    fun pageUrl(raw: String): String? = when (this) {
        F95ZONE -> canonicalF95ThreadUrl(raw)
        RYUUGAMES -> canonicalRyuugamesUrl(raw)
    }
    fun searchUrl(title: String, engine: SearchEngine) = engine.webSearchUrl("${title.trim()} $domain")
}

class RyuugamesProvider {
    suspend fun fetch(rawUrl: String): F95ZoneMetadata = withContext(Dispatchers.IO) {
        var url = canonicalRyuugamesUrl(rawUrl) ?: error("Lien Ryuugames invalide.")
        repeat(6) {
            val response = Jsoup.connect(url).userAgent("Mozilla/5.0 (Android) AstraGames/1.0")
                .timeout(20_000).maxBodySize(8 * 1024 * 1024).followRedirects(false).ignoreHttpErrors(true).execute()
            if (response.statusCode() in 300..399) {
                val location = response.header("Location") ?: error("Redirection Ryuugames invalide.")
                url = canonicalRyuugamesUrl(URI(url).resolve(location).toString()) ?: error("Redirection Ryuugames refusée.")
            } else {
                check(response.statusCode() in 200..299) { "Ryuugames inaccessible (${response.statusCode()})." }
                return@withContext parseHtml(response.body(), url)
            }
        }
        error("Trop de redirections Ryuugames.")
    }

    internal fun parseHtml(html: String, pageUrl: String): F95ZoneMetadata {
        val url = canonicalRyuugamesUrl(pageUrl) ?: error("Lien Ryuugames invalide.")
        val document = Jsoup.parse(html, url)
        val body = document.selectFirst(".td-post-content, .entry-content") ?: error("Fiche Ryuugames introuvable.")
        val title = document.selectFirst("h1.entry-title")?.text().orEmpty()
        require(title.isNotBlank()) { "Fiche Ryuugames introuvable." }
        val text = body.wholeText()
        fun field(name: String) = Regex("(?im)^\\s*${Regex.escape(name)}\\s*[:：]\\s*([^\\r\\n]+)")
            .find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it !in setOf("-", "–", "—") }?.take(300)
        val images = (listOfNotNull(document.selectFirst("meta[property=og:image]")?.attr("content")) +
            body.select("img").map { it.attr("data-src").ifBlank { it.attr("src") } })
            .mapNotNull { raw -> runCatching { URI(url).resolve(raw) }.getOrNull() }
            .filter { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.port in setOf(-1, 443) }
            .map { CoverCandidate(it.toString(), it.toString(), "Ryuugames", url, title, 1f) }
            .distinctBy { it.imageUrl }.take(30)
        val heading = body.select("h2,h3,h4,h5").firstOrNull { it.text().trim().equals("DESCRIPTION", true) }
        val description = heading?.let { start ->
            generateSequence(start.nextElementSibling()) { it.nextElementSibling() }
                .takeWhile { it.tagName() !in setOf("h2", "h3", "h4", "h5") }
                .filter { it.tagName() == "p" && it.select("img").isEmpty() }
                .joinToString("\n\n") { it.text() }.trim().take(20_000).ifBlank { null }
        }
        return F95ZoneMetadata(
            sourceUrl = url,
            tags = document.select(".td-tags a, .tags-links a").map { it.text().trim() }
                .filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }.take(150),
            images = images,
            version = field("Version") ?: Regex("[\\[(][vV](\\d[^\\])]{0,40})[\\])]").find(title)?.groupValues?.get(1),
            language = field("Language"), originalTitle = field("Original Title"),
            developer = field("Developer"), description = description
        )
    }
}

internal fun canonicalRyuugamesUrl(raw: String): String? {
    var value = raw.trim()
    repeat(4) {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme.equals("https", true) && uri.host?.lowercase(Locale.ROOT) in setOf("ryuugames.com", "www.ryuugames.com")) {
            if (uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
            val slug = uri.rawPath.orEmpty().trim('/')
            val decoded = runCatching { URLDecoder.decode(slug, "UTF-8") }.getOrNull() ?: return null
            if (!slug.matches(Regex("[a-zA-Z0-9_%.-]+")) || decoded.any { it in "/\\%?#" || it.isISOControl() } || decoded in setOf("tag", "category", "author", "page", "feed", "wp-json", "wp-login.php") || decoded.contains("..")) return null
            return "https://www.ryuugames.com/$slug/"
        }
        val redirect = uri.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') in setOf("url", "q", "u") }
            ?.substringAfter('=', "")?.takeIf(String::isNotBlank) ?: return null
        value = runCatching { URLDecoder.decode(redirect, "UTF-8") }.getOrNull() ?: return null
        if (value.startsWith("a1")) value = runCatching { String(java.util.Base64.getUrlDecoder().decode(value.drop(2)), Charsets.UTF_8) }.getOrNull() ?: return null
    }
    return null
}
