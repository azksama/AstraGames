package fr.astragames.app.core.search

import fr.astragames.app.data.local.GameEntity
import java.text.Normalizer

object DuplicateDetector {
    fun groups(games: List<GameEntity>): List<List<GameEntity>> {
        val groupedIds = mutableSetOf<String>()
        val result = mutableListOf<List<GameEntity>>()
        games.groupBy { it.fingerprint }.values.filter { it.size > 1 }.forEach { group ->
            result += group.sortedBy { it.title.lowercase() }
            groupedIds += group.map { it.id }
        }
        games.filterNot { it.id in groupedIds }
            .groupBy { normalizeTitle(it.title) }
            .filterKeys { it.length >= 4 }
            .values.filter { it.size > 1 }
            .forEach { result += it.sortedBy { game -> game.title.lowercase() } }
        return result.sortedByDescending { it.size }
    }

    internal fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("\\b(v(?:er(?:sion)?)?\\s*)?\\d+(?:[._-]\\d+)+\\b"), " ")
        .replace(Regex("\\b(?:win(?:dows)?|linux|mac(?:os)?|android|x64|x86)\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
