package fr.astragames.app.data.saves

import java.nio.charset.StandardCharsets

internal fun flattenPickle(root: PickleNode, limit: Int = 20000): List<SaveEntry> {
    val result = mutableListOf<SaveEntry>()
    val active = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PickleNode, Boolean>())
    fun walk(node: PickleNode, path: String, depth: Int) {
        if (result.size >= limit || depth > 32) return
        if (!active.add(node)) return
        when (node) {
            is PickleNode.PInt -> result += SaveEntry(path, SaveEntryType.INT, node.value.toString(), true)
            is PickleNode.PFloat -> result += SaveEntry(path, SaveEntryType.FLOAT, node.value.toString(), true)
            is PickleNode.PBool -> result += SaveEntry(path, SaveEntryType.BOOLEAN, if (node.value) "true" else "false", true)
            is PickleNode.PString -> result += SaveEntry(path, SaveEntryType.STRING, node.text, true)
            is PickleNode.PBytes -> result += SaveEntry(path, SaveEntryType.STRING, String(node.bytes, StandardCharsets.UTF_8), false)
            is PickleNode.PNil -> result += SaveEntry(path, SaveEntryType.UNKNOWN, "null", false)
            is PickleNode.PList -> {
                result += SaveEntry(path, SaveEntryType.LIST, "[" + node.items.size + "]", false)
                node.items.forEachIndexed { index, item -> walk(item, path + "[" + index + "]", depth + 1) }
            }
            is PickleNode.PTuple -> {
                result += SaveEntry(path, SaveEntryType.LIST, "(" + node.items.size + ")", false)
                node.items.forEachIndexed { index, item -> walk(item, path + "[" + index + "]", depth + 1) }
            }
            is PickleNode.PDict -> {
                result += SaveEntry(path, SaveEntryType.OBJECT, "{" + node.entries.size + "}", false)
                node.entries.entries.forEach { (key, value) ->
                    walk(value, path + "[" + pickleKey(key) + "]", depth + 1)
                }
            }
            is PickleNode.PSet -> {
                result += SaveEntry(path, SaveEntryType.LIST, "set(" + node.items.size + ")", false)
                node.items.forEachIndexed { index, item -> walk(item, path + "[" + index + "]", depth + 1) }
            }
            is PickleNode.PObject -> {
                result += SaveEntry(path, SaveEntryType.OBJECT, node.className, false)
                node.listItems.forEachIndexed { index, item -> walk(item, path + ".items[" + index + "]", depth + 1) }
                node.dictEntries.forEach { (key, value) -> walk(value, path + ".entries[" + pickleKey(key) + "]", depth + 1) }
                node.args.forEachIndexed { index, item -> walk(item, path + ".arg" + index, depth + 1) }
                node.state?.let { walk(it, path + ".state", depth + 1) }
            }
        }
        active.remove(node)
    }
    walk(root, "root", 0)
    return result
}

internal fun findPickleNode(root: PickleNode, path: String): PickleNode? {
    if (path == "root") return root
    if (!path.startsWith("root")) return null
    var current: PickleNode = root
    var rest = path.removePrefix("root")
    while (rest.isNotEmpty()) {
        when {
            rest.startsWith(".items[") -> {
                val obj = current as? PickleNode.PObject ?: return null
                current = PickleNode.PList(obj.listItems, obj.start, obj.end)
                rest = rest.removePrefix(".items")
            }
            rest.startsWith(".entries[") -> {
                val obj = current as? PickleNode.PObject ?: return null
                current = PickleNode.PDict(obj.dictEntries, obj.start, obj.end)
                rest = rest.removePrefix(".entries")
            }
            rest.startsWith(".arg") -> {
                val body = rest.removePrefix(".arg")
                val end = body.indexOfFirst { it == '.' || it == '[' }.let { if (it < 0) body.length else it }
                val index = body.substring(0, end).toIntOrNull() ?: return null
                current = (current as? PickleNode.PObject)?.args?.getOrNull(index) ?: return null
                rest = body.substring(end)
            }
            rest.startsWith(".state") -> {
                current = (current as? PickleNode.PObject)?.state ?: return null
                rest = rest.removePrefix(".state")
            }
            rest.startsWith(".") -> return null
            rest.startsWith("[") -> {
                val end = rest.indexOf(']')
                if (end < 0) return null
                val inside = rest.substring(1, end)
                current = when (current) {
                    is PickleNode.PList -> current.items.getOrNull(inside.toIntOrNull() ?: return null)
                    is PickleNode.PTuple -> current.items.getOrNull(inside.toIntOrNull() ?: return null)
                    is PickleNode.PSet -> current.items.getOrNull(inside.toIntOrNull() ?: return null)
                    is PickleNode.PDict -> current.entries.entries.singleOrNull { pickleKey(it.key) == inside }?.value
                    else -> null
                } ?: return null
                rest = rest.substring(end + 1)
            }
            else -> return null
        }
    }
    return current
}

internal fun pickleKey(node: PickleNode): String = (when (node) {
    is PickleNode.PString -> node.text
    is PickleNode.PInt -> node.value.toString()
    is PickleNode.PBool -> if (node.value) "true" else "false"
    is PickleNode.PBytes -> String(node.bytes, StandardCharsets.UTF_8)
    else -> "?"
}).replace("%", "%25").replace("[", "%5B").replace("]", "%5D")
