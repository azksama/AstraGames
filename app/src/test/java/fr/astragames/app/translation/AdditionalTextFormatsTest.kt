package fr.astragames.app.translation

import fr.astragames.app.data.saves.Marshal
import fr.astragames.app.data.saves.MarshalValue
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class AdditionalTextFormatsTest {
    @Test fun renpyEditsOnlyDialogueAndPreservesCodeTagsAndLineEndings() {
        val source = "\uFEFFdefine e = Character(\"Name\")\r\nlabel start:\r\n    e happy \"Hello [player.name]! {b}World{/b}\" # keep\r\n    menu:\r\n        \"Go\":\r\n            jump next\r\n    python:\r\n        value = \"Hidden\"\r\n        \"Python literal\"\r\n    scene bg \"image.png\"\r\n"
        val doc = RenPyTextDocument("story.rpy", source.toByteArray())
        assertEquals(listOf("Hello [player.name]! {b}World{/b}", "Go"), doc.entries.map { it.text })
        val changed = doc.translatedEntries(mapOf(doc.entries.first().path to "Bonjour [player.name]! {b}Monde{/b}"))
        assertEquals(source.replace("Hello", "Bonjour").replace("World", "Monde"), changed.toString(Charsets.UTF_8))
        assertArrayEquals(source.toByteArray(), RenPyTextDocument("story.rpy", source.toByteArray()).translatedEntries(emptyMap()))
        assertThrows(IllegalArgumentException::class.java) { RenPyTextDocument("story.rpy", source.toByteArray()).translatedEntries(mapOf(doc.entries.first().path to "Bonjour [other]")) }
    }
    @Test fun renpyInterpolationCannotBeLostDuplicatedOrInjectedInManualExchange() {
        val text = "Hello [name!q], {color=#ff0}world{/color}, %(gold)d %% [[ {{"
        val masked = ProtectedText.mask(text, renpy = true)
        assertEquals(listOf("[name!q]", "{color=#ff0}", "{/color}", "%(gold)d", "%%", "[[", "{{"), masked.tokens)
        assertEquals(text.replace("Hello", "Bonjour"), ProtectedText.unmask(text, masked.source.replace("Hello", "Bonjour"), masked, renpy = true))
        assertThrows(IllegalArgumentException::class.java) { ProtectedText.unmask(text, masked.source + "{image=evil}", masked, renpy = true) }
    }
    @Test fun renpyQuotesAndEscapesRoundTripAndCannotEscapeIntoCode() {
        val bytes = "label start:\n    e 'Hello \\'world\\''\n    \"Choose\":\n        pass\n".toByteArray()
        val doc = RenPyTextDocument("script.rpy", bytes)
        val translated = doc.translatedEntries(mapOf(doc.entries.first().path to "L'ami dit \"oui\""))
        assertEquals("L'ami dit \"oui\"", RenPyTextDocument("script.rpy", translated).entries.first().text)
    }
    @Test fun rgssSchemaLeavesRubyPathsAndTilesOpaqueAndChecksActorIdentity() {
        fun string(value: String) = MarshalValue.StringValue(value.toByteArray())
        fun obj(name: String, vararg fields: Pair<String, MarshalValue>) = MarshalValue.ObjectValue(MarshalValue.SymbolValue(name), linkedMapOf(*fields.map { MarshalValue.SymbolValue(it.first) to it.second }.toTypedArray()))
        val actor = obj("RPG::Actor", "@id" to MarshalValue.IntValue(1), "@name" to string("Alice"), "@character_name" to string("Character.png"), "@note" to string("<script>"))
        val bytes = Marshal.dump(MarshalValue.ArrayValue(mutableListOf(MarshalValue.NilValue, actor)))
        val doc = RgssTextDocument("Actors.rvdata2", bytes)
        assertEquals(listOf("Alice"), doc.entries.map { it.text })
        assertTrue(doc.entries.single().identityGroup!!.startsWith("actor-name:1:"))
        val changed = doc.translatedEntries(mapOf(doc.entries.single().path to "Alicia"))
        val read = (Marshal.load(changed) as MarshalValue.ArrayValue).items[1] as MarshalValue.ObjectValue
        assertEquals("Character.png", (read.ivars[MarshalValue.SymbolValue("@character_name")] as MarshalValue.StringValue).text)
        assertEquals("<script>", (read.ivars[MarshalValue.SymbolValue("@note")] as MarshalValue.StringValue).text)
        assertArrayEquals(bytes, RgssTextDocument("Actors.rvdata2", bytes).translatedEntries(emptyMap()))
    }
    @Test fun wolfCp932AndCompressedUtf8PreserveOperandsAndRejectControlChanges() {
        for (packed in listOf(false, true)) {
            val bytes = wolfMap(packed)
            val doc = WolfTextDocument("MapData/Test.mps", bytes)
            assertEquals(listOf("Hello \\v[1]"), doc.entries.map { it.text })
            assertArrayEquals(bytes, doc.translatedEntries(emptyMap()))
            val translated = doc.translatedEntries(mapOf(doc.entries.single().path to "Bonjour \\v[1]"))
            assertEquals("Bonjour \\v[1]", WolfTextDocument("MapData/Test.mps", translated).entries.single().text)
            assertThrows(IllegalArgumentException::class.java) { WolfTextDocument("MapData/Test.mps", bytes).translatedEntries(mapOf(doc.entries.single().path to "Bonjour \\v[2]")) }
            assertThrows(IllegalArgumentException::class.java) { WolfTextDocument("MapData/Test.mps", bytes.copyOf(bytes.size - 1)) }
        }
    }
    @Test fun pathsExcludeScriptsArchivesAndTraversal() {
        assertFalse(GameTextDocuments.accepts("RENPY", "tl/fr/script.rpy"))
        assertFalse(GameTextDocuments.accepts("RENPY", "../script.rpy"))
        assertFalse(GameTextDocuments.accepts("RPG_MAKER_XP", "Scripts.rxdata"))
        assertFalse(GameTextDocuments.accepts("WOLF_RPG", "Data.wolf"))
        assertTrue(GameTextDocuments.accepts("WOLF_RPG", "MapData/Test.mps"))
    }
    private fun wolfMap(packed: Boolean): ByteArray {
        val raw = ByteArrayOutputStream()
        fun int(value: Int) { repeat(4) { raw.write(value ushr (8 * it) and 255) } }
        fun string(value: String) { val bytes = value.toByteArray(); int(bytes.size + 1); raw.write(bytes); raw.write(0) }
        string("Map"); int(0); int(1); int(1); int(1)
        if (packed) { int(0); int(3) }
        repeat(3) { int(0) }
        raw.write(byteArrayOf(0x6f, 0x39, 0x30, 0, 0)); int(7); string("Event"); int(0); int(0); int(1); int(0)
        raw.write(0x79); int(0); string(""); raw.write(ByteArray(4 + 37 + 4 + 2)); int(0)
        int(1); raw.write(2); int(101); int(123456); raw.write(0); raw.write(1); string("Hello \\v[1]"); raw.write(0)
        if (packed) raw.write(0)
        int(0); raw.write(ByteArray(3)); raw.write(0x7a); raw.write(0x70); raw.write(0x66)
        val payload = raw.toByteArray()
        val header = ByteArrayOutputStream().apply {
            write(ByteArray(10)); write("WOLFM".toByteArray()); write(0); write(if (packed) 0x55 else 0); write(ByteArray(3))
            repeat(4) { write((if (packed) 103 else 100) ushr (it * 8) and 255) }; write(if (packed) 0x69 else 0x65)
        }
        if (!packed) return header.toByteArray() + payload
        val block = ByteArrayOutputStream().apply {
            write(0xf0); var size = payload.size - 15; while (size >= 255) { write(255); size -= 255 }; write(size); write(payload)
        }.toByteArray()
        return header.apply { for (size in listOf(payload.size, block.size)) repeat(4) { write(size ushr (it * 8) and 255) }; write(block) }.toByteArray()
    }
}
