package fr.astragames.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.local.AstraDao
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.local.DeletedGameEntity
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

    private fun removeManagedCover(value: String?) {
        value?.let(Uri::parse)?.path?.let(::File)
            ?.takeIf { it.parentFile == File(context.filesDir, "covers") }
            ?.let { runCatching { it.delete() } }
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
