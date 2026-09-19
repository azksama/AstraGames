package fr.astragames.app.translation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RpgTextDocumentTest {
    @Test fun mapTranslatesOnlyVisibleTextAndKeepsEventLogic() {
        val source = """{"displayName":"Village","note":"<plugin:Hello>","events":[null,{"name":"DO_NOT_CHANGE","pages":[{"list":[
            {"code":101,"indent":0,"parameters":["Actor1",0,0,2,"Alice"]},
            {"code":401,"indent":0,"parameters":["Hello \\N[1]!"]},
            {"code":102,"indent":0,"parameters":[["Yes","No"],1,0,2,0]},
            {"code":402,"indent":0,"parameters":[0,"Yes"]},
            {"code":355,"indent":0,"parameters":["Hello"]},
            {"code":356,"indent":0,"parameters":["Plugin Hello"]},
            {"code":122,"indent":0,"parameters":[1,1,0,4,"Hello"]},
            {"code":0,"indent":0,"parameters":[]}
        ]}]}]}"""
        val doc = RpgTextDocument("Map001.json", source.toByteArray())
        assertEquals(listOf("Village", "Alice", "Hello \\N[1]!", "Yes", "No", "Yes"), doc.texts)
        val result = JSONObject(doc.translated(mapOf("Village" to "Bourg", "Hello \\N[1]!" to "Bonjour \\N[1]!", "Yes" to "Oui")).toString(Charsets.UTF_8))
        assertEquals("<plugin:Hello>", result.getString("note"))
        val event = result.getJSONArray("events").getJSONObject(1)
        assertEquals("DO_NOT_CHANGE", event.getString("name"))
        val list = event.getJSONArray("pages").getJSONObject(0).getJSONArray("list")
        assertEquals("Actor1", list.getJSONObject(0).getJSONArray("parameters").getString(0))
        assertEquals("Bonjour \\N[1]!", list.getJSONObject(1).getJSONArray("parameters").getString(0))
        assertEquals("Oui", list.getJSONObject(2).getJSONArray("parameters").getJSONArray(0).getString(0))
        assertEquals("Hello", list.getJSONObject(4).getJSONArray("parameters").getString(0))
        assertEquals("Hello", list.getJSONObject(6).getJSONArray("parameters").getString(4))
    }

    @Test fun itemDescriptionsChangeButPathsNotesAndNumbersDoNot() {
        val doc = RpgTextDocument("Items.json", """[null,{"id":1,"name":"Potion","description":"Restores health","note":"<name:Potion>","iconIndex":12,"price":50}]""".toByteArray())
        val item = JSONArray(doc.translated(mapOf("Potion" to "Remède")).toString(Charsets.UTF_8)).getJSONObject(1)
        assertEquals("Remède", item.getString("name"))
        assertEquals("<name:Potion>", item.getString("note"))
        assertEquals(12, item.getInt("iconIndex"))
        assertEquals(50, item.getInt("price"))
    }

    @Test fun systemPreservesSwitchesVariableNamesAndResources() {
        val doc = RpgTextDocument("System.json", """{"gameTitle":"Hello","currencyUnit":"Gold","switches":["Hello"],"variables":["Hello"],"title1Name":"Hello","terms":{"commands":["Fight"],"messages":{"victory":"%1 wins!"}},"weaponTypes":["Sword"]}""".toByteArray())
        assertEquals(setOf("Hello", "Gold", "Fight", "%1 wins!", "Sword"), doc.texts.toSet())
        val value = JSONObject(doc.translated(mapOf("Hello" to "Bonjour", "Fight" to "Combattre")).toString(Charsets.UTF_8))
        assertEquals("Hello", value.getString("title1Name"))
        assertEquals("Hello", value.getJSONArray("variables").getString(0))
    }

    @Test fun controlsAndLineBreaksRemainExact() {
        val text = "  Hello \\N[1]!\r\n\\C[2]Gold %1\\C[0]\\. <color:red>World</color>"
        val result = ProtectedText.render(text, mapOf("Hello" to "Bonjour", "Gold" to "Or", "World" to "Monde"))
        assertEquals("  Bonjour \\N[1]!\r\n\\C[2]Or %1\\C[0]\\. <color:red>Monde</color>", result)
        assertEquals(ProtectedText.controls(text), ProtectedText.controls(result))
        assertEquals(listOf("Hello", "Gold", "World"), ProtectedText.fragments(text))
    }

    @Test fun rejectsCommandInjectionAndLoss() {
        assertThrows(IllegalArgumentException::class.java) { ProtectedText.render("Hello", mapOf("Hello" to "\\V[7]")) }
        val doc = RpgTextDocument("Actors.json", """[null,{"name":"Hello \\N[1]"}]""".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { doc.translated(mapOf("Hello \\N[1]" to "Bonjour")) }
    }

    @Test fun rejectsTraversalUnsupportedFilesAndDeepDocuments() {
        listOf("../Actors.json", "Scripts.json", "MapInfos.json", "plugins.js", "Actors.json.bak").forEach { assertFalse(RpgTextDocument.accepts(it)) }
        assertThrows(IllegalArgumentException::class.java) { RpgTextDocument("Actors.json", ("[".repeat(65) + "]".repeat(65)).toByteArray()) }
    }
}
