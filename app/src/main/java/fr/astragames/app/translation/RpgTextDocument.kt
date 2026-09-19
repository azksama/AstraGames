package fr.astragames.app.translation

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Only player-facing fields are writable; scripts, notes, paths and identifiers are opaque. */
internal class RpgTextDocument(val filename: String, bytes: ByteArray) {
    private val root: Any
    private val slots = mutableListOf<Slot>()
    private data class Slot(val text: String, val write: (String) -> Unit)
    val texts: List<String> get() = slots.map { it.text }

    init {
        require(accepts(filename)) { "Fichier RPG Maker non pris en charge : $filename" }
        require(bytes.size <= MAX_FILE_BYTES) { "Fichier trop volumineux : $filename" }
        val input = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        // Bound nesting before handing untrusted game data to the platform JSON parser.
        var depth = 0; var quoted = false; var escaped = false
        input.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '[', '{' -> { depth++; require(depth <= 64) { "JSON trop imbriqué." } }
                ']', '}' -> depth--
            }
        }
        val reader = JSONTokener(input)
        root = reader.nextValue()
        require(reader.nextClean() == '\u0000') { "Données après le document JSON." }
        when {
            filename.matches(Regex("Map[0-9]+\\.json")) -> {
                val map = root as JSONObject
                field(map, "displayName")
                objects(map.optJSONArray("events")) { event -> pages(event) }
            }
            filename == "CommonEvents.json" -> objects(root as JSONArray) { commands(it.optJSONArray("list")) }
            filename == "Troops.json" -> objects(root as JSONArray) { pages(it) }
            filename == "System.json" -> {
                val system = root as JSONObject
                field(system, "gameTitle"); field(system, "currencyUnit")
                listOf("armorTypes", "weaponTypes", "skillTypes", "equipTypes").forEach { strings(system.optJSONArray(it)) }
                system.optJSONObject("terms")?.let { terms ->
                    listOf("basic", "params", "commands").forEach { strings(terms.optJSONArray(it)) }
                    terms.optJSONObject("messages")?.let { messages -> messages.keys().forEach { field(messages, it) } }
                }
            }
            else -> objects(root as JSONArray) { item ->
                field(item, "name")
                when (filename) {
                    "Actors.json" -> { field(item, "nickname"); field(item, "profile") }
                    "Items.json", "Weapons.json", "Armors.json" -> field(item, "description")
                    "Skills.json" -> { field(item, "description"); field(item, "message1"); field(item, "message2") }
                    "States.json" -> (1..4).forEach { field(item, "message$it") }
                }
            }
        }
    }

    private fun field(obj: JSONObject, key: String) {
        (obj.opt(key) as? String)?.takeIf(String::isNotBlank)?.let { text -> slots += Slot(text) { obj.put(key, it) } }
    }
    private fun element(array: JSONArray, index: Int) {
        (array.opt(index) as? String)?.takeIf(String::isNotBlank)?.let { text -> slots += Slot(text) { array.put(index, it) } }
    }
    private fun strings(array: JSONArray?) { array ?: return; for (i in 0 until array.length()) element(array, i) }
    private fun objects(array: JSONArray?, visit: (JSONObject) -> Unit) {
        array ?: return
        for (i in 0 until array.length()) array.optJSONObject(i)?.let(visit)
    }
    private fun pages(parent: JSONObject) = objects(parent.optJSONArray("pages")) { commands(it.optJSONArray("list")) }
    private fun commands(list: JSONArray?) = objects(list) { command ->
        val params = command.optJSONArray("parameters") ?: return@objects
        when (command.optInt("code")) {
            401, 405 -> element(params, 0)
            101 -> element(params, 4) // MZ speaker name; MV has no fifth parameter.
            102 -> strings(params.optJSONArray(0))
            402, 320, 324, 325 -> element(params, 1)
        }
    }

    fun translated(dictionary: Map<String, String>): ByteArray {
        slots.forEach { slot ->
            val replacement = dictionary[slot.text] ?: slot.text
            require(ProtectedText.controls(slot.text) == ProtectedText.controls(replacement)) { "Commande RPG Maker altérée." }
            slot.write(replacement)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        private val tables = setOf("Actors", "Classes", "Skills", "Items", "Weapons", "Armors", "Enemies", "States", "System", "CommonEvents", "Troops")
        fun accepts(name: String) = name.removeSuffix(".json") in tables && name.endsWith(".json") || name.matches(Regex("Map[0-9]+\\.json"))
    }
}

internal object ProtectedText {
    // Preserve engine/plugin escapes, format arguments, markup and hard line boundaries byte-for-byte.
    private val control = Regex("""\\(?:[A-Za-z]+(?:\[[^\r\n]*?\])?|[^A-Za-z])|%\d+|<[^>\r\n]*>|\r\n|\r|\n""")
    fun controls(text: String): List<String> = control.findAll(text).map { it.value }.toList()
    fun fragments(text: String): List<String> = control.split(text).map(String::trim).filter { it.any(Char::isLetter) }
    fun render(text: String, translated: Map<String, String>): String {
        val result = StringBuilder()
        var position = 0
        fun appendPart(part: String) {
            val trimmed = part.trim()
            val value = translated[trimmed]
            if (value == null) result.append(part)
            else {
                require(controls(value).isEmpty() && value.isNotBlank()) { "Traduction invalide." }
                result.append(part.takeWhile(Char::isWhitespace)).append(value)
                    .append(part.takeLastWhile(Char::isWhitespace))
            }
        }
        control.findAll(text).forEach { token ->
            appendPart(text.substring(position, token.range.first))
            result.append(token.value)
            position = token.range.last + 1
        }
        appendPart(text.substring(position))
        return result.toString()
    }
}
