package fr.astragames.app.core.search

import fr.astragames.app.data.local.GameEntity
import java.text.Normalizer

object DuplicateDetector {
    data class DuplicateGroup(val key: String, val games: List<GameEntity>)

    fun isDuplicate(first: GameEntity, second: GameEntity): Boolean =
        first.id != second.id && groups(listOf(first, second)).isNotEmpty()

    fun groups(games: List<GameEntity>): List<DuplicateGroup> {
        val uniqueGames = games.distinctBy { it.id }
        val parents = uniqueGames.associate { it.id to it.id }.toMutableMap()
        fun root(id: String): String {
            var current = id
            while (parents.getValue(current) != current) {
                parents[current] = parents.getValue(parents.getValue(current))
                current = parents.getValue(current)
            }
            return current
        }
        val firstByFingerprint = mutableMapOf<String, String>()
        val firstByTitle = mutableMapOf<String, String>()
        val titles = uniqueGames.associate { it.id to normalizeTitle(it.title) }
        uniqueGames.forEach { game ->
            fun connect(index: MutableMap<String, String>, key: String) {
                val previous = index.putIfAbsent(key, game.id) ?: return
                parents[root(game.id)] = root(previous)
            }
            if (game.fingerprint.isNotBlank()) connect(firstByFingerprint, game.fingerprint)
            titles.getValue(game.id).takeIf { it.length >= 4 }?.let { connect(firstByTitle, it) }
        }
        return uniqueGames.groupBy { root(it.id) }.values.filter { it.size > 1 }.map { group ->
            val fingerprint = group.filter { it.fingerprint.isNotBlank() }
                .groupingBy { it.fingerprint }.eachCount().filterValues { it > 1 }.keys.minOrNull()
            val key = fingerprint?.let { "fingerprint:$it" }
                ?: "title:${titles.getValue(group.first().id)}"
            DuplicateGroup(key, group.sortedWith(compareBy<GameEntity> { it.title.lowercase(java.util.Locale.ROOT) }.thenBy { it.id }))
        }.sortedWith(compareByDescending<DuplicateGroup> { it.games.size }.thenBy { it.key })
    }

    internal fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("\\b(v(?:er(?:sion)?)?\\s*)?\\d+(?:[._-]\\d+)+\\b"), " ")
        .replace(Regex("\\b(?:win(?:dows)?|linux|mac(?:os)?|android|x64|x86)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
