package fr.astragames.app.core.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class F95ZoneMetadata(
    val sourceUrl: String,
    val tags: List<String>,
    val images: List<CoverCandidate>
)

data class F95ThreadSearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

class F95ZoneProvider {
    suspend fun searchThreads(gameTitle: String): List<F95ThreadSearchResult> = withContext(Dispatchers.IO) {
        val query = "${gameTitle.trim()} f95zone".trim()
        require(gameTitle.isNotBlank()) { "Le nom du jeu est vide." }
        val document = Jsoup.connect("https://html.duckduckgo.com/html/")
            .userAgent("Mozilla/5.0 (Android) AstraGames/1.0")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.7")
            .timeout(20_000)
            .data("q", query)
            .post()
        parseSearch(document)
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

    internal fun parseSearchHtml(html: String): List<F95ThreadSearchResult> =
        parseSearch(Jsoup.parse(html, "https://html.duckduckgo.com/html/"))

    private fun parseSearch(document: Document): List<F95ThreadSearchResult> = document
        .select(".result")
        .mapNotNull { result ->
            val anchor = result.selectFirst("a.result__a") ?: return@mapNotNull null
            val url = unwrapSearchRedirect(anchor.absUrl("href").ifBlank { anchor.attr("href") })
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            val host = uri.host?.lowercase().orEmpty()
            if (uri.scheme != "https" || (host != "f95zone.to" && !host.endsWith(".f95zone.to")) || !uri.path.startsWith("/threads/")) {
                return@mapNotNull null
            }
            F95ThreadSearchResult(
                title = anchor.text().trim().ifBlank { uri.path.substringAfter("/threads/").substringBefore('/') },
                url = URI(uri.scheme, uri.authority, uri.path, null, null).toString(),
                snippet = result.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            )
        }
        .distinctBy { it.url }
        .take(10)

    private fun unwrapSearchRedirect(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        val encoded = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.substringBefore('=') == "uddg" }
            ?.substringAfter('=', "")
            ?.takeIf(String::isNotBlank)
            ?: return value
        return runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
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
            sourceUrl = url.toString(), tags = tags, images = images
        )
    }

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

    private fun validate(value: String): URI = validate(runCatching { URI(value.trim()) }.getOrElse { error("Lien F95Zone invalide.") })

    private fun validate(uri: URI): URI {
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && (host == "f95zone.to" || host.endsWith(".f95zone.to")) && uri.path.startsWith("/threads/")) {
            "Utilisez un lien HTTPS F95Zone commençant par https://f95zone.to/threads/."
        }
        return uri
    }
}
