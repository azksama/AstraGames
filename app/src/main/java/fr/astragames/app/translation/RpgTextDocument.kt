package fr.astragames.app.translation

import org.json.JSONArray
import org.json.JSONObject

/** Only player-facing fields are writable; scripts, notes, paths and identifiers are opaque. */
internal class RpgTextDocument(val filename: String, bytes: ByteArray) {
    private val root: Any
    private val slots = mutableListOf<Slot>()
    data class Entry(val path: String, val text: String, val identityGroup: String? = null, val identityReference: Boolean = false)
    private data class Slot(val entry: Entry, val write: (String) -> Unit)
    val entries: List<Entry> get() = slots.map { it.entry }
    val texts: List<String> get() = slots.map { it.entry.text }

    init {
        require(accepts(filename)) { "Fichier RPG Maker non pris en charge : $filename" }
        require(bytes.size <= MAX_FILE_BYTES) { "Fichier trop volumineux : $filename" }
        root = TranslationJson.parse(bytes, MAX_FILE_BYTES)
        when {
            filename.matches(Regex("Map[0-9]+\\.json")) -> {
                val map = root as JSONObject
                field(map, "displayName", "")
                objects(map.optJSONArray("events"), "/events") { event, path -> pages(event, path) }
            }
            filename == "CommonEvents.json" -> objects(root as JSONArray, "") { item, path -> commands(item.optJSONArray("list"), "$path/list") }
            filename == "Troops.json" -> objects(root as JSONArray, "") { item, path -> pages(item, path) }
            filename == "System.json" -> {
                val system = root as JSONObject
                field(system, "gameTitle", ""); field(system, "currencyUnit", "")
                listOf("armorTypes", "weaponTypes", "skillTypes", "equipTypes").forEach { strings(system.optJSONArray(it), "/$it") }
                system.optJSONObject("terms")?.let { terms ->
                    listOf("basic", "params", "commands").forEach { strings(terms.optJSONArray(it), "/terms/$it") }
                    terms.optJSONObject("messages")?.let { messages -> messages.keys().asSequence().sorted().forEach { field(messages, it, "/terms/messages") } }
                }
            }
            else -> objects(root as JSONArray, "") { item, path ->
                // The engine addresses actors by array index; their names are also used by command 111.
                field(item, "name", path, if (filename == "Actors.json") path.removePrefix("/").toIntOrNull() else null)
                when (filename) {
                    "Actors.json" -> { field(item, "nickname", path); field(item, "profile", path) }
                    "Items.json", "Weapons.json", "Armors.json" -> field(item, "description", path)
                    "Skills.json" -> { field(item, "description", path); field(item, "message1", path); field(item, "message2", path) }
                    "States.json" -> (1..4).forEach { field(item, "message$it", path) }
                }
            }
        }
    }

    private fun field(obj: JSONObject, key: String, path: String, actorId: Int? = null) {
        (obj.opt(key) as? String)?.takeIf(String::isNotBlank)?.let { text -> slots += Slot(entry("$path/${key.replace("~", "~0").replace("/", "~1")}", text, actorId)) { obj.put(key, it) } }
    }
    private fun element(array: JSONArray, index: Int, path: String, actorId: Int? = null, identityReference: Boolean = false) {
        (array.opt(index) as? String)?.takeIf(String::isNotBlank)?.let { text -> slots += Slot(entry("$path/$index", text, actorId, identityReference)) { array.put(index, it) } }
    }
    private fun entry(path: String, text: String, actorId: Int?, identityReference: Boolean = false) = Entry(path, text,
        actorId?.takeIf { it > 0 }?.let { "actor-name:$it:${textHash(text.toByteArray(Charsets.UTF_8))}" }, identityReference)
    private fun strings(array: JSONArray?, path: String) { array ?: return; for (i in 0 until array.length()) element(array, i, path) }
    private fun objects(array: JSONArray?, path: String, visit: (JSONObject, String) -> Unit) {
        array ?: return
        for (i in 0 until array.length()) array.optJSONObject(i)?.let { visit(it, "$path/$i") }
    }
    private fun pages(parent: JSONObject, path: String) = objects(parent.optJSONArray("pages"), "$path/pages") { page, pagePath -> commands(page.optJSONArray("list"), "$pagePath/list") }
    private fun commands(list: JSONArray?, path: String) = objects(list, path) { command, commandPath ->
        val params = command.optJSONArray("parameters") ?: return@objects
        when (command.optInt("code")) {
            401, 405 -> element(params, 0, "$commandPath/parameters")
            101 -> element(params, 4, "$commandPath/parameters") // MZ speaker name; MV has no fifth parameter.
            102 -> strings(params.optJSONArray(0), "$commandPath/parameters/0")
            402, 324, 325 -> element(params, 1, "$commandPath/parameters")
            320 -> element(params, 1, "$commandPath/parameters", params.optInt(0))
            // Only the standard actor-name equality operand, never script conditions.
            111 -> if (params.optInt(0) == 4 && params.optInt(2) == 1) element(params, 3, "$commandPath/parameters", params.optInt(1), identityReference = true)
        }
    }

    fun translated(dictionary: Map<String, String>): ByteArray {
        return translatedEntries(entries.associate { it.path to (dictionary[it.text] ?: it.text) })
    }

    fun translatedEntries(dictionary: Map<String, String>): ByteArray {
        val paths = slots.mapTo(mutableSetOf()) { it.entry.path }
        require(dictionary.keys.all { it in paths }) { "Emplacement de texte inconnu." }
        slots.forEach { slot ->
            val replacement = dictionary[slot.entry.path] ?: slot.entry.text
            require(ProtectedText.controls(slot.entry.text) == ProtectedText.controls(replacement)) { "Commande RPG Maker altérée : ${slot.entry.path}" }
            slot.write(replacement)
        }
        return root.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_FILE_BYTES) { "Fichier traduit trop volumineux : $filename" } }
    }

    companion object {
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        private val tables = setOf("Actors", "Classes", "Skills", "Items", "Weapons", "Armors", "Enemies", "States", "System", "CommonEvents", "Troops")
        fun accepts(name: String) = name.removeSuffix(".json") in tables && name.endsWith(".json") || name.matches(Regex("Map[0-9]+\\.json"))
    }
}
