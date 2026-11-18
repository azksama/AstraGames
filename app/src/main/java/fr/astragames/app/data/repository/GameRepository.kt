package fr.astragames.app.data.repository

import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import java.util.Locale
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import fr.astragames.app.core.collections.SmartCollectionEvaluator
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.backup.BackupManager
import fr.astragames.app.data.local.DeletedGameEntity
import fr.astragames.app.data.local.AuditEventEntity
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.IgnoredDuplicateGroupEntity
import fr.astragames.app.data.local.PlaySessionEntity
import fr.astragames.app.data.local.MAX_SESSION_DURATION_MS
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.local.GameTagCrossRef
import fr.astragames.app.data.local.LibraryFolderEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.data.scanner.RecursiveSourceScanner
import fr.astragames.app.core.filesystem.GameFolders
import fr.astragames.app.core.filesystem.DirectoryEntry
import fr.astragames.app.core.filesystem.listDirectoryEntries
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.data.saves.documentFile
import fr.astragames.app.data.scanner.ScanProgressUpdate
import fr.astragames.app.core.model.ScanReport
import fr.astragames.app.core.model.ScanReportItem
import fr.astragames.app.core.model.ScanReportItemStatus
import fr.astragames.app.core.search.parseTextTagList
import fr.astragames.app.core.metadata.canonicalF95ThreadUrl
import fr.astragames.app.core.metadata.MetadataField
import fr.astragames.app.core.metadata.MetadataSource
import fr.astragames.app.core.metadata.MetadataImportSelection
import fr.astragames.app.core.metadata.MetadataValues
import fr.astragames.app.core.metadata.F95ZoneMetadata
import fr.astragames.app.data.saves.readBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import fr.astragames.app.launcher.LaunchProfileResolver
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
    private val database: AstraDatabase,
    private val scanner: RecursiveSourceScanner,
    private val backupManager: BackupManager
) {
    private val dao = database.dao()
    private fun effectiveGames(games: Flow<List<GameEntity>>) = combine(games, dao.observeLaunchProfiles()) { entries, profiles ->
        val byGame = profiles.associateBy { it.gameId }
        entries.map { LaunchProfileResolver.displayGame(it, byGame[it.id]) }
    }
    val games: Flow<List<GameEntity>> = effectiveGames(dao.observeGames())
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
        else effectiveGames(dao.searchGames(fr.astragames.app.core.search.SearchParser.toFtsQuery(query)))

    fun game(id: String) = dao.observeGame(id)
    fun gameTags(id: String) = dao.observeTagsForGame(id)
    fun launchProfile(id: String) = dao.observeLaunchProfile(id)

    suspend fun addSource(uri: Uri) = scanner.withLibraryMutation {
        if (uri.toString() in dao.getSourceTreeUris()) return@withLibraryMutation
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').substringAfterLast('/') }
            .getOrDefault("Source de jeux").ifBlank { "Source de jeux" }
        dao.upsertSource(GameSourceEntity(UUID.randomUUID().toString(), name, uri.toString()))
    }

    suspend fun toggleSource(id: String) = dao.toggleSource(id)
    suspend fun recoverInterruptedScans() = scanner.withLibraryMutation { dao.recoverInterruptedScans() }
    suspend fun toggleSourceRecursive(id: String) = dao.toggleSourceRecursive(id)

    suspend fun removeSource(id: String, removeGames: Boolean = false) = scanner.withLibraryMutation {
        if (!removeGames) {
            dao.deleteSource(id)
            return@withLibraryMutation
        }
        val removed = database.withTransaction {
            val games = dao.getGamesForSource(id)
            games.forEach { requireNoInstalledMods(it.id) }
            dao.deleteSourceAndGames(id)
            games
        }
        removeUnusedArtwork(removed.flatMap { it.artworkUris() })
    }

    suspend fun scanSource(id: String, onProgress: (ScanProgressUpdate) -> Unit = {}) = scanner.scan(id, onProgress)
    suspend fun scanSubfolder(id: String, segments: List<String>, onProgress: (ScanProgressUpdate) -> Unit = {}) = scanner.scanSubfolder(id, segments, onProgress)
    suspend fun listSourceSubfolders(id: String, segments: List<String>) = scanner.listSubfolders(id, segments)
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
    suspend fun toggleFavorite(id: String) {
        check(dao.toggleFavorite(id) == 1) { "Un jeu sélectionné a été retiré de la bibliothèque." }
    }
    suspend fun getGame(id: String) = dao.getGame(id)
    suspend fun getGameIds(): Set<String> = dao.getGameIds().toSet()
    suspend fun recordLaunch(id: String, trackSession: Boolean = false): Unit = database.withTransaction {
        val now = System.currentTimeMillis()
        if (dao.recordLaunch(id, now) == 0) return@withTransaction
        finishOpenSessions(now)
        if (trackSession) dao.insertPlaySession(PlaySessionEntity(UUID.randomUUID().toString(), id, now))
    }
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
    suspend fun confirmGameVersion(id: String, version: String, expectedInstalled: String?) = database.withTransaction {
        val game = checkNotNull(dao.getGame(id)) { "Jeu introuvable." }
        if (game.version == version) return@withTransaction
        check(game.version == expectedInstalled) { "La version installée a changé. Rouvrez la confirmation." }
        dao.setGameVersion(id, version)
    }

    suspend fun setCover(id: String, uri: Uri, onlyIfMissing: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val game = dao.getGame(id) ?: return@withContext false
        if (onlyIfMissing && !game.coverUri.isNullOrBlank()) return@withContext false
        val destination = copyToManagedCover(uri)
        var published = false
        try {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) publish@{
                val previous = changeCover(id, Uri.fromFile(destination).toString(), onlyIfMissing) ?: return@publish false
                published = true
                removeUnusedArtwork(listOf(previous.coverUri))
                true
            }
        } finally {
            if (!published) destination.delete()
        }
    }

    private suspend fun changeCover(id: String, value: String?, onlyIfMissing: Boolean = false): GameEntity? = database.withTransaction {
        val previous = dao.getGame(id) ?: return@withTransaction null
        val updated = if (onlyIfMissing) dao.setCoverIfMissing(id, requireNotNull(value)) else dao.setCover(id, value)
        previous.takeIf { updated > 0 }
    }

    private fun copyToManagedCover(uri: Uri): File {
        val directory = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Impossible de lire la jaquette.")
            input.use { source -> file.outputStream().use { output -> source.copyTo(output) } }
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    suspend fun removeCover(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        val previous = checkNotNull(changeCover(id, null)) { "Jeu introuvable." }
        removeUnusedArtwork(listOf(previous.coverUri))
    }

    suspend fun setGameFolder(id: String, folderId: String?) = setGamesFolder(setOf(id), folderId)

    suspend fun setGamesFolder(ids: Set<String>, folderId: String?) = database.withTransaction {
        if (ids.isEmpty()) return@withTransaction
        check(folderId == null || dao.getFolder(folderId) != null) { "Le dossier choisi a été supprimé." }
        ids.chunked(900).forEach { batch ->
            check(dao.setGamesFolder(batch, folderId) == batch.size) { "Un jeu sélectionné a été retiré de la bibliothèque." }
        }
    }

    suspend fun setGamesFavorite(ids: Set<String>, favorite: Boolean) = database.withTransaction {
        ids.chunked(900).forEach { batch ->
            check(dao.setGamesFavorite(batch, favorite) == batch.size) { "Un jeu sélectionné a été retiré de la bibliothèque." }
        }
    }

    suspend fun addTagsToGames(ids: Set<String>, tagIds: Set<String>) = dao.addTagsToGames(ids, tagIds)

    suspend fun createOrUpdateCollection(
        collectionId: String?, name: String, matchMode: String, rules: List<CollectionRuleDraft>, draftId: String? = null
    ) = database.withTransaction {
        val clean = name.trim()
        require(clean.isNotBlank()) { "Donnez un nom à la collection." }
        require(rules.isNotEmpty()) { "Ajoutez au moins une règle." }
        require(matchMode == "ALL" || matchMode == "ANY") { "Mode de collection inconnu." }
        val collection = when {
            collectionId != null -> checkNotNull(dao.getCustomCollection(collectionId)) { "Cette collection a été supprimée." }
            draftId != null && dao.getCustomCollection(draftId) != null -> return@withTransaction
            else -> CollectionEntity(draftId ?: UUID.randomUUID().toString(), clean)
        }
        rules.forEachIndexed { index, rule ->
            require(SmartCollectionEvaluator.isValidRule(rule.field, rule.operator, rule.value,
                tagExists = { dao.tagExists(it) }, folderExists = { dao.getFolder(it) != null })) {
                "Vérifiez la règle ${index + 1} avant d’enregistrer la collection."
            }
        }
        dao.replaceCollection(
            collection.copy(name = clean, matchMode = matchMode),
            rules.map { rule -> CollectionRuleEntity(UUID.randomUUID().toString(), collection.id, rule.field, rule.operator, rule.value) }
        )
    }

    suspend fun deleteCollection(id: String) = dao.deleteCollection(id)

    suspend fun finishActivePlaySession() = database.withTransaction { finishOpenSessions(System.currentTimeMillis()) }

    private suspend fun finishOpenSessions(now: Long) {
        val firstStart = dao.firstOpenSessionStart() ?: return
        var nextStart = now
        for (session in dao.sessionsSince(firstStart)) {
            if (session.endedAt == null) {
                val end = nextStart.coerceAtLeast(session.startedAt)
                val duration = (end - session.startedAt).coerceIn(0L, MAX_SESSION_DURATION_MS)
                dao.finishPlaySession(session.id, end, duration)
            }
            nextStart = minOf(nextStart, session.startedAt)
        }
    }

    suspend fun ignoreDuplicateGroup(groupKey: String) =
        dao.ignoreDuplicateGroup(IgnoredDuplicateGroupEntity(groupKey, System.currentTimeMillis()))

    suspend fun resetIgnoredDuplicateGroups() = dao.resetIgnoredDuplicateGroups()

    suspend fun previewDuplicateMerge(primaryId: String, secondaryId: String, includeSaves: Boolean = true): DuplicateMergePreview = withContext(Dispatchers.IO) {
        require(primaryId != secondaryId) { "Selectionnez deux jeux differents." }
        val primary = dao.getGame(primaryId) ?: error("Jeu principal introuvable.")
        val secondary = dao.getGame(secondaryId) ?: error("Jeu secondaire introuvable.")
        val primarySaves = if (includeSaves) findSaveFiles(primary) else emptyList()
        val secondarySaves = if (includeSaves) findSaveFiles(secondary) else emptyList()
        require(secondarySaves.distinctBy { it.relativePath.lowercase(Locale.ROOT) }.size == secondarySaves.size) {
            "Plusieurs sauvegardes du doublon ont le même chemin à la casse près. Vérifiez leurs noms avant de fusionner."
        }
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
    ): DuplicateMergeResult = scanner.withLibraryMutation { withContext(Dispatchers.IO + NonCancellable) {
        if (!deleteSecondaryFiles) requireNoInstalledMods(secondaryId)
        val preview = previewDuplicateMerge(primaryId, secondaryId, includeSaves = migrateSaves)
        require(!GameFolders.contains(context, preview.secondary.documentUri, preview.primary.documentUri) &&
            !GameFolders.contains(context, preview.primary.documentUri, preview.secondary.documentUri)) {
            "Ces fiches utilisent le même dossier ou des dossiers imbriqués. La fusion est bloquée pour protéger les fichiers."
        }
        if (deleteSecondaryFiles) validateGameDeletion(preview.secondary)
        if (migrateSaves) migrateSaveFiles(preview, saveStrategy)
        val recoveredImages = mutableMapOf<String, File>()
        val discardedArtwork = try {
            database.withTransaction {
                val primary = checkNotNull(dao.getGame(primaryId)) { "Jeu principal introuvable." }
                val secondary = checkNotNull(dao.getGame(secondaryId)) { "Jeu secondaire introuvable." }
                check(primary.documentUri == preview.primary.documentUri && secondary.documentUri == preview.secondary.documentUri) {
                    "L’emplacement des jeux a changé. Comparez-les à nouveau."
                }
                if (!deleteSecondaryFiles) requireNoInstalledMods(secondaryId)
                fun retainImage(value: String?): String? {
                    if (value == null || !deleteSecondaryFiles || !GameFolders.contains(context, secondary.documentUri, value)) return value
                    val copy = recoveredImages.getOrPut(value) { copyToManagedCover(value.toUri()) }
                    return Uri.fromFile(copy).toString()
                }
                val merged = primary.copy(
                    originalTitle = primary.originalTitle ?: secondary.originalTitle,
                    aliases = (primary.aliases.split('|') + secondary.aliases.split('|')).map(String::trim).filter(String::isNotBlank).distinct().joinToString(" | "),
                    coverUri = retainImage(primary.coverUri ?: secondary.coverUri),
                    bannerUri = retainImage(primary.bannerUri ?: secondary.bannerUri),
                    iconUri = retainImage(primary.iconUri ?: secondary.iconUri),
                    description = primary.description?.takeIf(String::isNotBlank) ?: secondary.description,
                    developer = primary.developer ?: secondary.developer,
                    version = primary.version ?: secondary.version,
                    productCode = primary.productCode ?: secondary.productCode,
                    language = primary.language ?: secondary.language,
                    f95Url = primary.f95Url ?: secondary.f95Url,
                    ryuugamesUrl = primary.ryuugamesUrl ?: secondary.ryuugamesUrl,
                    releaseDate = primary.releaseDate ?: secondary.releaseDate,
                    dateAdded = minOf(primary.dateAdded, secondary.dateAdded),
                    lastPlayedAt = listOfNotNull(primary.lastPlayedAt, secondary.lastPlayedAt).maxOrNull(),
                    playCount = primary.playCount + secondary.playCount,
                    favorite = primary.favorite || secondary.favorite,
                    keywords = (primary.keywords.split(' ') + secondary.keywords.split(' ')).filter(String::isNotBlank).distinct().joinToString(" ")
                )
                dao.upsertGame(merged)
                dao.insertGameTags(dao.getTagIdsForGame(secondaryId).map { GameTagCrossRef(primaryId, it) })
                dao.movePlaySessions(primaryId, secondaryId)
                if (!deleteSecondaryFiles) dao.upsertDeletedGame(
                    DeletedGameEntity(
                        UUID.randomUUID().toString(), secondary.title, secondary.documentUri, secondary.physicalPath,
                        secondary.fingerprint, secondary.sourceId, System.currentTimeMillis(), "DUPLICATE_MERGED"
                    )
                )
                dao.deleteGameCompletely(secondaryId)
                dao.refreshSourceGameCounts()
                // Do the irreversible operation last. A provider refusal rolls the catalogue back.
                if (deleteSecondaryFiles) deleteGameFolder(secondary)
                secondary.artworkUris() - merged.artworkUris().toSet()
            }
        } catch (failure: Throwable) {
            recoveredImages.values.forEach { it.delete() }
            throw failure
        }
        removeUnusedArtwork(discardedArtwork)
        return@withContext DuplicateMergeResult(preview.secondarySaves.size, preview.conflictingSaves.size)
    } }

    suspend fun deleteGames(ids: Set<String>, deleteAssociatedFiles: Boolean = false) = scanner.withLibraryMutation {
        withContext(Dispatchers.IO) {
            val games = ids.mapNotNull { dao.getGame(it) }
            // Validate the entire selection before the first irreversible operation.
            games.forEach { if (deleteAssociatedFiles) validateGameDeletion(it) else requireNoInstalledMods(it.id) }
            var completed = 0
            games.forEach { game ->
                try {
                    withContext(NonCancellable) { removeGameLocked(game, deleteAssociatedFiles) }
                    completed++
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    throw IllegalStateException("${game.title} : ${error.message ?: "opération impossible"}" +
                        if (completed > 0) " ($completed jeu(x) déjà traité(s))." else "", error)
                }
            }
        }
    }

    suspend fun deleteGame(id: String, deleteAssociatedFiles: Boolean) = deleteGames(setOf(id), deleteAssociatedFiles)

    private suspend fun removeGameLocked(game: GameEntity, deleteFiles: Boolean) {
        if (deleteFiles) deleteGameFolder(game)
        val removed = database.withTransaction {
            if (deleteFiles) {
                val current = dao.getGame(game.id)
                dao.deleteGameCompletely(game.id)
                listOfNotNull(current)
            } else {
                val affected = dao.getAllGames().filter { GameFolders.contains(context, game.documentUri, it.documentUri) }
                affected.forEach { requireNoInstalledMods(it.id) }
                dao.ignoreAndRemoveGames(
                    DeletedGameEntity(
                        id = UUID.randomUUID().toString(), title = game.title, documentUri = game.documentUri,
                        physicalPath = game.physicalPath, fingerprint = game.fingerprint, sourceId = game.sourceId,
                        deletedAt = System.currentTimeMillis(), reason = "USER_IGNORED"
                    ), affected.map { it.id }
                )
                affected
            }
        }
        removeUnusedArtwork(removed.flatMap { it.artworkUris() })
        dao.refreshSourceGameCounts()
        recordAudit(if (deleteFiles) "GAME_DELETED" else "GAME_IGNORED",
            if (deleteFiles) "Jeu et dossier supprimés : ${game.title}" else "Dossier ignoré : ${game.title}")
    }

    private suspend fun requireNoInstalledMods(gameId: String) {
        require(!dao.hasInstallationsForGame(gameId)) {
            "Désinstallez ou restaurez les mods de ce jeu avant de retirer sa fiche."
        }
    }

    suspend fun restoreDeletedGame(id: String) = scanner.withLibraryMutation { dao.restoreDeletedGame(id) }

    suspend fun createBackup(treeUri: Uri): String = backupManager.create(treeUri)
    suspend fun restoreBackup(uri: Uri) = backupManager.restore(uri)

    suspend fun findSaveFolderUri(gameId: String): Uri? = withContext(Dispatchers.IO) {
        val game = dao.getGame(gameId) ?: return@withContext null
        val root = documentDir(context, Uri.parse(game.documentUri)) ?: return@withContext null
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

    private fun GameEntity.artworkUris() = listOfNotNull(coverUri, bannerUri, iconUri)

    private suspend fun removeUnusedArtwork(values: List<String?>) {
        val directory = File(context.filesDir, "covers")
        for (value in values.filterNotNull().distinct()) {
            val uri = value.toUri()
            val file = when {
                uri.scheme == "file" && uri.authority.isNullOrEmpty() -> uri.path?.let(::File)
                uri.scheme == null -> File(value)
                else -> null
            } ?: continue
            if (file.parentFile != directory) continue
            val aliases = listOf(value, Uri.fromFile(file).toString(), file.toURI().toASCIIString(), file.path).distinct()
            database.withTransaction {
                if (!dao.isArtworkReferenced(aliases) && file.isFile) file.delete()
            }
        }
    }

    private suspend fun validateGameDeletion(game: GameEntity) {
        val uri = Uri.parse(game.documentUri)
        require(uri.scheme == "content") { "La suppression physique nécessite un dossier Android SAF." }
        val source = dao.getSource(game.sourceId) ?: error("Source introuvable. Rétablissez l’accès au dossier dans les paramètres.")
        require(GameFolders.contains(context, source.treeUri, game.documentUri)) { "Le dossier du jeu ne se trouve pas dans sa source autorisée." }
        require(dao.getSourceTreeUris().none { GameFolders.contains(context, game.documentUri, it) }) {
            "Ce dossier est une source de jeux. Sélectionnez son dossier parent comme source pour supprimer ce jeu, ou choisissez Ignorer."
        }
        require(dao.getAllGames().none { it.id != game.id && (GameFolders.contains(context, game.documentUri, it.documentUri) || GameFolders.contains(context, it.documentUri, game.documentUri)) }) {
            "Ce dossier contient ou partage les fichiers d’un autre jeu. Choisissez Ignorer pour protéger cet autre jeu."
        }
        val folder = DocumentFile.fromSingleUri(context, uri)
            ?.takeIf { it.exists() && it.isDirectory } ?: error("Dossier inaccessible. Vérifiez son accès dans les paramètres, ou choisissez Ignorer.")
        require(folder.canWrite()) { "Android n’autorise pas la suppression de ce dossier. Accordez l’accès à sa source dans les paramètres." }
    }

    private suspend fun deleteGameFolder(game: GameEntity) {
        validateGameDeletion(game)
        val uri = Uri.parse(game.documentUri)
        check(DocumentsContract.deleteDocument(context.contentResolver, uri)) { "Android n’a pas autorisé la suppression du dossier. La fiche est conservée." }
        check(DocumentFile.fromSingleUri(context, uri)?.exists() != true) {
            "Le dossier est encore présent. La fiche est conservée ; vérifiez les fichiers avant de réessayer."
        }
    }

    private suspend fun findSaveFiles(game: GameEntity): List<SaveFileDescriptor> {
        val root = documentDir(context, Uri.parse(game.documentUri)) ?: error("Dossier du jeu inaccessible.")
        val result = mutableListOf<SaveFileDescriptor>()
        val visited = mutableSetOf<Uri>()
        suspend fun walk(folder: Uri, path: String, insideSaveFolder: Boolean, depth: Int) {
            currentCoroutineContext().ensureActive()
            check(depth <= 128 && visited.size < 100_000 && visited.add(folder)) {
                "Inventaire des sauvegardes incomplet dans ${game.title} : arborescence trop grande ou cyclique."
            }
            for (child in listDirectoryEntries(context, folder)) {
                val name = child.name
                val relative = if (path.isEmpty()) name else "$path/$name"
                val inSave = insideSaveFolder || name.lowercase(Locale.ROOT) in SAVE_FOLDER_NAMES
                if (child.isDirectory) walk(child.uri, relative, inSave, depth + 1)
                else if (inSave || isStandaloneSave(name, path)) {
                    check(result.size < 1_000) { "Inventaire incomplet : plus de 1 000 sauvegardes dans ${game.title}." }
                    result += SaveFileDescriptor(relative, child.uri.toString(), child.size, child.lastModified)
                }
            }
        }
        walk(root.uri, "", false, 0)
        return result
    }

    private fun isStandaloneSave(name: String, parent: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in RGSS_EXTENSIONS) return extension in SAVE_EXTENSIONS
        if (!parent.substringAfterLast('/').equals("Data", ignoreCase = true)) return true
        // Standard project files share the save extension. Keep custom progress files eligible.
        val stem = name.substringBeforeLast('.').lowercase(Locale.ROOT)
        return stem !in RGSS_PROJECT_TABLES && !RGSS_MAP.matches(stem)
    }

    private suspend fun migrateSaveFiles(preview: DuplicateMergePreview, strategy: SaveConflictStrategy) {
        val primaryRoot = documentDir(context, Uri.parse(preview.primary.documentUri))
            ?: error("Le dossier du jeu principal est inaccessible.")
        preview.secondarySaves.forEach { save ->
            val source = documentFile(context, Uri.parse(save.documentUri))
                ?: error("Sauvegarde source inaccessible : ${save.relativePath}")
            require(fr.astragames.app.data.mods.ZipPathGuard.sanitize(save.relativePath) == save.relativePath) { "Chemin de sauvegarde invalide." }
            val parts = save.relativePath.split('/').filter(String::isNotBlank)
            if (parts.isEmpty()) return@forEach
            var targetFolder = primaryRoot
            parts.dropLast(1).forEach { part ->
                val child = listDirectoryEntries(context, targetFolder.uri).saveEntry(part)
                targetFolder = if (child == null) {
                    targetFolder.createDirectory(part)?.takeIf { it.name == part }
                        ?: error("Impossible de créer le dossier de sauvegarde $part.")
                } else {
                    documentDir(context, child.uri) ?: error("Le dossier de sauvegarde $part est inaccessible.")
                }
            }
            val originalName = parts.last()
            val entries = listDirectoryEntries(context, targetFolder.uri)
            val existing = entries.saveEntry(originalName)
            require(existing?.isDirectory != true) { "Un dossier occupe le nom $originalName." }
            if (existing != null && strategy == SaveConflictStrategy.KEEP_PRIMARY) return@forEach
            // Read the source fully before touching the destination. Providers can fail halfway.
            val replacement = readSaveDocument(source.uri)
            val replaced = existing?.takeIf { strategy == SaveConflictStrategy.REPLACE_WITH_SECONDARY }
            val original = replaced?.let { readSaveDocument(it.uri) }
            if (original?.contentEquals(replacement) == true) return@forEach
            val targetName = when {
                existing == null -> originalName
                replaced != null -> replaced.name
                existing.matchesSave(replacement) -> return@forEach
                else -> uniqueSaveName(entries, originalName, preview.secondary.title, replacement) ?: return@forEach
            }
            if (replaced != null && original != null) {
                val backupId = UUID.randomUUID().toString()
                val backup = File(fr.astragames.app.data.backup.SaveBackupPaths.directory(context, preview.primary.id), backupId)
                fr.astragames.app.data.backup.SaveBackupPaths.resolve(context, preview.primary.id, Uri.fromFile(backup).toString())
                check(backup.parentFile!!.isDirectory || backup.parentFile!!.mkdirs()) { "Création du dossier de backup impossible." }
                backup.writeBytes(original)
                check(backup.readBytes().contentEquals(original)) { "Sauvegarde de sécurité incomplète." }
                dao.insertSaveBackup(fr.astragames.app.data.local.SaveBackupEntity(
                    backupId, preview.primary.id, replaced.uri.toString(), originalName,
                    Uri.fromFile(backup).toString(), System.currentTimeMillis(), original.size.toLong()
                ))
            }
            val target = if (replaced != null) {
                documentFile(context, replaced.uri) ?: error("Sauvegarde principale inaccessible : ${replaced.name}")
            } else {
                targetFolder.createFile(source.type ?: "application/octet-stream", targetName)
                    ?: error("Impossible de créer $targetName dans le dossier principal.")
            }
            try {
                if (target.name != targetName) check(target.renameTo(targetName)) { "Le fournisseur a modifié le nom de sauvegarde." }
                writeSaveDocument(target, replacement)
                check(readSaveDocument(target.uri).contentEquals(replacement)) { "Copie de sauvegarde incomplète." }
            } catch (failure: Exception) {
                runCatching {
                    if (original != null) {
                        writeSaveDocument(target, original)
                        check(readSaveDocument(target.uri).contentEquals(original)) { "Restauration incomplète : utilisez le backup." }
                    } else check(target.delete()) { "Suppression de la copie incomplète impossible." }
                }.onFailure(failure::addSuppressed)
                throw failure
            }
        }
    }

    private fun readSaveDocument(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)
        ?.use { it.readBounded(64L * 1024 * 1024) } ?: error("Sauvegarde illisible : $uri")

    private fun List<DirectoryEntry>.saveEntry(name: String): DirectoryEntry? {
        val matches = filter { it.name.equals(name, ignoreCase = true) }
        require(matches.size <= 1) { "Plusieurs fichiers ou dossiers correspondent à $name. Vérifiez leurs noms avant de fusionner." }
        return matches.singleOrNull()
    }

    private fun DirectoryEntry.matchesSave(bytes: ByteArray): Boolean =
        !isDirectory && (size <= 0L || size == bytes.size.toLong()) && readSaveDocument(uri).contentEquals(bytes)

    private fun writeSaveDocument(file: DocumentFile, bytes: ByteArray) {
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
            ?: error("Sauvegarde non modifiable : ${file.name}")
    }

    private fun uniqueSaveName(entries: List<DirectoryEntry>, original: String, gameTitle: String, bytes: ByteArray): String? {
        val extension = original.substringAfterLast('.', "").takeIf(String::isNotBlank)
        val base = if (extension == null) original else original.removeSuffix(".$extension")
        val suffix = gameTitle.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(24).ifBlank { "doublon" }
        var candidate = "$base-astra-$suffix${extension?.let { ".$it" }.orEmpty()}"
        var index = 2
        while (true) {
            val existing = entries.saveEntry(candidate) ?: return candidate
            if (existing.matchesSave(bytes)) return null
            candidate = "$base-astra-$suffix-$index${extension?.let { ".$it" }.orEmpty()}"
            index++
        }
    }

    suspend fun toggleGameTag(gameId: String, tagId: String, selected: Boolean) {
        if (selected) dao.addTagsToGames(setOf(gameId), setOf(tagId))
        else dao.removeGameTag(gameId, tagId)
    }

    suspend fun setGameTags(gameId: String, tagIds: Set<String>, previous: Set<String>? = null) = database.withTransaction {
        if (previous == null) {
            dao.replaceGameTags(gameId, tagIds)
        } else {
            dao.addTagsToGames(setOf(gameId), tagIds - previous)
            (previous - tagIds).chunked(900).forEach { dao.removeGameTags(gameId, it) }
        }
    }

    private suspend fun currentCategoryName(categoryId: String?): String? = categoryId?.let {
        checkNotNull(dao.getTagCategory(it)) { "Catégorie introuvable." }.name
    }

    suspend fun createTag(name: String, categoryId: String?): Boolean = database.withTransaction {
        val clean = formatTagName(name)
        if (clean.isBlank()) return@withTransaction false
        val normalized = normalize(clean)
        val key = tagKey(normalized)
        if (dao.getTags().any { tagKey(normalize(it.name)) == key }) return@withTransaction false
        dao.insertTags(listOf(TagEntity(UUID.randomUUID().toString(), clean, normalized, currentCategoryName(categoryId)))).single() != -1L
    }

    suspend fun editTag(tag: TagEntity, name: String, categoryId: String?, categoryChanged: Boolean = true) = database.withTransaction {
        val clean = formatTagName(name)
        require(clean.isNotBlank()) { "Saisissez un nom pour le tag." }
        val tags = dao.getTags()
        val current = checkNotNull(tags.find { it.id == tag.id }) { "Tag introuvable." }
        val normalized = normalize(clean)
        require(tags.none { it.id != tag.id && it.normalizedName == normalized }) { "Un tag porte déjà ce nom." }
        val group = if (categoryChanged) currentCategoryName(categoryId) else current.groupName
        dao.upsertTag(current.copy(name = clean, normalizedName = normalized, groupName = group))
    }

    suspend fun deleteTags(tagIds: Set<String>): Int = database.withTransaction {
        if (tagIds.isEmpty()) return@withTransaction 0
        val selected = dao.getTags().filter { it.id in tagIds }
        if (selected.isEmpty()) return@withTransaction 0
        check(selected.size == tagIds.size) { "Un tag sélectionné a été supprimé. Vérifiez votre sélection." }
        dao.deleteTags(tagIds.toList())
        recordAudit("TAG_DELETED", "Tag(s) supprimé(s) : ${selected.joinToString(", ") { it.name }}")
        selected.size
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
        dao.mergeTags(keepTagId, removedTagId)
        recordAudit("TAG_MERGE", "Fusion de tags : « $removedName » → « $keepName »")
    }

    suspend fun moveTagsToCategory(tagIds: Set<String>, categoryId: String?) = database.withTransaction {
        if (tagIds.isEmpty()) return@withTransaction
        val name = currentCategoryName(categoryId)
        tagIds.chunked(900).forEach { batch ->
            check(dao.moveTagsToCategory(batch, name) == batch.size) { "Un tag sélectionné a été supprimé. Vérifiez votre sélection." }
        }
    }

    suspend fun createTagCategory(name: String): Unit = database.withTransaction {
        val clean = name.trim()
        if (clean.isNotBlank()) findOrCreateCategory(clean)
    }

    private suspend fun findOrCreateCategory(name: String): TagCategoryEntity {
        val normalized = normalize(name)
        val categories = dao.getTagCategories()
        categories.firstOrNull { it.matchesNormalizedName(normalized) }?.let { return it }
        val nextOrder = (categories.maxOfOrNull { it.sortOrder } ?: -1) + 1
        return TagCategoryEntity(UUID.randomUUID().toString(), name, normalized, nextOrder).also { dao.upsertTagCategory(it) }
    }

    private fun TagCategoryEntity.matchesNormalizedName(value: String) = normalizedName == value || normalize(name) == value

    suspend fun renameTagCategory(category: TagCategoryEntity, name: String) = database.withTransaction {
        val clean = name.trim()
        require(clean.isNotBlank()) { "Saisissez un nom pour la catégorie." }
        val categories = dao.getTagCategories()
        val current = checkNotNull(categories.find { it.id == category.id }) { "Catégorie introuvable." }
        val normalized = normalize(clean)
        require(categories.none { it.id != category.id && it.matchesNormalizedName(normalized) }) { "Une catégorie porte déjà ce nom." }
        dao.renameTagCategoryReferences(current.name, clean)
        dao.upsertTagCategory(current.copy(name = clean, normalizedName = normalized))
    }

    suspend fun deleteTagCategory(category: TagCategoryEntity) = database.withTransaction {
        val current = dao.getTagCategory(category.id) ?: return@withTransaction
        dao.clearTagCategoryReferences(current.name)
        dao.deleteTagCategoryRaw(current.id)
        recordAudit("CATEGORY_DELETED", "Catégorie supprimée : ${current.name}")
    }

    suspend fun moveTagCategory(categoryId: String, direction: Int) = database.withTransaction {
        val categories = dao.getTagCategories()
        val index = categories.indexOfFirst { it.id == categoryId }
        check(index >= 0) { "Catégorie introuvable." }
        val otherIndex = (index.toLong() + direction).coerceIn(0L, categories.lastIndex.toLong()).toInt()
        if (index == otherIndex) return@withTransaction
        val reordered = categories.toMutableList()
        java.util.Collections.swap(reordered, index, otherIndex)
        val tied = categories.asSequence().zipWithNext().any { (first, second) -> first.sortOrder == second.sortOrder }
        reordered.forEachIndexed { position, category ->
            val order = if (tied) position else categories[position].sortOrder
            if (category.sortOrder != order) dao.setTagCategoryOrder(category.id, order)
        }
    }

    suspend fun updateGame(
        gameId: String, edits: GameEdits, textTags: String = "", selectedTags: Set<String>? = null, previousTags: Set<String>? = null
    ): Unit = database.withTransaction {
        val title = edits.title.trim()
        require(title.isNotBlank()) { "Saisissez un titre pour le jeu." }
        val ryuu = edits.ryuugamesUrl?.trim()?.takeIf(String::isNotBlank)?.let { requireNotNull(fr.astragames.app.core.metadata.canonicalRyuugamesUrl(it)) { "Lien Ryuugames invalide." } }
        dao.updateGameFields(
            gameId, title, edits.originalTitle.cleanOrNull(), edits.developer.cleanOrNull(),
            edits.version.cleanOrNull(), edits.productCode.cleanOrNull(), edits.language.cleanOrNull(),
            edits.description.cleanOrNull(), edits.f95Url.cleanF95UrlOrNull(), ryuu
        )
        if (selectedTags != null) setGameTags(gameId, selectedTags, previousTags)
        if (textTags.isNotBlank()) importTextTags(gameId, textTags)
    }

    suspend fun setRyuugamesUrl(gameId: String, url: String?) = dao.setRyuugamesUrl(gameId,
        url?.trim()?.takeIf(String::isNotBlank)?.let { requireNotNull(fr.astragames.app.core.metadata.canonicalRyuugamesUrl(it)) { "Lien Ryuugames invalide." } })

    suspend fun setF95Url(gameId: String, url: String?) = dao.setF95Url(gameId, url.cleanF95UrlOrNull())

    suspend fun applyAutomaticMetadata(
        gameId: String,
        originalTitle: String?,
        developer: String?,
        description: String?,
        f95Url: String?,
        version: String? = null,
        language: String? = null
    ): Boolean = dao.mergeMetadata(gameId, MetadataValues(
        originalTitle = originalTitle.cleanOrNull(), developer = developer.cleanOrNull(),
        version = version.cleanOrNull(), language = language.cleanOrNull(),
        description = description.cleanOrNull(), f95Url = f95Url.cleanF95UrlOrNull()
    ))

    suspend fun importMetadata(
        gameId: String,
        metadata: F95ZoneMetadata,
        source: MetadataSource,
        selection: MetadataImportSelection
    ): Unit = database.withTransaction {
        check(dao.gameExists(gameId)) { "Jeu introuvable." }
        fun selected(field: MetadataField) = field.incoming(metadata).takeIf { field in selection.fields }
        val values = MetadataValues(
            originalTitle = selected(MetadataField.ORIGINAL_TITLE),
            developer = selected(MetadataField.DEVELOPER),
            version = selected(MetadataField.VERSION),
            language = selected(MetadataField.LANGUAGE),
            description = selected(MetadataField.DESCRIPTION),
            f95Url = metadata.sourceUrl.takeIf { selection.includeSourceLink && source == MetadataSource.F95ZONE },
            ryuugamesUrl = metadata.sourceUrl.takeIf { selection.includeSourceLink && source == MetadataSource.RYUUGAMES }
        )
        dao.mergeMetadata(gameId, values, replaceExisting = true)
        associateTagNames(gameId, selection.tags, source.label)
    }

    suspend fun importF95Tags(gameId: String, tagNames: Collection<String>): Int {
        val categoryName = "F95Zone"
        return associateTagNames(gameId, tagNames, categoryName)
    }

    suspend fun importSourceTags(gameId: String, tagNames: Collection<String>, source: String): Int = associateTagNames(gameId, tagNames, source)

    suspend fun importTextTags(gameId: String, raw: String): Int =
        associateTagNames(gameId, parseTextTagList(raw), categoryName = null)

    private suspend fun associateTagNames(gameId: String, tagNames: Collection<String>, categoryName: String?): Int {
        val selectedNames = tagNames.map(String::trim).filter(String::isNotBlank)
        if (selectedNames.isEmpty()) return 0
        return database.withTransaction {
            check(dao.gameExists(gameId)) { "Jeu introuvable." }
            val category = categoryName?.let { findOrCreateCategory(it).name }
            val knownIds = mutableMapOf<String, String>()
            dao.getTags().forEach { tag ->
                knownIds.putIfAbsent(tagKey(normalize(tag.name)), tag.id)
                knownIds.putIfAbsent(tagKey(tag.normalizedName), tag.id)
            }
            val newTags = mutableListOf<TagEntity>()
            val ids = selectedNames.mapTo(mutableSetOf()) { name ->
                val normalized = normalize(name)
                knownIds.getOrPut(tagKey(normalized)) {
                    val tag = TagEntity(UUID.randomUUID().toString(), name, normalized, category)
                    newTags += tag
                    tag.id
                }
            }
            if (newTags.isNotEmpty()) check(dao.insertTags(newTags).none { it == -1L }) { "Impossible de créer les tags sélectionnés." }
            dao.insertGameTags(ids.map { GameTagCrossRef(gameId, it) })
            ids.size
        }
    }

    suspend fun createFolder(name: String, parentId: String? = null, draftId: String? = null) = database.withTransaction {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Donnez un nom au dossier." }
        if (draftId != null && dao.getFolder(draftId) != null) return@withTransaction
        check(parentId == null || dao.getFolder(parentId) != null) { "Le dossier parent a été supprimé." }
        dao.upsertFolder(LibraryFolderEntity(draftId ?: UUID.randomUUID().toString(), clean, parentId))
    }

    suspend fun renameFolder(folder: LibraryFolderEntity, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Donnez un nom au dossier." }
        check(dao.renameFolder(folder.id, clean) == 1) { "Ce dossier a été supprimé." }
    }

    suspend fun deleteFolder(folder: LibraryFolderEntity) = database.withTransaction {
        val current = dao.getFolder(folder.id) ?: return@withTransaction
        dao.moveChildFolders(folder.id, survivingParentOf(current))
        dao.clearGamesFromFolder(folder.id)
        dao.deleteFolder(folder.id)
    }

    private suspend fun survivingParentOf(folder: LibraryFolderEntity): String? {
        val visited = mutableSetOf(folder.id)
        var ancestorId = folder.parentId
        while (ancestorId != null) {
            if (!visited.add(ancestorId)) return null
            val ancestor = dao.getFolder(ancestorId) ?: return null
            ancestorId = ancestor.parentId
        }
        return folder.parentId
    }

    suspend fun importTags(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)
            ?.use { it.readBounded(4L * 1024 * 1024).toString(Charsets.UTF_8) }
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

        database.withTransaction {
            val categories = dao.getTagCategories().associateBy { normalize(it.name) }.toMutableMap()
            var nextOrder = (categories.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
            imported.mapNotNull { it.groupName }.forEach { groupName ->
                val normalized = normalize(groupName)
                categories.getOrPut(normalized) {
                    TagCategoryEntity(UUID.randomUUID().toString(), groupName, normalized, nextOrder++).also { dao.upsertTagCategory(it) }
                }
            }
            val known = dao.getTags().mapTo(mutableSetOf()) { it.normalizedName }
            val newTags = imported.mapNotNull { tag ->
                val normalized = normalize(tag.name)
                if (!known.add(normalized)) return@mapNotNull null
                TagEntity(UUID.randomUUID().toString(), tag.name, normalized, tag.groupName?.let { categories[normalize(it)]?.name })
            }
            dao.insertTags(newTags).count { it != -1L }
        }
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
        .replace(COMBINING_MARKS, "").lowercase(Locale.ROOT)

    /** Nettoie et formate un nom de tag : première lettre de chaque mot en majuscule. */
    private fun formatTagName(raw: String): String =
        raw.trim().replace(Regex("[\\s_]+"), " ").split(' ').filter(String::isNotBlank)
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }

    /** Fusionne les doublons proches : « 3D Games » et « 3D Game » sont équivalents. */
    private fun tagKey(normalized: String): String = normalized.split(' ').joinToString(" ") { word ->
        if (word.length > 3 && word.endsWith("s")) word.dropLast(1) else word
    }

    private fun String?.cleanOrNull() = this?.trim()?.ifBlank { null }

    private fun String?.cleanF95UrlOrNull(): String? {
        val clean = cleanOrNull() ?: return null
        return requireNotNull(canonicalF95ThreadUrl(clean)) { "Lien F95Zone invalide. Utilisez le lien de la fiche du jeu." }
    }

    companion object {
        private val COMBINING_MARKS = Regex("\\p{Mn}+")
        private val SAVE_FOLDER_NAMES = setOf("save", "saves", "savedata", "savegames", "persistent")
        private val SAVE_EXTENSIONS = setOf("rpgsave", "rmmzsave", "save", "sav")
        private val RGSS_EXTENSIONS = setOf("rxdata", "rvdata", "rvdata2")
        private val RGSS_PROJECT_TABLES = setOf(
            "actors", "animations", "areas", "armors", "classes", "commonevents", "enemies", "items",
            "mapinfos", "scripts", "skills", "states", "system", "tilesets", "troops", "weapons"
        )
        private val RGSS_MAP = Regex("map[0-9]+")
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
    val f95Url: String?,
    val ryuugamesUrl: String? = null
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
