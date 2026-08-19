package fr.astragames.app.data.saves

import fr.astragames.app.data.mods.AstraModManifestParser
import fr.astragames.app.data.mods.ZipPathGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SaveAndModsTest {

    @Test
    fun marshalRoundTripPreservesEditedInteger() {
        val original = MarshalValue.ArrayValue(mutableListOf(
            MarshalValue.IntValue(12),
            MarshalValue.StringValue("hero".toByteArray()),
            MarshalValue.Bool(true)
        ))
        val bytes = Marshal.dump(original)
        val loaded = Marshal.load(bytes)
        assertTrue(MarshalFlattener.applyEdit(loaded, "root[0]", "99", SaveEntryType.INT))
        val rewritten = Marshal.load(Marshal.dump(loaded))
        val entries = MarshalFlattener.flatten(rewritten)
        assertEquals("99", entries.first { it.path == "root[0]" }.displayValue)
        assertEquals("hero", entries.first { it.path == "root[1]" }.displayValue)
    }

    @Test
    fun pickleSplicerReplacesIntegerWithoutBreakingNearbyValues() {
        val data = byteArrayOf(PickleParser.OP_BININT1.toByte(), 10, 97, 98, 99)
        val patched = PickleSplicer.spliceInt(data, 0..1, 250)
        assertEquals(PickleSplicer.OP_LONG1, patched[0].toInt() and 0xFF)
        assertEquals(97, patched[patched.size - 3].toInt())
        assertEquals(98, patched[patched.size - 2].toInt())
        assertEquals(99, patched[patched.size - 1].toInt())
    }

    @Test
    fun jsonEditorUpdatesNestedValue() {
        val json = JSONObject("""{"party":{"gold":100},"actors":[{"hp":20}]}""")
        JsonSaveEditor.apply(json, SaveEdit("root.party.gold", SaveEntryType.INT, "777"))
        JsonSaveEditor.apply(json, SaveEdit("root.actors[0].hp", SaveEntryType.INT, "50"))
        assertEquals(777, json.getJSONObject("party").getLong("gold"))
        assertEquals(50, json.getJSONArray("actors").getJSONObject(0).getLong("hp"))
    }

    @Test
    fun manifestParserReadsSupportedFields() {
        val manifest = AstraModManifestParser.parse("""{"formatVersion":1,"id":"example.mod","name":"Example","version":"1.0.0","author":"A","description":"D","engines":["RenPy"],"installMode":"OVERLAY","target":"game","filesRoot":"files"}""")
        assertEquals("example.mod", manifest.id)
        assertEquals("RENPY", manifest.engines.single())
        assertEquals("game", manifest.target)
        assertEquals("files", manifest.filesRoot)
    }

    @Test
    fun zipPathGuardRejectsTraversalAndAbsolutePaths() {
        assertNull(ZipPathGuard.sanitize("../secret.txt"))
        assertNull(ZipPathGuard.sanitize("/etc/passwd"))
        assertNull(ZipPathGuard.sanitize("C:/Windows/system32"))
        assertEquals("game/script.rpy", ZipPathGuard.sanitize("game/script.rpy"))
        assertNotNull(ZipPathGuard.sanitize("files/img/a.png"))
        assertFalse(ZipPathGuard.sanitize("ok/file.txt")!!.contains(".."))
    }
}
