package fr.astragames.app.core.collections

import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GamePlayStat
import fr.astragames.app.data.local.GameTagCrossRef
import fr.astragames.app.data.local.LibraryFolderEntity

object SmartCollectionEvaluator {
    fun matches(
        collection: CollectionEntity,
        rules: List<CollectionRuleEntity>,
        game: GameEntity,
        refs: List<GameTagCrossRef>,
        folders: List<LibraryFolderEntity>,
        playStat: GamePlayStat?,
        now: Long = System.currentTimeMillis(),
        folderIdsByRoot: Map<String, Set<String>> = emptyMap()
    ): Boolean {
        if (rules.isEmpty()) return false
        return if (collection.matchMode == "ANY") {
            rules.any { matches(it, game, refs, folders, playStat, now, folderIdsByRoot) }
        } else {
            rules.all { matches(it, game, refs, folders, playStat, now, folderIdsByRoot) }
        }
    }

    private fun matches(
        rule: CollectionRuleEntity,
        game: GameEntity,
        refs: List<GameTagCrossRef>,
        folders: List<LibraryFolderEntity>,
        playStat: GamePlayStat?,
        now: Long,
        folderIdsByRoot: Map<String, Set<String>>
    ): Boolean = when (rule.field) {
        "ENGINE" -> compareText(game.engine, rule.operator, rule.value)
        "TAG" -> (refs.any { it.gameId == game.id && it.tagId == rule.value }) xor (rule.operator == "NOT")
        "FOLDER" -> {
            val ids = folderIdsByRoot[rule.value] ?: descendantFolderIds(rule.value, folders)
            (game.libraryFolderId in ids) xor (rule.operator == "NOT")
        }
        "FAVORITE" -> compareBoolean(game.favorite, rule.operator, rule.value)
        "AVAILABLE" -> compareBoolean(!game.missing, rule.operator, rule.value)
        "COVER" -> compareBoolean(!game.coverUri.isNullOrBlank(), rule.operator, rule.value)
        "DATE_ADDED" -> compareAge(game.dateAdded, rule.operator, rule.value, now)
        "LAST_PLAYED" -> if (rule.operator == "NEVER") game.lastPlayedAt == null
            else game.lastPlayedAt?.let { compareAge(it, rule.operator, rule.value, now) } == true
        "PLAY_TIME" -> compareNumber(playStat?.totalDurationMs?.div(3_600_000.0) ?: 0.0, rule.operator, rule.value)
        else -> false
    }

    private fun compareText(actual: String, operator: String, expected: String) =
        actual.equals(expected, true) xor (operator == "NOT")

    private fun compareBoolean(actual: Boolean, operator: String, value: String): Boolean {
        val expected = value.toBooleanStrictOrNull() ?: true
        return (actual == expected) xor (operator == "NOT")
    }

    private fun compareAge(timestamp: Long, operator: String, value: String, now: Long): Boolean {
        val days = ((now - timestamp).coerceAtLeast(0) / 86_400_000.0)
        return when (operator) {
            "WITHIN_DAYS" -> days <= value.toDoubleOrNull().orZero()
            "OLDER_THAN_DAYS" -> days > value.toDoubleOrNull().orZero()
            else -> false
        }
    }

    private fun compareNumber(actual: Double, operator: String, value: String): Boolean = when (operator) {
        "GREATER_THAN" -> actual > value.toDoubleOrNull().orZero()
        "LESS_THAN" -> actual < value.toDoubleOrNull().orZero()
        else -> actual == value.toDoubleOrNull().orZero()
    }

    private fun descendantFolderIds(rootId: String, folders: List<LibraryFolderEntity>): Set<String> {
        val result = mutableSetOf(rootId)
        var changed: Boolean
        do changed = result.addAll(folders.filter { it.parentId in result }.map { it.id }) while (changed)
        return result
    }

    private fun Double?.orZero() = this ?: 0.0
}
