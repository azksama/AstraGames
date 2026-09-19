package fr.astragames.app.core.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import fr.astragames.app.settings.SearchEngine
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class F95ZoneMetadata(
    val sourceUrl: String,
    val tags: List<String>,
    val images: List<CoverCandidate>,
    val version: String? = null,
    val language: String? = null
)

/** Session membre F95Zone récupérée depuis le navigateur intégré. */
data class F95Session(
    val user: String?,
    val xfUser: String,
    val xfSession: String
)

class F95ZoneProvider {
    @Volatile
    private var session: F95Session? = null

    fun setSession(value: F95Session?) {
        session = value
    }

    private fun applySession(connection: org.jsoup.Connection): org.jsoup.Connection =
        session?.let { connection.cookie("xf_user", it.xfUser).cookie("xf_session", it.xfSession) } ?: connection
    fun searchUrl(gameTitle: String, searchEngine: SearchEngine = SearchEngine.YANDEX): String {
        require(gameTitle.isNotBlank()) { "Le nom du jeu est vide." }
        return searchEngine.webSearchUrl("${gameTitle.trim()} f95zone.to")
    }

    fun yandexSearchUrl(gameTitle: String): String = searchUrl(gameTitle, SearchEngine.YANDEX)

    suspend fun findThread(gameTitle: String, searchEngine: SearchEngine = SearchEngine.YANDEX): String? = withContext(Dispatchers.IO) {
        val endpoint = searchUrl(gameTitle, searchEngine)
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
        val document = fetchDocument(url.toString())
        parse(document, url)
    }

    /** Récupère uniquement le numéro de version affiché sur le thread (pour la détection de mises à jour). */
    suspend fun fetchVersion(rawUrl: String): String? = withContext(Dispatchers.IO) {
        val url = validate(rawUrl)
        val document = fetchDocument(url.toString(), maxBodySize = 4 * 1024 * 1024)
        extractField(document, "Version")
    }

    private suspend fun fetchDocument(rawUrl: String, maxBodySize: Int = 12 * 1024 * 1024): Document {
        var url = validate(rawUrl).toString()
        repeat(6) {
            val response = applySession(Jsoup.connect(url))
                .userAgent("Mozilla/5.0 (Android) AstraGames/1.0")
                .referrer("https://f95zone.to/")
                .timeout(20_000)
                .maxBodySize(maxBodySize)
                .followRedirects(false)
                .ignoreHttpErrors(true)
                .execute()
            if (response.statusCode() in 300..399) {
                val location = response.header("Location") ?: error("Redirection F95Zone invalide.")
                url = validate(URI(url).resolve(location).toString()).toString()
            } else {
                check(response.statusCode() in 200..299) { "F95Zone inaccessible (${response.statusCode()})." }
                val document = response.parse()
                if (session != null && document.title().contains("Log in", ignoreCase = true)) {
                    error("Session F95Zone expiree ou invalide — reconnectez-vous.")
                }
                return document
            }
        }
        error("Trop de redirections F95Zone.")
    }

    internal fun parseHtml(html: String, baseUrl: String): F95ZoneMetadata {
        val url = validate(baseUrl)
        return parse(Jsoup.parse(html, url.toString()), url)
    }

    private fun parse(document: Document, url: URI): F95ZoneMetadata {
        val tags = document.select("span.js-tagList a.tagItem")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
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
            sourceUrl = canonicalF95ThreadUrl(url.toString()) ?: url.toString(),
            tags = tags,
            images = images,
            version = extractField(document, "Version"),
            language = extractField(document, "Language")
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

    /** Extrait un champ « Version » / « Language » du premier message d’un thread XenForo. */
    private fun extractField(document: Document, label: String): String? {
        val message = document.selectFirst(".message-inner") ?: document.body()
        val body = message.selectFirst(".bbWrapper") ?: message
        val elements = body.select("b, strong, dt")
        for (element in elements) {
            if (!element.text().trim().removeSuffix(":").trim().equals(label, ignoreCase = true)) continue
            if (element.tagName() == "dt") {
                element.nextElementSibling()?.takeIf { it.tagName() == "dd" }
                    ?.text()?.fieldValue()?.let { return it }
                continue
            }
            val value = StringBuilder()
            var sibling = element.nextSibling()
            while (sibling != null && value.length < 80) {
                when (val node = sibling) {
                    is org.jsoup.nodes.TextNode -> value.append(node.text())
                    is Element -> {
                        if (node.tagName() in setOf("b", "strong", "dt") || node.isBlock) break
                        if (node.tagName() == "br") {
                            if (value.toString().fieldValue() != null) break
                        } else value.append(node.text())
                    }
                }
                sibling = sibling.nextSibling()
            }
            value.toString().fieldValue()?.let { return it }
        }
        return null
    }

    private fun String.fieldValue(): String? = trim().removePrefix(":").trim()
        .takeIf { it.isNotBlank() && it.length < 80 }

    private fun looksLikeImage(value: String): Boolean {
        val path = value.substringBefore('?').lowercase(java.util.Locale.ROOT)
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
        val host = uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
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
        val host = uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
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
