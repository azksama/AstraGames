package fr.astragames.app.core.search

import fr.astragames.app.data.local.GameEntity
import java.text.Normalizer

object DuplicateDetector {
    data class DuplicateGroup(val key: String, val games: List<GameEntity>)

    fun isDuplicate(first: GameEntity, second: GameEntity): Boolean =
        first.id != second.id && groups(listOf(first, second)).isNotEmpty()

    fun groups(games: List<GameEntity>): List<DuplicateGroup> {
        val groupedIds = mutableSetOf<String>()
        val result = mutableListOf<DuplicateGroup>()
        games.filter { it.fingerprint.isNotBlank() }.groupBy { it.fingerprint }.filterValues { it.size > 1 }.forEach { (fingerprint, group) ->
            result += DuplicateGroup("fingerprint:$fingerprint", group.sortedBy { it.title.lowercase(java.util.Locale.ROOT) })
            groupedIds += group.map { it.id }
        }
        games.filterNot { it.id in groupedIds }
            .groupBy { normalizeTitle(it.title) }
            .filterKeys { it.length >= 4 }
            .filterValues { it.size > 1 }
            .forEach { (title, group) -> result += DuplicateGroup("title:$title", group.sortedBy { game -> game.title.lowercase(java.util.Locale.ROOT) }) }
        return result.sortedByDescending { it.games.size }
    }

    internal fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("\\b(v(?:er(?:sion)?)?\\s*)?\\d+(?:[._-]\\d+)+\\b"), " ")
        .replace(Regex("\\b(?:win(?:dows)?|linux|mac(?:os)?|android|x64|x86)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
