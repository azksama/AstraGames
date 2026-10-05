package fr.astragames.app.translation

import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Android's JSONTokener accepts duplicate keys and non-JSON syntax; check strict grammar first. */
internal object TranslationJson {
    fun parse(bytes: ByteArray, maxBytes: Int): Any {
        require(bytes.size <= maxBytes) { "Document JSON trop volumineux." }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        Validator(text).validate()
        return JSONTokener(text).nextValue()
    }

    private class Validator(private val text: String) {
        private var i = 0
        fun validate() {
            value(0); whitespace()
            require(i == text.length) { "Données après le document JSON." }
        }
        private fun whitespace() { while (i < text.length && text[i] in " \t\r\n") i++ }
        private fun take(c: Char): Boolean { whitespace(); return if (text.getOrNull(i) == c) { i++; true } else false }
        private fun expect(c: Char) { require(take(c)) { "JSON invalide à la position $i." } }
        private fun value(depth: Int) {
            require(depth <= 64) { "JSON trop imbriqué." }; whitespace()
            when (text.getOrNull(i)) {
                '{' -> {
                    require(depth < 64) { "JSON trop imbriqué." }
                    i++; val keys = mutableSetOf<String>()
                    if (take('}')) return
                    do {
                        whitespace(); val start = i; string()
                        val key = JSONTokener(text.substring(start, i)).nextValue() as String
                        require(keys.add(key)) { "Clé JSON dupliquée : $key" }
                        expect(':'); value(depth + 1)
                    } while (take(','))
                    expect('}')
                }
                '[' -> {
                    require(depth < 64) { "JSON trop imbriqué." }
                    i++; if (take(']')) return
                    do { value(depth + 1) } while (take(','))
                    expect(']')
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }
        private fun string() {
            require(text.getOrNull(i) == '"') { "Chaîne JSON attendue." }; i++
            while (i < text.length) {
                val c = text[i++]
                if (c == '"') return
                require(c >= ' ') { "Caractère de contrôle JSON invalide." }
                if (c == '\\') {
                    val escape = text.getOrNull(i++)
                    require(escape != null && escape in "\"\\/bfnrtu") { "Échappement JSON invalide." }
                    if (escape == 'u') repeat(4) {
                        require(text.getOrNull(i++)?.digitToIntOrNull(16) != null) { "Unicode JSON invalide." }
                    }
                }
            }
            error("Chaîne JSON non terminée.")
        }
        private fun literal(value: String) { require(text.startsWith(value, i)) { "Valeur JSON invalide." }; i += value.length }
        private fun digits() {
            val start = i; while (text.getOrNull(i)?.let { it in '0'..'9' } == true) i++
            require(i > start) { "Nombre JSON invalide." }
        }
        private fun number() {
            if (text.getOrNull(i) == '-') i++
            if (text.getOrNull(i) == '0') i++ else digits()
            if (text.getOrNull(i) == '.') { i++; digits() }
            if (text.getOrNull(i) in listOf('e', 'E')) { i++; if (text.getOrNull(i) in listOf('+', '-')) i++; digits() }
        }
    }
}
