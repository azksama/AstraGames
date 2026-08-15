package fr.astragames.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
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

/** Sauvegarde portable du catalogue Room et des jaquettes gérées par Astra. */
class BackupManager(
    private val context: Context,
    private val database: AstraDatabase
) {
    private val lock = Mutex()

    suspend fun create(treeUri: Uri): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?.takeIf { it.exists() && it.canWrite() }
                ?: error("Le dossier de sauvegarde n’est plus accessible.")
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
            val displayName = "astra-games_$stamp.zip"
            val target = tree.createFile("application/zip", displayName)
                ?: error("Impossible de créer la sauvegarde.")
            context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    addFile(zip, context.getDatabasePath(DATABASE_NAME), DATABASE_ENTRY)
                    val covers = File(context.filesDir, COVERS_DIRECTORY)
                    covers.listFiles()?.filter(File::isFile)?.forEach { cover ->
                        addFile(zip, cover, "$COVERS_DIRECTORY/${cover.name}")
                    }
                }
            } ?: error("Impossible d’écrire dans le dossier choisi.")
            target.name ?: displayName
        }
    }

    suspend fun restore(archiveUri: Uri) = withContext(Dispatchers.IO) {
        lock.withLock {
            val work = File(context.cacheDir, "restore-${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                extractArchive(archiveUri, work)
                val importedDatabase = File(work, DATABASE_ENTRY)
                require(importedDatabase.isFile) { "Cette archive ne contient pas de catalogue Astra." }
                val db = database.openHelper.writableDatabase
                val escapedPath = importedDatabase.absolutePath.replace("'", "''")
                db.execSQL("ATTACH DATABASE '$escapedPath' AS imported")
                try {
                    val version = db.query("PRAGMA imported.user_version").use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                    require(version in SUPPORTED_DATABASE_VERSIONS) {
                        "Sauvegarde incompatible (version $version, versions acceptées ${SUPPORTED_DATABASE_VERSIONS.joinToString()})."
                    }
                    db.beginTransaction()
                    try {
                        RESTORED_TABLES.forEach { table -> db.execSQL("DELETE FROM `$table`") }
                        RESTORED_TABLES.reversed().forEach { table ->
                            copyTableByColumnName(db, table)
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                } finally {
                    db.execSQL("DETACH DATABASE imported")
                }
                restoreCovers(work)
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
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name == DATABASE_ENTRY || entry.name.startsWith("$COVERS_DIRECTORY/"))) {
                        val output = File(destination, entry.name)
                        val safeRoot = destination.canonicalFile
                        require(output.canonicalFile.toPath().startsWith(safeRoot.toPath())) { "Archive non sûre." }
                        output.parentFile?.mkdirs()
                        output.outputStream().buffered().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Impossible de lire la sauvegarde sélectionnée.")
    }

    private fun restoreCovers(work: File) {
        val imported = File(work, COVERS_DIRECTORY)
        if (!imported.isDirectory) return
        val destination = File(context.filesDir, COVERS_DIRECTORY).apply { mkdirs() }
        imported.listFiles()?.filter(File::isFile)?.forEach { cover ->
            cover.copyTo(File(destination, cover.name), overwrite = true)
        }
    }

    private fun copyTableByColumnName(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String) {
        fun columns(schema: String): List<String> = db.query("PRAGMA $schema.table_info('$table')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        val imported = columns("imported").toSet()
        val common = columns("main").filter { it in imported }
        require(common.isNotEmpty()) { "La table $table est absente de la sauvegarde." }
        val names = common.joinToString(", ") { "`$it`" }
        db.execSQL("INSERT INTO `$table` ($names) SELECT $names FROM imported.`$table`")
    }

    companion object {
        private const val DATABASE_NAME = "astra_games.db"
        private const val DATABASE_ENTRY = "database/astra_games.db"
        private const val COVERS_DIRECTORY = "covers"
        private const val DATABASE_VERSION = 6
        private val SUPPORTED_DATABASE_VERSIONS = setOf(5, DATABASE_VERSION)
        private val RESTORED_TABLES = listOf(
            "game_tags", "play_sessions", "metadata", "cover_candidates", "collection_rules",
            "scan_report_items", "launch_profiles", "deleted_games", "ignored_duplicate_groups", "game_search", "games",
            "source_exclusions", "game_sources", "tags", "tag_categories", "library_folders",
            "collections", "scan_history"
        )
    }
}
