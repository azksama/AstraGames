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
import fr.astragames.app.windows.WolfGameStorage

class SaveManager(
    private val context: Context,
    private val dao: AstraDao,
    private val finder: SaveFinder
) {
    fun observeLocations(gameId: String) = dao.observeSaveLocations(gameId)
    fun observeBackups(gameId: String) = dao.observeSaveBackups(gameId)

    suspend fun locations(gameId: String) = dao.getSaveLocations(gameId)

    suspend fun detectLocations(game: GameEntity): List<GameSaveLocationEntity> {
        val root = documentDir(context, Uri.parse(game.documentUri))
        val detected = (root?.let { finder.locations(game, it.uri) }.orEmpty() + withWolfStorage(game) { storage ->
            wolfSaves(storage).filter { isPrivate(it.file) }.map { it.file.canonicalFile.parentFile!! }.distinct().map { folder ->
                GameSaveLocationEntity(UUID.randomUUID().toString(), game.id, Uri.fromFile(folder).toString(),
                    "WOLF_PRIVATE", "Wolf · sauvegardes dans Astra", true, true, System.currentTimeMillis())
            }
        })
        val activePrivate = detected.filter { it.type == "WOLF_PRIVATE" }.map { it.uri }.toSet()
        dao.getSaveLocations(game.id).filter { it.type == "WOLF_PRIVATE" && it.uri !in activePrivate }
            .forEach { dao.deleteSaveLocation(it.id) }
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
        return (external + private).distinctBy { it.uri }.sortedWith(compareBy({ it.slot ?: Int.MAX_VALUE }, { it.name }))
    }

    suspend fun readSave(game: GameEntity, save: GameSave): Pair<SaveData, List<SaveEntry>> {
        return withWolfStorage(game) { storage ->
            requireSource(game, save.uri, storage)
            require(save.engine == game.engine) { "Moteur de sauvegarde incoherent." }
            val data = SaveCodec.read(game.engine, readBytes(context, save.uri))
            data to SaveCodec.entries(data)
        }
    }

    private val writes = kotlinx.coroutines.sync.Mutex()

    suspend fun writeSave(game: GameEntity, save: GameSave, edits: List<SaveEdit>, expectedHash: String? = null): SaveBackupEntity = writes.withLock {
        withWolfStorage(game) { storage ->
            requireSource(game, save.uri, storage)
            require(save.engine == game.engine) { "Moteur de sauvegarde incoherent." }
            val original = readBytes(context, save.uri)
            check(expectedHash == null || sha256Hex(original) == expectedHash) {
                "La sauvegarde a change depuis son ouverture. Fermez puis rouvrez l editeur."
            }
            val patched = SaveCodec.patch(game.engine, original, edits)
            val backup = backup(game, save, original)
            replaceVerified(save.uri, original, patched)
            publishWolfSave(storage, save.uri)
            // Retention cleanup must never turn a completed, verified write into an apparent failure.
            runCatching { rotateBackups(save.uri) }
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
        withWolfStorage(game) { storage ->
            requireSource(game, backup.sourceUri, storage)
            val bytes = readBackupBytes(backup.backupUri)
            val original = readBytes(context, backup.sourceUri)
            backup(game, GameSave(backup.sourceUri, backup.sourceName, null, game.engine, original.size.toLong(), 0), original)
            replaceVerified(backup.sourceUri, original, bytes)
            publishWolfSave(storage, backup.sourceUri)
            runCatching { rotateBackups(backup.sourceUri) }
        }
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

    private suspend fun requireSource(game: GameEntity, uri: String, storage: WolfGameStorage? = null) {
        val parsed = Uri.parse(uri)
        require(parsed.scheme == "content" || parsed.scheme == "file") { "Source de sauvegarde invalide." }
        if (parsed.scheme == "file") {
            val source = File(requireNotNull(parsed.path)).canonicalFile
            if (isPrivate(source)) {
                require(game.engine == "WOLF_RPG" && wolfSaves(storage).any { it.file.canonicalFile == source }) {
                    "Ce fichier interne Astra ne fait pas partie des sauvegardes Wolf autorisees pour ce jeu."
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
