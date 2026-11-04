package fr.astragames.app.data.backup

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import fr.astragames.app.core.filesystem.copyBoundedTo
import fr.astragames.app.data.mods.ZipPathGuard
import fr.astragames.app.mkxpz.MkxpzSavePaths
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A replacement journal is recoverable only with its own, unchanged original bytes. */
internal object ModBackupReferences {
    const val DIRECTORY = "mod-backups"
    private val ownerPattern = Regex("[A-Za-z0-9_-]{1,128}")
    private val hashPattern = Regex("[a-f0-9]{64}")
    private data class Reference(val id: String, val storedPath: File, val relative: String, val hash: String)

    private fun references(db: SQLiteDatabase): List<Reference> {
        if (!db.rawQuery("PRAGMA table_info('mod_installed_files')", null).use { it.count > 0 }) return emptyList()
        val entries = mutableSetOf<String>()
        val rows = mutableSetOf<String>()
        val destinations = mutableSetOf<Pair<String, String>>()
        return db.rawQuery("""
            SELECT f.id, f.installationId, f.relativePath, f.action, f.originalHash, f.backupUri,
                   i.id, g.id, m.id
            FROM mod_installed_files f
            LEFT JOIN mod_installations i ON i.id = f.installationId
            LEFT JOIN games g ON g.id = i.gameId
            LEFT JOIN mods m ON m.id = i.modId
        """.trimIndent(), null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val owner = cursor.getString(1)
                    require(!cursor.isNull(6) && !cursor.isNull(7) && !cursor.isNull(8) && ownerPattern.matches(owner)) {
                        "Propriétaire du backup de mod absent ou invalide."
                    }
                    require(rows.add(id) && destinations.add(owner to cursor.getString(2))) { "Journal de fichiers de mod dupliqué." }
                    when (cursor.getString(3)) {
                        "ADDED" -> require(cursor.isNull(4) && cursor.isNull(5)) { "Un fichier ajouté ne doit pas référencer de backup original." }
                        "REPLACED" -> {
                            require(!cursor.isNull(4) && hashPattern.matches(cursor.getString(4))) { "Empreinte du fichier original de mod absente ou invalide." }
                            require(!cursor.isNull(5)) { "Référence du backup original de mod absente." }
                            val stored = storedPath(cursor.getString(5))
                            val marker = "/$DIRECTORY/"
                            require(stored.path.indexOf(marker) >= 0 && stored.path.indexOf(marker) == stored.path.lastIndexOf(marker)) {
                                "Chemin de backup de mod invalide."
                            }
                            val relative = stored.path.substringAfter(marker)
                            require(relative.split('/').size == 2 && relative.substringBefore('/') == owner &&
                                ZipPathGuard.sanitize(relative) == relative) { "Ce backup n’appartient pas à l’installation du mod." }
                            // Legacy basenames were derived from game paths, not UUIDs.
                            val entry = "$DIRECTORY/$relative"
                            require(entries.add(entry.lowercase(Locale.ROOT))) { "Un backup de mod est attribué à plusieurs fichiers." }
                            add(Reference(id, stored, entry, cursor.getString(4)))
                        }
                        else -> error("Action du journal de mod inconnue.")
                    }
                }
            }
        }
    }

    private fun storedPath(value: String): File {
        val uri = Uri.parse(value)
        val path = if (uri.scheme.equals("file", true)) {
            require(uri.authority.isNullOrEmpty() && uri.query == null && uri.fragment == null) { "Chemin de backup de mod invalide." }
            requireNotNull(uri.path)
        } else {
            require(uri.scheme == null) { "Emplacement de backup de mod non reconnu." }
            value
        }
        val file = File(path)
        require(file.isAbsolute && file.toPath() == file.toPath().normalize()) { "Chemin de backup de mod invalide." }
        return file
    }

    private fun destination(filesDir: File, relative: String): File = File(filesDir, relative).also {
        MkxpzSavePaths.requireNoSymlinks(filesDir, it)
        require(!it.exists() || it.isFile) { "Destination du backup de mod invalide." }
    }

    /** Snapshot only referenced originals; unrelated local recovery remnants stay untouched. */
    fun prepareExport(filesDir: File, db: SQLiteDatabase, staging: File): List<Pair<String, File>> {
        val references = references(db)
        var remaining = BackupArchive.MAX_BYTES
        return references.map { reference ->
            val target = destination(filesDir, reference.relative)
            val stored = reference.storedPath
            val root = if (stored.toPath().startsWith(filesDir.absoluteFile.toPath())) filesDir else filesDir.canonicalFile
            MkxpzSavePaths.requireNoSymlinks(root, stored)
            require(stored.canonicalFile == target.canonicalFile && stored.isFile) { "Backup original de mod introuvable ; export annulé." }
            val snapshot = File(staging, reference.relative)
            MkxpzSavePaths.requireNoSymlinks(staging, snapshot)
            check(snapshot.parentFile!!.isDirectory || snapshot.parentFile!!.mkdirs()) { "Copie temporaire du backup de mod impossible." }
            stored.inputStream().use { input ->
                snapshot.outputStream().use { output -> remaining -= input.copyBoundedTo(output, remaining) }
            }
            require(hash(snapshot) == reference.hash) { "Backup original de mod endommagé ; export annulé." }
            // ModsManager consumes an absolute File path, including after a legacy file: import.
            db.execSQL("UPDATE mod_installed_files SET backupUri = ? WHERE id = ?", arrayOf(target.absolutePath, reference.id))
            reference.relative to snapshot
        }
    }

    /** All checks concern extracted bytes. A matching live backup never fills an archive gap. */
    fun planImport(filesDir: File, db: SQLiteDatabase, staging: File): MkxpzBackupReferences.ImportPlan {
        val references = references(db)
        val root = File(staging, DIRECTORY)
        MkxpzSavePaths.requireNoSymlinks(staging, root)
        val present = mutableSetOf<String>()
        root.walkTopDown().onEnter { MkxpzSavePaths.requireNoSymlinks(staging, it); true }.forEach { file ->
            // Check even dangling links before filtering to regular files.
            MkxpzSavePaths.requireNoSymlinks(staging, file)
            if (file.isFile) present += file.relativeTo(staging).invariantSeparatorsPath
            else require(!file.exists() || file.isDirectory) { "Type de backup de mod invalide." }
        }
        require(present == references.map { it.relative }.toSet()) { "Backups de mod absents ou sans propriétaire dans l’archive." }
        val files = mutableListOf<Pair<File, File>>()
        val rebinds = mutableListOf<MkxpzBackupReferences.Rebind>()
        references.forEach { reference ->
            val source = File(staging, reference.relative)
            require(source.isFile && hash(source) == reference.hash) { "Backup original de mod absent ou endommagé dans l’archive." }
            val target = destination(filesDir, reference.relative)
            files += source to target
            rebinds += MkxpzBackupReferences.Rebind("mod_installed_files", "backupUri", reference.id, target.absolutePath)
        }
        return MkxpzBackupReferences.ImportPlan(files, rebinds)
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
