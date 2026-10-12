package fr.astragames.app.translation

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Source-only say/menu reader. Python, screens, ATL and compiled scripts remain opaque. */
internal class RenPyTextDocument(filename: String, private val original: ByteArray) : GameTextDocument {
    private data class Slot(val entry: RpgTextDocument.Entry, val start: Int, val end: Int, val quote: Char)
    private val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(original)).toString()
    private val slots = mutableListOf<Slot>()
    override val entries get() = slots.map { it.entry }

    init {
        require(original.size <= RpgTextDocument.MAX_FILE_BYTES && filename.endsWith(".rpy", true))
        var offset = 0
        var blockedIndent: Int? = null
        var multilineQuote: String? = null
        source.splitToSequence('\n').forEachIndexed { lineIndex, line ->
            val clean = line.trimStart(' ', '\t', '\uFEFF')
            val indent = line.length - clean.length
            val blank = clean.isBlank() || clean.startsWith('#')
            if (!blank && blockedIndent?.let { indent <= it } == true) blockedIndent = null
            if (multilineQuote != null) {
                if (clean.contains(multilineQuote!!)) multilineQuote = null
            } else if (!blank && blockedIndent == null) {
                if (blockedBlock.matches(clean.trimEnd('\r')) || clean.endsWith("\\")) blockedIndent = indent
                else {
                    val first = clean.indexOfFirst { it == '\'' || it == '"' }
                    if (first >= 0) {
                        val prefix = clean.substring(0, first).trim()
                        val quote = clean[first]
                        if (clean.startsWith(quote.toString().repeat(3), first)) {
                            val marker = quote.toString().repeat(3)
                            if (clean.indexOf(marker, first + 3) < 0) multilineQuote = marker
                        } else if (validPrefix(prefix)) {
                            val end = closingQuote(clean, first)
                            if (end != null) {
                                val suffix = clean.substring(end + 1).trim()
                                // Complex say expressions are deliberately not rewritten.
                                if (suffix.isEmpty() || suffix.startsWith('#') || suffix == ":" || suffix.startsWith(": #") ||
                                    suffix.matches(Regex("with [A-Za-z_][A-Za-z0-9_.]*(?:\\s+#.*)?\\r?"))) {
                                    val text = decode(clean.substring(first + 1, end))
                                    if (text.isNotBlank()) slots += Slot(RpgTextDocument.Entry("/lines/${lineIndex + 1}/dialogue", text),
                                        offset + indent + first + 1, offset + indent + end, quote)
                                }
                            }
                        }
                    }
                }
            }
            offset += line.length + 1
        }
        require(slots.size <= 200_000) { "Trop de dialogues Ren’Py." }
    }

    override fun translatedEntries(dictionary: Map<String, String>): ByteArray {
        val paths = slots.mapTo(hashSetOf()) { it.entry.path }
        require(dictionary.keys.all { it in paths }) { "Emplacement Ren’Py inconnu." }
        if (slots.none { dictionary[it.entry.path]?.let { value -> value != it.entry.text } == true }) return original.copyOf()
        var position = 0
        val result = buildString {
            slots.forEach { slot ->
                val value = dictionary[slot.entry.path] ?: slot.entry.text
                require('\u0000' !in value && ProtectedText.controls(value, renpy = true) == ProtectedText.controls(slot.entry.text, renpy = true)) {
                    "Balise ou variable Ren’Py altérée : ${slot.entry.path}"
                }
                append(source, position, slot.start)
                if (value == slot.entry.text) append(source, slot.start, slot.end) else append(encode(value, slot.quote))
                position = slot.end
            }
            append(source, position, source.length)
        }.toByteArray(Charsets.UTF_8)
        require(result.size <= RpgTextDocument.MAX_FILE_BYTES)
        val after = RenPyTextDocument("verify.rpy", result).entries.associateBy { it.path }
        slots.forEach { check(after[it.entry.path]?.text == (dictionary[it.entry.path] ?: it.entry.text)) { "Vérification du dialogue Ren’Py échouée." } }
        return result
    }

    private fun validPrefix(prefix: String): Boolean {
        if (prefix.isEmpty()) return true
        val parts = prefix.split(Regex("\\s+"))
        return parts.first() !in keywords && parts.first().matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) &&
            parts.drop(1).all { it == "@" || it.matches(Regex("-?[A-Za-z_][A-Za-z0-9_]*")) }
    }
    private fun closingQuote(line: String, first: Int): Int? {
        var i = first + 1
        while (i < line.length) {
            if (line[i] == '\\') i += 2 else if (line[i] == line[first]) return i else i++
        }
        return null
    }
    private fun decode(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            val c = raw[i++]
            if (c != '\\') append(c) else {
                require(i < raw.length)
                when (val escaped = raw[i++]) {
                    'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t')
                    '\\', '\'', '"' -> append(escaped)
                    'u', 'U', 'x' -> {
                        val count = when (escaped) { 'U' -> 8; 'u' -> 4; else -> 2 }
                        require(count <= raw.length - i) { "Échappement Ren’Py tronqué." }
                        val point = raw.substring(i, i + count).toLongOrNull(16) ?: error("Échappement Ren’Py invalide.")
                        require(point in 0..0x10ffff && point !in 0xd800..0xdfff) { "Caractère Ren’Py invalide." }
                        append(String(Character.toChars(point.toInt()))); i += count
                    }
                    else -> { append('\\'); append(escaped) }
                }
            }
        }
    }
    private fun encode(value: String, quote: Char) = buildString {
        value.forEach { c -> when (c) {
            '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            quote -> { append('\\'); append(c) }; else -> append(c)
        } }
    }
    private companion object {
        val blockedBlock = Regex("(?:init(?: -?\\d+)? python(?: early)?(?: in [A-Za-z_][A-Za-z0-9_]*)?|python(?: early)?(?: in [A-Za-z_][A-Za-z0-9_]*)?|screen .+|transform .+|style .+|image .+):\\s*(?:#.*)?")
        val keywords = setOf("define", "default", "image", "show", "scene", "hide", "play", "stop", "queue", "voice", "pause", "call", "jump", "window", "label", "menu", "if", "elif", "else", "while", "for", "return", "pass", "with", "init", "python", "screen", "style", "transform", "translate", "old", "new", "text", "textbutton", "button", "add", "use", "on", "key", "timer", "config", "import", "from", "assert", "raise")
    }
}
