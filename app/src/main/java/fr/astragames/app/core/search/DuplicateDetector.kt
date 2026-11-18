package fr.astragames.app.core.search

import fr.astragames.app.core.metadata.MetadataSource
import fr.astragames.app.data.local.GameEntity
import java.text.Normalizer
import java.util.Locale

object DuplicateDetector {
    data class DuplicateGroup(
        val key: String, val games: List<GameEntity>, val reason: String = "Titres similaires",
        private val previousKey: String? = null
    ) {
        fun isIgnored(keys: Set<String>) = key in keys || (previousKey != null && previousKey in keys)
    }

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
        val firstByMetadata = mutableMapOf<String, String>()
        val titles = uniqueGames.associate { it.id to normalizeTitle(it.title) }
        uniqueGames.forEach { game ->
            fun connect(index: MutableMap<String, String>, key: String) {
                val previous = index.putIfAbsent(key, game.id) ?: return
                parents[root(game.id)] = root(previous)
            }
            if (game.fingerprint.isNotBlank()) connect(firstByFingerprint, game.fingerprint)
            titles.getValue(game.id).takeIf { it.length >= 4 }?.let { connect(firstByTitle, it) }
            metadataKeys(game).forEach { connect(firstByMetadata, it) }
        }
        return uniqueGames.groupBy { root(it.id) }.values.filter { it.size > 1 }.map { group ->
            val fingerprint = group.filter { it.fingerprint.isNotBlank() }
                .groupingBy { it.fingerprint }.eachCount().filterValues { it > 1 }.keys.minOrNull()
            val title = group.minOf { titles.getValue(it.id) }
            val previousKey = fingerprint?.let { "fingerprint:$it" } ?: "title:$title"
            // Short titles never join groups. Two unrelated products can both be named "II".
            val key = if (fingerprint == null && title.length < 4) {
                group.flatMap(::metadataKeys).groupingBy { it }.eachCount()
                    .filterValues { it > 1 }.keys.minOrNull() ?: "game:${group.minOf { it.id }}"
            } else previousKey
            val sameTitle = group.map { titles.getValue(it.id) }.distinct().size == 1
            DuplicateGroup(key, group.sortedWith(compareBy<GameEntity> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }),
                if (fingerprint != null) "Même identité détectée" else if (sameTitle && title.length >= 4) "Titres similaires" else "Même fiche source ou identifiant produit",
                previousKey.takeUnless { it == key })
        }.sortedWith(compareByDescending<DuplicateGroup> { it.games.size }.thenBy { it.key })
    }

    private fun metadataKeys(game: GameEntity): List<String> = listOfNotNull(
        game.f95Url?.let(MetadataSource.F95ZONE::pageUrl)?.let { "source:$it" },
        game.ryuugamesUrl?.let(MetadataSource.RYUUGAMES::pageUrl)?.let { "source:$it" },
        game.productCode?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length >= 4 }?.let { "product:$it" }
    )

    private val accents = Regex("\\p{Mn}+")
    private val version = Regex("\\b(v(?:er(?:sion)?)?\\s*)?\\d+(?:[._-]\\d+)+\\b")
    private val platform = Regex("\\b(?:win(?:dows)?|linux|mac(?:os)?|android|x64|x86)\\b")
    private val copySuffix = Regex("\\b(?:copy|copie|duplicate|doublon)(?:\\s*\\d+)?\\s*$")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    internal fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(accents, "")
        .lowercase(Locale.ROOT)
        .replace(version, " ")
        .replace(platform, " ")
        .replace(copySuffix, " ")
        .replace(separators, " ")
        .trim()
}
