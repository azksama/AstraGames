package fr.astragames.app.data.scanner

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.core.filesystem.FileAccessResolver
import fr.astragames.app.core.model.GameLauncherType
import fr.astragames.app.core.model.DetectionResult
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.core.model.ScanReport
import fr.astragames.app.core.model.ScanReportItem
import fr.astragames.app.core.model.ScanReportItemStatus
import fr.astragames.app.core.model.ScanStatus
import fr.astragames.app.data.local.AstraDao
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.local.ScanHistoryEntity
import fr.astragames.app.data.local.ScanReportItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import java.util.Locale
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException

data class ScanProgressUpdate(
    val sourceId: String,
    val sourceName: String,
    val phase: String,
    val currentPath: String = "",
    val depth: Int = 0,
    val visitedFolders: Int = 0,
    val foundGames: Int = 0
)

class RecursiveSourceScanner(
    private val context: Context,
    private val dao: AstraDao,
    private val fileAccessResolver: FileAccessResolver
) {
    private val scanLock = Mutex()

    suspend fun scan(
        sourceId: String,
        onProgress: (ScanProgressUpdate) -> Unit = {}
    ): ScanReport = scanLock.withLock { withContext(Dispatchers.IO) {
        val source = dao.getSource(sourceId) ?: return@withContext ScanReport(
            UUID.randomUUID().toString(), sourceId, "Source introuvable", 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, listOf("Source introuvable")
        )
        if (!source.enabled) return@withContext ScanReport(
            UUID.randomUUID().toString(), sourceId, source.displayName, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, emptyList()
        )
        fun progress(phase: String, path: String = "", depth: Int = 0, visitedFolders: Int = 0, foundGames: Int = 0) {
            onProgress(ScanProgressUpdate(source.id, source.displayName, phase, path, depth, visitedFolders, foundGames))
        }
        progress("Préparation du dossier")
        val startedAt = System.currentTimeMillis()
        val historyId = UUID.randomUUID().toString()
        val errors = mutableListOf<String>()
        val reportItems = mutableListOf<ScanReportItem>()
        var found = 0
        var added = 0
        var updated = 0
        var unchanged = 0
        var moved = 0
        var ignored = 0
        val visited = mutableSetOf<String>()
        val foundIds = mutableSetOf<String>()
        try {
        dao.insertScanHistory(ScanHistoryEntity(historyId, sourceId, startedAt))
        dao.upsertSource(source.copy(lastScanStatus = ScanStatus.RUNNING.name, lastError = null))
        val knownGamesByUri = dao.getGamesForSource(sourceId).associateBy { it.documentUri }
        val exclusions = (dao.getExclusions(sourceId).mapNotNull { it.folderNamePattern } + DEFAULT_EXCLUSIONS)
            .map { it.lowercase(Locale.ROOT) }.toSet()
        val root = fr.astragames.app.data.saves.documentDir(context, source.treeUri.toUri())
        if (root == null || !root.exists() || !root.canRead()) {
            val message = "Permission de stockage absente ou expirée"
            val finishedAt = System.currentTimeMillis()
            val item = ScanReportItem(source.displayName, null, ScanReportItemStatus.ERROR, message)
            dao.upsertSource(source.copy(lastScanAt = finishedAt, lastScanStatus = ScanStatus.FAILED.name, lastError = message))
            dao.insertScanHistory(ScanHistoryEntity(id = historyId, sourceId = sourceId, startedAt = startedAt, finishedAt = finishedAt, errors = 1))
            dao.insertScanReportItems(listOf(item.toEntity(historyId, sourceId)))
            return@withContext ScanReport(
                historyId, sourceId, source.displayName, startedAt, finishedAt, 0,
                0, 0, 0, 0, 0, 0, 0, listOf(message), listOf(item)
            )
        }
        dao.getDeletedGamesForSource(sourceId).forEach { deleted ->
            val stillExists = runCatching {
                DocumentFile.fromSingleUri(context, deleted.documentUri.toUri())?.exists() == true
            }.getOrDefault(false)
            if (!stillExists) dao.restoreDeletedGame(deleted.id)
        }

        suspend fun walk(directory: DocumentFile, relativePath: String, depth: Int) {
            coroutineContext.ensureActive()
            require(depth <= 128 && visited.size < 100_000) { "Source trop profonde ou trop volumineuse : limitez le dossier à analyser." }
            if (!visited.add(directory.uri.toString())) return
            if (source.maxDepth != null && depth > source.maxDepth) return
            val deletedGame = dao.findDeletedGameByDocumentUri(directory.uri.toString())
            if (deletedGame != null) {
                ignored++
                reportItems += ScanReportItem(
                    relativePath.ifBlank { source.displayName }, deletedGame.title,
                    ScanReportItemStatus.IGNORED, "Jeu supprimé de la bibliothèque par l’utilisateur"
                )
                progress("Jeu supprimé ignoré", relativePath.ifBlank { source.displayName }, depth, visited.size, found)
                return
            }
            val knownGame = knownGamesByUri[directory.uri.toString()]
            if (knownGame != null && knownGame.engine != GameEngine.UNKNOWN.name) {
                found++
                unchanged++
                foundIds += knownGame.id
                if (knownGame.missing) dao.markGameFound(knownGame.id)
                reportItems += ScanReportItem(
                    relativePath.ifBlank { source.displayName }, knownGame.title,
                    ScanReportItemStatus.UNCHANGED, "Jeu déjà connu, analyse interne non répétée", knownGame.id
                )
                progress(
                    "Jeu déjà connu — analyse ignorée",
                    relativePath.ifBlank { source.displayName }, depth, visited.size, found
                )
                return
            }
            progress(
                phase = if (depth == 0) "Analyse du dossier principal" else "Analyse des sous-dossiers",
                path = relativePath.ifBlank { source.displayName },
                depth = depth,
                visitedFolders = visited.size,
                foundGames = found
            )
            val children = runCatching {
                check(directory.canRead()) { "Accès au dossier refusé" }
                directory.listFiles().toList()
            }.getOrElse {
                val message = "Lecture impossible: ${directory.name ?: relativePath}"
                errors += message
                reportItems += ScanReportItem(
                    relativePath.ifBlank { source.displayName }, null, ScanReportItemStatus.ERROR, message
                )
                return
            }
            val signatureNames = buildSet<String> {
                var decisiveSignatureFound = false
                fun addDescendants(folder: DocumentFile, prefix: String, remainingDepth: Int) {
                    if (remainingDepth <= 0 || decisiveSignatureFound || size >= MAX_SIGNATURE_ENTRIES) return
                    runCatching {
                        folder.listFiles().sortedBy { nested ->
                            if (nested.name.orEmpty().lowercase(Locale.ROOT) in SIGNATURE_PRIORITY_NAMES) 0 else 1
                        }.forEach { nested ->
                            if (decisiveSignatureFound || size >= MAX_SIGNATURE_ENTRIES) return@forEach
                            val nestedPath = "$prefix/${nested.name.orEmpty()}"
                            add(nestedPath)
                            decisiveSignatureFound = nestedPath.isDecisiveSignature()
                            if (nested.isDirectory) addDescendants(nested, nestedPath, remainingDepth - 1)
                        }
                    }
                }
                children.forEach { child ->
                    val name = child.name.orEmpty()
                    add(name)
                    if (source.recursive && child.isDirectory && name.lowercase(Locale.ROOT) in setOf("www", "js", "game", "renpy", "tyrano", "data")) {
                        addDescendants(child, name, if (name.equals("game", true)) 4 else 3)
                    }
                }
            }
            val executable = children.asSequence()
                .filter { !it.isDirectory && it.name.orEmpty().endsWith(".exe", ignoreCase = true) }
                .sortedBy { executableFile ->
                    val name = executableFile.name.orEmpty().lowercase(Locale.ROOT)
                    when {
                        name == "game.exe" -> 0
                        IGNORED_EXECUTABLE_MARKERS.any(name::contains) -> 2
                        else -> 1
                    }
                }
                .firstOrNull()
            val detection = EngineSignatureDetector.detect(signatureNames) ?: executable?.let {
                DetectionResult(GameEngine.UNKNOWN, .25f, listOf("Exécutable détecté"), it.name)
            }
            if (detection != null) {
                found++
                val rawName = directory.name ?: "Jeu sans titre"
                val normalized = GameTitleNormalizer.normalize(rawName)
                val executableName = executable?.name ?: detection.executableName
                val fingerprint = GameFingerprint.create(detection.engine, normalized.title, normalized.productCode, executableName)
                if (dao.findDeletedGameByFingerprint(fingerprint) != null) {
                    ignored++
                    reportItems += ScanReportItem(
                        relativePath.ifBlank { source.displayName }, normalized.title,
                        ScanReportItemStatus.IGNORED, "Jeu supprimé reconnu par son empreinte"
                    )
                    progress("Jeu supprimé ignoré", relativePath.ifBlank { source.displayName }, depth, visited.size, found)
                    return
                }
                val existing = dao.findGameByDocumentUri(directory.uri.toString())
                    ?: dao.findGameByFingerprint(fingerprint)?.takeIf { previous ->
                        // An accessible copy is a duplicate, not a move of the existing record.
                        runCatching { fr.astragames.app.data.saves.documentDir(context, previous.documentUri.toUri())?.exists() != true }.getOrDefault(false)
                    }
                val now = System.currentTimeMillis()
                val cover = children.firstOrNull {
                    !it.isDirectory && it.name.orEmpty().lowercase(Locale.ROOT) in COVER_NAMES
                }?.uri?.toString()
                val physicalPath = fileAccessResolver.physicalPath(source.treeUri.toUri(), relativePath)
                val itemStatus = when {
                    existing == null -> ScanReportItemStatus.ADDED.also { added++ }
                    existing.documentUri != directory.uri.toString() -> ScanReportItemStatus.MOVED.also { moved++ }
                    else -> ScanReportItemStatus.UPDATED.also { updated++ }
                }
                val game = GameEntity(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    title = existing?.title ?: normalized.title,
                    originalTitle = existing?.originalTitle,
                    aliases = existing?.aliases.orEmpty(),
                    documentUri = directory.uri.toString(),
                    physicalPath = physicalPath,
                    executableName = executableName,
                    engine = detection.engine.name,
                    launcher = if (detection.engine.name == "UNKNOWN") GameLauncherType.NONE.name else GameLauncherType.JOIPLAY.name,
                    sourceId = sourceId,
                    coverUri = existing?.coverUri ?: cover,
                    bannerUri = existing?.bannerUri,
                    iconUri = existing?.iconUri,
                    description = existing?.description,
                    developer = existing?.developer ?: normalized.developer,
                    version = existing?.version ?: normalized.version,
                    productCode = existing?.productCode ?: normalized.productCode,
                    language = existing?.language,
                    f95Url = existing?.f95Url,
                    releaseDate = existing?.releaseDate,
                    dateAdded = existing?.dateAdded ?: now,
                    lastModified = directory.lastModified(),
                    lastPlayedAt = existing?.lastPlayedAt,
                    playCount = existing?.playCount ?: 0,
                    favorite = existing?.favorite ?: false,
                    hidden = existing?.hidden ?: false,
                    missing = false,
                    autoDetected = true,
                    libraryFolderId = existing?.libraryFolderId,
                    keywords = existing?.keywords.orEmpty(),
                    fingerprint = fingerprint
                )
                dao.upsertGame(game)
                foundIds += game.id
                reportItems += ScanReportItem(
                    relativePath.ifBlank { source.displayName }, game.title, itemStatus,
                    when (itemStatus) {
                        ScanReportItemStatus.ADDED -> "Nouveau jeu ${detection.engine.name.replace('_', ' ')} détecté"
                        ScanReportItemStatus.MOVED -> "Jeu connu retrouvé à ce nouvel emplacement"
                        else -> "Détection actualisée : ${detection.engine.name.replace('_', ' ')}"
                    },
                    game.id
                )
                progress("Jeu détecté : ${game.title}", relativePath.ifBlank { source.displayName }, depth, visited.size, found)
                return
            }

            if (source.recursive) {
                children.asSequence()
                    .filter { it.isDirectory }
                    .filter { it.name != ".astra-translation" && (source.includeHiddenFolders || !it.name.orEmpty().startsWith('.')) }
                    .forEach { child ->
                        val childRelative = listOf(relativePath, child.name.orEmpty()).filter { it.isNotBlank() }.joinToString("/")
                        if (child.name.orEmpty().lowercase(Locale.ROOT) in exclusions) {
                            ignored++
                            reportItems += ScanReportItem(
                                childRelative, null, ScanReportItemStatus.IGNORED,
                                "Dossier technique exclu du scan"
                            )
                        } else walk(child, childRelative, depth + 1)
                    }
            }
        }

        try {
            walk(root, "", 0)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errors += (error.message ?: "Erreur de scan")
        }
        val finishedAt = System.currentTimeMillis()
        val status = if (errors.isEmpty()) ScanStatus.SUCCESS else if (found > 0) ScanStatus.PARTIAL else ScanStatus.FAILED
        if (status == ScanStatus.SUCCESS) {
            progress("Vérification des jeux déplacés ou supprimés", visitedFolders = visited.size, foundGames = found)
            dao.reconcileSourceGames(sourceId, foundIds.toList())
        }
        val missing = dao.countMissing(sourceId)
        if (missing > 0) {
            dao.getGamesForSource(sourceId).filter { it.missing }.forEach { game ->
                reportItems += ScanReportItem(
                    game.physicalPath ?: game.documentUri, game.title, ScanReportItemStatus.MISSING,
                    "Jeu absent de son emplacement lors de ce scan", game.id
                )
            }
        }
        dao.upsertSource(
            source.copy(
                lastScanAt = finishedAt,
                lastScanStatus = status.name,
                gamesCount = found,
                lastError = errors.firstOrNull()
            )
        )
        dao.insertScanHistory(
            ScanHistoryEntity(
                id = historyId, sourceId = sourceId, startedAt = startedAt, finishedAt = finishedAt,
                gamesFound = found, gamesAdded = added, gamesUpdated = updated, gamesUnchanged = unchanged,
                gamesMoved = moved, gamesMissing = missing, visitedFolders = visited.size,
                ignoredFolders = ignored, errors = errors.size
            )
        )
        dao.insertScanReportItems(reportItems.map { it.toEntity(historyId, sourceId) })
        progress("Scan terminé", visitedFolders = visited.size, foundGames = found)
        ScanReport(
            historyId, sourceId, source.displayName, startedAt, finishedAt, visited.size,
            found, added, updated, unchanged, moved, missing, ignored, errors, reportItems
        )
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                val finishedAt = System.currentTimeMillis()
                dao.upsertSource(source.copy(lastScanAt = finishedAt, lastScanStatus = ScanStatus.PARTIAL.name, lastError = "Scan interrompu"))
                dao.insertScanHistory(ScanHistoryEntity(
                    id = historyId, sourceId = sourceId, startedAt = startedAt, finishedAt = finishedAt,
                    gamesFound = found, gamesAdded = added, gamesUpdated = updated, gamesUnchanged = unchanged,
                    gamesMoved = moved, visitedFolders = visited.size, ignoredFolders = ignored, errors = errors.size + 1
                ))
            }
            throw error
        }
    }

    }

    companion object {
        private const val MAX_SIGNATURE_ENTRIES = 2_048
        private val DEFAULT_EXCLUSIONS = setOf(
            "backup", "saves", "save", "cache", "temp", ".git", "node_modules",
            "audio", "graphics", "img", "movies", "fonts", "screenshots", "logs"
        )
        private val COVER_NAMES = setOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.png", "icon.png")
        private val IGNORED_EXECUTABLE_MARKERS = setOf(
            "unins", "uninstall", "setup", "crash", "update", "config", "notification", "helper"
        )
        private val SIGNATURE_PRIORITY_NAMES = setOf("js", "data", "game", "renpy", "tyrano")

        private fun String.isDecisiveSignature(): Boolean {
            val path = lowercase(Locale.ROOT)
            return path.endsWith("rmmz_core.js") || path.endsWith("rpg_core.js") ||
                (path.startsWith("game/") && (path.endsWith(".rpy") || path.endsWith(".rpyc"))) ||
                path.endsWith(".rvdata2") || path.endsWith(".rvdata") || path.endsWith(".rxdata") ||
                path.endsWith("tyrano/plugins/kag/kag.js") || path.endsWith("data/system/config.tjs")
        }
    }
}

private fun ScanReportItem.toEntity(scanId: String, sourceId: String) = ScanReportItemEntity(
    id = UUID.randomUUID().toString(), scanId = scanId, sourceId = sourceId, gameId = gameId,
    path = path, title = title, status = status.name, reason = reason
)
