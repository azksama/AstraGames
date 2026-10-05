package fr.astragames.app.translation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fixtures exercise standard editor fields, including unused/blank slots retained by MV/MZ. */
class RpgStandardCoverageTest {
    @Test fun systemIncludesEveryTermCategoryCurrencyAndPlayerFacingType() {
        val source = """{"gameTitle":"Title","currencyUnit":"Gold","armorTypes":["","Light"],"weaponTypes":["","Sword"],"skillTypes":["","Magic"],"equipTypes":["","Head"],"terms":{"basic":["Level","Lv"],"params":["Attack"],"commands":["Fight","",null,"Formation"],"messages":{"victory":"%1 wins!","futureMessage":"%1 uses %2","a/b~c":"Escaped key"}},"elements":["Fire"],"switches":["Door opened"],"variables":["Quest state"],"locale":"ja_JP","title1Name":"TitleBackground","sounds":[{"name":"Cursor1"}]}"""
        val doc = RpgTextDocument("System.json", source.toByteArray())
        assertEquals(setOf("Title", "Gold", "Light", "Sword", "Magic", "Head", "Level", "Lv", "Attack", "Fight", "Formation", "%1 wins!", "%1 uses %2", "Escaped key"), doc.texts.toSet())
        assertTrue(doc.entries.any { it.path == "/terms/messages/a~1b~0c" })
        val translated = JSONObject(doc.translated(doc.texts.associateWith { "$it translated" }).toString(Charsets.UTF_8))
        val commands = translated.getJSONObject("terms").getJSONArray("commands")
        assertEquals(4, commands.length()); assertEquals("", commands.getString(1)); assertTrue(commands.isNull(2))
        listOf("elements", "switches", "variables", "sounds").forEach { assertEquals(JSONObject(source).getJSONArray(it).toString(), translated.getJSONArray(it).toString()) }
        assertEquals("ja_JP", translated.getString("locale")); assertEquals("TitleBackground", translated.getString("title1Name"))
    }

    @Test fun databaseCoversNamesDescriptionsProfilesAndAllCombatMessagesWithoutTouchingResources() {
        val fields = mapOf(
            "Actors.json" to listOf("name", "nickname", "profile"), "Classes.json" to listOf("name"),
            "Skills.json" to listOf("name", "description", "message1", "message2"),
            "Items.json" to listOf("name", "description"), "Weapons.json" to listOf("name", "description"),
            "Armors.json" to listOf("name", "description"), "Enemies.json" to listOf("name"),
            "States.json" to listOf("name", "message1", "message2", "message3", "message4")
        )
        fields.forEach { (file, expected) ->
            val item = JSONObject().put("id", 1).put("note", "Technical note").put("battlerName", "EnemyImage")
                .put("characterName", "ActorSheet").put("faceName", "FaceSheet").put("damage", JSONObject().put("formula", "a.atk + v[1]"))
            expected.forEach { item.put(it, "Text for $it") }
            val doc = RpgTextDocument(file, JSONArray().put(JSONObject.NULL).put(item).toString().toByteArray())
            assertEquals(file, expected.map { "/1/$it" }, doc.entries.map { it.path })
            val translated = JSONArray(doc.translated(doc.texts.associateWith { "$it translated" }).toString(Charsets.UTF_8)).getJSONObject(1)
            listOf("note", "battlerName", "characterName", "faceName", "damage").forEach { assertEquals(item.get(it).toString(), translated.get(it).toString()) }
            assertEquals(1, translated.getInt("id"))
        }
    }

    @Test fun allEventContainersIncludeMessagesAndIdentityReferencesButExcludeExecutableAndResourceParameters() {
        val list = """[
            {"code":101,"indent":0,"parameters":["FaceResource",0,0,2]},
            {"code":101,"indent":0,"parameters":["FaceResource",0,0,2,"Speaker"]},
            {"code":401,"indent":0,"parameters":["Dialogue"]},
            {"code":102,"indent":0,"parameters":[["Yes","", "No"],-2,0,2,0]},
            {"code":402,"indent":0,"parameters":[0,"Yes"]},
            {"code":105,"indent":0,"parameters":[2,false]},
            {"code":405,"indent":0,"parameters":["Scrolling text"]},
            {"code":320,"indent":0,"parameters":[1,"Hero"]},
            {"code":324,"indent":0,"parameters":[1,"Brave"]},
            {"code":325,"indent":0,"parameters":[1,"Profile"]},
            {"code":111,"indent":0,"parameters":[4,1,1,"Hero"]},
            {"code":111,"indent":0,"parameters":[12,"actor.name() === 'Hero'"]},
            {"code":108,"indent":0,"parameters":["Plugin comment"]},
            {"code":408,"indent":0,"parameters":["Continued comment"]},
            {"code":122,"indent":0,"parameters":[1,1,0,4,"Variable expression"]},
            {"code":231,"indent":0,"parameters":[1,"PictureResource",0,0,0,0,100,100,255,0]},
            {"code":355,"indent":0,"parameters":["showText('Script text')"]},
            {"code":655,"indent":0,"parameters":["continued('Script text')"]},
            {"code":356,"indent":0,"parameters":["Plugin Text"]},
            {"code":357,"indent":0,"parameters":["Plugin","Command","Display label",{"text":"Plugin text"}]},
            {"code":0,"indent":0,"parameters":[]}
        ]"""
        val files = mapOf(
            "Map001.json" to """{"events":[null,{"name":"Editor event name","pages":[{"list":$list}]}]}""",
            "CommonEvents.json" to """[null,{"name":"Editor common name","list":$list}]""",
            "Troops.json" to """[null,{"name":"Editor troop name","pages":[{"list":$list}]}]"""
        )
        files.forEach { (file, source) ->
            val doc = RpgTextDocument(file, source.toByteArray())
            assertEquals(file, listOf("Speaker", "Dialogue", "Yes", "No", "Yes", "Scrolling text", "Hero", "Brave", "Profile", "Hero"), doc.texts)
            val snapshot = TranslationSnapshot(file, textHash(source.toByteArray()), doc.entries)
            val translated = doc.translatedEntries(localTranslationReplacements(listOf(snapshot), doc.texts.associateWith { "$it translated" }).getValue(file)).toString(Charsets.UTF_8)
            val commands = when (file) {
                "Map001.json" -> JSONObject(translated).getJSONArray("events").getJSONObject(1).getJSONArray("pages").getJSONObject(0).getJSONArray("list")
                "Troops.json" -> JSONArray(translated).getJSONObject(1).getJSONArray("pages").getJSONObject(0).getJSONArray("list")
                else -> JSONArray(translated).getJSONObject(1).getJSONArray("list")
            }
            val original = JSONArray(list)
            for (index in 11 until original.length()) assertEquals(original.getJSONObject(index).toString(), commands.getJSONObject(index).toString())
            assertEquals("Hero", commands.getJSONObject(7).getJSONArray("parameters").getString(1))
            assertEquals("Hero", commands.getJSONObject(10).getJSONArray("parameters").getString(3))
            assertEquals(0, commands.getJSONObject(4).getJSONArray("parameters").getInt(0))
            assertEquals("", commands.getJSONObject(3).getJSONArray("parameters").getJSONArray(0).getString(1))
            assertEquals(original.getJSONObject(0).toString(), commands.getJSONObject(0).toString())
        }
    }
}
