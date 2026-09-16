package fr.astragames.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import fr.astragames.app.data.saves.readBounded
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.core.security.KeystoreCrypto
import fr.astragames.app.data.local.AstraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Archive chiffrée du catalogue et des fichiers internes, liée à la clé de cette installation. */
private val BACKUP_MAGIC = byteArrayOf('A'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())

class BackupManager(
    private val context: Context,
    private val database: AstraDatabase
) {
    private val lock = Mutex()

    suspend fun create(treeUri: Uri): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val tree = documentDir(context, treeUri)
                ?.takeIf { it.exists() && it.canWrite() }
                ?: error("Le dossier de sauvegarde n’est plus accessible.")
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
            val displayName = "astra-games_$stamp.astra"
            val target = tree.createFile("application/octet-stream", displayName)
                ?: error("Impossible de créer la sauvegarde.")
            val work = File(context.cacheDir, "backup-${System.currentTimeMillis()}.zip")
            try {
                val snapshot = File(context.cacheDir, "snapshot-${java.util.UUID.randomUUID()}.db")
                try {
                    database.withTransaction {
                        context.getDatabasePath(database.openHelper.databaseName ?: error("Base en memoire non exportable.")).copyTo(snapshot, overwrite = true)
                        val wal = File(context.getDatabasePath(database.openHelper.databaseName ?: error("Base en memoire non exportable.")).path + "-wal")
                        if (wal.isFile) wal.copyTo(File(snapshot.path + "-wal"), overwrite = true)
                    }
                    android.database.sqlite.SQLiteDatabase.openDatabase(snapshot.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { copy ->
                        copy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                    }
                    work.outputStream().buffered().use { output ->
                        ZipOutputStream(output).use { zip ->
                            addFile(zip, snapshot, DATABASE_ENTRY)
                            INTERNAL_DIRECTORIES.forEach { name ->
                                val directory = File(context.filesDir, name)
                                directory.walkTopDown().filter(File::isFile).forEach { file ->
                                    addFile(zip, file, "$name/${file.relativeTo(directory).invariantSeparatorsPath}")
                                }
                            }
                        }
                    }
                } finally {
                    snapshot.delete()
                    File(snapshot.path + "-wal").delete()
                    File(snapshot.path + "-shm").delete()
                }
                require(work.length() <= MAX_ARCHIVE_BYTES) { "Sauvegarde trop volumineuse." }
                val encrypted = BACKUP_MAGIC + KeystoreCrypto.encrypt(work.readBytes())
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(encrypted) }
                    ?: error("Impossible d’écrire dans le dossier choisi.")
            } catch (error: Exception) {
                target.delete()
                throw error
            } finally {
                work.delete()
            }
            target.name ?: displayName
        }
    }

    suspend fun restore(archiveUri: Uri) = withContext(Dispatchers.IO) {
        lock.withLock {
            val work = File(context.cacheDir, "restore-${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val raw = context.contentResolver.openInputStream(archiveUri)?.use { it.readBounded(MAX_ARCHIVE_BYTES + 1024) }
                    ?: error("Impossible de lire la sauvegarde sélectionnée.")
                val archiveFile = File(work, "archive.zip")
                if (raw.size > BACKUP_MAGIC.size && raw.copyOfRange(0, BACKUP_MAGIC.size).contentEquals(BACKUP_MAGIC)) {
                    archiveFile.writeBytes(KeystoreCrypto.decrypt(raw.copyOfRange(BACKUP_MAGIC.size, raw.size)))
                } else {
                    archiveFile.writeBytes(raw)
                }
                extractArchive(Uri.fromFile(archiveFile), work)
                val importedDatabase = File(work, DATABASE_ENTRY)
                require(importedDatabase.isFile) { "Cette archive ne contient pas de catalogue Astra." }
                android.database.sqlite.SQLiteDatabase.openDatabase(importedDatabase.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { checkDb ->
                    checkDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                        require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Catalogue endommage." }
                    }
                }
                val rollback = File(work, "rollback").apply { mkdirs() }
                val changedFiles = mutableListOf<Pair<File, File?>>()
                try {
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
                            listOf("save_backups" to "backupUri", "mod_installed_files" to "backupUri").forEach { (table, column) ->
                                db.query("SELECT id, `$column` FROM `$table` WHERE `$column` IS NOT NULL").use { cursor ->
                                    while (cursor.moveToNext()) {
                                        val old = cursor.getString(1)
                                        val directory = INTERNAL_DIRECTORIES.firstOrNull { old.contains("/$it/") } ?: continue
                                        val relative = old.substringAfter("/$directory/")
                                        val file = File(context.filesDir, "$directory/$relative")
                                        require(file.canonicalFile.toPath().startsWith(File(context.filesDir, directory).canonicalFile.toPath())) { "Chemin de backup invalide." }
                                        db.execSQL("UPDATE `$table` SET `$column` = ? WHERE id = ?", arrayOf(if (old.startsWith("file:")) Uri.fromFile(file).toString() else file.path, cursor.getString(0)))
                                    }
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    changedFiles.asReversed().forEach { (file, old) ->
                        if (old == null) file.delete() else old.copyTo(file, overwrite = true)
                    }
                    throw error
                }
            } finally {
                work.deleteRecursively()
            }
        }
    }

    fun archiveDisplayName(uri: Uri): String = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "sauvegarde"

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        require(file.isFile) { "Le catalogue local est introuvable." }
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().buffered().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun extractArchive(uri: Uri, destination: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                val seen = mutableSetOf<String>()
                var total = 0L
                while (entry != null) {
                    require(seen.size < 10000 && seen.add(entry.name)) { "Archive dupliquee ou trop volumineuse." }
                    val bytes = zip.readBounded(MAX_ARCHIVE_BYTES - total)
                    total += bytes.size
                    if (!entry.isDirectory && (entry.name == DATABASE_ENTRY || INTERNAL_DIRECTORIES.any { entry!!.name.startsWith("$it/") })) {
                        val output = File(destination, entry.name)
                        val safeRoot = destination.canonicalFile
                        require(output.canonicalFile.toPath().startsWith(safeRoot.toPath())) { "Archive non sûre." }
                        output.parentFile?.mkdirs()
                        output.writeBytes(bytes)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Impossible de lire la sauvegarde sélectionnée.")
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
        private const val DATABASE_ENTRY = "database/astra_games.db"
        private const val DATABASE_VERSION = 8
        private val SUPPORTED_DATABASE_VERSIONS = (5..DATABASE_VERSION).toSet()
        private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
        private val INTERNAL_DIRECTORIES = listOf("covers", "save-backups", "mod-backups")
        private val OPTIONAL_TABLES = listOf("audit_events", "game_save_locations", "save_backups", "mods", "mod_installations", "mod_installed_files")
        private val RESTORED_TABLES = OPTIONAL_TABLES + listOf(
            "game_tags", "play_sessions", "metadata", "cover_candidates", "collection_rules",
            "scan_report_items", "launch_profiles", "deleted_games", "ignored_duplicate_groups", "game_search", "games",
            "source_exclusions", "game_sources", "tags", "tag_categories", "library_folders",
            "collections", "scan_history"
        )
    }
}
