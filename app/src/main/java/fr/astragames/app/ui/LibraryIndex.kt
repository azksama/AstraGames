package fr.astragames.app.ui

import fr.astragames.app.core.collections.SmartCollectionEvaluator
import fr.astragames.app.core.collections.FolderHierarchy
import fr.astragames.app.core.metadata.hasGameUpdate
import fr.astragames.app.core.search.TagMatcher
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GamePlayStat
import fr.astragames.app.data.local.GameTagCrossRef
import fr.astragames.app.data.local.LibraryFolderEntity
import java.util.Locale

/** Derived catalog data, rebuilt when database inputs or time-based membership changes. */
internal class LibraryIndex(
    private val games: List<GameEntity>,
    refs: List<GameTagCrossRef>,
    folders: List<LibraryFolderEntity>,
    collections: List<CollectionEntity>,
    rules: List<CollectionRuleEntity>,
    playStats: List<GamePlayStat>,
    tagIds: Set<String>,
    now: Long = System.currentTimeMillis()
) {
    val gamesById = games.associateBy { it.id }
    val playStatsByGame = playStats.associateBy { it.gameId }
    private val refsByGame = refs.groupBy { it.gameId }
    private val tagsByGame = refsByGame.mapValues { (_, gameRefs) -> gameRefs.mapTo(hashSetOf()) { it.tagId } }
    private val hierarchy = FolderHierarchy(folders)
    private val titleKeys = games.associate { it.id to it.title.lowercase(Locale.ROOT) }
    private val rulesByCollection = rules.groupBy { it.collectionId }
    private val folderIdsByRoot = hierarchy.ruleMemberships(rules)
    val collectionGames = collections.associate { collection ->
        val collectionRules = rulesByCollection[collection.id].orEmpty()
        val valid = collectionRules.isNotEmpty() && collectionRules.all { rule ->
            SmartCollectionEvaluator.isValidRule(rule.field, rule.operator, rule.value, tagIds::contains, hierarchy::contains)
        }
        collection.id to if (!valid) emptyList() else games.filter { game ->
            SmartCollectionEvaluator.matches(
                collection, collectionRules, game, refsByGame[game.id].orEmpty(),
                folders, playStatsByGame[game.id], now, folderIdsByRoot
            )
        }
    }
    private val collectionGameIds = collectionGames.mapValues { (_, members) -> members.mapTo(hashSetOf()) { it.id } }

    fun filter(
        filters: LibraryFilters,
        searchIds: Set<String>?,
        systemFolders: List<SystemFolderFilter>,
        latestVersions: Map<String, String>
    ): List<GameEntity> {
        val folderIds = filters.folderId?.let { folderIdsByRoot[it] ?: hierarchy.descendantsOf(it) }
        val collectionIds = filters.collectionId?.let { collectionGameIds[it].orEmpty() }
        val systemIds = filters.systemFolderId?.let { selected ->
            systemFolders.firstOrNull { it.id == selected }?.gameIds.orEmpty()
        }
        val matching = games.filter { game ->
            val tags = tagsByGame[game.id].orEmpty()
            (searchIds == null || game.id in searchIds) &&
                (filters.sourceId == null || game.sourceId == filters.sourceId) &&
                (folderIds == null || game.libraryFolderId in folderIds) &&
                (collectionIds == null || game.id in collectionIds) &&
                (systemIds == null || game.id in systemIds) &&
                (filters.engine == null || game.engine == filters.engine.name) &&
                (!filters.favoritesOnly || game.favorite) &&
                (!filters.missingOnly || game.missing) &&
                TagMatcher.matches(filters.tagIds, tags, filters.tagMode) &&
                tags.none { it in filters.excludedTagIds }
        }
        val titleOrder = compareBy<GameEntity> { titleKeys[it.id] }.thenBy { it.id }
        val order = when (filters.sort) {
            LibrarySort.TITLE -> titleOrder
            LibrarySort.RECENTLY_ADDED -> compareByDescending<GameEntity> { it.dateAdded }.then(titleOrder)
            LibrarySort.LAST_PLAYED -> compareByDescending<GameEntity> { it.lastPlayedAt ?: Long.MIN_VALUE }.then(titleOrder)
            LibrarySort.MOST_PLAYED -> compareByDescending<GameEntity> { it.playCount }.then(titleOrder)
            LibrarySort.NEVER_PLAYED -> compareBy<GameEntity> { it.playCount > 0 }.then(titleOrder)
            LibrarySort.UPDATE_AVAILABLE -> {
                val updatedIds = matching.filter { hasGameUpdate(it.version, latestVersions[it.id]) }.mapTo(hashSetOf()) { it.id }
                compareByDescending<GameEntity> { it.id in updatedIds }.then(titleOrder)
            }
        }
        return matching.sortedWith(order)
    }

}
