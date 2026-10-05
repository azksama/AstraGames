package fr.astragames.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.core.security.KeystoreCrypto
import fr.astragames.app.data.local.AstraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Archive chiffrée du catalogue et des fichiers internes, liée à la clé de cette installation. */
class BackupManager(
    private val context: Context,
    private val database: AstraDatabase
) {
    private val lock = Mutex()
    private val envelope = BackupEnvelope(KeystoreCrypto::backupEncryptionCipher, KeystoreCrypto::backupDecryptionCipher)

    suspend fun create(treeUri: Uri): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val tree = documentDir(context, treeUri)
                ?.takeIf { it.exists() && it.canWrite() }
                ?: error("Le dossier de sauvegarde n’est plus accessible.")
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
            val displayName = "astra-games_${stamp}_${UUID.randomUUID().toString().take(8)}.astra"
            require(tree.findFile(displayName) == null) { "Un fichier de sauvegarde porte déjà ce nom. Réessayez." }
            val target = tree.createFile("application/octet-stream", displayName)
                ?: error("Impossible de créer la sauvegarde.")
            val workDirectory = File(context.cacheDir, "backup-${UUID.randomUUID()}")
            val work = File(workDirectory, "archive.zip")
            var ownsWorkDirectory = false
            try {
                ownsWorkDirectory = workDirectory.mkdir()
                check(ownsWorkDirectory) { "Dossier de sauvegarde temporaire inaccessible." }
                val snapshot = File(workDirectory, "snapshot.db")
                try {
                    database.withTransaction {
                        context.getDatabasePath(database.openHelper.databaseName ?: error("Base en memoire non exportable.")).copyTo(snapshot, overwrite = true)
                        val wal = File(context.getDatabasePath(database.openHelper.databaseName ?: error("Base en memoire non exportable.")).path + "-wal")
                        if (wal.isFile) wal.copyTo(File(snapshot.path + "-wal"), overwrite = true)
                    }
                    android.database.sqlite.SQLiteDatabase.openDatabase(snapshot.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { copy ->
                        copy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                    }
                    val files = sequence {
                        yield(DATABASE_ENTRY to snapshot)
                        INTERNAL_DIRECTORIES.forEach { name ->
                            val directory = File(context.filesDir, name)
                            directory.walkTopDown().filter(File::isFile).forEach { file ->
                                yield("$name/${file.relativeTo(directory).invariantSeparatorsPath}" to file)
                            }
                        }
                    }
                    work.outputStream().use { BackupArchive.write(it, files) }
                } finally {
                    snapshot.delete()
                    File(snapshot.path + "-wal").delete()
                    File(snapshot.path + "-shm").delete()
                }
                require(work.length() <= MAX_ARCHIVE_BYTES) { "Sauvegarde trop volumineuse." }
                val expectedLength = work.length()
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openOutputStream(target.uri, "wt")?.buffered()?.use { output ->
                    DigestInputStream(work.inputStream().buffered(), digest).use { envelope.write(it, expectedLength, output) }
                }
                    ?: error("Impossible d’écrire dans le dossier choisi.")
                val expectedSha256 = digest.digest()
                context.contentResolver.openInputStream(target.uri)?.buffered()?.use {
                    envelope.verify(it, expectedLength, expectedSha256)
                } ?: error("Impossible de vérifier la sauvegarde exportée.")
            } catch (error: Exception) {
                target.delete()
                throw error
            } finally {
                if (ownsWorkDirectory) workDirectory.deleteRecursively()
            }
            target.name ?: displayName
        }
    }

    suspend fun restore(archiveUri: Uri) = withContext(Dispatchers.IO) {
        lock.withLock {
            // Failed rollback originals must survive Android cache eviction.
            val work = File(context.noBackupFilesDir, "restore-${UUID.randomUUID()}").apply {
                check(mkdir()) { "Dossier de restauration temporaire inaccessible." }
            }
            var preserveRecovery = false
            try {
                val archiveFile = File(work, "archive.zip")
                context.contentResolver.openInputStream(archiveUri)?.buffered()?.use { input ->
                    archiveFile.outputStream().buffered().use { envelope.read(input, it) }
                } ?: error("Impossible de lire la sauvegarde sélectionnée.")
                // No extraction, file replacement or database writes before all records authenticate.
                archiveFile.inputStream().use { BackupArchive.extract(it, work) }
                val importedDatabase = File(work, DATABASE_ENTRY)
                require(importedDatabase.isFile) { "Cette archive ne contient pas de catalogue Astra." }
                android.database.sqlite.SQLiteDatabase.openDatabase(importedDatabase.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { checkDb ->
                    require(checkDb.version in SUPPORTED_DATABASE_VERSIONS) { "Version de catalogue incompatible : ${checkDb.version}" }
                    validateFileReferences(checkDb)
                    checkDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                        require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Catalogue endommage." }
                    }
                }
                val rollback = File(work, "rollback").apply { mkdirs() }
                val changedFiles = mutableListOf<Pair<File, File?>>()
                try {
                    // Also preserve originals if a fatal Throwable bypasses the Exception handler.
                    preserveRecovery = true
                    INTERNAL_DIRECTORIES.forEach { name ->
                        val source = File(work, name)
                        source.walkTopDown().filter(File::isFile).forEach { file ->
                            val relative = "$name/${file.relativeTo(source).invariantSeparatorsPath}"
                            val destination = File(context.filesDir, relative)
                            val old = destination.takeIf { it.isFile }?.let { original ->
                                File(rollback, relative).also { it.parentFile?.mkdirs(); original.copyTo(it) }
                            }
                            changedFiles += destination to old
                            destination.parentFile?.mkdirs()
                            file.copyTo(destination, overwrite = true)
                        }
                    }
                    android.database.sqlite.SQLiteDatabase.openDatabase(importedDatabase.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { imported ->
                        require(imported.version in SUPPORTED_DATABASE_VERSIONS) { "Version de catalogue incompatible : ${imported.version}" }
                        database.withTransaction {
                            val db = database.openHelper.writableDatabase
                            RESTORED_TABLES.forEach { table -> db.execSQL("DELETE FROM `$table`") }
                            RESTORED_TABLES.reversed().forEach { table -> copyTableByColumnName(db, imported, table) }
                            // Absolute private paths change after restoring under another Android user.
                            listOf("save_backups" to "backupUri", "mod_installed_files" to "backupUri", "games" to "coverUri", "games" to "bannerUri", "games" to "iconUri").forEach { (table, column) ->
                                db.query("SELECT id, `$column` FROM `$table` WHERE `$column` IS NOT NULL").use { cursor ->
                                    while (cursor.moveToNext()) {
                                        val old = cursor.getString(1)
                                        val oldPath = if (old.startsWith("file:")) Uri.parse(old).path.orEmpty() else old
                                        val expectedDirectory = when (table) {
                                            "save_backups" -> "save-backups"
                                            "mod_installed_files" -> "mod-backups"
                                            else -> "covers"
                                        }
                                        if (table == "games" && !old.startsWith("file:")) continue
                                        if (table == "games" && !oldPath.contains("/$expectedDirectory/")) continue
                                        require(oldPath.contains("/$expectedDirectory/")) { "Chemin de backup invalide." }
                                        val directory = expectedDirectory
                                        val relative = oldPath.substringAfter("/$directory/")
                                        require(fr.astragames.app.data.mods.ZipPathGuard.sanitize(relative) == relative) { "Chemin de backup invalide." }
                                        val file = File(context.filesDir, "$directory/$relative")
                                        require(file.canonicalFile.toPath().startsWith(File(context.filesDir, directory).canonicalFile.toPath())) { "Chemin de backup invalide." }
                                        db.execSQL("UPDATE `$table` SET `$column` = ? WHERE id = ?", arrayOf(if (old.startsWith("file:")) Uri.fromFile(file).toString() else file.path, cursor.getString(0)))
                                    }
                                }
                            }
                        }
                    }
                    preserveRecovery = false
                } catch (error: Exception) {
                    val failures = BackupRollback.restore(changedFiles)
                    if (failures.isNotEmpty()) {
                        val incomplete = IOException(
                            "La restauration a échoué et le retour arrière est incomplet. Les copies originales sont conservées pour récupération. Ne désinstallez pas Astra. Dossier : ${work.absolutePath}",
                            error
                        )
                        failures.forEach(incomplete::addSuppressed)
                        throw incomplete
                    }
                    preserveRecovery = false
                    throw error
                }
            } finally {
                if (!preserveRecovery) work.deleteRecursively()
            }
        }
    }

    fun archiveDisplayName(uri: Uri): String = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "sauvegarde"

    /** A legacy ZIP is untrusted input: it must not turn a game/mod action into a write
     * to preferences, the live catalog, credentials, or Astra's own managed backups. */
    private fun validateFileReferences(imported: android.database.sqlite.SQLiteDatabase) {
        val privateRoots = listOfNotNull(
            context.filesDir, context.noBackupFilesDir, context.getDatabasePath("astra_games.db").parentFile,
            File(context.applicationInfo.dataDir, "shared_prefs")
        ).map { it.canonicalFile.toPath() }
        val references = mapOf(
            "games" to listOf("documentUri", "physicalPath"),
            "game_sources" to listOf("treeUri"),
            "game_save_locations" to listOf("uri"),
            "save_backups" to listOf("sourceUri"),
            "mods" to listOf("folderUri"),
            "mod_installed_files" to listOf("relativePath"),
            "launch_profiles" to listOf("physicalPath")
        )
        references.forEach { (table, wanted) ->
            val columns = imported.rawQuery("PRAGMA table_info('$table')", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            wanted.filter { it in columns }.forEach { column ->
                imported.rawQuery("SELECT `$column` FROM `$table` WHERE `$column` IS NOT NULL", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val value = cursor.getString(0)
                        val path = if (value.startsWith("file:")) Uri.parse(value).path else value.takeIf { it.startsWith('/') }
                        if (path != null) {
                            val resolved = File(path).canonicalFile.toPath()
                            require(privateRoots.none { resolved.startsWith(it) || it.startsWith(resolved) }) { "La sauvegarde référence des fichiers privés d’Astra." }
                        }
                    }
                }
            }
        }
    }

    private fun copyTableByColumnName(db: androidx.sqlite.db.SupportSQLiteDatabase, imported: android.database.sqlite.SQLiteDatabase, table: String) {
        fun columns(cursor: android.database.Cursor): List<String> = cursor.use { buildList { while (it.moveToNext()) add(it.getString(1)) } }
        val sourceColumns = columns(imported.rawQuery("PRAGMA table_info('$table')", null)).toSet()
        val common = columns(db.query("PRAGMA table_info('$table')")).filter { it in sourceColumns }
        if (common.isEmpty() && table in OPTIONAL_TABLES) return
        require(common.isNotEmpty()) { "La table $table est absente de la sauvegarde." }
        val names = common.joinToString(", ") { "`$it`" }
        val placeholders = common.joinToString(", ") { "?" }
        db.compileStatement("INSERT INTO `$table` ($names) VALUES ($placeholders)").use { statement ->
            imported.rawQuery("SELECT $names FROM `$table`", null).use { cursor ->
                while (cursor.moveToNext()) {
                    statement.clearBindings()
                    common.indices.forEach { index ->
                        when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> statement.bindNull(index + 1)
                            android.database.Cursor.FIELD_TYPE_INTEGER -> statement.bindLong(index + 1, cursor.getLong(index))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> statement.bindDouble(index + 1, cursor.getDouble(index))
                            android.database.Cursor.FIELD_TYPE_BLOB -> statement.bindBlob(index + 1, cursor.getBlob(index))
                            else -> statement.bindString(index + 1, cursor.getString(index))
                        }
                    }
                    statement.executeInsert()
                }
            }
        }
    }

    companion object {
        private const val DATABASE_ENTRY = BackupArchive.DATABASE_ENTRY
        private const val DATABASE_VERSION = 9
        private val SUPPORTED_DATABASE_VERSIONS = (5..DATABASE_VERSION).toSet()
        private const val MAX_ARCHIVE_BYTES = BackupArchive.MAX_BYTES
        private val INTERNAL_DIRECTORIES = BackupArchive.DIRECTORIES
        private val OPTIONAL_TABLES = listOf("audit_events", "game_save_locations", "save_backups", "mods", "mod_installations", "mod_installed_files")
        private val RESTORED_TABLES = OPTIONAL_TABLES + listOf(
            "game_tags", "play_sessions", "metadata", "cover_candidates", "collection_rules",
            "scan_report_items", "launch_profiles", "deleted_games", "ignored_duplicate_groups", "game_search", "games",
            "source_exclusions", "game_sources", "tags", "tag_categories", "library_folders",
            "collections", "scan_history"
        )
    }
}
