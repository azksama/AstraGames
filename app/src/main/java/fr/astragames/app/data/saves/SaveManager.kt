package fr.astragames.app.data.saves

import android.content.Context
import android.net.Uri
import fr.astragames.app.data.local.AstraDao
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSaveLocationEntity
import fr.astragames.app.data.local.SaveBackupEntity
import fr.astragames.app.data.backup.SaveBackupPaths
import fr.astragames.app.launcher.LaunchProfileResolver
import fr.astragames.app.mkxpz.MkxpzRuntime
import fr.astragames.app.mkxpz.MkxpzSavePaths
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.sync.withLock
import java.io.File
import fr.astragames.app.windows.WolfGameStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/** Entries and revision come from one authorized read, so edits cannot use an unrelated revision. */
data class SaveReadSnapshot(val data: SaveData, val entries: List<SaveEntry>, val sha256: String, val provenance: SaveProvenance?)

class SaveManager(
    private val context: Context,
    private val dao: AstraDao,
    private val finder: SaveFinder
) {
    private val provenance = SaveProvenanceResolver(context)
    fun observeLocations(gameId: String) = dao.observeSaveLocations(gameId)
    fun observeBackups(gameId: String) = dao.observeSaveBackups(gameId)
    fun observeBackupsWithProvenance(gameId: String) = combine(
        dao.observeSaveBackups(gameId), dao.observeGame(gameId), dao.observeLaunchProfile(gameId)
    ) { backups, game, profile ->
        if (game == null) emptyList() else {
            val effective = LaunchProfileResolver.displayGame(game, profile)
                .copy(launcher = LaunchProfileResolver.launcherType(game, profile))
            backups.map { SaveBackupDetails(it, provenance.describe(effective, it.sourceUri)) }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun locations(gameId: String) = dao.getSaveLocations(gameId)

    // Reload both records at each operation boundary; callers may hold an old catalogue projection.
    private suspend fun effectiveGame(gameId: String): GameEntity {
        val stored = dao.getGame(gameId) ?: error("Jeu introuvable.")
        val profile = dao.getLaunchProfile(gameId)
        return LaunchProfileResolver.displayGame(stored, profile)
            .copy(launcher = LaunchProfileResolver.launcherType(stored, profile))
    }

    suspend fun detectLocations(game: GameEntity): List<GameSaveLocationEntity> =
        detectResolvedLocations(effectiveGame(game.id))

    private suspend fun detectResolvedLocations(game: GameEntity): List<GameSaveLocationEntity> {
        val detected = (finder.locations(game, Uri.parse(game.documentUri)) + withWolfStorage(game) { storage ->
            wolfSaves(storage).filter { isPrivate(it.file) }.map { it.file.canonicalFile.parentFile!! }.distinct().map { folder ->
                GameSaveLocationEntity(UUID.randomUUID().toString(), game.id, Uri.fromFile(folder).toString(),
                    "WOLF_PRIVATE", "Wolf · sauvegardes dans Astra", true, true, System.currentTimeMillis())
            }
        })
        val activePrivate = detected.filter { it.type == "WOLF_PRIVATE" }.map { it.uri }.toSet()
        dao.getSaveLocations(game.id).filter { it.type == "WOLF_PRIVATE" && it.uri !in activePrivate }
            .forEach { dao.deleteSaveLocation(it.id) }
        val existing = dao.getSaveLocations(game.id).associateBy { it.uri }
        detected.forEach { location ->
            val previous = existing[location.uri]
            if (previous == null) dao.upsertSaveLocation(location)
            else if (previous.autoDetected && previous.type != location.type) {
                dao.upsertSaveLocation(previous.copy(type = location.type))
            }
        }
        return dao.getSaveLocations(game.id)
    }

    suspend fun addLocation(game: GameEntity, uri: Uri, displayName: String): GameSaveLocationEntity {
        persistTree(uri)
        val folder = documentDir(context, uri) ?: error("Dossier inaccessible.")
        require(folder.canRead() && folder.canWrite()) { "Acces en lecture et ecriture requis." }
        dao.getSaveLocations(game.id).firstOrNull { it.uri == folder.uri.toString() }?.let { return it }
        val location = GameSaveLocationEntity(
            id = UUID.randomUUID().toString(),
            gameId = game.id,
            uri = folder.uri.toString(),
            type = "CUSTOM",
            displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
            autoDetected = false,
            enabled = true,
            addedAt = System.currentTimeMillis()
        )
        dao.upsertSaveLocation(location)
        return location
    }

    suspend fun removeLocation(id: String) = dao.deleteSaveLocation(id)

    suspend fun listSaves(game: GameEntity): List<GameSave> = listResolvedSaves(effectiveGame(game.id))

    private suspend fun listResolvedSaves(game: GameEntity): List<GameSave> {
        val locations = detectResolvedLocations(game)
        val private = withWolfStorage(game) { storage -> wolfSaves(storage).filter { isPrivate(it.file) }.map { saved ->
            GameSave(Uri.fromFile(saved.file.canonicalFile).toString(), saved.path.substringAfterLast('/'),
                saved.path.substringAfterLast('/').filter(Char::isDigit).take(3).toIntOrNull(), game.engine,
                saved.file.length(), saved.file.lastModified(), title = saved.path,
                auto = saved.path.substringAfterLast('/').startsWith("auto", ignoreCase = true))
        } }
        val privateUris = private.map { it.uri }.toSet()
        val external = finder.saves(game, locations.filter { it.type != "WOLF_PRIVATE" }).filter { save ->
            val uri = Uri.parse(save.uri)
            game.engine != "WOLF_RPG" || uri.scheme != "file" || !isPrivate(File(requireNotNull(uri.path))) || save.uri in privateUris
        }
        return (external + private).distinctBy { it.uri }
            .map { it.copy(provenance = provenance.describe(game, it.uri)) }
            .sortedWith(compareBy<GameSave>({ it.provenance?.activeForCurrentLauncher != true }, { it.slot ?: Int.MAX_VALUE }, { it.name }))
    }

    suspend fun readSave(game: GameEntity, save: GameSave): Pair<SaveData, List<SaveEntry>> =
        readSaveSnapshot(game, save).let { it.data to it.entries }

    suspend fun readSaveSnapshot(game: GameEntity, save: GameSave): SaveReadSnapshot {
        val effective = effectiveGame(game.id)
        return withWolfStorage(effective) { storage ->
            require(save.engine == effective.engine) { "Moteur de sauvegarde incoherent. Rouvrez la liste des sauvegardes." }
            requireSource(effective, save.uri, storage)
            val bytes = readBytes(context, save.uri)
            val data = SaveCodec.read(effective.engine, bytes)
            SaveReadSnapshot(data, SaveCodec.entries(data), sha256Hex(bytes), provenance.describe(effective, save.uri))
        }
    }

    private val writes = kotlinx.coroutines.sync.Mutex()

    suspend fun writeSave(game: GameEntity, save: GameSave, edits: List<SaveEdit>, expectedHash: String? = null): SaveBackupEntity = writes.withLock {
        val effective = effectiveGame(game.id)
        withWolfStorage(effective) { storage ->
            require(save.engine == effective.engine) { "Moteur de sauvegarde incoherent. Rouvrez la liste des sauvegardes." }
            requireSource(effective, save.uri, storage)
            val original = readBytes(context, save.uri)
            check(expectedHash == null || sha256Hex(original) == expectedHash) {
                "La sauvegarde a change depuis son ouverture. Fermez puis rouvrez l editeur."
            }
            val patched = SaveCodec.patch(effective.engine, original, edits)
            val backup = backup(effective, save, original)
            replaceVerified(save.uri, original, patched)
            publishWolfSave(storage, save.uri)
            // Retention cleanup must never turn a completed, verified write into an apparent failure.
            runCatching { rotateBackups(effective.id, save.uri) }
            backup
        }
    }

    private fun replaceVerified(uri: String, original: ByteArray, replacement: ByteArray) {
        // Patching and backup creation may take time while the game is still writing its slot.
        check(readBytes(context, uri).contentEquals(original)) { "La sauvegarde a change pendant la preparation. Rouvrez l editeur." }
        try {
            writeBytes(context, uri, replacement)
            check(readBytes(context, uri).contentEquals(replacement)) { "Verification de l ecriture echouee." }
        } catch (failure: Exception) {
            try {
                writeBytes(context, uri, original)
                check(readBytes(context, uri).contentEquals(original)) { "Restauration incomplete. Utilisez le backup." }
            } catch (restore: Exception) { failure.addSuppressed(restore) }
            throw failure
        }
    }

    private suspend fun backup(game: GameEntity, save: GameSave, original: ByteArray): SaveBackupEntity {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // IDs and display names can originate in an imported catalog; never use them as paths.
        val backupName = "save-" + stamp + "-" + UUID.randomUUID() + ".backup"
        val dir = SaveBackupPaths.directory(context, game.id)
        MkxpzSavePaths.requireNoSymlinks(context.filesDir, dir)
        check(dir.isDirectory || dir.mkdirs()) { "Dossier de backup inaccessible." }
        val file = File(dir, backupName)
        java.io.FileOutputStream(file).use { output -> output.write(original); output.fd.sync() }
        check(file.readBytes().contentEquals(original)) { "Verification du backup echouee." }
        val backupUri = Uri.fromFile(file).toString()
        val entity = SaveBackupEntity(
            id = UUID.randomUUID().toString(),
            gameId = game.id,
            sourceUri = save.uri,
            sourceName = save.name,
            backupUri = backupUri,
            createdAt = System.currentTimeMillis(),
            sizeBytes = original.size.toLong()
        )
        try { dao.insertSaveBackup(entity) } catch (error: Exception) { file.delete(); throw error }
        return entity
    }

    suspend fun restoreBackup(backup: SaveBackupEntity) = writes.withLock {
        requireStoredBackup(backup)
        val bytes = readBackupBytes(backup)
        val game = effectiveGame(backup.gameId)
        withWolfStorage(game) { storage ->
            requireSource(game, backup.sourceUri, storage)
            val original = readBytes(context, backup.sourceUri)
            backup(game, GameSave(backup.sourceUri, backup.sourceName, null, game.engine, original.size.toLong(), 0), original)
            replaceVerified(backup.sourceUri, original, bytes)
            publishWolfSave(storage, backup.sourceUri)
            runCatching { rotateBackups(game.id, backup.sourceUri) }
        }
    }

    suspend fun deleteBackup(backup: SaveBackupEntity) = writes.withLock {
        requireStoredBackup(backup)
        deleteBackupFile(backup)
        dao.deleteSaveBackup(backup.id)
    }

    private suspend fun requireStoredBackup(backup: SaveBackupEntity) {
        require(dao.getSaveBackupsForSource(backup.sourceUri).any { it == backup }) {
            "Ce backup a changé ou n’est plus disponible. Actualisez la liste."
        }
    }

    private fun readBackupBytes(backup: SaveBackupEntity): ByteArray =
        SaveBackupPaths.resolveStored(context, backup.gameId, backup.id, backup.backupUri).inputStream().use { it.readBounded(MAX_SAVE_BYTES.toLong()) }

    private fun deleteBackupFile(backup: SaveBackupEntity) {
        val file = SaveBackupPaths.resolveStored(context, backup.gameId, backup.id, backup.backupUri)
        check(!file.exists() || file.delete()) { "Suppression du backup impossible." }
    }

    private suspend fun requireSource(game: GameEntity, uri: String, storage: WolfGameStorage? = null) {
        val parsed = Uri.parse(uri)
        require(parsed.scheme == "content" || parsed.scheme == "file") { "Source de sauvegarde invalide." }
        if (parsed.scheme == "file") {
            val path = File(requireNotNull(parsed.path))
            val source = path.canonicalFile
            if (isPrivate(path) || path.absoluteFile.toPath().startsWith(File(context.applicationInfo.dataDir).absoluteFile.toPath())) {
                val mkxpzSave = MkxpzRuntime.rgssVersion(game.engine) != null &&
                    MkxpzSavePaths.relative(context, game.id, game.engine, path, directory = false) != null && path.isFile
                require(mkxpzSave || (game.engine == "WOLF_RPG" && wolfSaves(storage).any { it.file.canonicalFile == source })) {
                    "Ce fichier interne Astra ne fait pas partie des sauvegardes autorisées pour ce jeu."
                }
                return
            }
        }
        require(finder.saves(game, dao.getSaveLocations(game.id).filter { it.type != "WOLF_PRIVATE" }).any { it.uri == uri }) { "Sauvegarde absente des dossiers autorises du jeu. Actualisez la liste." }
    }

    private fun isPrivate(file: File) = file.canonicalFile.toPath().startsWith(File(context.applicationInfo.dataDir).canonicalFile.toPath())

    private suspend fun wolfSaves(storage: WolfGameStorage?): List<WolfGameStorage.SaveFile> =
        storage?.saveFiles().orEmpty().filter { it.path.endsWith(".sav", ignoreCase = true) && it.file.isFile }

    private suspend fun <T> withWolfStorage(game: GameEntity, block: suspend (WolfGameStorage?) -> T): T {
        val storage = if (game.engine == "WOLF_RPG") WolfGameStorage(context, game.id, game.documentUri) else null
        try { return block(storage) } finally { storage?.closeMount() }
    }

    private suspend fun publishWolfSave(storage: WolfGameStorage?, uriValue: String) {
        if (storage == null) return
        val uri = Uri.parse(uriValue)
        if (uri.scheme != "file" || !isPrivate(File(requireNotNull(uri.path)))) return
        try {
            storage.savedFileChanged(File(requireNotNull(uri.path)))
            check(storage.synchronize() == 0) { "Le dossier source a change ; la version modifiee reste dans Astra." }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            throw IllegalStateException("Modification verifiee et backup conserves dans Astra. La copie vers le dossier source a echoue : ${failure.message}", failure)
        }
    }

    private suspend fun rotateBackups(gameId: String, sourceUri: String) {
        val existing = dao.getSaveBackupsForSource(sourceUri).filter { it.gameId == gameId }
        existing.drop(5).forEach { stale ->
            deleteBackupFile(stale)
            dao.deleteSaveBackup(stale.id)
        }
    }

    private fun persistTree(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

}

internal fun readBytes(context: Context, uri: String): ByteArray {
    val parsed = Uri.parse(uri)
    return context.contentResolver.openInputStream(parsed)?.use { input ->
        input.readBounded(MAX_SAVE_BYTES.toLong())
    } ?: error("Lecture impossible.")
}

internal fun writeBytes(context: Context, uri: String, bytes: ByteArray) {
    context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use { it.write(bytes) }
        ?: error("Ecriture impossible.")
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
