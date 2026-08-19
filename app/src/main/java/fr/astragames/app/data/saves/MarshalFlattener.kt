package fr.astragames.app.data.saves

import java.nio.charset.StandardCharsets

/** Aplatit un graphe Marshal en entrees editables et permet d'y appliquer des editions. */
object MarshalFlattener {

    fun flatten(root: MarshalValue, limit: Int = 4000): List<SaveEntry> {
        val result = mutableListOf<SaveEntry>()
        fun walk(value: MarshalValue, path: String, depth: Int) {
            if (result.size >= limit) return
            when (value) {
                is MarshalValue.IntValue -> result += SaveEntry(path, SaveEntryType.INT, value.value.toString(), editable = true)
                is MarshalValue.FloatValue -> result += SaveEntry(path, SaveEntryType.FLOAT, value.value.toString(), editable = true)
                is MarshalValue.Bool -> result += SaveEntry(path, SaveEntryType.BOOLEAN, if (value.value) "true" else "false", editable = true)
                is MarshalValue.StringValue -> result += SaveEntry(path, SaveEntryType.STRING, value.text, editable = true)
                MarshalValue.NilValue -> result += SaveEntry(path, SaveEntryType.UNKNOWN, "null", editable = false)
                is MarshalValue.SymbolValue -> result += SaveEntry(path, SaveEntryType.STRING, value.name, editable = false)
                is MarshalValue.ArrayValue -> {
                    result += SaveEntry(path, SaveEntryType.LIST, "[" + value.items.size + "]", editable = false)
                    if (depth < 6) value.items.forEachIndexed { index, item -> walk(item, path + "[" + index + "]", depth + 1) }
                }
                is MarshalValue.HashValue -> {
                    result += SaveEntry(path, SaveEntryType.LIST, "{" + value.entries.size + "}", editable = false)
                    if (depth < 5) value.entries.entries.take(200).forEach { (key, entry) ->
                        walk(entry, path + "[" + describeKey(key) + "]", depth + 1)
                    }
                }
                is MarshalValue.ObjectValue -> {
                    result += SaveEntry(path, SaveEntryType.OBJECT, value.className.name, editable = false)
                    if (depth < 6) value.ivars.entries.take(300).forEach { (name, attribute) ->
                        walk(attribute, path + "." + name.name.removePrefix("@"), depth + 1)
                    }
                }
                is MarshalValue.StructValue -> {
                    result += SaveEntry(path, SaveEntryType.OBJECT, value.className.name, editable = false)
                    if (depth < 6) value.members.entries.take(300).forEach { (name, member) ->
                        walk(member, path + "." + name.name, depth + 1)
                    }
                }
                is MarshalValue.UserDefined -> result += SaveEntry(path, SaveEntryType.UNKNOWN, value.className.name + " (binaire)", editable = false)
                is MarshalValue.ExtendedValue -> walk(value.value, path, depth)
                is MarshalValue.ModuleValue -> walk(value.value, path, depth)
            }
        }
        walk(root, "root", 0)
        return result
    }

    fun describeKey(key: MarshalValue): String = when (key) {
        is MarshalValue.StringValue -> key.text
        is MarshalValue.SymbolValue -> key.name
        is MarshalValue.IntValue -> key.value.toString()
        is MarshalValue.FloatValue -> key.value.toString()
        is MarshalValue.Bool -> if (key.value) "true" else "false"
        else -> "?"
    }

    /** Applique une edition de feuille sur le graphe. Retourne true si la valeur a ete trouvee et modifiee. */
    fun applyEdit(root: MarshalValue, path: String, newValue: String, type: SaveEntryType): Boolean {
        val tokens = parsePath(path) ?: return false
        val parent = resolve(root, tokens.dropLast(1)) ?: return false
        val last = tokens.last()
        val coerced = when (type) {
            SaveEntryType.INT -> newValue.trim().toLongOrNull()?.let { MarshalValue.IntValue(it) } ?: return false
            SaveEntryType.FLOAT -> newValue.trim().toDoubleOrNull()?.let { MarshalValue.FloatValue(it) } ?: return false
            SaveEntryType.BOOLEAN -> MarshalValue.Bool(newValue.trim().equals("true", ignoreCase = true))
            SaveEntryType.STRING -> MarshalValue.StringValue(newValue.toByteArray(StandardCharsets.UTF_8))
            else -> return false
        }
        when (parent) {
            is MarshalValue.ArrayValue -> {
                val index = (last as? PathToken.Index)?.index ?: return false
                val target = parent.items.getOrNull(index) ?: return false
                if (leafType(target) != type) return false
                parent.items[index] = coerced
            }
            is MarshalValue.HashValue -> {
                val key = (last as? PathToken.Key)?.display ?: return false
                val entry = parent.entries.entries.firstOrNull { describeKey(it.key) == key } ?: return false
                if (leafType(entry.value) != type) return false
                parent.entries[entry.key] = coerced
            }
            is MarshalValue.ObjectValue -> {
                val name = (last as? PathToken.Field)?.name ?: return false
                val symbol = parent.ivars.keys.firstOrNull { it.name.removePrefix("@") == name } ?: return false
                val target = parent.ivars[symbol] ?: return false
                if (leafType(target) != type) return false
                parent.ivars[symbol] = coerced
            }
            is MarshalValue.StructValue -> {
                val name = (last as? PathToken.Field)?.name ?: return false
                val symbol = parent.members.keys.firstOrNull { it.name == name } ?: return false
                val target = parent.members[symbol] ?: return false
                if (leafType(target) != type) return false
                parent.members[symbol] = coerced
            }
            else -> return false
        }
        return true
    }

    private sealed interface PathToken {
        data class Index(val index: Int) : PathToken
        data class Field(val name: String) : PathToken
        data class Key(val display: String) : PathToken
    }

    private fun parsePath(path: String): List<PathToken>? {
        if (!path.startsWith("root")) return null
        var rest = path.removePrefix("root")
        val tokens = mutableListOf<PathToken>()
        while (rest.isNotEmpty()) {
            when {
                rest.startsWith('.') -> {
                    val body = rest.substring(1)
                    val end = body.indexOfFirst { it == '.' || it == '[' }.let { if (it < 0) body.length else it }
                    tokens += PathToken.Field(body.substring(0, end))
                    rest = body.substring(end)
                }
                rest.startsWith('[') -> {
                    val end = rest.indexOf(']')
                    if (end < 0) return null
                    val inside = rest.substring(1, end)
                    tokens += inside.toIntOrNull()?.let { PathToken.Index(it) as PathToken } ?: PathToken.Key(inside)
                    rest = rest.substring(end + 1)
                }
                else -> return null
            }
        }
        return tokens.ifEmpty { null }
    }

    private fun resolve(value: MarshalValue, tokens: List<PathToken>): MarshalValue? {
        var current = value
        for (token in tokens) {
            current = when (token) {
                is PathToken.Index -> (current as? MarshalValue.ArrayValue)?.items?.getOrNull(token.index) ?: return null
                is PathToken.Field -> when (current) {
                    is MarshalValue.ObjectValue -> current.ivars.entries.firstOrNull { it.key.name.removePrefix("@") == token.name }?.value
                    is MarshalValue.StructValue -> current.members.entries.firstOrNull { it.key.name == token.name }?.value
                    else -> null
                } ?: return null
                is PathToken.Key -> (current as? MarshalValue.HashValue)?.entries?.entries
                    ?.firstOrNull { describeKey(it.key) == token.display }?.value ?: return null
            }
        }
        return current
    }

    private fun leafType(value: MarshalValue) = when (value) {
        is MarshalValue.IntValue -> SaveEntryType.INT
        is MarshalValue.FloatValue -> SaveEntryType.FLOAT
        is MarshalValue.Bool -> SaveEntryType.BOOLEAN
        is MarshalValue.StringValue -> SaveEntryType.STRING
        else -> null
    }
}
