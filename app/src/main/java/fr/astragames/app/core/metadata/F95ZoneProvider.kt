package fr.astragames.app.core.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class F95ZoneMetadata(
    val sourceUrl: String,
    val tags: List<String>,
    val images: List<CoverCandidate>
)

class F95ZoneProvider {
    fun yandexSearchUrl(gameTitle: String): String {
        require(gameTitle.isNotBlank()) { "Le nom du jeu est vide." }
        val query = URLEncoder.encode("${gameTitle.trim()} site:f95zone.to", StandardCharsets.UTF_8.name())
        return "https://yandex.com/search/?text=$query"
    }

    suspend fun findThread(gameTitle: String): String? = withContext(Dispatchers.IO) {
        val endpoint = yandexSearchUrl(gameTitle)
        val response = Jsoup.connect(endpoint)
            .userAgent(BROWSER_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .timeout(20_000)
            .maxBodySize(4 * 1024 * 1024)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .execute()
        if (response.statusCode() !in 200..299) return@withContext null
        parseSearchHtml(response.body(), endpoint)
    }

    suspend fun fetch(rawUrl: String): F95ZoneMetadata = withContext(Dispatchers.IO) {
        val url = validate(rawUrl)
        val document = Jsoup.connect(url.toString())
            .userAgent("Mozilla/5.0 (Android) AstraGames/1.0")
            .referrer("https://f95zone.to/")
            .timeout(20_000)
            .maxBodySize(12 * 1024 * 1024)
            .followRedirects(true)
            .get()
        validate(document.location())

        parse(document, url)
    }

    internal fun parseHtml(html: String, baseUrl: String): F95ZoneMetadata {
        val url = validate(baseUrl)
        return parse(Jsoup.parse(html, url.toString()), url)
    }

    private fun parse(document: Document, url: URI): F95ZoneMetadata {
        val tags = document.select("span.js-tagList a.tagItem")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val images = document.select("img.bbImage")
            .mapNotNull { image ->
                val absolute = originalImageUrl(image, url) ?: return@mapNotNull null
                if (!absolute.startsWith("https://")) return@mapNotNull null
                CoverCandidate(
                    imageUrl = absolute,
                    thumbnailUrl = absolute,
                    source = runCatching { URI(absolute).host?.removePrefix("www.") }.getOrNull().orEmpty(),
                    contextUrl = url.toString(),
                    matchedTitle = image.attr("alt").ifBlank { null },
                    confidence = 1f
                )
            }
            .distinctBy { it.imageUrl }
            .take(30)

        return F95ZoneMetadata(
            sourceUrl = canonicalF95ThreadUrl(url.toString()) ?: url.toString(), tags = tags, images = images
        )
    }

    internal fun parseSearchHtml(html: String, baseUrl: String = "https://yandex.com/search/"): String? =
        Jsoup.parse(html, baseUrl).select("a[href]")
            .firstNotNullOfOrNull { extractF95ThreadUrl(it.absUrl("href").ifBlank { it.attr("href") }) }
            ?: Regex("https?://(?:www\\.)?f95zone\\.to/threads/[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
                .find(html)?.value?.let(::extractF95ThreadUrl)

    /**
     * XenForo wraps the displayed preview in a link to the original attachment.
     * The preview attributes are intentionally kept as fallbacks because older
     * F95Zone posts do not always contain that link.
     */
    private fun originalImageUrl(image: Element, pageUrl: URI): String? {
        val linkedOriginal = image.parents()
            .firstOrNull { it.tagName() == "a" && it.hasAttr("href") }
            ?.attr("href")
            ?.takeIf(::looksLikeImage)
        val largestSrcSet = sequenceOf(image.attr("data-srcset"), image.attr("srcset"))
            .filter { it.isNotBlank() }
            .flatMap { srcSet -> srcSet.split(',').asSequence() }
            .mapNotNull { entry ->
                val parts = entry.trim().split(Regex("\\s+"), limit = 2)
                parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let { candidate ->
                    val descriptor = parts.getOrNull(1).orEmpty()
                    val score = descriptor.removeSuffix("w").removeSuffix("x").toFloatOrNull() ?: 1f
                    candidate to score
                }
            }
            .maxByOrNull { it.second }
            ?.first
        val raw = sequenceOf(
            linkedOriginal,
            image.attr("data-url").ifBlank { null },
            largestSrcSet,
            image.attr("data-src").ifBlank { null },
            image.attr("src").ifBlank { null }
        ).filterNotNull().firstOrNull { it.isNotBlank() && !it.startsWith("data:") } ?: return null
        val absolute = runCatching { pageUrl.resolve(raw).toString() }.getOrNull() ?: return null
        return unwrapProxyUrl(absolute)
    }

    private fun looksLikeImage(value: String): Boolean {
        val path = value.substringBefore('?').lowercase()
        return "/attachments/" in path || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".png") || path.endsWith(".webp") || path.endsWith(".gif")
    }

    private fun unwrapProxyUrl(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        if (!uri.path.orEmpty().endsWith("proxy.php")) return value
        val encoded = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.substringBefore('=') == "image" }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }
            ?: return value
        return runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }
            .getOrDefault(value)
            .takeIf { it.startsWith("https://") }
            ?: value
    }

    private fun validate(value: String): URI {
        val canonical = canonicalF95ThreadUrl(value) ?: throw IllegalArgumentException("Lien F95Zone invalide.")
        return validate(URI(canonical))
    }

    private fun validate(uri: URI): URI {
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && (host == "f95zone.to" || host.endsWith(".f95zone.to")) && uri.path.startsWith("/threads/")) {
            "Utilisez un lien HTTPS F95Zone commençant par https://f95zone.to/threads/."
        }
        return uri
    }

    private companion object {
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/127.0 Mobile Safari/537.36"
    }
}

internal fun canonicalF95ThreadUrl(rawValue: String): String? {
    var candidate = rawValue.trim()
    if (candidate.startsWith('/')) candidate = "https://yandex.com$candidate"

    repeat(4) {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (
            uri.scheme.equals("https", ignoreCase = true) &&
            (host == "f95zone.to" || host.endsWith(".f95zone.to")) &&
            uri.path.orEmpty().startsWith("/threads/")
        ) {
            val thread = THREAD_PATH.matchEntire(uri.path.orEmpty())?.groupValues?.get(1) ?: return null
            return "https://f95zone.to/threads/$thread/"
        }

        val redirected = uri.rawQuery.orEmpty().split('&')
            .firstNotNullOfOrNull { parameter ->
                val key = parameter.substringBefore('=')
                if (key !in setOf("q", "url", "u")) return@firstNotNullOfOrNull null
                parameter.substringAfter('=', "").takeIf(String::isNotBlank)
            }
            ?: return null
        candidate = runCatching {
            URLDecoder.decode(redirected, StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null
        if (candidate.startsWith("a1")) {
            candidate = runCatching {
                val encoded = candidate.removePrefix("a1")
                val padding = "=".repeat((4 - encoded.length % 4) % 4)
                String(Base64.getUrlDecoder().decode(encoded + padding), StandardCharsets.UTF_8)
            }.getOrNull() ?: return null
        }
    }
    return null
}

internal fun extractF95ThreadUrl(rawValue: String): String? = canonicalF95ThreadUrl(rawValue)

private val THREAD_PATH = Regex("^/threads/([^/?#]+?\\.\\d+|\\d+)(?:/.*)?$", RegexOption.IGNORE_CASE)
