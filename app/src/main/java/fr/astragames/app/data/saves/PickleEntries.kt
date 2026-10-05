package fr.astragames.app.data.saves

import java.nio.charset.StandardCharsets

internal fun flattenPickle(root: PickleNode, limit: Int = 20000): List<SaveEntry> {
    val result = mutableListOf<SaveEntry>()
    val references = pickleReferenceCounts(root)
    val active = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PickleNode, Boolean>())
    fun walk(node: PickleNode, path: String, depth: Int) {
        if (result.size >= limit || depth > 32) return
        if (!active.add(node)) return
        when (node) {
            is PickleNode.PInt -> result += SaveEntry(path, SaveEntryType.INT, node.value.toString(), references[node] == 1)
            is PickleNode.PBigInt -> result += SaveEntry(path, SaveEntryType.UNKNOWN, "Entier Python (${node.value.bitLength()} bits)", false)
            is PickleNode.PFloat -> result += SaveEntry(path, SaveEntryType.FLOAT, node.value.toString(), node.value.isFinite() && references[node] == 1)
            is PickleNode.PBool -> result += SaveEntry(path, SaveEntryType.BOOLEAN, if (node.value) "true" else "false", references[node] == 1)
            is PickleNode.PString -> result += SaveEntry(path, SaveEntryType.STRING, node.text, references[node] == 1)
            is PickleNode.PBytes -> {
                val text = node.legacyText
                result += SaveEntry(path, SaveEntryType.STRING, text ?: String(node.bytes, StandardCharsets.UTF_8), text != null && references[node] == 1)
            }
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
    require(result.distinctBy { it.path }.size == result.size) { "Cles pickle ambigues : modification non securisee." }
    return result
}

/** Count direct graph edges once: aliases of containers are safe, aliased scalar opcodes are not. */
private fun pickleReferenceCounts(root: PickleNode): java.util.IdentityHashMap<PickleNode, Int> {
    val counts = java.util.IdentityHashMap<PickleNode, Int>()
    val pending = java.util.ArrayDeque<PickleNode>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        val previous = counts[node] ?: 0
        counts[node] = previous + 1
        if (previous > 0) continue
        require(counts.size <= MAX_SAVE_NODES) { "Graphe pickle trop complexe." }
        when (node) {
            is PickleNode.PList -> pending.addAll(node.items)
            is PickleNode.PTuple -> pending.addAll(node.items)
            is PickleNode.PSet -> pending.addAll(node.items)
            is PickleNode.PDict -> { pending.addAll(node.entries.keys); pending.addAll(node.entries.values) }
            is PickleNode.PObject -> {
                pending.addAll(node.args); pending.addAll(node.listItems)
                pending.addAll(node.dependencies)
                pending.addAll(node.dictEntries.keys); pending.addAll(node.dictEntries.values)
                node.state?.let(pending::add)
            }
            else -> Unit
        }
    }
    return counts
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

internal fun pickleKey(node: PickleNode): String = when (node) {
    is PickleNode.PString -> escapePickleKey(node.text)
    is PickleNode.PInt -> "int:" + node.value
    is PickleNode.PBigInt -> "bigint:" + node.value
    is PickleNode.PBool -> "bool:" + node.value
    is PickleNode.PFloat -> "float:" + node.value
    is PickleNode.PBytes -> node.legacyText?.let { "str:" + escapePickleKey(it) }
        ?: "bytes:" + node.bytes.joinToString("") { "%02x".format(it) }
    is PickleNode.PTuple -> "tuple:" + node.items.joinToString(",") { pickleKey(it).let { key -> "${key.length}:$key" } }
    PickleNode.PNil -> "none:"
    else -> "?"
}

private fun escapePickleKey(value: String) = value.replace("%", "%25").replace(":", "%3A").replace("[", "%5B").replace("]", "%5D")

/** Mutable or excessively nested keys must not recurse through data-class hashCode on input. */
internal fun requireSafePickleKey(node: PickleNode, depth: Int = 0) {
    require(depth <= 32) { "Cle pickle trop complexe." }
    when (node) {
        is PickleNode.PTuple -> node.items.forEach { requireSafePickleKey(it, depth + 1) }
        is PickleNode.PString, is PickleNode.PBytes, is PickleNode.PInt, is PickleNode.PBigInt,
        is PickleNode.PFloat, is PickleNode.PBool, PickleNode.PNil -> Unit
        else -> error("Type de cle pickle non pris en charge.")
    }
}
