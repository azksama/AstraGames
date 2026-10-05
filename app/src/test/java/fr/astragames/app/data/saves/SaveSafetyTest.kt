package fr.astragames.app.data.saves

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SaveSafetyTest {
    @Test fun nativeRubyCustomDumpReferencesResolveToTheirActualObjects() {
        val original = javaClass.getResourceAsStream("/saves/rgss-userdef-links.rxdata")!!.use { it.readBytes() }
        val values = (Marshal.load(original) as MarshalValue.ArrayValue).items
        val table = values[1] as MarshalValue.UserDefined
        assertEquals("shared table tag", (values[2] as MarshalValue.StringValue).text)
        assertSame(values[2], table.attributes["@tag"])
        assertSame(table, values[3])
        val entries = SaveCodec.entries(SaveCodec.read("RPG_MAKER_XP", original))
        assertTrue(entries.single { it.path == "root[2]" }.editable)
        assertFalse(entries.single { it.path == "root[3]" }.editable)
        for (name in listOf("rgss-userdef-links", "rgss-time")) {
            val fixture = javaClass.getResourceAsStream("/saves/$name.rxdata")!!.use { it.readBytes() }
            val patched = SaveCodec.patch("RPG_MAKER_XP", fixture, listOf(SaveEdit("root[0]", SaveEntryType.INT, "999")))
            val output = java.io.File("build/verification/$name-patched.rxdata")
            requireNotNull(output.parentFile).mkdirs(); output.writeBytes(patched)
        }
    }

    @Test fun nativeRubyScalarStreamHeadersAndSingleScalarRootsCanBeEdited() {
        val original = javaClass.getResourceAsStream("/saves/rgss-streams.rxdata")!!.use { it.readBytes() }
        val patched = SaveCodec.patch("RPG_MAKER_XP", original, listOf(SaveEdit("root[1]", SaveEntryType.INT, "99")))
        assertEquals(99L, (Marshal.loadAll(patched)[1] as MarshalValue.IntValue).value)
        val output = java.io.File("build/verification/rgss-streams-patched.rxdata")
        requireNotNull(output.parentFile).mkdirs(); output.writeBytes(patched)
        val scalar = SaveCodec.patch("RPG_MAKER_XP", Marshal.dump(MarshalValue.IntValue(1)), listOf(SaveEdit("root", SaveEntryType.INT, "2")))
        assertEquals(2L, (Marshal.load(scalar) as MarshalValue.IntValue).value)
    }

    @Test fun simpleFieldsDoNotMistakeExperienceLabelsOrLevelCapsForPlayerStats() {
        val entries = JsonSaveEditor.flatten(JSONObject("""{"experienceLabel":"XP","levelCap":99,"party":{"_gold":42},"actor":{"_level":7,"_exp":{"1":300}}}"""))
        val fields = SaveFieldClassifier.classify(entries)
        assertEquals("root.party._gold", fields.money?.path)
        assertEquals("root.actor._level", fields.level?.path)
        assertEquals("root.actor._exp.1", fields.experience?.path)
    }
    @Test fun jsonExMetadataCannotBeEditedButWrappedArraysRemainVisible() {
        val original = """{"party":{"@":"Game_Party","@c":1,"_gold":30},"variables":{"@a":[null,17],"@c":2},"ref":{"@r":1}}""".toByteArray()
        val entries = SaveCodec.entries(SaveCodec.read("RPG_MAKER_MV", original))
        assertFalse(entries.any { it.path.endsWith(".@") || it.path.endsWith(".@c") || it.path.endsWith(".@r") })
        assertTrue(entries.any { it.path == "root.variables.@a[1]" && it.editable })
        assertTrue(runCatching { SaveCodec.patch("RPG_MAKER_MV", original, listOf(SaveEdit("root.party.@c", SaveEntryType.INT, "9"))) }.isFailure)
        val patched = SaveCodec.patch("RPG_MAKER_MV", original, listOf(SaveEdit("root.variables.@a[1]", SaveEntryType.INT, "19")))
        assertEquals(1, RpgMakerSaveCodec.decode(patched).json.getJSONObject("party").getInt("@c"))
    }

    @Test fun unsafeJavascriptIntegerEditIsRejectedBeforeWriting() {
        assertTrue(runCatching {
            SaveCodec.patch("RPG_MAKER_MZ", "{\"gold\":1}".toByteArray(), listOf(SaveEdit("root.gold", SaveEntryType.INT, "9007199254740993")))
        }.isFailure)
    }

    @Test fun deepJsonFailsWithoutStackOverflowButBracketsInTextAreAllowed() {
        val malformed = "{\"x\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}"
        assertTrue(runCatching { RpgMakerSaveCodec.decode(malformed.toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("[[[", parseSaveJson("""{"text":"[[["}""").getString("text"))
    }

    @Test fun malformedLzBase64IsRejected() {
        assertTrue(runCatching { decodeRpgMakerJson("!".repeat(50)) }.isFailure)
    }

    @Test fun pickleLongStateAndBytearrayArePreservedWhileEditingMoney() {
        for (protocol in listOf(0, 2, 4, 5)) {
            val original = javaClass.getResourceAsStream("/saves/renpy-state-$protocol.pickle")!!.use { it.readBytes() }
            val patched = SaveCodec.patch("RENPY", original, listOf(SaveEdit("root[money]", SaveEntryType.INT, "999")))
            val entries = SaveCodec.entries(SaveCodec.read("RENPY", patched))
            assertEquals("999", entries.single { it.path == "root[money]" }.displayValue)
            assertFalse(entries.single { it.path == "root[large]" }.editable)
            val output = java.io.File("build/verification/renpy-state-patched-$protocol.pickle")
            requireNotNull(output.parentFile).mkdirs(); output.writeBytes(patched)
        }
    }

    @Test fun pythonTwoStringsRemainDiscoverableAndEditableWithoutBecomingUnicodeObjects() {
        val original = javaClass.getResourceAsStream("/saves/renpy-python2-strings.pickle")!!.use { it.readBytes() }
        val entries = SaveCodec.entries(SaveCodec.read("RENPY", original))
        assertEquals("root[str:money]", SaveFieldClassifier.classify(entries).money?.path)
        assertEquals("Joueur é", entries.single { it.path == "root[str:name]" }.displayValue)
        assertTrue(entries.single { it.path == "root[str:name]" }.editable)
        assertFalse(entries.single { it.path == "root[str:binary]" }.editable)
        val patched = SaveCodec.patch("RENPY", original, listOf(
            SaveEdit("root[str:money]", SaveEntryType.INT, "999"),
            SaveEdit("root[str:name]", SaveEntryType.STRING, "Joueur 日本語 😀")
        ))
        val name = findPickleNode(PickleParser(patched).parse(), "root[str:name]") as PickleNode.PBytes
        assertTrue(name.legacyString)
        val output = java.io.File("build/verification/renpy-python2-strings-patched.pickle")
        requireNotNull(output.parentFile).mkdirs(); output.writeBytes(patched)
    }

    @Test fun pickleTypedKeysAndEscapesDoNotCollide() {
        val data = javaClass.getResourceAsStream("/saves/renpy-keys.pickle")!!.use { it.readBytes() }
        val entries = SaveCodec.entries(SaveCodec.read("RENPY", data))
        assertEquals(entries.size, entries.map { it.path }.distinct().size)
        val intPath = entries.single { it.displayValue == "10" }.path
        val patched = SaveCodec.patch("RENPY", data, listOf(SaveEdit(intPath, SaveEntryType.INT, "11")))
        val after = SaveCodec.entries(SaveCodec.read("RENPY", patched))
        assertEquals("20", after.single { it.path == "root[1]" }.displayValue)
        assertEquals("11", after.single { it.path == "root[int:1]" }.displayValue)
        assertEquals("é", after.single { it.path == "root[escaped]" }.displayValue)
    }

    @Test fun editingMemoizedScalarCannotChangeAnotherValueOrDictionaryKey() {
        val data = javaClass.getResourceAsStream("/saves/renpy-alias.pickle")!!.use { it.readBytes() }
        val entries = SaveCodec.entries(SaveCodec.read("RENPY", data))
        assertFalse(entries.single { it.path == "root[name]" }.editable)
        assertTrue(runCatching { SaveCodec.patch("RENPY", data, listOf(SaveEdit("root[name]", SaveEntryType.STRING, "other"))) }.isFailure)
        assertTrue(entries.single { it.path == "root[money]" }.editable)
    }

    @Test fun pickleIncompleteFrameAndTrailingDataAreRejected() {
        val header = byteArrayOf(0x80.toByte(), 4, 0x95.toByte()) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(4).array()
        assertTrue(runCatching { PickleParser(header + byteArrayOf('N'.code.toByte(), '.'.code.toByte(), 'N'.code.toByte(), '.'.code.toByte())).parse() }.isFailure)
        assertTrue(runCatching { PickleParser(byteArrayOf('N'.code.toByte(), '.'.code.toByte(), 0)).parse() }.isFailure)
    }

    @Test fun protocolZeroEscapesAreDecodedWithoutPythonEvaluation() {
        assertEquals("a\né", decodePythonEscapes("a\\n\\xe9", false))
        assertEquals("日本語 😀", decodePythonEscapes("\\u65e5\\u672c\\u8a9e \\U0001f600", true))
        assertEquals("\\n", decodePythonEscapes("\\n", true))
    }

    @Test fun marshalCustomDumpStateRetainsOpaqueObjectsAndAttributes() {
        val binary = MarshalValue.UserDefined(MarshalValue.SymbolValue("Table"), byteArrayOf(0, 1, 2, 3)).apply {
            attributes["E"] = MarshalValue.Bool(false)
        }
        val state = MarshalValue.UserMarshal(MarshalValue.SymbolValue("GameState"), MarshalValue.ArrayValue(mutableListOf(MarshalValue.IntValue(30), binary)))
        val original = Marshal.dump(state)
        val patched = SaveCodec.patch("RPG_MAKER_VX_ACE", original, listOf(SaveEdit("root.marshal[0]", SaveEntryType.INT, "99")))
        val result = (Marshal.load(patched) as MarshalValue.UserMarshal).value as MarshalValue.ArrayValue
        assertEquals(99L, (result.items[0] as MarshalValue.IntValue).value)
        val preserved = result.items[1] as MarshalValue.UserDefined
        assertArrayEquals(binary.payload, preserved.payload)
        assertEquals(MarshalValue.Bool(false), preserved.attributes["E"])
    }

    @Test fun marshalExtendedAndUserClassValuesAreEditableWithoutLosingWrappers() {
        val root = MarshalValue.ExtendedValue(MarshalValue.SymbolValue("Rules"), MarshalValue.UserClass(MarshalValue.SymbolValue("Stats"), MarshalValue.ArrayValue(mutableListOf(MarshalValue.IntValue(7)))))
        val patched = SaveCodec.patch("RPG_MAKER_VX", Marshal.dump(root), listOf(SaveEdit("root[0]", SaveEntryType.INT, "8")))
        assertTrue(Marshal.load(patched) is MarshalValue.ExtendedValue)
        assertEquals("8", SaveCodec.entries(SaveCodec.read("RPG_MAKER_VX", patched)).single { it.path == "root[0]" }.displayValue)
    }

    @Test fun marshalBigIntegerRegexpAndClassReferenceDoNotPreventGoldEditing() {
        val root = MarshalValue.ArrayValue(mutableListOf(
            MarshalValue.BigIntValue(false, byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 1)),
            MarshalValue.RegexpValue("hello".toByteArray(), 1), MarshalValue.ClassReference('c', "Actor"), MarshalValue.IntValue(3)
        ))
        val patched = SaveCodec.patch("RPG_MAKER_XP", Marshal.dump(root), listOf(SaveEdit("root[3]", SaveEntryType.INT, "12")))
        val decoded = Marshal.load(patched) as MarshalValue.ArrayValue
        assertArrayEquals((root.items[0] as MarshalValue.BigIntValue).magnitude, (decoded.items[0] as MarshalValue.BigIntValue).magnitude)
        assertEquals(1, (decoded.items[1] as MarshalValue.RegexpValue).options)
        assertEquals("Actor", (decoded.items[2] as MarshalValue.ClassReference).name)
    }

    @Test fun rubyStringEncodingIsRetainedAndInvalidConversionsAreRejected() {
        val encoded = MarshalValue.StringValue(byteArrayOf(0xe9.toByte()), linkedMapOf("encoding" to MarshalValue.StringValue("ISO-8859-1".toByteArray())))
        val root = MarshalValue.ArrayValue(mutableListOf(encoded))
        val original = Marshal.dump(root)
        val patched = SaveCodec.patch("RPG_MAKER_VX", original, listOf(SaveEdit("root[0]", SaveEntryType.STRING, "à")))
        val result = (Marshal.load(patched) as MarshalValue.ArrayValue).items[0] as MarshalValue.StringValue
        assertArrayEquals(byteArrayOf(0xe0.toByte()), result.bytes)
        assertTrue(runCatching { SaveCodec.patch("RPG_MAKER_VX", original, listOf(SaveEdit("root[0]", SaveEntryType.STRING, "日本語"))) }.isFailure)
    }

    @Test fun marshalDeepOrHugeCollectionsFailBeforeAllocation() {
        val deep = byteArrayOf(4, 8) + List(1000) { byteArrayOf('['.code.toByte(), 6) }.fold(ByteArray(0)) { acc, next -> acc + next } + '0'.code.toByte()
        assertTrue(runCatching { Marshal.load(deep) }.exceptionOrNull() is IllegalArgumentException)
        val huge = byteArrayOf(4, 8, '['.code.toByte(), 4, -1, -1, -1, 0x7f)
        assertTrue(runCatching { Marshal.load(huge) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun boundedStreamHandlesZeroLengthReadsAndRefusesOverflow() {
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int = if (available() == 3) 0 else super.read(target, offset, length)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), input.readBounded(3))
        assertTrue(runCatching { ByteArrayInputStream(byteArrayOf(1, 2)).readBounded(1) }.isFailure)
    }
}
