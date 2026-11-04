package fr.astragames.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.core.security.KeystoreCrypto
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.mkxpz.MkxpzSavePaths
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
                    val snapshotFiles = android.database.sqlite.SQLiteDatabase.openDatabase(snapshot.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { copy ->
                        copy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                        copy.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
                        val files = MkxpzBackupReferences.prepareExport(context, copy) + WolfBackupReferences.prepareExport(context, copy, workDirectory) +
                            prepareSaveBackupExport(copy) + ModBackupReferences.prepareExport(context.filesDir, copy, workDirectory)
                        validateFileReferences(copy)
                        saveBackupReferences(copy)
                        val exportedEntries = files.map { it.first }.toSet()
                        MkxpzBackupReferences.planImport(context, copy, workDirectory, exportedEntries)
                        WolfBackupReferences.planImport(context, copy, workDirectory, exportedEntries)
                        files
                    }
                    val files = sequence {
                        yield(DATABASE_ENTRY to snapshot)
                        INTERNAL_DIRECTORIES.filter { it !in setOf("save-backups", ModBackupReferences.DIRECTORY) }.forEach { name ->
                            val directory = File(context.filesDir, name)
                            MkxpzSavePaths.requireNoSymlinks(context.filesDir, directory)
                            directory.walkTopDown().onEnter { MkxpzSavePaths.requireNoSymlinks(context.filesDir, it); true }.filter(File::isFile).forEach { file ->
                                MkxpzSavePaths.requireNoSymlinks(context.filesDir, file)
                                yield("$name/${file.relativeTo(directory).invariantSeparatorsPath}" to file)
                            }
                        }
                        snapshotFiles.forEach { yield(it) }
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
                val nativePlan = android.database.sqlite.SQLiteDatabase.openDatabase(importedDatabase.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { checkDb ->
                    require(checkDb.version in SUPPORTED_DATABASE_VERSIONS) { "Version de catalogue incompatible : ${checkDb.version}" }
                    validateFileReferences(checkDb)
                    val mods = ModBackupReferences.planImport(context.filesDir, checkDb, work)
                    checkDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                        require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Catalogue endommage." }
                    }
                    val mkxpz = MkxpzBackupReferences.planImport(context, checkDb, work)
                    val wolf = WolfBackupReferences.planImport(context, checkDb, work)
                    val backups = saveBackupReferences(checkDb)
                    val expectedBackups = backups.map { it.second }.toSet()
                    require(expectedBackups.size == backups.size) { "Un backup est attribué à plusieurs entrées du catalogue." }
                    val archiveBackups = File(work, "save-backups").walkTopDown().filter(File::isFile)
                        .map { it.relativeTo(work).invariantSeparatorsPath }.toSet()
                    require(expectedBackups == archiveBackups) { "Backups de sauvegarde absents ou sans propriétaire dans l’archive." }
                    val backupFiles = backups.map { (reference, relative) -> File(work, relative) to File(requireNotNull(Uri.parse(reference.uri).path)) }
                    MkxpzBackupReferences.ImportPlan(mkxpz.files + wolf.files + backupFiles + mods.files,
                        mkxpz.references + wolf.references + backups.map { it.first } + mods.references)
                }
                val filesToRestore = buildList {
                    INTERNAL_DIRECTORIES.filter { it !in setOf("save-backups", ModBackupReferences.DIRECTORY) }.forEach { name ->
                        val source = File(work, name)
                        source.walkTopDown().filter(File::isFile).forEach { file ->
                            add(file to File(context.filesDir, "$name/${file.relativeTo(source).invariantSeparatorsPath}"))
                        }
                    }
                    addAll(nativePlan.files)
                }
                // Validate all destinations (including links inside an allowed root)
                // before copying the first asset or changing the live catalogue.
                filesToRestore.forEach { (_, destination) ->
                    MkxpzSavePaths.requireNoSymlinks(context.filesDir, destination)
                    require(!destination.exists() || destination.isFile) { "Destination de restauration invalide." }
                }
                val rollback = File(work, "rollback").apply { mkdirs() }
                val changedFiles = mutableListOf<Pair<File, File?>>()
                try {
                    // Also preserve originals if a fatal Throwable bypasses the Exception handler.
                    preserveRecovery = true
                    filesToRestore.forEach { (file, destination) ->
                            val relative = destination.relativeTo(context.filesDir).invariantSeparatorsPath
                            val old = destination.takeIf { it.isFile }?.let { original ->
                                File(rollback, relative).also { it.parentFile?.mkdirs(); original.copyTo(it) }
                            }
                            changedFiles += destination to old
                            destination.parentFile?.mkdirs()
                            file.copyTo(destination, overwrite = true)
                    }
                    android.database.sqlite.SQLiteDatabase.openDatabase(importedDatabase.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { imported ->
                        require(imported.version in SUPPORTED_DATABASE_VERSIONS) { "Version de catalogue incompatible : ${imported.version}" }
                        database.withTransaction {
                            val db = database.openHelper.writableDatabase
                            RESTORED_TABLES.forEach { table -> db.execSQL("DELETE FROM `$table`") }
                            RESTORED_TABLES.reversed().forEach { table -> copyTableByColumnName(db, imported, table) }
                            nativePlan.references.forEach { reference ->
                                db.execSQL("UPDATE `${reference.table}` SET `${reference.column}` = ? WHERE id = ?", arrayOf(reference.uri, reference.id))
                            }
                            // Absolute private paths change after restoring under another Android user.
                            listOf("games" to "coverUri", "games" to "bannerUri", "games" to "iconUri").forEach { (table, column) ->
                                db.query("SELECT id, `$column` FROM `$table` WHERE `$column` IS NOT NULL").use { cursor ->
                                    while (cursor.moveToNext()) {
                                        val old = cursor.getString(1)
                                        val oldPath = if (old.startsWith("file:")) Uri.parse(old).path.orEmpty() else old
                                        val expectedDirectory = "covers"
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
                        // Typed references are fully owner/engine/path checked by planImport.
                        if (MkxpzBackupReferences.isToken(value) || WolfBackupReferences.isToken(value)) {
                            require((table == "game_save_locations" && column == "uri") ||
                                (table == "save_backups" && column == "sourceUri")) { "Référence de sauvegarde privée interdite dans ce champ." }
                            continue
                        }
                        val path = if (Uri.parse(value).scheme.equals("file", true)) Uri.parse(value).path else value.takeIf { it.startsWith('/') }
                        if (path != null) {
                            val resolved = File(path).canonicalFile.toPath()
                            require(privateRoots.none { resolved.startsWith(it) || it.startsWith(resolved) }) { "La sauvegarde référence des fichiers privés d’Astra." }
                        }
                    }
                }
            }
        }
    }

    private fun saveBackupReferences(imported: android.database.sqlite.SQLiteDatabase): List<Pair<MkxpzBackupReferences.Rebind, String>> {
        val present = imported.rawQuery("PRAGMA table_info('save_backups')", null).use { it.count > 0 }
        if (!present) return emptyList()
        return imported.rawQuery("SELECT b.id, b.gameId, b.backupUri, g.id FROM save_backups b LEFT JOIN games g ON g.id = b.gameId", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val gameId = cursor.getString(1)
                    require(!cursor.isNull(3)) { "Jeu propriétaire du backup absent." }
                    val file = SaveBackupPaths.rebind(context, gameId, cursor.getString(2), cursor.getString(0))
                    val archiveRelative = "save-backups/" + requireNotNull(Uri.parse(cursor.getString(2)).path).substringAfter("/save-backups/")
                    add(MkxpzBackupReferences.Rebind("save_backups", "backupUri", cursor.getString(0), Uri.fromFile(file).toString()) to archiveRelative)
                }
            }
        }
    }

    private fun prepareSaveBackupExport(snapshot: android.database.sqlite.SQLiteDatabase): List<Pair<String, File>> =
        snapshot.rawQuery("SELECT id, gameId, backupUri FROM save_backups", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val gameId = cursor.getString(1)
                    val source = SaveBackupPaths.resolveStored(context, gameId, id, cursor.getString(2))
                    require(source.isFile) { "Backup de sauvegarde introuvable ; export annulé." }
                    val target = File(SaveBackupPaths.directory(context, gameId), source.name)
                    SaveBackupPaths.resolve(context, gameId, Uri.fromFile(target).toString())
                    snapshot.execSQL("UPDATE save_backups SET backupUri = ? WHERE id = ?", arrayOf(Uri.fromFile(target).toString(), id))
                    add("save-backups/${MkxpzSavePaths.ownerKey(gameId)}/${source.name}" to source)
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
