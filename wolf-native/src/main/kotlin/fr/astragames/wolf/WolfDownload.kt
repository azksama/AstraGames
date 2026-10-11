package fr.astragames.wolf

import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class WolfDownloadProfile { W2, W3 }

/** An unsupported command is never translated into a successful network completion. */
class WolfDownloadUnsupported(message: String) : IllegalArgumentException(message)

data class WolfDownloadDestination(val path: String, val directory: Boolean)

/**
 * Opcode260 after the VM has expanded its string operands. This value performs no IO.
 * REA W2 46bd10/4bfe40; W3 a2a300/9a3bd0/9a4f90. The host owns completion,
 * Sys48 (-1/0/1), event waiting, atomic file writes, and updating a variable on success.
 */
class WolfDownloadRequest private constructor(
    val url: String,
    val destination: WolfDownloadDestination?,
    val variable: Int?,
    val waitForCompletion: Boolean,
    val urlEncoding: WolfEncoding,
    val responseEncoding: WolfEncoding,
    val postEncoding: WolfEncoding?,
    val contentType: String?,
    val verifyTls: Boolean,
    urlBytes: ByteArray,
    postBytes: ByteArray?,
) {
    private val encodedUrl = urlBytes.copyOf()
    private val encodedPost = postBytes?.copyOf()
    val urlBytes: ByteArray get() = encodedUrl.copyOf()
    val postBytes: ByteArray? get() = encodedPost?.copyOf()
    val method: String get() = if (encodedPost == null) "GET" else "POST"

    /** Uses the requested URL, not a redirect or a Content-Disposition filename. */
    fun filePath(): String? {
        val output = destination ?: return null
        if (!output.directory) return output.path
        val leaf = URI(url).rawPath.orEmpty().substringAfterLast('/').ifEmpty { "index.htm" }
        // Percent escapes stay literal filename bytes. They are never URL-decoded into a path.
        require(leaf.length <= MAX_PATH && leaf != "." && !leaf.contains("..") &&
            leaf.none { it in "\\:*?\"<>|" || it.code < 32 }) { "Nom du fichier téléchargé invalide" }
        return (output.path + leaf).also { require(it.length <= MAX_PATH) { "Chemin téléchargé trop long" } }
    }

    /** Native conversion stops at NUL, then strips one BOM from the internal UTF8 string. */
    fun decodeResponse(bytes: ByteArray): String = decode(bytes, responseEncoding)

    companion object {
        const val MAX_PATH = 4096
        const val MAX_TEXT_BYTES = 16 * 1024 * 1024
        const val MAX_TEXT_CHARACTERS = 1_000_000
        private const val EXTENSIONS = 0x176 // URL UTF8/encode; POST on/UTF8/encode; response UTF8.
        private val cp932 = Charset.forName("windows-31j")
        private const val TLS = "<<VERIFY_TLS>>"
        private const val CONTENT = "<<CONTENT>>"
        private const val CONTENT_END = "<<CONTENT_END>>"
        private const val DEFAULT_CONTENT = "application/x-www-form-urlencoded"
        private val forbiddenPathParts = listOf("//", "\\\\", "..", "./", ".\\")

        /**
         * [encodingMode] is the native DWORD f51360: zero disables the W3 UTF8 options.
         * The analysed initializer sets it to -1; no Game.dat binding has been established.
         * The engine's ordinary legacy codepage is CP932. Other ACPs are outside this API.
         */
        fun parse(
            arguments: IntArray,
            expandedStrings: List<String>,
            profile: WolfDownloadProfile,
            commonId: Int = -1,
            encodingMode: Int = -1,
        ): WolfDownloadRequest {
            require(arguments.size in 3..4 && expandedStrings.size in 2..3) { "Opérandes260 incomplets ou non portés" }
            val flags = arguments[1]
            require(flags and 3 == flags) { "Drapeaux260 non portés : $flags" }
            val extended = arguments.getOrElse(3) { 0 }
            if (profile == WolfDownloadProfile.W2 && extended != 0)
                throw WolfDownloadUnsupported("Les extensions UTF8/POST de260 ne sont pas portées pour W2")
            require(extended and EXTENSIONS == extended) { "Extensions260 non portées : $extended" }
            val rawVariable = arguments[2]
            if (rawVariable < 0) throw WolfDownloadUnsupported(
                "Alias260 non porté : $rawVariable (navigateur, mode Pro ou opération spéciale)",
            )
            val variable = when (rawVariable) {
                0 -> null
                in 1_600_000..1_699_999 -> {
                    require(commonId in 0..9999) { "Contexte CommonSelf260 absent ou hors capacité" }
                    15_000_000 + commonId * 100 + rawVariable % 100
                }
                in 3_000_000..3_999_999, in 15_000_000..15_999_999 -> rawVariable
                else -> throw WolfDownloadUnsupported("Destination de chaîne260 non portée : $rawVariable")
            }
            val textOnly = flags and 1 != 0
            require(!textOnly || variable != null) { "Réception260 en chaîne sans variable cible" }
            val modern = profile == WolfDownloadProfile.W3 && encodingMode != 0
            val urlEncoding = if (modern && extended and 2 != 0) WolfEncoding.UTF8 else WolfEncoding.CP932
            val responseEncoding = if (modern && extended and 0x100 != 0) WolfEncoding.UTF8 else WolfEncoding.CP932
            var source = boundedText(expandedStrings[0], MAX_PATH)
            val verifyTls = source.startsWith(TLS)
            if (verifyTls) {
                if (profile != WolfDownloadProfile.W3) throw WolfDownloadUnsupported("Marqueur TLS260 non porté pour W2")
                source = source.substring(TLS.length)
            }
            var urlBytes = encode(source, urlEncoding)
            if (extended and 4 != 0) {
                val question = urlBytes.indexOf('?'.code.toByte())
                if (question >= 0) urlBytes = urlBytes.copyOfRange(0, question + 1) +
                    formEncode(urlBytes.copyOfRange(question + 1, urlBytes.size))
            }
            require(urlBytes.size <= MAX_PATH) { "URL260 encodée trop longue" }
            val url = decodeWhole(urlBytes, urlEncoding)
            val uri = try { URI(url) } catch (e: Exception) { throw WolfFormatException("URL260 invalide", e) }
            if (uri.scheme?.lowercase() !in setOf("http", "https"))
                throw WolfDownloadUnsupported("Protocole260 non porté : ${uri.scheme}")
            require(uri.host?.isNotEmpty() == true && uri.rawUserInfo == null && uri.fragment == null) {
                "Hôte, identifiants ou fragment URL260 hors capacité"
            }
            val path = destination(expandedStrings[1]) // Native validates it even in text-only mode.
            var post: ByteArray? = null
            var postEncoding: WolfEncoding? = null
            var contentType: String? = null
            if (extended and 0x10 != 0) {
                require(expandedStrings.size == 3) { "Corps POST260 absent" }
                postEncoding = if (modern && extended and 0x20 != 0) WolfEncoding.UTF8 else WolfEncoding.CP932
                post = encode(boundedText(expandedStrings[2], MAX_TEXT_BYTES), postEncoding)
                if (extended and 0x40 != 0) post = formEncode(post)
                // Handler encoding occurs BEFORE the worker scans the content-type marker.
                val text = decodeWhole(post, postEncoding)
                val begin = text.indexOf(CONTENT)
                val end = if (begin >= 0) text.indexOf(CONTENT_END, begin + CONTENT.length) else -1
                contentType = DEFAULT_CONTENT
                if (end >= 0) {
                    contentType = text.substring(begin + CONTENT.length, end)
                    require(contentType.length in 1..256 && contentType.all { it.code in 32..126 }) {
                        "Type de contenu POST260 invalide"
                    }
                    // Native drops any prefix before the marker, not just the marker itself.
                    post = encode(text.substring(end + CONTENT_END.length), postEncoding)
                }
                require(post.size <= MAX_TEXT_BYTES) { "Corps POST260 trop volumineux" }
            }
            return WolfDownloadRequest(url, if (textOnly) null else path, variable, flags and 2 != 0,
                urlEncoding, responseEncoding, postEncoding, contentType, verifyTls, urlBytes, post)
                .also { it.filePath() } // Reject derived names before the host starts a download.
        }

        /** The caller must preserve the previous variable if decoding fails. */
        fun decode(bytes: ByteArray, encoding: WolfEncoding): String {
            require(bytes.size <= MAX_TEXT_BYTES) { "Réponse texte260 trop volumineuse" }
            val end = bytes.indexOf(0).takeIf { it >= 0 } ?: bytes.size
            // 9a6cd0 strips EF BB BF from the internal UTF8 string after any
            // legacy-codepage conversion, before assigning the target variable.
            return decodeWhole(bytes.copyOfRange(0, end), encoding).removePrefix("\uFEFF").also {
                require(it.length <= MAX_TEXT_CHARACTERS) { "Réponse texte260 hors capacité de chaîne" }
            }
        }

        /** REA 426e20: space=>+, preserve ASCII alnum and *.-_&=, otherwise %HH uppercase. */
        fun formEncode(bytes: ByteArray): ByteArray {
            require(bytes.size <= MAX_TEXT_BYTES) { "Texte à encoder260 trop volumineux" }
            val output = java.io.ByteArrayOutputStream(bytes.size)
            val digits = "0123456789ABCDEF"
            for (raw in bytes) {
                val value = raw.toInt() and 255
                val plain = value in 48..57 || value in 65..90 || value in 97..122 ||
                    value == 42 || value == 46 || value == 45 || value == 95 || value == 38 || value == 61
                require(output.size() <= MAX_TEXT_BYTES - if (plain || value == 32) 1 else 3) { "Texte encodé260 trop volumineux" }
                when {
                    plain -> output.write(value)
                    value == 32 -> output.write(43)
                    else -> { output.write(37); output.write(digits[value ushr 4].code); output.write(digits[value and 15].code) }
                }
            }
            return output.toByteArray()
        }

        fun destination(value: String): WolfDownloadDestination {
            val path = boundedText(value, MAX_PATH)
            require(path.none { it in "%:*?\"<>|" || it.code < 32 } && forbiddenPathParts.none(path::contains)) {
                "Chemin de destination260 invalide"
            }
            val normalized = path.replace('\\', '/')
            require(!normalized.startsWith('/') && normalized.split('/').none { it == "." }) {
                "Destination260 hors du dossier du jeu"
            }
            return WolfDownloadDestination(normalized, normalized.isEmpty() || normalized.endsWith('/'))
        }

        private fun boundedText(value: String, maximum: Int): String {
            require(value.length <= maximum && '\u0000' !in value) { "Texte260 absent, nul ou trop long" }
            return value
        }
        private fun charset(encoding: WolfEncoding) = if (encoding == WolfEncoding.UTF8) Charsets.UTF_8 else cp932
        private fun encode(value: String, encoding: WolfEncoding): ByteArray = try {
            charset(encoding).newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).let { buffer ->
                    ByteArray(buffer.remaining()).also(buffer::get)
                }
        } catch (e: java.nio.charset.CharacterCodingException) { throw WolfFormatException("Texte260 non représentable en $encoding", e) }
        private fun decodeWhole(bytes: ByteArray, encoding: WolfEncoding): String = try {
            charset(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) { throw WolfFormatException("Réponse260 invalide en $encoding", e) }
    }
}
