package fr.astragames.app.data.saves

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
        val original = readBytes(context, save.uri)
        check(expectedHash == null || sha256Hex(original) == expectedHash) {
            "La sauvegarde a change depuis son ouverture. Fermez puis rouvrez l editeur."
        }
        val patched = SaveCodec.patch(game.engine, original, edits)
        val backup = backup(game, save, original)
        replaceVerified(save.uri, original, patched)
        backup
    }

    private fun replaceVerified(uri: String, original: ByteArray, replacement: ByteArray) {
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
        val backupName = save.name + ".astra-backup-" + stamp + "-" + UUID.randomUUID()
        val dir = java.io.File(context.filesDir, "save-backups/" + game.id)
        check(dir.isDirectory || dir.mkdirs()) { "Dossier de backup inaccessible." }
        val file = java.io.File(dir, backupName)
        file.writeBytes(original)
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
        dao.insertSaveBackup(entity)
        rotateBackups(save.uri)
        return entity
    }

    suspend fun restoreBackup(backup: SaveBackupEntity) = writes.withLock {
        val bytes = readBackupBytes(backup.backupUri)
        val original = readBytes(context, backup.sourceUri)
        val game = dao.getGame(backup.gameId) ?: error("Jeu introuvable.")
        backup(game, GameSave(backup.sourceUri, backup.sourceName, null, game.engine, original.size.toLong(), 0), original)
        replaceVerified(backup.sourceUri, original, bytes)
    }

    suspend fun deleteBackup(backup: SaveBackupEntity) {
        val uri = Uri.parse(backup.backupUri)
        if (uri.scheme == "file") uri.path?.let { java.io.File(it).delete() }
        else DocumentFile.fromSingleUri(context, uri)?.delete()
        dao.deleteSaveBackup(backup.id)
    }

    private fun readBackupBytes(uriValue: String): ByteArray = readBytes(context, uriValue)

    private suspend fun rotateBackups(sourceUri: String) {
        val existing = dao.getSaveBackupsForSource(sourceUri)
        existing.drop(5).forEach { stale ->
            val uri = Uri.parse(stale.backupUri)
            if (uri.scheme == "file") uri.path?.let { java.io.File(it).delete() }
            else DocumentFile.fromSingleUri(context, uri)?.delete()
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
