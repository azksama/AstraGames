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
    val language: String? = null,
    val originalTitle: String? = null,
    val developer: String? = null,
    val description: String? = null,
    val title: String? = null,
    val imagesRequireLogin: Boolean = false,
    val sessionExpired: Boolean = false
)

/** Session membre F95Zone récupérée depuis le navigateur intégré. */
data class F95Session(
    val user: String?,
    val xfUser: String,
    val xfSession: String
)

class F95ZoneProvider(private val browserCookies: (String) -> String? = { null }) {
    @Volatile
    private var session: F95Session? = null

    fun setSession(value: F95Session?) {
        session = value
    }

    /** Recomputed for each request, including image redirects. Never send member cookies off-site. */
    internal fun requestHeaders(url: String, referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", BROWSER_USER_AGENT)
        referer?.takeIf { it.startsWith("https://") }?.let { put("Referer", it) }
        if (isF95Url(url)) {
            put("Referer", referer?.takeIf(::isF95Url) ?: "https://f95zone.to/")
            val cookies = linkedMapOf<String, String>()
            session?.let { if (it.xfUser.isNotBlank()) cookies["xf_user"] = it.xfUser; cookies["xf_session"] = it.xfSession }
            browserCookies(url)?.split(';')?.forEach { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name in setOf("xf_user", "xf_session", "cf_clearance") && value.isNotBlank()) cookies[name] = value
            }
            if (cookies.isNotEmpty()) put("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
    }

    internal fun isF95Url(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            (host == "f95zone.to" || host.endsWith(".f95zone.to"))
    }.getOrDefault(false)
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
            val response = Jsoup.connect(url)
                .headers(requestHeaders(url))
                .timeout(20_000)
                .maxBodySize(maxBodySize)
                .followRedirects(false)
                .ignoreHttpErrors(true)
                .execute()
            if (response.statusCode() in 300..399) {
                val location = response.header("Location") ?: error("Redirection F95Zone invalide.")
                val destination = URI(url).resolve(location).toString()
                check(!destination.contains("/login", ignoreCase = true)) { "Connectez-vous à F95Zone pour accéder à cette fiche." }
                url = validate(destination).toString()
            } else {
                check(response.statusCode() in 200..299) {
                    if (response.statusCode() in setOf(401, 403)) "F95Zone demande une connexion ou une vérification. Ouvrez le site dans Astra, puis réessayez."
                    else "F95Zone inaccessible (${response.statusCode()})."
                }
                val document = response.parse()
                check(!document.title().contains("Log in", ignoreCase = true) && document.selectFirst("form[action*=login]") == null) {
                    "Connectez-vous à F95Zone pour accéder à cette fiche."
                }
                check(document.selectFirst(".message-body, .message-inner, .bbWrapper") != null) {
                    "F95Zone n’a pas renvoyé la fiche. Ouvrez le site dans Astra, puis réessayez."
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
        val body = openingPost(document)
        val images = body.select("img")
            .filterNot { it.hasClass("smilie") || it.hasClass("emoji") || it.parents().any { parent -> parent.hasClass("bbCodeBlock--quote") || parent.hasClass("message-signature") || parent.hasClass("avatar") } }
            .mapNotNull { image ->
                val absolute = originalImageUrl(image, url) ?: return@mapNotNull null
                val preview = previewImageUrl(image, url) ?: absolute
                CoverCandidate(
                    imageUrl = absolute,
                    thumbnailUrl = preview,
                    source = runCatching { URI(absolute).host?.removePrefix("www.") }.getOrNull().orEmpty(),
                    contextUrl = url.toString(),
                    matchedTitle = image.attr("alt").ifBlank { null },
                    confidence = 1f,
                    fallbackUrls = listOf(preview, image.attr("data-src"), image.attr("src"))
                        .mapNotNull { resolveImageUrl(it, url) }.distinct().filter { it != absolute }
                )
            }
            .distinctBy { it.imageUrl }
            .take(30)

        return F95ZoneMetadata(
            sourceUrl = canonicalF95ThreadUrl(url.toString()) ?: url.toString(),
            tags = tags,
            images = images,
            version = extractField(document, "Version"),
            language = extractField(document, "Language"),
            originalTitle = extractField(document, "Original title"),
            developer = extractField(document, "Developer"),
            description = extractDescription(body),
            title = document.selectFirst("h1.p-title-value")?.clone()?.apply { select(".label, .labelLink, .label-append").remove() }
                ?.text()?.trim()?.takeIf(String::isNotBlank),
            imagesRequireLogin = body.select(".messageHide--attach").isNotEmpty(),
            sessionExpired = session != null && document.selectFirst("html[data-logged-in=false]") != null
        )
    }

    private fun openingPost(document: Document): Element {
        val message = document.selectFirst(".message--post, .message-inner, .message-body, .bbWrapper") ?: document.body()
        return message.selectFirst(".bbWrapper") ?: message
    }

    private fun extractDescription(body: Element): String? {
        val text = body.clone().apply { select(".bbCodeBlock--quote, .bbCodeSpoiler, .messageHide, .message-signature, script, style").remove() }.wholeText()
        return Regex("(?is)(?:^|\\n)\\s*(?:Overview|Description)\\s*:\\s*(.*?)(?=\\n\\s*(?:Developer|Version|Language|Thread Updated|Release Date|Installation|Downloads?|Screenshots?|Changelog|OS|Censored)\\s*:|$)")
            .find(text)?.groupValues?.get(1)?.trim()?.take(20_000)?.takeIf(String::isNotBlank)
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
        return sequenceOf(linkedOriginal, image.attr("data-url"), previewImageUrl(image, pageUrl))
            .filterNotNull().mapNotNull { resolveImageUrl(it, pageUrl) }.firstOrNull()
    }

    private fun previewImageUrl(image: Element, pageUrl: URI): String? {
        val pictureSources = image.parent()?.takeIf { it.tagName() == "picture" }?.select("source").orEmpty()
            .asSequence().flatMap { sequenceOf(it.attr("data-srcset"), it.attr("srcset")) }
        val largestSrcSet = sequenceOf(image.attr("data-srcset"), image.attr("srcset")).plus(pictureSources)
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
        return sequenceOf(
            largestSrcSet,
            image.attr("data-src").ifBlank { null },
            image.attr("src").ifBlank { null },
            image.attr("data-url").ifBlank { null }
        ).filterNotNull().mapNotNull { resolveImageUrl(it, pageUrl) }.firstOrNull()
    }

    private fun resolveImageUrl(raw: String, pageUrl: URI): String? {
        if (raw.isBlank()) return null
        val uri = runCatching { pageUrl.resolve(raw.trim()) }.getOrNull() ?: return null
        return uri.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.port in setOf(-1, 443) }?.toString()
    }

    /** Extrait un champ « Version » / « Language » du premier message d’un thread XenForo. */
    private fun extractField(document: Document, label: String): String? {
        val body = openingPost(document)
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
            uri.path.orEmpty().startsWith("/threads/") && uri.userInfo == null && uri.port in setOf(-1, 443)
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
