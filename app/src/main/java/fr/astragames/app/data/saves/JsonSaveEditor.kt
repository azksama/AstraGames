package fr.astragames.app.data.saves

import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

object JsonSaveEditor {
    fun flatten(json: JSONObject, limit: Int = 20000): List<SaveEntry> {
        val result = mutableListOf<SaveEntry>()
        fun walk(value: Any?, path: String, depth: Int) {
            if (result.size >= limit || depth > 32) return
            when (value) {
                null, JSONObject.NULL -> result += SaveEntry(path, SaveEntryType.UNKNOWN, "null", false)
                is Boolean -> result += SaveEntry(path, SaveEntryType.BOOLEAN, if (value) "true" else "false", true)
                is Int, is Long -> result += SaveEntry(path, SaveEntryType.INT, value.toString(), true)
                is Double, is Float -> result += SaveEntry(path, SaveEntryType.FLOAT, value.toString(), true)
                is Number -> result += SaveEntry(path, SaveEntryType.UNKNOWN, value.toString(), false)
                is String -> result += SaveEntry(path, SaveEntryType.STRING, value, true)
                is JSONObject -> {
                    result += SaveEntry(path, SaveEntryType.OBJECT, "{" + value.length() + "}", false)
                    value.keys().asSequence().forEach { key ->
                        // JsonEx uses these keys for constructors and object references, not game values.
                        if (key !in setOf("@", "@c", "@r")) walk(value.opt(key), path + "." + encodeKey(key), depth + 1)
                    }
                }
                is JSONArray -> {
                    result += SaveEntry(path, SaveEntryType.LIST, "[" + value.length() + "]", false)
                    for (index in 0 until value.length()) {
                        walk(value.opt(index), path + "[" + index + "]", depth + 1)
                    }
                }
            }
        }
        walk(json, "root", 0)
        return result
    }

    fun apply(json: JSONObject, edit: SaveEdit) {
        val tokens = parseJsonPath(edit.path) ?: error("Chemin JSON invalide")
        require(tokens.isNotEmpty()) { "Chemin JSON vide" }
        var current: Any = json
        tokens.dropLast(1).forEach { token ->
            current = when (token) {
                is JsonToken.Field -> (current as? JSONObject)?.opt(token.name)
                is JsonToken.Index -> (current as? JSONArray)?.opt(token.index)
            } ?: error("Chemin introuvable : " + edit.path)
        }
        val last = tokens.last()
        val coerced = SaveValues.parse(edit)
        when (last) {
            is JsonToken.Field -> (current as? JSONObject)?.put(last.name, coerced) ?: error("Objet JSON attendu")
            is JsonToken.Index -> (current as? JSONArray)?.put(last.index, coerced) ?: error("Tableau JSON attendu")
        }
    }

    private fun encodeKey(key: String) = key.replace("%", "%25").replace(".", "%2E").replace("[", "%5B").replace("]", "%5D")
    private fun decodeKey(key: String) = key.replace("%2E", ".").replace("%5B", "[").replace("%5D", "]").replace("%25", "%")

    private sealed interface JsonToken {
        data class Field(val name: String) : JsonToken
        data class Index(val index: Int) : JsonToken
    }

    private fun parseJsonPath(path: String): List<JsonToken>? {
        if (!path.startsWith("root")) return null
        var rest = path.removePrefix("root")
        val tokens = mutableListOf<JsonToken>()
        while (rest.isNotEmpty()) {
            when {
                rest.startsWith(".") -> {
                    val body = rest.substring(1)
                    val end = body.indexOfFirst { it == '.' || it == '[' }.let { if (it < 0) body.length else it }
                    tokens += JsonToken.Field(decodeKey(body.substring(0, end)))
                    rest = body.substring(end)
                }
                rest.startsWith("[") -> {
                    val end = rest.indexOf(']')
                    if (end < 0) return null
                    val index = rest.substring(1, end).toIntOrNull() ?: return null
                    tokens += JsonToken.Index(index)
                    rest = rest.substring(end + 1)
                }
                else -> return null
            }
        }
        return tokens
    }
}

object SaveFieldClassifier {
    fun classify(entries: List<SaveEntry>): SimpleSaveFields {
        fun fieldName(path: String): String = path.split('.', '[', ']', ':')
            .lastOrNull { it.isNotBlank() && it != "@a" && it.toIntOrNull() == null }
            .orEmpty().trim('_', '@').lowercase(Locale.ROOT)
        fun first(vararg needles: String) = entries.firstOrNull { entry ->
            entry.editable && entry.type in setOf(SaveEntryType.INT, SaveEntryType.FLOAT, SaveEntryType.VARIABLE) &&
                fieldName(entry.path) in needles
        }
        fun many(vararg needles: String) = entries.filter { entry ->
            entry.editable && needles.any { needle -> entry.path.lowercase(Locale.ROOT).contains(needle) }
        }.take(40)
        return SimpleSaveFields(
            money = first("gold", "money", "argent", "partygold"),
            level = first("level", "niveau"),
            experience = first("exp", "experience", "xp"),
            hp = first("hp", "hitpoints", "hit_points", "currenthp"),
            mp = first("mp", "mana", "sp", "currentmp"),
            inventory = many("item", "invent", "bag", "equip"),
            relations = many("affection", "relation", "love", "friend"),
            progress = many("chapter", "progress", "scene", "label", "map"),
            variables = many("variable", "var[", "gamevariables", ".variables", "_variables", "game_variables"),
            switches = many("switch", "flag", "gameswitches", ".switches", "_switches", "game_switches")
        )
    }
}
