package fr.astragames.app.translation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManualTranslationBundleTest {
    private val source = """{"displayName":"Village","note":"<doNotTranslate:Hello>","events":[null,{"name":"DO_NOT_CHANGE","pages":[{"list":[{"code":401,"parameters":["Hello \\C[\\V[1]]world\\C[0]!"]},{"code":102,"parameters":[["Yes","No"],0]},{"code":355,"parameters":["run('Hello')"]},{"code":357,"parameters":["Plugin","Hello",{"label":"Hello"}]}]}]}]}""".toByteArray()
    private fun documents(bytes: ByteArray = source) = listOf(TranslationSnapshot("Map001.json", textHash(bytes), RpgTextDocument("Map001.json", bytes).entries))
    private fun bundle() = JSONObject(ManualTranslationBundle.export("game-1", "Example", "RPG_MAKER_MZ", "en", "fr", documents()).toString(Charsets.UTF_8))
    private fun load(value: JSONObject, game: String = "game-1", docs: List<TranslationSnapshot> = documents()) =
        ManualTranslationBundle.import(value.toString().toByteArray(), game, "RPG_MAKER_MZ", docs)

    @Test fun exportsEveryVisibleOccurrenceWithStableContextAndRoundTripsCommands() {
        val value = bundle()
        val entries = value.getJSONArray("entries")
        assertEquals(4, entries.length())
        assertEquals("/displayName", entries.getJSONObject(0).getString("path"))
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            entry.put("translation", entry.getString("source").replace("Village", "Bourg").replace("Hello", "Bonjour").replace("world", "monde").replace("Yes", "Oui").replace("No", "Non"))
        }
        val imported = load(value)
        assertEquals(0, imported.preserved)
        val translated = JSONObject(RpgTextDocument("Map001.json", source).translatedEntries(imported.replacements.getValue("Map001.json")).toString(Charsets.UTF_8))
        assertEquals("Bourg", translated.getString("displayName"))
        assertEquals("<doNotTranslate:Hello>", translated.getString("note"))
        val commands = translated.getJSONArray("events").getJSONObject(1).getJSONArray("pages").getJSONObject(0).getJSONArray("list")
        assertEquals("Bonjour \\C[\\V[1]]monde\\C[0]!", commands.getJSONObject(0).getJSONArray("parameters").getString(0))
        assertEquals("run('Hello')", commands.getJSONObject(2).getJSONArray("parameters").getString(0))
        assertEquals("Hello", commands.getJSONObject(3).getJSONArray("parameters").getJSONObject(2).getString("label"))
        assertEquals("en", imported.source); assertEquals("fr", imported.target)
    }

    @Test fun emptyTranslationsPreserveAllOriginalTextAndExportsAreDeterministic() {
        val value = bundle()
        val imported = load(value)
        assertEquals(4, imported.preserved)
        val current = RpgTextDocument("Map001.json", source)
        current.entries.forEach { assertEquals(it.text, imported.replacements.getValue("Map001.json")[it.path]) }
        val second = bundle().getJSONArray("entries")
        assertEquals(value.getJSONArray("entries").toString(), second.toString())
    }

    @Test fun rejectsDifferentGameEngineOrModifiedSourceBeforeReturningChanges() {
        assertThrows(IllegalArgumentException::class.java) { load(bundle(), game = "other") }
        assertThrows(IllegalArgumentException::class.java) { load(bundle().put("engine", "RPG_MAKER_MV")) }
        val changed = source.toString(Charsets.UTF_8).replace("Village", "New village").toByteArray()
        assertThrows(IllegalArgumentException::class.java) { load(bundle(), docs = documents(changed)) }
        assertThrows(IllegalArgumentException::class.java) { load(bundle().put("version", 2)) }
        assertThrows(IllegalArgumentException::class.java) { load(bundle().put("version", "1")) }
    }

    @Test fun rejectsRemovedDuplicatedUnknownAndRelocatedEntries() {
        val missing = bundle(); missing.getJSONArray("entries").remove(0)
        assertThrows(IllegalArgumentException::class.java) { load(missing) }
        val duplicate = bundle(); duplicate.getJSONArray("entries").put(1, duplicate.getJSONArray("entries").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { load(duplicate) }
        val unknown = bundle(); unknown.getJSONArray("entries").getJSONObject(0).put("id", "unknown")
        assertThrows(IllegalStateException::class.java) { load(unknown) }
        val relocated = bundle(); relocated.getJSONArray("entries").getJSONObject(0).put("path", "/note")
        assertThrows(IllegalArgumentException::class.java) { load(relocated) }
        val traversal = bundle(); traversal.getJSONArray("entries").getJSONObject(0).put("file", "../System.json")
        assertThrows(IllegalArgumentException::class.java) { load(traversal) }
    }

    @Test fun rejectsMissingRepeatedReorderedOrModifiedCommandPlaceholders() {
        val originalEntry = bundle().getJSONArray("entries").getJSONObject(1)
        val original = originalEntry.getString("source")
        val first = originalEntry.getJSONArray("protectedTokens").getJSONObject(0).getString("marker")
        val second = originalEntry.getJSONArray("protectedTokens").getJSONObject(1).getString("marker")
        listOf(
            original.replace(first, ""), original + first,
            original.replace(first, "SWAP").replace(second, first).replace("SWAP", second),
            original.replace(first, "⟦ASTRA_broken_0⟧"), original + "\\V[99]", original + "\nInjected", original + "\u0000"
        ).forEach { text ->
            val value = bundle(); value.getJSONArray("entries").getJSONObject(1).put("translation", text)
            assertThrows(IllegalArgumentException::class.java) { load(value) }
        }
        val damaged = bundle()
        damaged.getJSONArray("entries").getJSONObject(1).getJSONArray("protectedTokens").getJSONObject(0).put("value", "\\V[99]")
        assertThrows(IllegalArgumentException::class.java) { load(damaged) }
    }

    @Test fun rejectsSourceChangesMissingFilesWrongTypesAndOversizedTranslations() {
        val modified = bundle(); modified.getJSONArray("entries").getJSONObject(0).put("source", "changed")
        assertThrows(IllegalArgumentException::class.java) { load(modified) }
        assertThrows(IllegalArgumentException::class.java) { load(bundle().put("files", JSONArray())) }
        val wrongType = bundle(); wrongType.getJSONArray("entries").getJSONObject(0).put("translation", 42)
        assertThrows(IllegalStateException::class.java) { load(wrongType) }
        val large = bundle(); large.getJSONArray("entries").getJSONObject(0).put("translation", "x".repeat(4097))
        assertThrows(IllegalArgumentException::class.java) { load(large) }
    }

    @Test fun contextDistinguishesIdenticalTextAcrossOccurrences() {
        val bytes = """[null,{"name":"Hello","nickname":"Hello","profile":"Hello"}]""".toByteArray()
        val doc = RpgTextDocument("Actors.json", bytes)
        val snapshots = listOf(TranslationSnapshot("Actors.json", textHash(bytes), doc.entries))
        val value = JSONObject(ManualTranslationBundle.export("game-1", "Example", "RPG_MAKER_MZ", "en", "fr", snapshots).toString(Charsets.UTF_8))
        val entries = value.getJSONArray("entries")
        assertEquals(3, (0 until entries.length()).map { entries.getJSONObject(it).getString("id") }.distinct().size)
        entries.getJSONObject(0).put("translation", "Nom")
        entries.getJSONObject(1).put("translation", "Surnom")
        entries.getJSONObject(2).put("translation", "Profil")
        val translated = JSONArray(doc.translatedEntries(load(value, docs = snapshots).replacements.getValue("Actors.json")).toString(Charsets.UTF_8)).getJSONObject(1)
        assertEquals("Nom", translated.getString("name")); assertEquals("Surnom", translated.getString("nickname")); assertEquals("Profil", translated.getString("profile"))
    }

    @Test fun explicitUnchangedNamesAreCompleteButEmptyTranslationsArePreserved() {
        val value = bundle()
        val rows = value.getJSONArray("entries")
        for (index in 0 until rows.length()) rows.getJSONObject(index).put("translation", rows.getJSONObject(index).getString("source"))
        assertEquals(0, load(value).preserved)
        rows.getJSONObject(0).put("translation", "")
        assertEquals(1, load(value).preserved)
    }

    @Test fun markerOverheadDoesNotRejectValidShortCommandRichTranslations() {
        val original = "First\u000c" + "\\G ".repeat(500) + "Last"
        val bytes = JSONArray().put(JSONObject.NULL).put(JSONObject().put("profile", original)).toString().toByteArray()
        val snapshots = listOf(TranslationSnapshot("Actors.json", textHash(bytes), RpgTextDocument("Actors.json", bytes).entries))
        val value = JSONObject(ManualTranslationBundle.export("game-1", "Example", "RPG_MAKER_MZ", "en", "fr", snapshots).toString(Charsets.UTF_8))
        val row = value.getJSONArray("entries").getJSONObject(0)
        assertTrue(row.getString("source").length > original.length * 8)
        row.put("translation", row.getString("source").replace("First", "Premier").replace("Last", "Dernier"))
        assertEquals(original.replace("First", "Premier").replace("Last", "Dernier"),
            load(value, docs = snapshots).replacements.getValue("Actors.json").getValue("/1/profile"))
        row.put("translation", row.getString("source") + "x".repeat(original.length * 8))
        assertThrows(IllegalArgumentException::class.java) { load(value, docs = snapshots) }
    }

    @Test fun existingSourceLongerThanTranslationExpansionCapCanRoundTrip() {
        val original = "a".repeat(1_048_577)
        val bytes = JSONArray().put(JSONObject.NULL).put(JSONObject().put("profile", original)).toString().toByteArray()
        val snapshots = listOf(TranslationSnapshot("Actors.json", textHash(bytes), RpgTextDocument("Actors.json", bytes).entries))
        val value = JSONObject(ManualTranslationBundle.export("game-1", "Example", "RPG_MAKER_MZ", "en", "fr", snapshots).toString(Charsets.UTF_8))
        value.getJSONArray("entries").getJSONObject(0).put("translation", original)
        assertEquals(original, load(value, docs = snapshots).replacements.getValue("Actors.json").getValue("/1/profile"))
    }

    @Test fun actorNamesAssignmentsAndNameConditionsMustAgreeAcrossFiles() {
        val actors = """[null,{"id":1,"name":"Hero"},{"id":2,"name":"Hero"}]""".toByteArray()
        val common = """[null,{"list":[{"code":320,"parameters":[1,"Hero"]},{"code":111,"parameters":[4,1,1,"Hero"]},{"code":111,"parameters":[12,"actor.name() === 'Hero'"]},{"code":320,"parameters":[2,"Hero"]}]}]""".toByteArray()
        val snapshots = listOf("Actors.json" to actors, "CommonEvents.json" to common).map { (file, bytes) ->
            TranslationSnapshot(file, textHash(bytes), RpgTextDocument(file, bytes).entries)
        }
        val value = JSONObject(ManualTranslationBundle.export("game-1", "Example", "RPG_MAKER_MZ", "en", "fr", snapshots).toString(Charsets.UTF_8))
        val rows = value.getJSONArray("entries")
        assertEquals(5, rows.length())
        assertEquals(rows.getJSONObject(0).getString("identityGroup"), rows.getJSONObject(3).getString("identityGroup"))
        assertNotEquals(rows.getJSONObject(0).getString("identityGroup"), rows.getJSONObject(1).getString("identityGroup"))
        assertTrue(rows.getJSONObject(0).getBoolean("preserveOriginal"))
        assertTrue(rows.getJSONObject(3).getString("readOnlyReason").contains("condition"))
        assertFalse(rows.getJSONObject(1).has("preserveOriginal"))
        for (index in 0 until rows.length()) rows.getJSONObject(index).put("translation", if (index == 1 || index == 4) "Héroïne" else "Hero")
        val imported = load(value, docs = snapshots)
        assertEquals("Hero", imported.replacements.getValue("CommonEvents.json").getValue("/1/list/1/parameters/3"))
        assertEquals("Héroïne", imported.replacements.getValue("CommonEvents.json").getValue("/1/list/3/parameters/1"))
        val translated = JSONArray(RpgTextDocument("CommonEvents.json", common).translatedEntries(imported.replacements.getValue("CommonEvents.json")).toString(Charsets.UTF_8))
        assertEquals("actor.name() === 'Hero'", translated.getJSONObject(1).getJSONArray("list").getJSONObject(2).getJSONArray("parameters").getString(1))
        rows.getJSONObject(3).put("translation", "Autre nom")
        assertThrows(IllegalArgumentException::class.java) { load(value, docs = snapshots) }
        rows.getJSONObject(3).put("translation", "")
        assertEquals(0, load(value, docs = snapshots).preserved)
        rows.getJSONObject(4).put("translation", "Autre nom")
        assertThrows(IllegalArgumentException::class.java) { load(value, docs = snapshots) }
        rows.getJSONObject(4).put("translation", "Héroïne")
        rows.getJSONObject(3).put("translation", "Hero").put("identityGroup", "changed")
        assertThrows(IllegalArgumentException::class.java) { load(value, docs = snapshots) }
        for (index in 0 until rows.length()) {
            rows.getJSONObject(index).remove("identityGroup")
            rows.getJSONObject(index).remove("preserveOriginal")
            rows.getJSONObject(index).remove("readOnlyReason")
        }
        assertEquals("Hero", load(value, docs = snapshots).replacements.getValue("Actors.json").getValue("/1/name"))
        // Omitting optional v1 metadata never disables coherence derived from the real source files.
        rows.getJSONObject(3).put("translation", "Héros")
        assertThrows(IllegalArgumentException::class.java) { load(value, docs = snapshots) }
    }

    @Test fun localTranslationPreservesLogicalNamesCompatibleWithExistingSaves() {
        val actors = """[null,{"name":"Hero","profile":"A hero"},{"name":"Companion"}]""".toByteArray()
        val common = """[null,{"list":[{"code":320,"parameters":[1,"Hero"]},{"code":111,"parameters":[4,1,1,"Hero"]},{"code":401,"parameters":["Hello"]}]}]""".toByteArray()
        val snapshots = listOf("Actors.json" to actors, "CommonEvents.json" to common).map { (file, bytes) ->
            TranslationSnapshot(file, textHash(bytes), RpgTextDocument(file, bytes).entries)
        }
        val changes = localTranslationReplacements(snapshots, mapOf("Hero" to "Héros", "Companion" to "Compagnon", "Hello" to "Bonjour", "A hero" to "Un héros"))
        val savedActorName = "Hero" // An existing save keeps the original actor name.
        val translatedActors = JSONArray(RpgTextDocument("Actors.json", actors).translatedEntries(changes.getValue("Actors.json")).toString(Charsets.UTF_8))
        val translatedEvents = JSONArray(RpgTextDocument("CommonEvents.json", common).translatedEntries(changes.getValue("CommonEvents.json")).toString(Charsets.UTF_8)).getJSONObject(1).getJSONArray("list")
        assertEquals(savedActorName, translatedActors.getJSONObject(1).getString("name"))
        assertEquals(savedActorName, translatedEvents.getJSONObject(0).getJSONArray("parameters").getString(1))
        assertEquals(savedActorName, translatedEvents.getJSONObject(1).getJSONArray("parameters").getString(3))
        assertEquals("Bonjour", translatedEvents.getJSONObject(2).getJSONArray("parameters").getString(0))
        assertEquals("Un héros", translatedActors.getJSONObject(1).getString("profile"))
        assertEquals("Compagnon", translatedActors.getJSONObject(2).getString("name"))
    }
}
