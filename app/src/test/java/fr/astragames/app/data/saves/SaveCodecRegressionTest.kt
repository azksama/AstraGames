package fr.astragames.app.data.saves

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SaveCodecRegressionTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/saves/$name")!!.use { it.readBytes() }

    @Test fun realPythonZipProtocolsRemainReadableAfterLongerEdits() {
        for (protocol in listOf(2, 4, 5)) {
            val original = fixture("renpy-protocol-$protocol.save")
            val patched = SaveCodec.patch("RENPY", original, listOf(
                SaveEdit("root[0][store.money]", SaveEntryType.INT, "9999999"),
                SaveEdit("root[0][store.name]", SaveEntryType.STRING, "Joueur 日本語 😀 plus long"),
                SaveEdit("root[0][store.flag]", SaveEntryType.BOOLEAN, "true")
            ))
            java.io.File("build/verification").mkdirs()
            java.io.File("build/verification/renpy-patched-$protocol.save").writeBytes(patched)
            val decoded = RenPyArchive.decode(patched)
            assertArrayEquals(RenPyArchive.decode(original).entries!!["screenshot.png"], decoded.entries!!["screenshot.png"])
            assertArrayEquals(ByteArray(0), decoded.entries["signatures"])
            val parser = PickleParser(decoded.payload)
            val root = parser.parse() as PickleNode.PTuple
            assertSame(root.items[0], ((root.items[1] as PickleNode.PObject).state as PickleNode.PDict).entries.values.first())
            parser.frames.forEach { (start, end) ->
                assertEquals((end - start - 9).toLong(), ByteBuffer.wrap(decoded.payload, start + 1, 8).order(ByteOrder.LITTLE_ENDIAN).long)
            }
        }
    }

    @Test fun pickleMemoReadsDoNotExtendLeafByteRanges() {
        val bytes = byteArrayOf(0x80.toByte(), 2, ']'.code.toByte(), '('.code.toByte(), 'X'.code.toByte(), 1, 0, 0, 0, 'x'.code.toByte(), 'q'.code.toByte(), 0, 'h'.code.toByte(), 0, 'e'.code.toByte(), '.'.code.toByte())
        val parser = PickleParser(bytes)
        val root = parser.parse() as PickleNode.PList
        assertEquals(4..9, parser.range(root.items[0]))
        assertSame(root.items[0], root.items[1])
    }

    @Test fun mzFixturePreservesZlibFormatAndJsonExMetadata() {
        val patched = SaveCodec.patch("RPG_MAKER_MZ", fixture("mz.rmmzsave"), listOf(SaveEdit("root.party._gold", SaveEntryType.INT, "999")))
        val decoded = RpgMakerSaveCodec.decode(patched)
        assertEquals(RpgMakerSaveCodec.Format.MZ_ZLIB, decoded.format)
        assertEquals("Game_Party", decoded.json.getJSONObject("party").getString("@"))
        assertEquals("Élève 日本語 😀", decoded.json.getString("message"))
    }

    @Test fun plainJsonStaysPlainAndHighVariableIndicesAreVisible() {
        val json = org.json.JSONObject().put("variables", org.json.JSONArray((0..900).toList()))
        val patched = SaveCodec.patch("RPG_MAKER_MV", json.toString().toByteArray(), listOf(SaveEdit("root.variables[900]", SaveEntryType.INT, "42")))
        assertEquals('{', patched.toString(Charsets.UTF_8).first())
    }

    @Test fun unusualJsonKeysDoNotEditNestedValues() {
        val original = """{"a.b[0]%":1,"a":{"b":[2]}}""".toByteArray()
        val path = SaveCodec.entries(SaveCodec.read("RPG_MAKER_MV", original)).single { it.displayValue == "1" }.path
        val result = RpgMakerSaveCodec.decode(SaveCodec.patch("RPG_MAKER_MV", original, listOf(SaveEdit(path, SaveEntryType.INT, "9")))).json
        assertEquals(9, result.getInt("a.b[0]%"))
        assertEquals(2, result.getJSONObject("a").getJSONArray("b").getInt(0))
    }

    @Test fun marshalHashKeysAndCyclesStayDistinctAndEditable() {
        val root = MarshalValue.HashValue(linkedMapOf(
            MarshalValue.IntValue(1) to MarshalValue.IntValue(10),
            MarshalValue.StringValue("1".toByteArray()) to MarshalValue.IntValue(20),
            MarshalValue.SymbolValue("a]b") to MarshalValue.IntValue(30)
        ))
        root.entries[MarshalValue.SymbolValue("self")] = root
        val entries = MarshalFlattener.flatten(root)
        assertEquals(entries.size, entries.distinctBy { it.path }.size)
        val edit = entries.single { it.displayValue == "10" }
        val result = SaveCodec.patch("RPG_MAKER_VX", Marshal.dump(root), listOf(SaveEdit(edit.path, edit.type, "11")))
        val decoded = Marshal.load(result) as MarshalValue.HashValue
        assertEquals(11L, (decoded.entries[MarshalValue.IntValue(1)] as MarshalValue.IntValue).value)
        assertEquals(20L, (decoded.entries[MarshalValue.StringValue("1".toByteArray())] as MarshalValue.IntValue).value)
        assertSame(decoded, decoded.entries[MarshalValue.SymbolValue("self")])
    }

    @Test fun invalidBooleansAndNonFiniteNumbersAreRejected() {
        for (edit in listOf(SaveEdit("root", SaveEntryType.BOOLEAN, "yes"), SaveEdit("root", SaveEntryType.FLOAT, "NaN"))) {
            assertTrue(runCatching { SaveValues.parse(edit) }.isFailure)
        }
    }

    @Test fun truncatedCompressionAndPickleFailPromptly() {
        assertTrue(runCatching { inflateSave(deflateSave("abc".toByteArray()).dropLast(3).toByteArray()) }.isFailure)
        assertTrue(runCatching { PickleParser(byteArrayOf(0x80.toByte(), 2, 'N'.code.toByte())).parse() }.isFailure)
    }

    @Test fun marshalNegativeIntegersMatchRubyEncoding() {
        for (value in listOf(-1L, -122, -123, -124, -256, -32768, Int.MIN_VALUE.toLong())) {
            assertEquals(value, (Marshal.load(Marshal.dump(MarshalValue.IntValue(value))) as MarshalValue.IntValue).value)
        }
        assertArrayEquals(byteArrayOf(4, 8, 'i'.code.toByte(), 250.toByte()), Marshal.dump(MarshalValue.IntValue(-1)))
    }

    @Test fun marshalPreservesCyclesAndStringEncodingReferences() {
        val text = MarshalValue.StringValue("hero".toByteArray(), linkedMapOf("E" to MarshalValue.Bool(true)))
        val root = MarshalValue.ArrayValue(mutableListOf(text, text))
        root.items += root
        val result = Marshal.load(Marshal.dump(root)) as MarshalValue.ArrayValue
        assertSame(result.items[0], result.items[1])
        assertSame(result, result.items[2])
        assertEquals(MarshalValue.Bool(true), (result.items[0] as MarshalValue.StringValue).ivars["E"])
    }

    @Test fun rgssMultipleStreamsKeepAllObjects() {
        val original = Marshal.dump(MarshalValue.ArrayValue(mutableListOf(MarshalValue.IntValue(3)))) + Marshal.dump(MarshalValue.IntValue(17))
        val patched = SaveCodec.patch("RPG_MAKER_XP", original, listOf(SaveEdit("root[0][0]", SaveEntryType.INT, "9")))
        assertEquals(2, Marshal.loadAll(patched).size)
        assertEquals(17L, (Marshal.loadAll(patched)[1] as MarshalValue.IntValue).value)
    }
}
