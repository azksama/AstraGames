package fr.astragames.app.data.saves

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opt-in local integration fixtures produced by unchanged official Game.exe. Skips without them. */
class WolfRealSaveCodecTest {
    private val legacyPath = "build/wolf-research/wolf-data-proof/official-1-1791837212213816600/Save/SaveData01.sav"
    private val recentPath = "build/wolf-research/wolf-data-proof/official-2-1791837220246889100/Save/SaveData01.sav"
    private fun optionalFile(relative: String): File {
        val found = listOf(File(relative), File("../$relative")).firstOrNull { it.isFile }
        assumeNotNull(found)
        return found!!
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun realWolf2SaveAgreesWithUnchangedCppParserAndControlledVariableAssignment() {
        val bytes = optionalFile(legacyPath).readBytes()
        assertEquals("841ae6b9bdbfcea168c91260c0fa445123f5eb336f2b44c58f6329923e11846d", sha(bytes))
        val doc = WolfSaveCodec.read(bytes)
        val reference = JSONObject(optionalFile("build/mtool-implementation/wolf-save-reference-real-w2-corrected.json").readText())
        assertEquals(0x80, doc.formatVersion)
        assertTrue(doc.checksumValid)
        assertEquals(17_580, doc.numbers.size)
        assertEquals(1_538, doc.strings.size)
        assertEquals(18, doc.variables.size)
        assertEquals(271828, doc.variables[17].value)
        assertEquals(14257, doc.variables[17].offset)
        doc.sections.forEachIndexed { index, section ->
            val bounds = reference.getJSONArray("sections").getJSONArray(index)
            assertEquals(bounds.getInt(0), section.start)
            assertEquals(bounds.getInt(1), section.endExclusive)
        }
        doc.numbers.forEachIndexed { index, cell ->
            val fields = reference.getJSONArray("numbers").getJSONArray(index)
            assertEquals(fields.getInt(0), cell.typeId)
            assertEquals(fields.getInt(1), cell.rowId)
            assertEquals(fields.getInt(2), cell.fieldId)
            assertEquals(fields.getInt(3), cell.value)
        }
    }

    @Test fun realWolf2NormalVariablePatchChangesOnlyTheCellAndChecksum() {
        val original = optionalFile(legacyPath).readBytes()
        val before = WolfSaveCodec.read(original)
        val patched = SaveCodec.patch("WOLF_RPG", original, listOf(SaveEdit("root.variables[17]", SaveEntryType.VARIABLE, "314159")))
        val after = WolfSaveCodec.read(patched)
        assertEquals("26a67b594055a573e32c1d647f0585d8d123c7a6cd66855f2cee647ac921878c", sha(patched))
        assertEquals(listOf(2, 14257, 14258), original.indices.filter { original[it] != patched[it] })
        assertEquals(314159, after.variables[17].value)
        assertEquals(before.numbers, after.numbers)
        assertEquals(before.strings, after.strings)
        assertEquals(before.sections, after.sections)
        assertTrue(after.checksumValid)
        assertEquals(before.variables.take(17), after.variables.take(17))
        assertTrue(runCatching { WolfSaveCodec.patch(original, listOf(SaveEdit("root.variables[18]", SaveEntryType.VARIABLE, "1"))) }.isFailure)
        assertArrayEquals(original, optionalFile(legacyPath).readBytes())
    }

    @Test fun realWolf3729SaveIsRefusedWithoutTouchingTheOriginal() {
        val original = optionalFile(recentPath).readBytes()
        assertEquals("cbf4f4a427c35a9ae52557dc10aa672943b0e8f3dd416cbaa3a01445dc936e4d", sha(original))
        val read = runCatching { SaveCodec.read("WOLF_RPG", original) }
        assertTrue(read.isFailure)
        assertTrue(read.exceptionOrNull()!!.message.orEmpty().contains("Format de sauvegarde Wolf"))
        assertTrue(runCatching { SaveCodec.patch("WOLF_RPG", original, listOf(SaveEdit("root.variables[17]", SaveEntryType.VARIABLE, "314159"))) }.isFailure)
        assertArrayEquals(original, optionalFile(recentPath).readBytes())
    }
}
