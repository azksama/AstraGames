package fr.astragames.app.translation

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Wolf 2/3 map/common-event format reader, not a native game engine.
 * Structure derived from Sinflower/WolfTL (MIT, copyright 2024 Sinflower)
 * and djytw/wolf-rpg-formats (MIT, copyright 2024 djytw); notices in assets/licenses.
 * All non-text bytes, command IDs, branch operands and extensions stay intact.
 */
internal class WolfTextDocument(private val filename: String, private val original: ByteArray) : GameTextDocument {
    private data class Slot(val entry: RpgTextDocument.Entry, val start: Int, val end: Int)
    private val slots = mutableListOf<Slot>()
    private val payload: ByteArray
    private val header: ByteArray
    private val packed: Boolean
    private val charset: Charset
    override val entries: List<RpgTextDocument.Entry> get() = slots.map { it.entry }

    init {
        require(original.size <= RpgTextDocument.MAX_FILE_BYTES) { "Fichier Wolf trop volumineux." }
        val source = Reader(original)
        val map = filename.endsWith(".mps", true)
        val magic = source.take(if (map) 20 else 10)
        val encoding = magic[if (map) 16 else 6].toInt() and 255
        require(encoding == 0 || encoding == 0x55) { "Encodage Wolf inconnu." }
        magic[if (map) 16 else 6] = 0
        val expected = if (map) ByteArray(10) + "WOLFM".toByteArray(Charsets.US_ASCII) + ByteArray(5)
            else byteArrayOf(0, 0x57, 0, 0, 0x4f, 0x4c, 0, 0x46, 0x43, 0)
        require(magic.contentEquals(expected)) { "Données Wolf protégées ou format inconnu : $filename" }
        val version = if (map) source.int() else source.byte()
        if (map) { require(version in 100..103) { "Version de carte Wolf inconnue." }; val marker = source.byte(); require(marker in setOf(0x65, 0x66, 0x67, 0x69)) { "Structure de carte Wolf inconnue." } }
        else require(version == 0x8f || version == 0x93) { "Version de common events Wolf inconnue." }
        packed = if (map) version >= 101 else version == 0x93
        header = original.copyOfRange(0, source.position)
        payload = if (packed) {
            val expanded = source.count(RpgTextDocument.MAX_FILE_BYTES)
            val compressed = source.count(RpgTextDocument.MAX_FILE_BYTES)
            decodeLz4(source.take(compressed), expanded).also { source.end() }
        } else source.take(original.size - source.position)
        charset = if (encoding == 0x55) Charsets.UTF_8 else Charset.forName("windows-31j")
        val reader = Reader(payload)
        if (map) readMap(reader, version) else readCommon(reader, version)
        reader.end()
        require(slots.size <= 200_000) { "Trop de textes Wolf." }
    }

    private fun string(reader: Reader, path: String? = null) {
        val start = reader.position
        val size = reader.count(RpgTextDocument.MAX_FILE_BYTES)
        require(size > 0) { "Chaîne Wolf sans terminateur." }
        val raw = reader.take(size)
        require(raw.last() == 0.toByte() && raw.indexOf(0) == raw.lastIndex) { "Chaîne Wolf invalide." }
        val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw, 0, raw.size - 1)).toString()
        if (path != null && text.isNotBlank()) slots += Slot(RpgTextDocument.Entry(path, text), start, reader.position)
    }

    private fun route(reader: Reader) {
        repeat(reader.count(100_000, 4)) {
            reader.byte(); reader.skip(reader.byte() * 4); reader.expect(1, 0)
        }
    }
    private fun command(reader: Reader, extended: Boolean, path: String) {
        val words = reader.byte()
        require(words > 0) { "Commande Wolf invalide." }
        val opcode = reader.int()
        reader.skip((words - 1) * 4); reader.byte()
        repeat(reader.byte()) { index -> string(reader, if (opcode in setOf(101, 102)) "$path/strings/$index" else null) }
        when (reader.byte()) { 0 -> Unit; 1 -> { reader.skip(6); route(reader) }; else -> error("Route Wolf invalide.") }
        if (extended) reader.skip(reader.byte())
    }
    private fun readMap(r: Reader, version: Int) {
        string(r); r.int()
        val width = r.count(10_000); val height = r.count(10_000)
        val events = r.count(100_000, 26)
        if (version >= 103) r.int()
        val layers = if (version >= 103) r.count(64) else 3
        require(width > 0 && height > 0 && layers > 0)
        val bytes = width.toLong() * height * layers * 4
        require(bytes <= RpgTextDocument.MAX_FILE_BYTES)
        if (charset == Charsets.UTF_8 && r.peekInt() == -1) r.skip(4) else r.skip(bytes.toInt())
        val ids = mutableSetOf<Int>()
        repeat(events) {
            r.expect(0x6f, 0x39, 0x30, 0, 0)
            val id = r.int(); require(ids.add(id)) { "ID d’événement Wolf dupliqué." }
            string(r); r.skip(8)
            val pages = r.count(10_000, 64); r.expect(0, 0, 0, 0)
            repeat(pages) { page ->
                r.expect(0x79); r.int(); string(r); r.skip(4 + 37 + 4 + 2); route(r)
                repeat(r.count(200_000, 8)) { index -> command(r, version >= 103, "/events/$id/pages/$page/commands/$index") }
                val features = r.int(); require(features in 0..4); r.skip(3 + if (features > 3) 1 else 0); r.expect(0x7a)
            }
            r.expect(0x70)
        }
        r.expect(0x66)
    }
    private fun readCommon(r: Reader, version: Int) {
        val ids = mutableSetOf<Int>()
        repeat(r.count(100_000, 50)) {
            r.expect(0x8e); val id = r.int(); require(ids.add(id)) { "ID de common event Wolf dupliqué." }
            r.skip(11); string(r)
            repeat(r.count(200_000, 8)) { index -> command(r, packed, "/events/$id/commands/$index") }
            string(r); string(r); r.expect(0x8f)
            repeat(r.count(100_000, 5)) { string(r) }
            r.skip(r.count(100_000))
            repeat(r.count(100_000, 4)) { repeat(r.count(100_000, 5)) { string(r) } }
            repeat(r.count(100_000, 4)) { r.skip(r.count(100_000, 4) * 4) }
            r.skip(r.count(100_000, 4) * 4); r.expect(0x90); r.int()
            repeat(100) { string(r) }; r.expect(0x91); string(r)
            when (r.byte()) { 0x91 -> Unit; 0x92 -> { string(r); r.int(); r.expect(0x92) }; else -> error("Fin de common event Wolf inconnue.") }
        }
        r.expect(if (version == 0x93) 0x92 else 0x8f)
    }

    override fun translatedEntries(dictionary: Map<String, String>): ByteArray {
        val paths = slots.mapTo(hashSetOf()) { it.entry.path }
        require(dictionary.keys.all { it in paths }) { "Emplacement Wolf inconnu." }
        val changed = slots.filter { dictionary[it.entry.path]?.let { value -> value != it.entry.text } == true }
        if (changed.isEmpty()) return original.copyOf()
        val output = ByteArrayOutputStream()
        var position = 0
        changed.forEach { slot ->
            val text = dictionary.getValue(slot.entry.path)
            require('\u0000' !in text && ProtectedText.controls(text) == ProtectedText.controls(slot.entry.text)) { "Commande Wolf altérée : ${slot.entry.path}" }
            val encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
            val bytes = ByteArray(encoded.remaining()).also(encoded::get)
            output.write(payload, position, slot.start - position); output.leInt(bytes.size + 1); output.write(bytes); output.write(0)
            position = slot.end
            require(output.size() <= RpgTextDocument.MAX_FILE_BYTES)
        }
        output.write(payload, position, payload.size - position)
        val raw = output.toByteArray()
        val encoded = if (!packed) header + raw else {
            // Valid raw LZ4 block, consisting of one literal sequence. No lossy rewrite.
            val block = ByteArrayOutputStream().apply {
                write(minOf(15, raw.size) shl 4)
                if (raw.size >= 15) { var rest = raw.size - 15; while (rest >= 255) { write(255); rest -= 255 }; write(rest) }
                write(raw)
            }.toByteArray()
            ByteArrayOutputStream().apply { write(header); leInt(raw.size); leInt(block.size); write(block) }.toByteArray()
        }
        require(encoded.size <= RpgTextDocument.MAX_FILE_BYTES) { "Traduction Wolf trop volumineuse." }
        val after = WolfTextDocument(filename, encoded).entries.associateBy { it.path }
        slots.forEach { check(after[it.entry.path]?.text == (dictionary[it.entry.path] ?: it.entry.text)) { "Vérification du texte Wolf échouée." } }
        return encoded
    }

    private class Reader(val bytes: ByteArray) {
        var position = 0
        fun take(size: Int): ByteArray { require(size >= 0 && size <= bytes.size - position) { "Données Wolf tronquées." }; return bytes.copyOfRange(position, position + size).also { position += size } }
        fun skip(size: Int) { require(size >= 0 && size <= bytes.size - position) { "Données Wolf tronquées." }; position += size }
        fun byte(): Int { require(position < bytes.size) { "Données Wolf tronquées." }; return bytes[position++].toInt() and 255 }
        fun int(): Int = byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)
        fun peekInt(): Int { val saved = position; return int().also { position = saved } }
        fun count(max: Int, minimumBytes: Int = 0): Int = int().also { require(it in 0..max && it.toLong() * minimumBytes <= bytes.size - position) { "Compteur Wolf invalide." } }
        fun expect(vararg values: Int) { values.forEach { require(byte() == it) { "Marqueur Wolf inconnu." } } }
        fun end() { require(position == bytes.size) { "Données Wolf supplémentaires non reconnues." } }
    }
    private fun ByteArrayOutputStream.leInt(value: Int) { repeat(4) { write(value ushr (8 * it) and 255) } }
    private fun decodeLz4(block: ByteArray, expected: Int): ByteArray {
        val r = Reader(block); val result = ByteArray(expected); var pos = 0
        fun length(nibble: Int): Int { var value = nibble; if (nibble == 15) { var add: Int; do { add = r.byte(); value += add; require(value <= expected) { "Bloc LZ4 trop grand." } } while (add == 255) }; return value }
        while (r.position < block.size) {
            val token = r.byte(); val literals = length(token ushr 4)
            require(literals <= expected - pos); r.take(literals).copyInto(result, pos); pos += literals
            if (r.position == block.size) break
            val offset = r.byte() or (r.byte() shl 8); require(offset in 1..pos)
            val match = length(token and 15) + 4; require(match <= expected - pos)
            repeat(match) { result[pos] = result[pos - offset]; pos++ }
        }
        require(pos == expected) { "Taille LZ4 incohérente." }; return result
    }
}
