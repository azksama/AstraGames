package fr.astragames.app.core.metadata

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import fr.astragames.app.data.local.GameEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class CoverCandidate(
    val imageUrl: String,
    val thumbnailUrl: String = imageUrl,
    val source: String,
    val contextUrl: String? = null,
    val matchedTitle: String?,
    val confidence: Float
)

interface CoverProvider {
    suspend fun search(game: GameEntity): List<CoverCandidate>
}

class CompositeCoverProvider(private val providers: List<CoverProvider>) : CoverProvider {
    override suspend fun search(game: GameEntity): List<CoverCandidate> = providers
        .flatMap { provider -> runCatching { provider.search(game) }.getOrDefault(emptyList()) }
        .distinctBy(CoverCandidate::imageUrl)
        .sortedByDescending(CoverCandidate::confidence)
}

class GoogleCoverProvider(
    private val context: Context? = null
) : CoverProvider {
    val configured: Boolean = true

    fun searchUrl(game: GameEntity): String {
        val engine = game.engine.lowercase().replace('_', ' ')
        val query = "${game.title} $engine game"
        return "https://www.google.com/search?udm=2&safe=active&hl=fr&q=${encoded(query)}"
    }

    override suspend fun search(game: GameEntity): List<CoverCandidate> = withContext(Dispatchers.IO) {
        val endpoint = searchUrl(game)
        val response = Jsoup.connect(endpoint)
            .userAgent(BROWSER_USER_AGENT)
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.7")
            .header("Accept", "text/html,application/xhtml+xml")
            .timeout(20_000)
            .maxBodySize(8 * 1024 * 1024)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .execute()
        check(response.statusCode() in 200..299) { "Google Images est inaccessible (${response.statusCode()})." }
        val body = response.body()
        parseHtml(body, endpoint).ifEmpty {
            if (body.contains("/httpservice/retry/enablejs") || body.contains("enablejs")) {
                error("Google demande un navigateur interactif pour afficher les images.")
            }
            error("Google n'a renvoyé aucune image exploitable.")
        }
    }

    internal fun parseHtml(html: String, searchUrl: String = "https://www.google.com/search?tbm=isch"): List<CoverCandidate> {
        val document = Jsoup.parse(html, searchUrl)
        val results = linkedMapOf<String, CoverCandidate>()
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            val original = queryParameter(href, "imgurl") ?: return@forEach
            if (!isRemoteResult(original)) return@forEach
            val context = queryParameter(href, "imgrefurl")
            val thumbnail = anchor.selectFirst("img")?.let { image ->
                sequenceOf(image.attr("data-src"), image.attr("src"))
                    .firstOrNull { it.startsWith("https://") }
            }
            results.putIfAbsent(
                original,
                candidate(original, thumbnail ?: original, context, anchor.selectFirst("img")?.attr("alt"), results.size)
            )
        }

        val unescaped = html
            .replace("\\u003d", "=").replace("\\u0026", "&")
            .replace("\\u002f", "/").replace("\\/", "/")
        URL_PATTERN.findAll(unescaped).map { it.value.trimEnd(',', ';', ')') }
            .map { org.jsoup.parser.Parser.unescapeEntities(it, false) }
            .filter(::isRemoteResult)
            .filter(::looksLikeImageUrl)
            .forEach { original ->
                results.putIfAbsent(original, candidate(original, original, null, null, results.size))
            }
        return results.values.take(10)
    }

    private fun candidate(
        imageUrl: String,
        thumbnailUrl: String,
        contextUrl: String?,
        title: String?,
        index: Int
    ) = CoverCandidate(
        imageUrl = imageUrl,
        thumbnailUrl = thumbnailUrl,
        source = hostOf(contextUrl.orEmpty().ifBlank { imageUrl }),
        contextUrl = contextUrl,
        matchedTitle = title?.ifBlank { null },
        confidence = 1f - (index.coerceAtMost(9) * .05f)
    )

    private fun queryParameter(rawHref: String, name: String): String? {
        val href = org.jsoup.parser.Parser.unescapeEntities(rawHref, false)
        val rawQuery = runCatching {
            URI(if (href.startsWith('/')) "https://www.google.com$href" else href).rawQuery
        }.getOrNull().orEmpty()
        return rawQuery.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")?.takeIf(String::isNotBlank)
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull() }
    }

    private fun isRemoteResult(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        return uri.scheme == "https" && host.isNotBlank() &&
            host != "google.com" && !host.endsWith(".google.com") &&
            host != "gstatic.com" && !host.endsWith(".gstatic.com")
    }

    private fun looksLikeImageUrl(value: String): Boolean {
        val path = runCatching { URI(value).path.lowercase() }.getOrDefault("")
        return IMAGE_EXTENSIONS.any { path.endsWith(it) } ||
            value.contains("format=image", true) || value.contains("/image/", true)
    }

    suspend fun download(candidate: CoverCandidate): Uri = withContext(Dispatchers.IO) {
        val appContext = checkNotNull(context) { "Contexte Android absent pour télécharger la jaquette." }
        val connection = (URI(candidate.imageUrl).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            candidate.contextUrl?.takeIf { it.startsWith("https://") }?.let { setRequestProperty("Referer", it) }
        }
        val status = connection.responseCode
        check(status in 200..299) { "L'image sélectionnée est inaccessible ($status)." }
        val type = connection.contentType.orEmpty().substringBefore(';')
        check(type.startsWith("image/")) { "Le résultat sélectionné n'est pas une image." }
        check(connection.contentLengthLong <= MAX_DOWNLOAD_BYTES || connection.contentLengthLong < 0) {
            "Cette image dépasse 20 Mo."
        }
        val directory = File(appContext.cacheDir, "cover-imports").apply { mkdirs() }
        val extension = when (type) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val output = File(directory, "${UUID.randomUUID()}.$extension")
        connection.inputStream.use { input ->
            output.outputStream().use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= MAX_DOWNLOAD_BYTES) { "Cette image dépasse 20 Mo." }
                    stream.write(buffer, 0, read)
                }
            }
        }
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", output)
    }

    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    private fun hostOf(value: String): String = runCatching { URI(value).host?.removePrefix("www.") }.getOrNull().orEmpty()

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/127.0 Mobile Safari/537.36"
        val URL_PATTERN = Regex("""https?://[^\s"'<>\\\[\]]+""", RegexOption.IGNORE_CASE)
        val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif")
    }
}
