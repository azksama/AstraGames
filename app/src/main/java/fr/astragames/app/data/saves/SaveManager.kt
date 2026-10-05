package fr.astragames.app.data.saves

import android.content.Context
import android.net.Uri
import fr.astragames.app.data.local.AstraDao
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSaveLocationEntity
import fr.astragames.app.data.local.SaveBackupEntity
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.sync.withLock
import java.io.File

class SaveManager(
    private val context: Context,
    private val dao: AstraDao,
    private val finder: SaveFinder
) {
    fun observeLocations(gameId: String) = dao.observeSaveLocations(gameId)
    fun observeBackups(gameId: String) = dao.observeSaveBackups(gameId)

    suspend fun locations(gameId: String) = dao.getSaveLocations(gameId)

    suspend fun detectLocations(game: GameEntity): List<GameSaveLocationEntity> {
        val root = documentDir(context, Uri.parse(game.documentUri)) ?: return emptyList()
        val detected = finder.locations(game, root.uri)
        val existing = dao.getSaveLocations(game.id).map { it.uri }.toSet()
        detected.filter { it.uri !in existing }.forEach { dao.upsertSaveLocation(it) }
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

    suspend fun listSaves(game: GameEntity): List<GameSave> {
        val locations = detectLocations(game).ifEmpty { dao.getSaveLocations(game.id) }
        return finder.saves(game, locations)
    }

    suspend fun readSave(game: GameEntity, save: GameSave): Pair<SaveData, List<SaveEntry>> {
        val data = SaveCodec.read(game.engine, readBytes(context, save.uri))
        return data to SaveCodec.entries(data)
    }

    private val writes = kotlinx.coroutines.sync.Mutex()

    suspend fun writeSave(game: GameEntity, save: GameSave, edits: List<SaveEdit>, expectedHash: String? = null): SaveBackupEntity = writes.withLock {
        requireSource(game, save.uri)
        require(save.engine == game.engine) { "Moteur de sauvegarde incoherent." }
        val original = readBytes(context, save.uri)
        check(expectedHash == null || sha256Hex(original) == expectedHash) {
            "La sauvegarde a change depuis son ouverture. Fermez puis rouvrez l editeur."
        }
        val patched = SaveCodec.patch(game.engine, original, edits)
        val backup = backup(game, save, original)
        replaceVerified(save.uri, original, patched)
        // Retention cleanup must never turn a completed, verified write into an apparent failure.
        runCatching { rotateBackups(save.uri) }
        backup
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

    suspend fun backup(game: GameEntity, save: GameSave, original: ByteArray = readBytes(context, save.uri)): SaveBackupEntity {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // IDs and display names can originate in an imported catalog; never use them as paths.
        val backupName = "save-" + stamp + "-" + UUID.randomUUID() + ".backup"
        val dir = File(context.filesDir, "save-backups/" + sha256Hex(game.id.toByteArray(Charsets.UTF_8)))
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
        val game = dao.getGame(backup.gameId) ?: error("Jeu introuvable.")
        requireSource(game, backup.sourceUri)
        val bytes = readBackupBytes(backup.backupUri)
        val original = readBytes(context, backup.sourceUri)
        backup(game, GameSave(backup.sourceUri, backup.sourceName, null, game.engine, original.size.toLong(), 0), original)
        replaceVerified(backup.sourceUri, original, bytes)
        runCatching { rotateBackups(backup.sourceUri) }
    }

    suspend fun deleteBackup(backup: SaveBackupEntity) = writes.withLock {
        deleteBackupFile(backup.backupUri)
        dao.deleteSaveBackup(backup.id)
    }

    private fun backupFile(uriValue: String): File {
        val uri = Uri.parse(uriValue)
        require(uri.scheme == "file" && uri.path != null) { "Emplacement de backup invalide." }
        val root = File(context.filesDir, "save-backups").canonicalFile
        val file = File(requireNotNull(uri.path)).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Backup hors du dossier autorise." }
        return file
    }

    private fun readBackupBytes(uriValue: String): ByteArray = backupFile(uriValue).inputStream().use { it.readBounded(MAX_SAVE_BYTES.toLong()) }

    private fun deleteBackupFile(uriValue: String) {
        val file = backupFile(uriValue)
        check(!file.exists() || file.delete()) { "Suppression du backup impossible." }
    }

    private suspend fun requireSource(game: GameEntity, uri: String) {
        val parsed = Uri.parse(uri)
        require(parsed.scheme == "content" || parsed.scheme == "file") { "Source de sauvegarde invalide." }
        if (parsed.scheme == "file") {
            val source = File(requireNotNull(parsed.path)).canonicalFile
            val privateRoot = File(context.applicationInfo.dataDir).canonicalFile
            require(!source.path.startsWith(privateRoot.path + File.separator)) { "Un fichier interne Astra ne peut pas etre une sauvegarde de jeu." }
        }
        require(finder.saves(game, dao.getSaveLocations(game.id)).any { it.uri == uri }) { "Sauvegarde absente des dossiers autorises du jeu. Actualisez la liste." }
    }

    private suspend fun rotateBackups(sourceUri: String) {
        val existing = dao.getSaveBackupsForSource(sourceUri)
        existing.drop(5).forEach { stale ->
            deleteBackupFile(stale.backupUri)
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
