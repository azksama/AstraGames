package fr.astragames.app.data.saves

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Golden data is generated with native MSVCRT, not with WolfSaveCodec. */
class WolfSaveCodecTest {
    private val manifest by lazy {
        JSONObject(resource("manifest.json").toString(Charsets.UTF_8))
    }
    private fun resource(name: String) = javaClass.getResourceAsStream("/saves/wolf/$name")!!.use { it.readBytes() }
    private fun names(): List<String> = manifest.getJSONObject("fixtures").keys().asSequence().toList()
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun sample() = resource("format-008e-utf8.sav")
    private fun editedPlain(bytes: ByteArray, block: (ByteArray) -> Unit): ByteArray {
        val plain = WolfSaveCodec.crypt(bytes)
        block(plain)
        plain[2] = WolfSaveCodec.checksum(plain).toByte()
        return WolfSaveCodec.crypt(plain)
    }
    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0..3) bytes[offset + index] = (value ushr (8 * index)).toByte()
    }
    private fun rejects(block: () -> Any) {
        assertTrue("Malformed or unsupported input must be refused", runCatching(block).isFailure)
    }

    @Test fun cipherMatchesTheWindowsCrtVectorAndLeavesHeaderUntouched() {
        val vector = manifest.getJSONObject("cipherVector")
        val plain = hex(vector.getString("plaintextHex"))
        val encrypted = hex(vector.getString("ciphertextHex"))
        assertArrayEquals(encrypted, WolfSaveCodec.crypt(plain))
        assertArrayEquals(plain, WolfSaveCodec.crypt(encrypted))
        assertArrayEquals(plain.take(20).toByteArray(), encrypted.take(20).toByteArray())
    }

    @Test fun allGoldenFormatsWalkSevenSectionsAndDeclaredCellsInBothEncodings() {
        for (name in names()) {
            val expected = manifest.getJSONObject("fixtures").getJSONObject(name)
            val doc = WolfSaveCodec.read(resource(name))
            assertEquals(name, expected.getInt("format"), doc.formatVersion)
            assertEquals(name, expected.getBoolean("utf8"), doc.utf8)
            assertTrue(name, doc.checksumValid)
            assertEquals("Astra Test 日本語", doc.gameName)
            assertEquals(7, doc.sections.size)
            doc.sections.forEachIndexed { index, section ->
                val range = expected.getJSONObject("sections").getJSONArray((index + 1).toString())
                assertEquals(name, range.getInt(0), section.start)
                assertEquals(name, range.getInt(1), section.endExclusive)
            }
            val cells = expected.getJSONObject("numbers")
            assertEquals(cells.length(), doc.numbers.size)
            doc.numbers.forEach { cell ->
                val expectedCell = cells.getJSONObject(cell.path)
                assertEquals(expectedCell.getInt("offset"), cell.offset)
                assertEquals(expectedCell.getInt("value"), cell.value)
            }
            assertEquals("日本語", doc.strings.first().text)
            if (doc.formatVersion == 0x80) assertEquals(listOf(1, 2), doc.variables.map { it.value })
            else assertTrue(doc.variables.isEmpty())
            assertTrue(doc.entries().filter { it.type == SaveEntryType.VARIABLE }.all { it.editable })
            assertTrue(doc.entries().filter { it.type != SaveEntryType.VARIABLE }.none { it.editable })
        }
    }

    @Test fun patchChangesOnlyRequestedInt32CellsAndTheChecksum() {
        for (name in names()) {
            val bytes = resource(name)
            val before = WolfSaveCodec.read(bytes)
            val cell = before.numbers.first()
            val edit = SaveEdit(cell.path, SaveEntryType.VARIABLE, "-2147483648")
            val patched = SaveCodec.patch("WOLF_RPG", bytes, listOf(edit))
            val after = WolfSaveCodec.read(patched)
            assertEquals(Int.MIN_VALUE, after.numbers.first().value)
            assertEquals(before.numbers.drop(1), after.numbers.drop(1))
            assertEquals(before.strings, after.strings)
            assertEquals(before.variables, after.variables)
            assertEquals(before.sections, after.sections)
            assertEquals(bytes.size, patched.size)
            val allowed = (cell.offset until cell.offset + 4).toSet() + 2
            val difference = bytes.indices.filter { bytes[it] != patched[it] }
            assertTrue(name, difference.isNotEmpty() && difference.all { it in allowed })
            assertArrayEquals(bytes, resource(name))
        }
    }

    @Test fun checksumMismatchIsReadableButNeverWritable() {
        val corrupted = sample().also { it[2] = (it[2].toInt() xor 1).toByte() }
        val doc = WolfSaveCodec.read(corrupted)
        assertFalse(doc.checksumValid)
        assertTrue(doc.entries().none { it.editable })
        val edit = SaveEdit(doc.numbers.first().path, SaveEntryType.VARIABLE, "9")
        rejects { WolfSaveCodec.patch(corrupted, listOf(edit)) }
        rejects { SaveCodec.patch("WOLF_RPG", corrupted, listOf(edit)) }
    }

    @Test fun unknownVersionMagicTrailingBytesAndAllTruncationsAreRejected() {
        val bytes = sample()
        val doc = WolfSaveCodec.read(bytes)
        val versionOffset = doc.sections.first().start - 2
        for (version in listOf(0, WolfSaveCodec.HIGHEST_FORMAT_VERSION + 1, 0xffff)) {
            rejects { WolfSaveCodec.read(editedPlain(bytes) {
                it[versionOffset] = version.toByte()
                it[versionOffset + 1] = (version ushr 8).toByte()
            }) }
        }
        rejects { WolfSaveCodec.read(editedPlain(bytes) { it[20] = 0 }) }
        rejects { WolfSaveCodec.read(editedPlain(bytes) { it[it.lastIndex] = 0 }) }
        rejects { WolfSaveCodec.read(bytes + byteArrayOf(0)) }
        for (length in bytes.indices) rejects { WolfSaveCodec.read(bytes.copyOf(length)) }
    }

    @Test fun declaredNumbersCannotChangeTypeWidthOrTouchStringsAndMetadata() {
        val bytes = sample()
        val doc = WolfSaveCodec.read(bytes)
        val path = doc.numbers.first().path
        for (value in listOf("2147483648", "-2147483649", "1.5", "NaN", "true", "999999999999999999999999")) {
            rejects { WolfSaveCodec.patch(bytes, listOf(SaveEdit(path, SaveEntryType.VARIABLE, value))) }
        }
        for (entry in doc.entries().filter { !it.editable }) {
            rejects { WolfSaveCodec.patch(bytes, listOf(SaveEdit(entry.path, entry.type, "5"))) }
        }
        rejects { WolfSaveCodec.patch(bytes, listOf(SaveEdit(path, SaveEntryType.INT, "1"))) }
        rejects { WolfSaveCodec.patch(bytes, listOf(SaveEdit("root.variableDatabase.types[999].rows[0].fields[0]", SaveEntryType.VARIABLE, "1"))) }
        val edit = SaveEdit(path, SaveEntryType.VARIABLE, "10")
        rejects { WolfSaveCodec.patch(bytes, listOf(edit, edit)) }
        rejects { WolfSaveCodec.patch(bytes, emptyList()) }
    }

    @Test(timeout = 3000) fun hostileCountsAndStringLengthsFailBeforeAllocationOrLongLoops() {
        val bytes = sample()
        val doc = WolfSaveCodec.read(bytes)
        val db = doc.sections[5].start
        for (offset in listOf(21, db + 1, db + 13, db + 33)) {
            rejects { WolfSaveCodec.read(editedPlain(bytes) { putInt(it, offset, Int.MAX_VALUE) }) }
        }
        val map = doc.sections.first().start
        rejects { WolfSaveCodec.read(editedPlain(bytes) { putInt(it, map + 12, 500_000); putInt(it, map + 16, 500_000) }) }
        val string = doc.numbers[1].offset + 4
        rejects { WolfSaveCodec.read(editedPlain(bytes) { putInt(it, string, -1) }) }
    }

    @Test fun unsignedFieldDefinitionsNeverBecomeEditableNumbersBySignOverflow() {
        val bytes = sample()
        val db = WolfSaveCodec.read(bytes).sections[5].start
        // First numeric definition 0 changes to 0xffffffff: it is a text field, not a signed -1 number.
        rejects { WolfSaveCodec.read(editedPlain(bytes) { putInt(it, db + 17, -1) }) }
    }
}
