package fr.astragames.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.local.AstraDao
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.local.DeletedGameEntity
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.IgnoredDuplicateGroupEntity
import fr.astragames.app.data.local.PlaySessionEntity
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.local.LibraryFolderEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import fr.astragames.app.data.scanner.ScanProgressUpdate
import fr.astragames.app.core.model.ScanReport
import fr.astragames.app.core.model.ScanReportItem
import fr.astragames.app.core.model.ScanReportItemStatus
import kotlinx.coroutines.flow.Flow
import java.text.Normalizer
import java.io.File
import java.util.UUID

class GameRepository(
    private val context: Context,
    private val dao: AstraDao,
    private val scanner: RecursiveSourceScanner,
    private val backupManager: BackupManager
) {
    val games: Flow<List<GameEntity>> = dao.observeGames()
    val sources: Flow<List<GameSourceEntity>> = dao.observeSources()
    val tags: Flow<List<TagEntity>> = dao.observeTags()
    val tagCategories: Flow<List<TagCategoryEntity>> = dao.observeTagCategories()
    val folders: Flow<List<LibraryFolderEntity>> = dao.observeFolders()
    val deletedGames: Flow<List<DeletedGameEntity>> = dao.observeDeletedGames()
    val collections: Flow<List<CollectionEntity>> = dao.observeCollections()
    val collectionRules: Flow<List<CollectionRuleEntity>> = dao.observeCollectionRules()
    val playStats = dao.observePlayStats()
    val ignoredDuplicateGroups = dao.observeIgnoredDuplicateGroups()
    fun gameTagRefs() = dao.observeGameTagRefs()
    fun search(query: String) = dao.searchGames(fr.astragames.app.core.search.SearchParser.toFtsQuery(query))

    fun game(id: String) = dao.observeGame(id)
    fun gameTags(id: String) = dao.observeTagsForGame(id)
    fun launchProfile(id: String) = dao.observeLaunchProfile(id)

    suspend fun addSource(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').substringAfterLast('/') }
            .getOrDefault("Source de jeux").ifBlank { "Source de jeux" }
        dao.upsertSource(GameSourceEntity(UUID.randomUUID().toString(), name, uri.toString()))
    }

    suspend fun toggleSource(id: String) = dao.toggleSource(id)
    suspend fun recoverInterruptedScans() = dao.recoverInterruptedScans()
    suspend fun toggleSourceRecursive(id: String) = dao.toggleSourceRecursive(id)

    suspend fun removeSource(id: String, removeGames: Boolean = false) {
        if (removeGames) dao.deleteSourceAndGames(id) else dao.deleteSource(id)
    }

    suspend fun scanSource(id: String, onProgress: (ScanProgressUpdate) -> Unit = {}) = scanner.scan(id, onProgress)
    suspend fun scanAll(onProgress: (ScanProgressUpdate) -> Unit = {}) = dao.getEnabledSources().map { scanner.scan(it.id, onProgress) }
    suspend fun latestScanReport(sourceId: String): ScanReport? {
        val source = dao.getSource(sourceId) ?: return null
        val history = dao.getLatestScanHistory(sourceId) ?: return null
        val items = dao.getScanReportItems(history.id).map { item ->
            ScanReportItem(
                path = item.path, title = item.title,
                status = runCatching { ScanReportItemStatus.valueOf(item.status) }.getOrDefault(ScanReportItemStatus.ERROR),
                reason = item.reason, gameId = item.gameId
            )
        }
        return ScanReport(
            reportId = history.id, sourceId = sourceId, sourceName = source.displayName,
            startedAt = history.startedAt, finishedAt = history.finishedAt ?: history.startedAt,
            visitedFolders = history.visitedFolders, found = history.gamesFound, added = history.gamesAdded,
            updated = history.gamesUpdated, unchanged = history.gamesUnchanged, moved = history.gamesMoved,
            missing = history.gamesMissing, ignored = history.ignoredFolders,
            errors = items.filter { it.status == ScanReportItemStatus.ERROR }.map { it.reason }, items = items
        )
    }
    suspend fun getLaunchProfile(id: String) = dao.getLaunchProfile(id)
    suspend fun saveLaunchProfile(profile: LaunchProfileEntity) = dao.upsertLaunchProfile(profile)
    suspend fun resetLaunchProfile(gameId: String) = dao.deleteLaunchProfile(gameId)
    suspend fun toggleFavorite(id: String) = dao.toggleFavorite(id)
    suspend fun getGame(id: String) = dao.getGame(id)
    suspend fun getGameIds(): Set<String> = dao.getGameIds().toSet()
    suspend fun recordLaunch(id: String) = dao.recordLaunch(id, System.currentTimeMillis())
    suspend fun verifyGamePresence(id: String): Boolean {
        val game = dao.getGame(id) ?: return false
        if (!game.missing) return true
        val uri = runCatching { Uri.parse(game.documentUri) }.getOrNull()
        val exists = when (uri?.scheme) {
            "file" -> uri.path?.let(::File)?.exists() == true
            "content" -> runCatching { DocumentFile.fromSingleUri(context, uri)?.exists() == true }.getOrDefault(false)
            else -> game.physicalPath?.let(::File)?.exists() == true
        }
        if (exists) dao.markGameFound(id)
        return exists
    }
    suspend fun setCover(id: String, uri: Uri) {
        val directory = File(context.filesDir, "covers").apply { mkdirs() }
        val previous = dao.getGame(id)?.coverUri?.let(Uri::parse)
        val destination = File(directory, "$id-${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use(input::copyTo)
        } ?: error("Impossible de lire l'image recadrée.")
        dao.setCover(id, Uri.fromFile(destination).toString())
        previous?.path?.let(::File)?.takeIf { it.parentFile == directory && it != destination }?.delete()
    }

    suspend fun removeCover(id: String) {
        dao.getGame(id)?.coverUri?.let(Uri::parse)?.path?.let(::File)
            ?.takeIf { it.parentFile == File(context.filesDir, "covers") }
            ?.let { runCatching { it.delete() } }
        dao.setCover(id, null)
    }

    suspend fun setGameFolder(id: String, folderId: String?) = dao.setGameFolder(id, folderId)

    suspend fun setGamesFolder(ids: Set<String>, folderId: String?) {
        if (ids.isNotEmpty()) dao.setGamesFolder(ids.toList(), folderId)
    }

    suspend fun setGamesFavorite(ids: Set<String>, favorite: Boolean) {
        if (ids.isNotEmpty()) dao.setGamesFavorite(ids.toList(), favorite)
    }

    suspend fun addTagsToGames(ids: Set<String>, tagIds: Set<String>) {
        ids.forEach { gameId ->
            dao.insertGameTags(tagIds.map { fr.astragames.app.data.local.GameTagCrossRef(gameId, it) })
        }
    }

    suspend fun createOrUpdateCollection(
        collectionId: String?, name: String, matchMode: String, rules: List<CollectionRuleDraft>
    ) {
        val clean = name.trim()
        require(clean.isNotBlank()) { "Donnez un nom à la collection." }
        require(rules.isNotEmpty()) { "Ajoutez au moins une règle." }
        val id = collectionId ?: UUID.randomUUID().toString()
        dao.replaceCollection(
            CollectionEntity(id, clean, sortOrder = 0, matchMode = if (matchMode == "ANY") "ANY" else "ALL"),
            rules.map { rule -> CollectionRuleEntity(UUID.randomUUID().toString(), id, rule.field, rule.operator, rule.value) }
        )
    }

    suspend fun deleteCollection(id: String) = dao.deleteCollection(id)

    suspend fun startPlaySession(gameId: String) {
        finishActivePlaySession()
        dao.insertPlaySession(PlaySessionEntity(UUID.randomUUID().toString(), gameId, System.currentTimeMillis()))
    }

    suspend fun finishActivePlaySession() {
        val active = dao.getActivePlaySession() ?: return
        val now = System.currentTimeMillis()
        val duration = (now - active.startedAt).coerceIn(0, 12 * 60 * 60 * 1000L)
        dao.finishPlaySession(active.id, now, duration)
    }

    suspend fun ignoreDuplicateGroup(groupKey: String) =
        dao.ignoreDuplicateGroup(IgnoredDuplicateGroupEntity(groupKey, System.currentTimeMillis()))

    suspend fun previewDuplicateMerge(primaryId: String, secondaryId: String): DuplicateMergePreview {
        val primary = dao.getGame(primaryId) ?: error("Jeu principal introuvable.")
        val secondary = dao.getGame(secondaryId) ?: error("Jeu secondaire introuvable.")
        val primarySaves = findSaveFiles(primary)
        val secondarySaves = findSaveFiles(secondary)
        val primaryPaths = primarySaves.associateBy { it.relativePath.lowercase() }
        return DuplicateMergePreview(
            primary, secondary, primarySaves, secondarySaves,
            secondarySaves.filter { it.relativePath.lowercase() in primaryPaths }
        )
    }

    suspend fun mergeDuplicate(
        primaryId: String,
        secondaryId: String,
        migrateSaves: Boolean,
        saveStrategy: SaveConflictStrategy,
        deleteSecondaryFiles: Boolean
    ): DuplicateMergeResult {
        val preview = previewDuplicateMerge(primaryId, secondaryId)
        if (migrateSaves) migrateSaveFiles(preview, saveStrategy)
        if (deleteSecondaryFiles) deleteGameFolder(preview.secondary)

        val primary = preview.primary
        val secondary = preview.secondary
        val mergedCoverUri = primary.coverUri ?: secondary.coverUri
        val primaryTags = dao.getTagIdsForGame(primary.id).toSet()
        val secondaryTags = dao.getTagIdsForGame(secondary.id).toSet()
        dao.updateMergedGame(
            id = primary.id,
            title = primary.title,
            originalTitle = primary.originalTitle ?: secondary.originalTitle,
            aliases = (primary.aliases.split('|') + secondary.aliases.split('|')).map(String::trim).filter(String::isNotBlank).distinct().joinToString(" | "),
            coverUri = mergedCoverUri,
            bannerUri = primary.bannerUri ?: secondary.bannerUri,
            iconUri = primary.iconUri ?: secondary.iconUri,
            description = primary.description?.takeIf(String::isNotBlank) ?: secondary.description,
            developer = primary.developer ?: secondary.developer,
            version = primary.version ?: secondary.version,
            productCode = primary.productCode ?: secondary.productCode,
            language = primary.language ?: secondary.language,
            releaseDate = primary.releaseDate ?: secondary.releaseDate,
            dateAdded = minOf(primary.dateAdded, secondary.dateAdded),
            lastPlayedAt = listOfNotNull(primary.lastPlayedAt, secondary.lastPlayedAt).maxOrNull(),
            playCount = primary.playCount + secondary.playCount,
            favorite = primary.favorite || secondary.favorite,
            keywords = (primary.keywords.split(' ') + secondary.keywords.split(' ')).filter(String::isNotBlank).distinct().joinToString(" ")
        )
        dao.replaceGameTags(primary.id, primaryTags + secondaryTags)
        dao.movePlaySessions(primary.id, secondary.id)
        if (!deleteSecondaryFiles) dao.upsertDeletedGame(
            DeletedGameEntity(
                UUID.randomUUID().toString(), secondary.title, secondary.documentUri, secondary.physicalPath,
                secondary.fingerprint, secondary.sourceId, System.currentTimeMillis(), "DUPLICATE_MERGED"
            )
        )
        if (secondary.coverUri != null && secondary.coverUri != mergedCoverUri) {
            removeManagedCover(secondary.coverUri)
        }
        dao.deleteGameCompletely(secondary.id)
        dao.getGame(primary.id)?.let { merged -> dao.upsertGame(merged) }
        return DuplicateMergeResult(preview.secondarySaves.size, preview.conflictingSaves.size)
    }

    suspend fun deleteGame(id: String, deleteAssociatedFiles: Boolean) {
        val game = dao.getGame(id) ?: return
        if (deleteAssociatedFiles) {
            val uri = Uri.parse(game.documentUri)
            require(uri.scheme == "content") {
                "La suppression des fichiers n’est autorisée que pour un dossier Android sélectionné."
            }
            val folder = DocumentFile.fromSingleUri(context, uri)
                ?: error("Le dossier associé au jeu est introuvable.")
            require(folder.exists() && folder.isDirectory) { "Le dossier associé au jeu est introuvable." }
            check(folder.delete()) { "Android n’a pas autorisé la suppression du dossier du jeu." }
        } else {
            dao.upsertDeletedGame(
                DeletedGameEntity(
                    id = UUID.randomUUID().toString(), title = game.title, documentUri = game.documentUri,
                    physicalPath = game.physicalPath, fingerprint = game.fingerprint, sourceId = game.sourceId,
                    deletedAt = System.currentTimeMillis()
                )
            )
        }
        removeManagedCover(game.coverUri)
        dao.deleteGameCompletely(id)
    }

    suspend fun restoreDeletedGame(id: String) = dao.restoreDeletedGame(id)

    suspend fun createBackup(treeUri: Uri): String = backupManager.create(treeUri)
    suspend fun restoreBackup(uri: Uri) = backupManager.restore(uri)

    suspend fun findSaveFolderUri(gameId: String): Uri? {
        val game = dao.getGame(gameId) ?: return null
        val root = DocumentFile.fromSingleUri(context, Uri.parse(game.documentUri)) ?: return null
        fun find(folder: DocumentFile, depth: Int): DocumentFile? {
            if (depth > 6) return null
            val children = runCatching { folder.listFiles().toList() }.getOrDefault(emptyList())
            children.firstOrNull { it.isDirectory && it.name.orEmpty().lowercase() in SAVE_FOLDER_NAMES }?.let { return it }
            return children.asSequence().filter(DocumentFile::isDirectory).mapNotNull { find(it, depth + 1) }.firstOrNull()
        }
        return find(root, 0)?.uri ?: root.uri
    }

    private fun removeManagedCover(value: String?) {
        value?.let(Uri::parse)?.path?.let(::File)
            ?.takeIf { it.parentFile == File(context.filesDir, "covers") }
            ?.let { runCatching { it.delete() } }
    }

    private fun deleteGameFolder(game: GameEntity) {
        val uri = Uri.parse(game.documentUri)
        require(uri.scheme == "content") { "La suppression physique nécessite un dossier Android SAF." }
        val folder = DocumentFile.fromSingleUri(context, uri)
            ?.takeIf { it.exists() && it.isDirectory } ?: error("Le dossier secondaire est introuvable.")
        check(folder.delete()) { "Android n’a pas autorisé la suppression du dossier secondaire." }
    }

    private fun findSaveFiles(game: GameEntity): List<SaveFileDescriptor> {
        val root = DocumentFile.fromSingleUri(context, Uri.parse(game.documentUri)) ?: return emptyList()
        val result = mutableListOf<SaveFileDescriptor>()
        fun walk(folder: DocumentFile, path: String, insideSaveFolder: Boolean, depth: Int) {
            if (depth > 7 || result.size >= 1_000) return
            folder.listFiles().forEach { child ->
                val name = child.name.orEmpty()
                val relative = listOf(path, name).filter(String::isNotBlank).joinToString("/")
                val inSave = insideSaveFolder || name.lowercase() in SAVE_FOLDER_NAMES
                if (child.isDirectory) walk(child, relative, inSave, depth + 1)
                else if (inSave || name.substringAfterLast('.', "").lowercase() in SAVE_EXTENSIONS) {
                    result += SaveFileDescriptor(relative, child.uri.toString(), child.length(), child.lastModified())
                }
            }
        }
        runCatching { walk(root, "", false, 0) }
        return result
    }

    private fun migrateSaveFiles(preview: DuplicateMergePreview, strategy: SaveConflictStrategy) {
        val primaryRoot = DocumentFile.fromSingleUri(context, Uri.parse(preview.primary.documentUri))
            ?: error("Le dossier du jeu principal est inaccessible.")
        preview.secondarySaves.forEach { save ->
            val source = DocumentFile.fromSingleUri(context, Uri.parse(save.documentUri)) ?: return@forEach
            val parts = save.relativePath.split('/').filter(String::isNotBlank)
            if (parts.isEmpty()) return@forEach
            var targetFolder = primaryRoot
            parts.dropLast(1).forEach { part ->
                targetFolder = targetFolder.findFile(part)?.takeIf { it.isDirectory }
                    ?: targetFolder.createDirectory(part) ?: error("Impossible de créer le dossier de sauvegarde $part")
            }
            val originalName = parts.last()
            val existing = targetFolder.findFile(originalName)
            val targetName = when {
                existing == null -> originalName
                strategy == SaveConflictStrategy.KEEP_PRIMARY -> return@forEach
                strategy == SaveConflictStrategy.REPLACE_WITH_SECONDARY -> originalName.also { check(existing.delete()) }
                else -> uniqueSaveName(targetFolder, originalName, preview.secondary.title)
            }
            val target = targetFolder.createFile(source.type ?: "application/octet-stream", targetName)
                ?: error("Impossible de créer $targetName dans le dossier principal.")
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri, "w")?.use(input::copyTo)
                    ?: error("Impossible d’écrire la sauvegarde $targetName")
            } ?: error("Impossible de lire la sauvegarde ${save.relativePath}")
        }
    }

    private fun uniqueSaveName(folder: DocumentFile, original: String, gameTitle: String): String {
        val extension = original.substringAfterLast('.', "").takeIf(String::isNotBlank)
        val base = if (extension == null) original else original.removeSuffix(".$extension")
        val suffix = gameTitle.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(24).ifBlank { "doublon" }
        var candidate = "$base-astra-$suffix${extension?.let { ".$it" }.orEmpty()}"
        var index = 2
        while (folder.findFile(candidate) != null) {
            candidate = "$base-astra-$suffix-$index${extension?.let { ".$it" }.orEmpty()}"
            index++
        }
        return candidate
    }

    suspend fun toggleGameTag(gameId: String, tagId: String, selected: Boolean) {
        if (selected) dao.addGameTag(fr.astragames.app.data.local.GameTagCrossRef(gameId, tagId))
        else dao.removeGameTag(gameId, tagId)
    }

    suspend fun setGameTags(gameId: String, tagIds: Set<String>) = dao.replaceGameTags(gameId, tagIds)

    suspend fun createTag(name: String, categoryName: String?): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return false
        dao.upsertTag(TagEntity(UUID.randomUUID().toString(), clean, normalize(clean), categoryName))
        return true
    }

    suspend fun editTag(tag: TagEntity, name: String, categoryName: String?) {
        val clean = name.trim()
        if (clean.isNotBlank()) dao.upsertTag(tag.copy(name = clean, normalizedName = normalize(clean), groupName = categoryName))
    }

    suspend fun deleteTags(tagIds: Set<String>) = dao.deleteTags(tagIds.toList())

    suspend fun moveTagsToCategory(tagIds: Set<String>, categoryName: String?) =
        dao.moveTagsToCategory(tagIds.toList(), categoryName)

    suspend fun createTagCategory(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val nextOrder = (dao.getTagCategories().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsertTagCategory(TagCategoryEntity(UUID.randomUUID().toString(), clean, normalize(clean), nextOrder))
    }

    suspend fun renameTagCategory(category: TagCategoryEntity, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        dao.renameTagCategoryReferences(category.name, clean)
        dao.upsertTagCategory(category.copy(name = clean, normalizedName = normalize(clean)))
    }

    suspend fun deleteTagCategory(category: TagCategoryEntity) {
        dao.clearTagCategoryReferences(category.name)
        dao.deleteTagCategoryRaw(category.id)
    }

    suspend fun moveTagCategory(categoryId: String, direction: Int) {
        val categories = dao.getTagCategories()
        val index = categories.indexOfFirst { it.id == categoryId }
        val otherIndex = (index + direction).coerceIn(0, categories.lastIndex)
        if (index < 0 || index == otherIndex) return
        val current = categories[index]
        val other = categories[otherIndex]
        dao.upsertTagCategory(current.copy(sortOrder = other.sortOrder))
        dao.upsertTagCategory(other.copy(sortOrder = current.sortOrder))
    }

    suspend fun updateGame(gameId: String, edits: GameEdits) {
        val title = edits.title.trim()
        if (title.isBlank()) return
        dao.updateGameFields(
            gameId, title, edits.originalTitle.cleanOrNull(), edits.developer.cleanOrNull(),
            edits.version.cleanOrNull(), edits.productCode.cleanOrNull(), edits.language.cleanOrNull(),
            edits.description.cleanOrNull()
        )
    }

    suspend fun importF95Tags(gameId: String, tagNames: Collection<String>): Int {
        val categoryName = "F95Zone"
        val selectedNames = tagNames.map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalize)
        if (selectedNames.isNotEmpty() && dao.getTagCategories().none { normalize(it.name) == normalize(categoryName) }) {
            createTagCategory(categoryName)
        }
        val known = dao.getTags().associateBy { it.normalizedName }.toMutableMap()
        selectedNames.forEach { name ->
            val normalized = normalize(name)
            if (known[normalized] == null) {
                val tag = TagEntity(UUID.randomUUID().toString(), name, normalized, categoryName)
                dao.upsertTag(tag)
                known[normalized] = tag
            }
        }
        val importedIds = selectedNames.mapNotNull { known[normalize(it)]?.id }.toSet()
        dao.replaceGameTags(gameId, dao.getTagIdsForGame(gameId).toSet() + importedIds)
        return importedIds.size
    }

    suspend fun createFolder(name: String, parentId: String? = null) {
        if (name.isNotBlank()) dao.upsertFolder(LibraryFolderEntity(UUID.randomUUID().toString(), name.trim(), parentId))
    }

    suspend fun renameFolder(folder: LibraryFolderEntity, name: String) {
        val clean = name.trim()
        if (clean.isNotBlank()) dao.upsertFolder(folder.copy(name = clean))
    }

    suspend fun deleteFolder(folder: LibraryFolderEntity) {
        dao.moveChildFolders(folder.id, folder.parentId)
        dao.clearGamesFromFolder(folder.id)
        dao.deleteFolder(folder.id)
    }

    suspend fun importTags(uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val extension = context.contentResolver.getType(uri).orEmpty()
        val names = when {
            extension.contains("json") || text.trimStart().startsWith('[') -> Regex("\"(?:name\"\\s*:\\s*\")?([^\"]+)\"")
                .findAll(text).map { it.groupValues[1] }.filterNot { it == "name" || it == "group" }.toList()
            extension.contains("csv") -> text.lineSequence().dropWhile { it.contains("name", true) }.map { it.substringBefore(',') }.toList()
            else -> text.lineSequence().toList()
        }.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { normalize(it) }
        return dao.insertTags(names.map { TagEntity(UUID.randomUUID().toString(), it, normalize(it)) }).count { it != -1L }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase()

    private fun String?.cleanOrNull() = this?.trim()?.ifBlank { null }

    companion object {
        private val SAVE_FOLDER_NAMES = setOf("save", "saves", "savedata", "savegames", "persistent")
        private val SAVE_EXTENSIONS = setOf("rpgsave", "rvdata", "rvdata2", "rxdata", "save", "sav")
    }
}

data class GameEdits(
    val title: String,
    val originalTitle: String?,
    val developer: String?,
    val version: String?,
    val productCode: String?,
    val language: String?,
    val description: String?
)

data class CollectionRuleDraft(val field: String, val operator: String, val value: String)

enum class SaveConflictStrategy { KEEP_PRIMARY, REPLACE_WITH_SECONDARY, KEEP_BOTH }

data class SaveFileDescriptor(val relativePath: String, val documentUri: String, val size: Long, val lastModified: Long)

data class DuplicateMergePreview(
    val primary: GameEntity,
    val secondary: GameEntity,
    val primarySaves: List<SaveFileDescriptor>,
    val secondarySaves: List<SaveFileDescriptor>,
    val conflictingSaves: List<SaveFileDescriptor>
)

data class DuplicateMergeResult(val savesConsidered: Int, val saveConflicts: Int)
