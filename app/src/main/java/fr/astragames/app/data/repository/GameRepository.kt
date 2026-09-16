package fr.astragames.app.data.repository

import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import java.util.Locale
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.local.AstraDao
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.local.DeletedGameEntity
import fr.astragames.app.data.local.AuditEventEntity
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
import fr.astragames.app.core.search.parseTextTagList
import fr.astragames.app.core.metadata.canonicalF95ThreadUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.Normalizer
import java.io.File
import java.util.UUID

private data class ImportedTag(
    val name: String,
    val groupName: String? = null
)

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
    val auditEvents: Flow<List<AuditEventEntity>> = dao.observeAuditEvents()
    fun gameTagRefs() = dao.observeGameTagRefs()
    fun search(query: String) = if (fr.astragames.app.core.search.SearchParser.terms(query).isEmpty()) games
        else dao.searchGames(fr.astragames.app.core.search.SearchParser.toFtsQuery(query))

    fun game(id: String) = dao.observeGame(id)
    fun gameTags(id: String) = dao.observeTagsForGame(id)
    fun launchProfile(id: String) = dao.observeLaunchProfile(id)

    suspend fun addSource(uri: Uri) {
        if (dao.observeSources().first().any { it.treeUri == uri.toString() }) return
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
        if (!removeGames) {
            dao.deleteSource(id)
            return
        }
        val games = dao.getGamesForSource(id)
        games.forEach { requireNoInstalledMods(it.id) }
        dao.deleteSourceAndGames(id)
        games.forEach { game -> removeManagedCover(game.coverUri) }
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
    suspend fun verifyGamePresence(id: String): Boolean = withContext(Dispatchers.IO) {
        val game = dao.getGame(id) ?: return@withContext false
        val uri = runCatching { Uri.parse(game.documentUri) }.getOrNull()
        val exists = when (uri?.scheme) {
            "file" -> uri.path?.let(::File)?.exists() == true
            "content" -> runCatching { DocumentFile.fromSingleUri(context, uri)?.exists() == true }.getOrDefault(false)
            else -> game.physicalPath?.let(::File)?.exists() == true
        }
        if (exists) dao.markGameFound(id)
        return@withContext exists
    }
    suspend fun setGameVersion(id: String, version: String) = dao.setGameVersion(id, version)

    suspend fun setCover(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "covers").apply { mkdirs() }
        val previous = dao.getGame(id)?.coverUri?.let(Uri::parse)
        val destination = File(directory, "$id-${System.currentTimeMillis()}.jpg")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use(input::copyTo)
        } ?: error("Impossible de lire l'image recadrée.")
            dao.setCover(id, Uri.fromFile(destination).toString())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runCatching { destination.delete() }
            throw error
        }
        previous?.path?.let(::File)?.takeIf { it.parentFile == directory && it != destination }?.delete()
    }

    suspend fun removeCover(id: String) = withContext(Dispatchers.IO) {
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

    suspend fun previewDuplicateMerge(primaryId: String, secondaryId: String, includeSaves: Boolean = true): DuplicateMergePreview = withContext(Dispatchers.IO) {
        require(primaryId != secondaryId) { "Selectionnez deux jeux differents." }
        val primary = dao.getGame(primaryId) ?: error("Jeu principal introuvable.")
        val secondary = dao.getGame(secondaryId) ?: error("Jeu secondaire introuvable.")
        val primarySaves = if (includeSaves) findSaveFiles(primary) else emptyList()
        val secondarySaves = if (includeSaves) findSaveFiles(secondary) else emptyList()
        val primaryPaths = primarySaves.associateBy { it.relativePath.lowercase(Locale.ROOT) }
        return@withContext DuplicateMergePreview(
            primary, secondary, primarySaves, secondarySaves,
            secondarySaves.filter { it.relativePath.lowercase(Locale.ROOT) in primaryPaths }
        )
    }

    suspend fun mergeDuplicate(
        primaryId: String,
        secondaryId: String,
        migrateSaves: Boolean,
        saveStrategy: SaveConflictStrategy,
        deleteSecondaryFiles: Boolean
    ): DuplicateMergeResult = withContext(Dispatchers.IO) {
        if (!deleteSecondaryFiles) requireNoInstalledMods(secondaryId)
        val preview = previewDuplicateMerge(primaryId, secondaryId, includeSaves = migrateSaves)
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
            f95Url = primary.f95Url ?: secondary.f95Url,
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
        return@withContext DuplicateMergeResult(preview.secondarySaves.size, preview.conflictingSaves.size)
    }

    suspend fun deleteGame(id: String, deleteAssociatedFiles: Boolean) = withContext(Dispatchers.IO) {
        val game = dao.getGame(id) ?: return@withContext
        if (!deleteAssociatedFiles) requireNoInstalledMods(id)
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
        recordAudit("GAME_DELETED", "Jeu supprimé : ${game.title}${if (deleteAssociatedFiles) " (fichiers inclus)" else ""}")
    }

    private suspend fun requireNoInstalledMods(gameId: String) {
        require(dao.observeInstallationsForGame(gameId).first().isEmpty()) {
            "Désinstallez ou restaurez les mods de ce jeu avant de retirer sa fiche."
        }
    }

    suspend fun restoreDeletedGame(id: String) = dao.restoreDeletedGame(id)

    suspend fun createBackup(treeUri: Uri): String = backupManager.create(treeUri)
    suspend fun restoreBackup(uri: Uri) = backupManager.restore(uri)

    suspend fun findSaveFolderUri(gameId: String): Uri? = withContext(Dispatchers.IO) {
        val game = dao.getGame(gameId) ?: return@withContext null
        val root = fr.astragames.app.data.saves.documentDir(context, Uri.parse(game.documentUri)) ?: return@withContext null
        val preferred = when (game.engine) {
            "RENPY" -> listOf("game/saves", "saves", "save")
            "RPG_MAKER_MV" -> listOf("www/save", "save")
            "RPG_MAKER_MZ" -> listOf("save")
            else -> listOf("Save", "save", "saves")
        }
        preferred.forEach { path ->
            var current: DocumentFile? = root
            path.split("/").filter(String::isNotBlank).forEach { segment ->
                current = current?.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(segment, ignoreCase = true) }
            }
            current?.let { return@withContext it.uri }
        }
        return@withContext root.uri
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
        val root = fr.astragames.app.data.saves.documentDir(context, Uri.parse(game.documentUri)) ?: error("Dossier du jeu inaccessible.")
        val result = mutableListOf<SaveFileDescriptor>()
        fun walk(folder: DocumentFile, path: String, insideSaveFolder: Boolean, depth: Int) {
            if (depth > 7 || result.size >= 1_000) return
            folder.listFiles().forEach { child ->
                val name = child.name.orEmpty()
                val relative = listOf(path, name).filter(String::isNotBlank).joinToString("/")
                val inSave = insideSaveFolder || name.lowercase(Locale.ROOT) in SAVE_FOLDER_NAMES
                if (child.isDirectory) walk(child, relative, inSave, depth + 1)
                else if (inSave || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in SAVE_EXTENSIONS) {
                    result += SaveFileDescriptor(relative, child.uri.toString(), child.length(), child.lastModified())
                }
            }
        }
        walk(root, "", false, 0)
        return result
    }

    private fun migrateSaveFiles(preview: DuplicateMergePreview, strategy: SaveConflictStrategy) {
        val primaryRoot = fr.astragames.app.data.saves.documentDir(context, Uri.parse(preview.primary.documentUri))
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
        val suffix = gameTitle.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(24).ifBlank { "doublon" }
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
        val clean = formatTagName(name)
        if (clean.isBlank()) return false
        val normalized = normalize(clean)
        if (dao.getTags().any { tagEquivalent(normalize(it.name), normalized) }) return false
        dao.upsertTag(TagEntity(UUID.randomUUID().toString(), clean, normalized, categoryName))
        return true
    }

    suspend fun editTag(tag: TagEntity, name: String, categoryName: String?) {
        val clean = formatTagName(name)
        if (clean.isNotBlank()) dao.upsertTag(tag.copy(name = clean, normalizedName = normalize(clean), groupName = categoryName))
    }

    suspend fun deleteTags(tagIds: Set<String>) {
        if (tagIds.isEmpty()) return
        val names = dao.getTags().filter { it.id in tagIds }.joinToString(", ") { it.name }
        dao.deleteTags(tagIds.toList())
        recordAudit("TAG_DELETED", "Tag(s) supprimé(s) : $names")
    }

    private suspend fun recordAudit(type: String, detail: String) {
        dao.insertAudit(AuditEventEntity(UUID.randomUUID().toString(), type, detail, System.currentTimeMillis()))
    }

    /** Fusionne deux tags : les jeux liés au tag supprimé pointent vers le tag conservé. */
    suspend fun mergeTags(keepTagId: String, removedTagId: String) {
        if (keepTagId == removedTagId) return
        val tags = dao.getTags()
        val keepName = tags.firstOrNull { it.id == keepTagId }?.name ?: "?"
        val removedName = tags.firstOrNull { it.id == removedTagId }?.name ?: "?"
        dao.replaceTagReferences(keepTagId, removedTagId)
        dao.deleteTagsRaw(listOf(removedTagId))
        recordAudit("TAG_MERGE", "Fusion de tags : « $removedName » → « $keepName »")
    }

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
        recordAudit("CATEGORY_DELETED", "Catégorie supprimée : ${category.name}")
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
            edits.description.cleanOrNull(), edits.f95Url.cleanF95UrlOrNull()
        )
    }

    suspend fun setF95Url(gameId: String, url: String?) = dao.setF95Url(gameId, url.cleanF95UrlOrNull())

    suspend fun applyAutomaticMetadata(
        gameId: String,
        originalTitle: String?,
        developer: String?,
        description: String?,
        f95Url: String?,
        version: String? = null,
        language: String? = null
    ) {
        val game = dao.getGame(gameId) ?: return
        dao.updateGameFields(
            id = game.id,
            title = game.title,
            originalTitle = game.originalTitle ?: originalTitle.cleanOrNull(),
            developer = game.developer ?: developer.cleanOrNull(),
            version = game.version ?: version.cleanOrNull(),
            productCode = game.productCode,
            language = game.language ?: language.cleanOrNull(),
            description = game.description ?: description.cleanOrNull(),
            f95Url = game.f95Url ?: f95Url.cleanF95UrlOrNull()
        )
    }

    suspend fun importF95Tags(gameId: String, tagNames: Collection<String>): Int {
        val categoryName = "F95Zone"
        return associateTagNames(gameId, tagNames, categoryName)
    }

    suspend fun importTextTags(gameId: String, raw: String): Int =
        associateTagNames(gameId, parseTextTagList(raw), categoryName = null)

    private suspend fun associateTagNames(gameId: String, tagNames: Collection<String>, categoryName: String?): Int {
        val selectedNames = tagNames.map(String::trim).filter(String::isNotBlank).distinctBy(::normalize)
        if (categoryName != null && selectedNames.isNotEmpty() && dao.getTagCategories().none { normalize(it.name) == normalize(categoryName) }) {
            createTagCategory(categoryName)
        }
        val known = dao.getTags().toMutableList()
        selectedNames.forEach { name ->
            val normalized = normalize(name)
            if (known.none { tagEquivalent(normalize(it.name), normalized) }) {
                val tag = TagEntity(UUID.randomUUID().toString(), name, normalized, categoryName)
                dao.upsertTag(tag)
                known += tag
            }
        }
        val importedIds = selectedNames.mapNotNull { name ->
            known.firstOrNull { tagEquivalent(normalize(it.name), normalize(name)) }?.id
        }.toSet()
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

    suspend fun importTags(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Impossible de lire le fichier de tags.")
        val content = text.removePrefix("\uFEFF")
        if (content.isBlank()) return@withContext 0

        val mimeType = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        val extension = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val trimmed = content.trimStart()
        val imported = when {
            mimeType.contains("json") || extension == "json" || trimmed.startsWith('[') || trimmed.startsWith('{') ->
                parseJsonTags(content)
            mimeType.contains("csv") || extension == "csv" -> parseCsvTags(content)
            else -> parseTextTagList(content).map(::ImportedTag)
        }
            .map { it.copy(name = formatTagName(it.name), groupName = it.groupName?.trim()?.ifBlank { null }) }
            .filter { it.name.isNotBlank() }
            .distinctBy { normalize(it.name) }

        if (imported.isEmpty()) return@withContext 0

        val categories = dao.getTagCategories().associateBy { normalize(it.name) }.toMutableMap()
        val canonicalNames = categories.mapValues { (_, category) -> category.name }.toMutableMap()
        var nextOrder = (categories.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
        imported.mapNotNull { it.groupName }
            .distinctBy(::normalize)
            .forEach { groupName ->
                val normalized = normalize(groupName)
                if (categories[normalized] == null) {
                    val category = TagCategoryEntity(UUID.randomUUID().toString(), groupName, normalized, nextOrder++)
                    dao.upsertTagCategory(category)
                    categories[normalized] = category
                    canonicalNames[normalized] = groupName
                }
            }

        val known = dao.getTags().mapTo(mutableSetOf()) { it.normalizedName }
        val newTags = imported.mapNotNull { tag ->
            val normalized = normalize(tag.name)
            if (!known.add(normalized)) return@mapNotNull null
            TagEntity(
                UUID.randomUUID().toString(), tag.name, normalized,
                tag.groupName?.let(::normalize)?.let(canonicalNames::get)
            )
        }
        return@withContext dao.insertTags(newTags).count { it != -1L }
    }

    private fun parseJsonTags(raw: String): List<ImportedTag> {
        val root = try {
            JSONTokener(raw).nextValue()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error("Le fichier JSON des tags est invalide.")
        }
        return collectJsonTags(root, allowScalar = true)
    }

    private fun collectJsonTags(value: Any?, allowScalar: Boolean = false): List<ImportedTag> = when (value) {
        is JSONArray -> buildList {
            for (index in 0 until value.length()) addAll(collectJsonTags(value.opt(index), allowScalar = true))
        }
        is JSONObject -> buildList {
            val name = firstJsonString(value, "name", "tag", "label")
            val group = firstJsonString(value, "groupName", "group", "categoryName", "category")
            if (name != null) add(ImportedTag(name, group))
            listOf("tags", "data", "items").forEach { key ->
                if (value.has(key)) addAll(collectJsonTags(value.opt(key), allowScalar = true))
            }
        }
        is String -> if (allowScalar) listOf(ImportedTag(value)) else emptyList()
        else -> emptyList()
    }

    private fun firstJsonString(value: JSONObject, vararg keys: String): String? =
        keys.asSequence()
            .map { key -> value.optString(key, "").trim() }
            .firstOrNull(String::isNotBlank)

    private fun parseCsvTags(raw: String): List<ImportedTag> {
        val rows = parseCsvRows(raw)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map(::csvHeaderKey)
        val nameIndex = header.indexOfFirst { it in setOf("name", "tag", "label", "nom", "tagname") }
        val groupIndex = header.indexOfFirst { it in setOf("group", "groupname", "category", "categoryname", "groupe", "categorie") }
        val hasHeader = nameIndex >= 0
        val valueIndex = if (hasHeader) nameIndex else 0
        return rows.drop(if (hasHeader) 1 else 0).mapNotNull { row ->
            val name = row.getOrNull(valueIndex)?.trim().orEmpty()
            if (name.isBlank()) null else ImportedTag(name, row.getOrNull(groupIndex)?.trim()?.ifBlank { null })
        }
    }



    /** Choisit le séparateur CSV (virgule ou point-virgule) en ignorant les champs cités. */
    private fun detectCsvSeparator(raw: String): Char {
        var quoted = false
        var commas = 0
        var semicolons = 0
        var index = 0
        while (index < raw.length) {
            when (raw[index]) {
                '"' -> if (quoted && raw.getOrNull(index + 1) == '"') index++ else quoted = !quoted
                ',' -> if (!quoted) commas++
                ';' -> if (!quoted) semicolons++
                else -> Unit
            }
            index++
        }
        return if (semicolons > commas) ';' else ','
    }

    private fun parseCsvRows(raw: String): List<List<String>> {
        val separator = detectCsvSeparator(raw)
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        fun finishField() {
            row += field.toString().trim()
            field.setLength(0)
        }
        fun finishRow() {
            finishField()
            if (row.any(String::isNotBlank)) rows += row.toList()
            row.clear()
        }
        while (index < raw.length) {
            when (val character = raw[index]) {
                '"' -> if (quoted && raw.getOrNull(index + 1) == '"') {
                    field.append('"')
                    index++
                } else {
                    quoted = !quoted
                }
                separator -> if (quoted) field.append(character) else finishField()
                '\n' -> if (quoted) field.append(character) else finishRow()
                '\r' -> if (quoted) field.append(character) else if (raw.getOrNull(index + 1) != '\n') finishRow()
                else -> field.append(character)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    private fun csvHeaderKey(value: String): String = normalize(value).replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

    /** Nettoie et formate un nom de tag : première lettre de chaque mot en majuscule. */
    private fun formatTagName(raw: String): String =
        raw.trim().replace(Regex("[\\s_]+"), " ").split(' ').filter(String::isNotBlank)
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }

    /** Sépare une saisie en tags : les symboles (#, @, …) et la ponctuation deviennent des séparateurs. */
    private fun parseTagNames(raw: String): List<String> =
        raw.replace(Regex("[#@!?&%$^*+=|<>\\[\\]{}()~]"), ",")
            .split(',', ';', '\n', '\r')
            .map(::formatTagName)
            .filter(String::isNotBlank)

    /** Fusionne les doublons proches : « 3D Games » et « 3D Game » sont équivalents. */
    private fun tagEquivalent(first: String, second: String): Boolean {
        if (first == second) return true
        fun stripPlural(value: String): String = value.split(' ').joinToString(" ") { word ->
            if (word.length > 3 && word.endsWith("s")) word.dropLast(1) else word
        }
        return stripPlural(first) == stripPlural(second)
    }

    private fun String?.cleanOrNull() = this?.trim()?.ifBlank { null }

    private fun String?.cleanF95UrlOrNull(): String? {
        val clean = cleanOrNull() ?: return null
        return canonicalF95ThreadUrl(clean)
    }

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
    val description: String?,
    val f95Url: String?
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
