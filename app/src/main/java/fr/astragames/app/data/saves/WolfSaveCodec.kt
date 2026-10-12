package fr.astragames.app.data.saves

import java.nio.charset.Charset

/**
 * Bounded, byte-preserving Kotlin implementation of the WolfSave file layout.
 * Format reference: Sinflower/WolfSave, 537ceba4262368c61bfb9c4fa5333341a115c662 (MIT).
 * Copyright (c) 2024 Sinflower. License: assets/licenses/WolfSave-MIT.txt.
 *
 * The seven sections are walked completely. Only numeric cells whose type is declared by the
 * variable database, and the normal variable bank proven for format 0x80, are writable. Unrelated
 * numeric fields, strings and unknown data are preserved.
 * A Wolf editor release number is not a save-format version. Unknown save formats are refused.
 */
internal object WolfSaveCodec {
    const val HIGHEST_FORMAT_VERSION = 0x8e
    private const val HEADER_SIZE = 0x14
    private const val MAX_CELLS = 100_000
    private const val MAX_PREVIEW_BYTES = 8_192

    data class Section(val name: String, val start: Int, val endExclusive: Int)
    data class NumericCell(val typeId: Int, val rowId: Int, val fieldId: Int, val offset: Int, val value: Int) {
        val path: String get() = "root.variableDatabase.types[$typeId].rows[$rowId].fields[$fieldId]"
    }
    data class TextCell(val typeId: Int, val rowId: Int, val fieldId: Int, val text: String) {
        val path: String get() = "root.variableDatabase.types[$typeId].rows[$rowId].fields[$fieldId]"
    }
    data class VariableCell(val id: Int, val offset: Int, val value: Int) {
        val path: String get() = "root.variables[$id]"
    }
    class Document internal constructor(
        val gameName: String,
        val formatVersion: Int,
        val utf8: Boolean,
        val checksumValid: Boolean,
        val sections: List<Section>,
        val variables: List<VariableCell>,
        val numbers: List<NumericCell>,
        val strings: List<TextCell>,
        internal val plaintext: ByteArray
    ) {
        fun entries(): List<SaveEntry> = buildList {
            add(SaveEntry("root.header.gameName", SaveEntryType.STRING, gameName, false))
            add(SaveEntry("root.header.formatVersion", SaveEntryType.INT, formatVersion.toString(), false))
            add(SaveEntry("root.header.encoding", SaveEntryType.STRING, if (utf8) "UTF-8" else "Windows-31J", false))
            add(SaveEntry("root.header.checksumValid", SaveEntryType.BOOLEAN, checksumValid.toString(), false))
            variables.forEach { add(SaveEntry(it.path, SaveEntryType.VARIABLE, it.value.toString(), checksumValid)) }
            numbers.forEach { add(SaveEntry(it.path, SaveEntryType.VARIABLE, it.value.toString(), checksumValid)) }
            strings.forEach { add(SaveEntry(it.path, SaveEntryType.STRING, it.text, false)) }
        }
    }

    fun read(bytes: ByteArray): Document {
        require(bytes.size in (HEADER_SIZE + 6)..MAX_SAVE_BYTES) { "Sauvegarde Wolf vide, tronquée ou trop volumineuse." }
        val plain = crypt(bytes)
        val reader = Reader(plain)
        reader.skip(HEADER_SIZE)
        require(reader.u8() == 0x19) { "Format de sauvegarde Wolf non reconnu ou trop récent. Aucune modification autorisée." }
        val utf8 = (plain[6].toInt() and 0xff) == 0x55
        val gameName = reader.text(2, utf8)
        val version = reader.u16()
        require(version in 1..HIGHEST_FORMAT_VERSION) {
            "Format de sauvegarde Wolf 0x${version.toString(16)} non pris en charge (maximum : 0x8e). Aucune modification autorisée."
        }
        val layout = Layout(reader, version, utf8)
        layout.parse()
        require(reader.u8() == 0x19 && reader.position == plain.size) { "Fin de sauvegarde Wolf invalide ou données supplémentaires." }
        return Document(gameName, version, utf8, (plain[2].toInt() and 0xff) == checksum(plain),
            layout.sections.toList(), layout.variables.toList(), layout.numbers.toList(), layout.strings.toList(), plain)
    }

    fun patch(original: ByteArray, edits: List<SaveEdit>): ByteArray {
        require(edits.isNotEmpty() && edits.distinctBy { it.path }.size == edits.size) { "Modifications Wolf vides ou dupliquées." }
        val document = read(original)
        require(document.checksumValid) { "Checksum de sauvegarde Wolf invalide : écriture refusée." }
        val cells = (document.numbers.map { PatchCell(it.path, it.offset, it.value) } +
            document.variables.map { PatchCell(it.path, it.offset, it.value) }).associateBy { it.path }
        val replacements = edits.map { edit ->
            val cell = cells[edit.path] ?: error("Ce champ Wolf n'est pas une variable numérique reconnue : ${edit.path}")
            require(edit.type == SaveEntryType.VARIABLE) { "Type de variable Wolf incorrect." }
            val value = edit.newValue.trim().toLongOrNull() ?: error("Variable Wolf : nombre entier attendu.")
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Variable Wolf hors des limites d'un entier 32 bits." }
            cell to value.toInt()
        }
        val plain = document.plaintext.copyOf()
        replacements.forEach { (cell, value) -> putInt(plain, cell.offset, value) }
        plain[2] = checksum(plain).toByte()
        val patched = crypt(plain)
        val verified = read(patched)
        check(verified.checksumValid && verified.formatVersion == document.formatVersion && patched.size == original.size)
        val after = (verified.numbers.map { it.path to it.value } + verified.variables.map { it.path to it.value }).toMap()
        replacements.forEach { (cell, value) -> check(after[cell.path] == value) { "Vérification de variable Wolf échouée." } }
        return patched
    }

    private data class PatchCell(val path: String, val offset: Int, val value: Int)

    /** MSVCRT rand(), not Java Random or the host libc PRNG. XOR rounds preserve header seeds. */
    internal fun crypt(bytes: ByteArray): ByteArray {
        require(bytes.size >= HEADER_SIZE) { "En-tête de sauvegarde Wolf tronqué." }
        val result = bytes.copyOf()
        for ((seedOffset, stride) in listOf(0 to 1, 3 to 2, 9 to 5)) {
            var state = bytes[seedOffset].toInt() and 0xff
            var offset = HEADER_SIZE
            while (offset < result.size) {
                state = state * 214013 + 2531011 // Int overflow intentionally reproduces uint32 arithmetic.
                val mask = ((state ushr 16) and 0x7fff) ushr 12
                result[offset] = (result[offset].toInt() xor mask).toByte()
                offset += stride
            }
        }
        return result
    }

    internal fun checksum(plain: ByteArray): Int {
        var sum = 0
        for (i in HEADER_SIZE until plain.size) sum = (sum + (plain[i].toInt() and 0xff)) and 0xff
        return sum
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        for (byteIndex in 0..3) bytes[offset + byteIndex] = (value ushr (byteIndex * 8)).toByte()
    }

    private class Reader(private val bytes: ByteArray) {
        var position = 0
            private set
        private var operations = 0
        var section = "En-tête"
        private fun bounded(size: Long) {
            require(size >= 0 && size <= bytes.size.toLong() - position) { "Bloc Wolf tronqué ou taille invalide ($section, octet $position)." }
        }
        fun work(count: Int) {
            require(count >= 0 && count <= MAX_SAVE_NODES - operations) { "Structure Wolf trop complexe ($section)." }
            operations += count
        }
        fun skip(size: Int) { bounded(size.toLong()); position += size }
        fun array(count: Int, width: Int) { val size = count.toLong() * width; bounded(size); position += size.toInt() }
        fun u8(): Int { bounded(1); return bytes[position++].toInt() and 0xff }
        fun u16(): Int { val low = u8(); return low or (u8() shl 8) }
        fun i32(): Int { val low = u16(); return low or (u16() shl 16) }
        fun peekInt(): Int { val value = i32(); position -= 4; return value }
        fun u32(): Long = i32().toLong() and 0xffff_ffffL
        fun count(signedSentinel: Boolean = false, maximum: Int = MAX_SAVE_NODES): Int {
            val value = i32()
            if (signedSentinel && value < 0) return 0
            return checkedCount(value, maximum)
        }
        fun checkedCount(value: Int, maximum: Int = MAX_SAVE_NODES): Int {
            require(value in 0..maximum) { "Compteur Wolf invalide ($section)." }
            return value
        }
        fun text(lengthWidth: Int, utf8: Boolean): String {
            val length = when (lengthWidth) { 2 -> u16().toLong(); 4 -> u32(); else -> error("Largeur de chaîne Wolf invalide.") }
            bounded(length)
            val start = position
            position += length.toInt()
            val end = if (length > 0 && bytes[position - 1] == 0.toByte()) position - 1 else position
            val preview = minOf(end - start, MAX_PREVIEW_BYTES)
            val charset = if (utf8) Charsets.UTF_8 else Charset.forName("windows-31j")
            return String(bytes, start, preview, charset) + if (end - start > preview) "…" else ""
        }
        fun blob(lengthWidth: Int) {
            val length = if (lengthWidth == 2) u16().toLong() else u32()
            bounded(length)
            position += length.toInt()
        }
        inline fun repeatCount(count: Int, block: (Int) -> Unit) { work(count); repeat(count, block) }
    }

    private class Layout(private val r: Reader, private val version: Int, private val utf8: Boolean) {
        val sections = mutableListOf<Section>()
        val variables = mutableListOf<VariableCell>()
        val numbers = mutableListOf<NumericCell>()
        val strings = mutableListOf<TextCell>()

        fun parse() {
            section("État du jeu", ::part1)
            section("Configuration du moteur", ::part2)
            section("État des événements", ::part3)
            section("État des personnages", ::part4)
            section("État des images", ::part5)
            section("Base de variables", ::variableDatabase)
            section("Données finales", ::part7)
        }
        private inline fun section(name: String, block: () -> Unit) {
            r.section = name
            val start = r.position
            block()
            sections += Section(name, start, r.position)
        }
        private fun array32(sentinel: Boolean = true) = r.array(r.count(sentinel), 4)
        private fun blobs(width: Int, count: Int) = r.repeatCount(count) { r.blob(width) }

        private fun part1() {
            r.skip(12)
            if (version >= 0x69) {
                val width = r.count()
                val height = r.count()
                val sentinel = r.peekInt()
                if (sentinel != -1) {
                    val cells = width.toLong() * height * 3
                    require(cells <= MAX_SAVE_BYTES / 4) { "Dimensions Wolf invalides." }
                    r.array(cells.toInt(), 4)
                } else r.skip(4)
            }
            r.blob(4)
            blobs(4, if (version >= 0x8a) 31 else if (version >= 0x73) 15 else 7)
            r.array(r.count(true), if (version >= 0x64) 4 else 1)
            r.repeatCount(r.count(true)) { character() }
            if (version >= 0x72) r.skip(44)
            array32()
        }

        private fun character() {
            r.skip(21)
            r.blob(2)
            r.skip(10)
            movement()
            movement()
            r.skip(15)
            array32()
            if (version >= 0x70) r.skip(2)
            if (version >= 0x73) r.skip(16)
            if (version >= 0x78) r.skip(4)
            if (version >= 0x85) r.skip(12)
            if (version >= 0x8a) {
                val count = r.u16()
                if (count < 0x8000) blobs(2, count)
                r.skip(13)
            }
            if (version >= 0x8b) r.skip(4)
            if (version >= 0x8c) r.skip(4)
        }

        private fun movement() {
            r.skip(6)
            r.repeatCount(r.count(maximum = 0x10000)) {
                r.skip(1)
                r.array(r.u8(), 4)
                r.skip(r.u8())
            }
        }

        private fun part2() {
            r.skip(2 + 19 * 4 + 3 * 2 + 4 + 3 * 2 + 9 * 4 + 1 + 2 + 2 * 4 + 4 * 2 + 3 * 4 + 1)
            if (version <= 96) r.skip(4)
            r.skip(4)
            if (version >= 98) r.skip(8)
            if (version >= 100) {
                r.skip(9)
                blobs(4, r.count(true))
                array32()
                r.skip(20)
            }
            if (version >= 101) r.skip(24)
            if (version >= 102) r.skip(2)
            if (version >= 103) { r.blob(2); r.blob(2) }
            if (version >= 104) r.skip(4)
            if (version >= 106) r.skip(12)
            if (version >= 108) { r.blob(2); r.skip(12); r.blob(2); r.skip(12) }
            if (version >= 109) { r.blob(2); r.blob(2); r.skip(1) }
            if (version >= 110) r.skip(1)
            if (version >= 119) blobs(2, 3)
            if (version >= 121) r.skip(4)
            if (version >= 122) r.skip(4)
            if (version >= 124) r.skip(4)
            if (version >= 126) r.skip(4)
            if (version >= 128) r.skip(4)
            if (version >= 129) r.skip(8)
            if (version >= 130) r.skip(4)
            if (version >= 131) r.skip(4)
            if (version >= 132) r.skip(16)
            if (version >= 134) r.skip(1)
            if (version >= 136) r.skip(1)
            if (version >= 137) r.skip(16 + 2 * 12 * 4)
            if (version >= 0x8a) { r.skip(16); r.blob(4); blobs(4, r.count(true)) }
            if (version >= 0x8d) r.skip(14 + 0x100 + 2)
            if (version >= 0x8e) r.skip(4)
        }

        private fun part3() {
            r.skip(4)
            val count = r.i32()
            if (count < 0) return
            r.repeatCount(r.checkedCount(count)) {
                r.repeatCount(r.count()) { r.array(r.u8(), 4) }
            }
            val arrays = r.u32()
            if (arrays > 0x270f) return
            r.repeatCount(arrays.toInt()) { bank ->
                val values = r.count()
                // Proven by Game.exe 2.2961: assigning normal variable 17 changes bank 0, cell 17
                // in format 0x80. Other banks and unverified format variants remain opaque.
                if (version == 0x80 && bank == 0) {
                    require(values <= MAX_CELLS) { "Trop de variables Wolf." }
                    r.repeatCount(values) { id ->
                        val offset = r.position
                        variables += VariableCell(id, offset, r.i32())
                    }
                } else r.array(values, 4)
            }
            val textCount = r.i32()
            if (textCount < 0) return
            val textWidth = if (version < 0x6f) 2 else 4
            blobs(textWidth, r.checkedCount(textCount))
            r.repeatCount(r.count(maximum = 10000)) { r.array(r.u8(), 4) }
            val nestedTexts = r.u32()
            if (nestedTexts > 10000) return
            require(nestedTexts > 0) { "Structure des événements Wolf invalide." }
            r.repeatCount(nestedTexts.toInt()) { blobs(textWidth, r.u8()) }
        }

        private fun part4() {
            character()
            r.repeatCount(r.count()) { character() }
            r.skip(2)
            array32()
            if (version >= 0x8a) r.array(r.count(true), 8)
        }

        private fun part5() {
            val count = r.u16()
            if (count >= 0x8000) return
            r.repeatCount(count) {
                r.skip(10)
                r.blob(2)
                r.skip(12 * 4 + 2 * 3 * 4)
                if (version >= 0x69) r.skip(18 * 4)
                if (version >= 0x6b) r.skip(1)
                if (version >= 0x72) r.skip(2 + 8 * 4)
                if (version >= 0x73) {
                    r.skip(4 * 4)
                    if (version >= 0x74) r.skip(5 * 4)
                    if (version >= 0x75) r.skip(6 * 4)
                    r.skip(6 * 4)
                }
                if (version >= 0x76) r.skip(3 * 4)
                if (version >= 0x81) {
                    r.skip(21 * 4)
                    if (version >= 0x87) r.skip(5 * 4)
                    if (version >= 0x89) {
                        r.repeatCount(r.count(true)) { array32() }
                        r.skip(7 * 4)
                    }
                }
            }
        }

        private fun variableDatabase() {
            r.skip(1)
            r.repeatCount(r.count()) { type ->
                val flag = r.i32()
                if (flag <= -2) r.skip(4) // Data ID specification; preserved, not guessed.
                val fieldCount = if (flag <= -1) r.count() else r.checkedCount(flag)
                r.work(fieldCount)
                val config = LongArray(fieldCount) { r.u32() }
                val rowCount = r.count()
                require(fieldCount.toLong() * rowCount <= MAX_CELLS - numbers.size - strings.size) { "Base de variables Wolf trop volumineuse." }
                r.repeatCount(rowCount) { row ->
                    config.forEachIndexed { field, definition ->
                        if (definition < 2000) {
                            val offset = r.position
                            numbers += NumericCell(type, row, field, offset, r.i32())
                        }
                    }
                    config.forEachIndexed { field, definition ->
                        if (definition >= 2000) strings += TextCell(type, row, field, r.text(4, utf8))
                    }
                }
            }
        }

        private fun part7() {
            if (r.u8() != 1) return
            r.repeatCount(r.count()) { r.skip(if (r.u8() < 0xfa) 1 else 4) }
        }
    }
}
