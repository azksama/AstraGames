package fr.astragames.app.translation

/** A scanner, rather than a flat regex: plugin arguments may contain other escape commands. */
internal object ProtectedText {
    private const val MAX_FRAGMENT = 3500
    private val markerPattern = Regex("⟦ASTRA_[^⟦⟧]*⟧")
    private data class Part(val value: String, val protected: Boolean)
    data class Masked(val source: String, val tokens: List<String>, val markers: List<String>)

    fun controls(text: String): List<String> = parts(text).filter { it.protected }.map { it.value }
    fun fragments(text: String): List<String> = parts(text).filterNot { it.protected }
        .flatMap { chunks(it.value) }.map(String::trim).filter { it.any(Char::isLetter) }

    fun render(text: String, translated: Map<String, String>): String = buildString {
        parts(text).forEach { part ->
            if (part.protected) append(part.value)
            else chunks(part.value).forEach { chunk ->
                val value = translated[chunk.trim()]
                if (value == null) append(chunk)
                else {
                    require(value.isNotBlank() && controls(value).isEmpty()) { "Traduction invalide." }
                    append(chunk.takeWhile(Char::isWhitespace)).append(value)
                        .append(chunk.takeLastWhile(Char::isWhitespace))
                }
            }
        }
    }.also { require(controls(it) == controls(text)) { "Commande RPG Maker altérée." } }

    /** The namespace is source-specific, so literal text resembling a placeholder remains legal. */
    fun mask(text: String): Masked {
        var namespace = "ASTRA_${textHash(text.toByteArray()).take(12)}_"
        while (text.contains("⟦$namespace")) namespace += "X"
        val tokens = mutableListOf<String>()
        val markers = mutableListOf<String>()
        val source = buildString {
            parts(text).forEach { part ->
                if (!part.protected) append(part.value)
                else {
                    val marker = "⟦${namespace}${tokens.size}⟧"
                    tokens += part.value; markers += marker; append(marker)
                }
            }
        }
        return Masked(source, tokens, markers)
    }

    fun unmask(original: String, translated: String, masked: Masked = mask(original)): String {
        if (translated == masked.source) return original
        require(translated.isNotBlank()) { "Texte traduit vide." }
        require(translated.none { it < ' ' && it != '\t' }) { "Caractère de contrôle ajouté dans la traduction." }
        var position = 0
        var nextToken = 0
        val markerIndices = masked.markers.withIndex().associate { it.value to it.index }
        val value = buildString {
            // One pass also bounds work for profiles containing thousands of short commands.
            markerPattern.findAll(translated).forEach { match ->
                val index = markerIndices[match.value] ?: return@forEach
                require(index == nextToken) { "Balise protégée absente, déplacée ou dupliquée : ${match.value}" }
                append(translated, position, match.range.first).append(masked.tokens[index])
                position = match.range.last + 1
                nextToken++
            }
            require(nextToken == masked.tokens.size) { "Balise protégée absente." }
            append(translated, position, translated.length)
        }
        // Reject newly invented placeholders and raw commands, including tokens joined at a boundary.
        val originalMarkers = markerPattern.findAll(original).map { it.value }.toList()
        require(markerPattern.findAll(value).map { it.value }.toList() == originalMarkers) { "Balise protégée inconnue." }
        require(controls(original) == controls(value)) { "Commande RPG Maker ajoutée ou altérée." }
        return value
    }

    private fun parts(text: String): List<Part> {
        val result = mutableListOf<Part>()
        var start = 0; var i = 0
        while (i < text.length) {
            val end = tokenEnd(text, i)
            if (end == i) { i++; continue }
            if (start < i) result += Part(text.substring(start, i), false)
            result += Part(text.substring(i, end), true)
            i = end; start = end
        }
        if (start < text.length) result += Part(text.substring(start), false)
        return result
    }

    private fun tokenEnd(text: String, start: Int): Int {
        val c = text[start]
        if (c == '\r') return start + if (text.getOrNull(start + 1) == '\n') 2 else 1
        if (c == '\n' || c == '\u000c' || c == '\u0085' || c == '\u2028' || c == '\u2029') return start + 1
        if (c == '%') {
            var end = start + 1
            while (end < text.length && text[end].isDigit()) end++
            return if (end > start + 1) end else start
        }
        if (c == '<') {
            var end = start + 1
            while (end < text.length && text[end] != '>' && text[end] != '<' && text[end] != '\r' && text[end] != '\n') end++
            if (text.getOrNull(end) == '>') return end + 1
        }
        if (c != '\\' && c != '\u001b') return start
        var end = start + 1
        if (end == text.length) return end
        if (text[end] in 'a'..'z' || text[end] in 'A'..'Z') {
            end++
            while (end < text.length && (text[end] in 'a'..'z' || text[end] in 'A'..'Z' || text[end] in '0'..'9' || text[end] == '_')) end++
            while (end < text.length && text[end] in "[({") end = argumentEnd(text, end)
        } else end++ // Includes escaped backslash and single-character RPGM control codes.
        return end
    }

    private fun argumentEnd(text: String, start: Int): Int {
        val closers = ArrayDeque<Char>()
        var quote: Char? = null
        var escaped = false
        var index = start
        while (index < text.length) {
            val c = text[index]
            // An incomplete plugin expression stays opaque up to its line boundary.
            if (c == '\r' || c == '\n') return index
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '[' -> closers.addLast(']')
                '(' -> closers.addLast(')')
                '{' -> closers.addLast('}')
                ']', ')', '}' -> {
                    if (closers.lastOrNull() != c) return lineEnd(text, index)
                    closers.removeLast()
                    if (closers.isEmpty()) return index + 1
                }
            }
            index++
        }
        return index
    }

    private fun lineEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end] != '\r' && text[end] != '\n') end++
        return end
    }

    /** Deterministic bounds for ML Kit; keep whitespace and surrogate pairs at chunk boundaries. */
    private fun chunks(text: String): List<String> {
        if (text.length <= MAX_FRAGMENT) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + MAX_FRAGMENT)
            if (end < text.length) {
                val boundary = (end - 1 downTo start + MAX_FRAGMENT / 2).firstOrNull { text[it].isWhitespace() }
                if (boundary != null) end = boundary + 1
                else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            result += text.substring(start, end)
            start = end
        }
        return result
    }
}
