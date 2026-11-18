package fr.astragames.app.core.collections

import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.LibraryFolderEntity

internal class FolderHierarchy(folders: List<LibraryFolderEntity>) {
    private val byId = folders.associateBy { it.id }
    private val byParent = folders.groupBy { it.parentId }

    fun contains(id: String) = id in byId

    fun descendantsOf(rootId: String): Set<String> {
        if (rootId !in byId) return emptySet()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<String>().apply { add(rootId) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (visited.add(id)) byParent[id].orEmpty().forEach { pending.add(it.id) }
        }
        return visited
    }

    fun ruleMemberships(rules: List<CollectionRuleEntity>): Map<String, Set<String>> =
        rules.asSequence().filter { it.field == "FOLDER" }.map { it.value }.distinct().associateWith(::descendantsOf)

    fun pathOf(id: String): String? {
        var folder = byId[id] ?: return null
        val names = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        while (visited.add(folder.id)) {
            names.addFirst(folder.name)
            folder = byId[folder.parentId] ?: break
        }
        return names.joinToString(" / ")
    }
}
