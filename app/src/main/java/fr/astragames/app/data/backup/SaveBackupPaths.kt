package fr.astragames.app.data.backup

import android.content.Context
import android.net.Uri
import fr.astragames.app.mkxpz.MkxpzSavePaths
import java.io.File

/** A backup is a capability owned by one game, never by the global backup directory. */
internal object SaveBackupPaths {
    private val legacyId = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
    fun directory(context: Context, gameId: String) = File(context.filesDir, "save-backups/${MkxpzSavePaths.ownerKey(gameId)}")

    fun resolve(context: Context, gameId: String, value: String): File {
        val uri = Uri.parse(value)
        require(uri.scheme == "file" && uri.authority.isNullOrEmpty() && uri.query == null && uri.fragment == null && uri.path != null) {
            "Emplacement de backup invalide."
        }
        val root = directory(context, gameId).absoluteFile
        val file = File(requireNotNull(uri.path)).absoluteFile
        MkxpzSavePaths.requireNoSymlinks(context.filesDir, file)
        require(file.parentFile == root) { "Ce backup n’appartient pas au jeu sélectionné." }
        require(!file.exists() || file.isFile) { "Type de backup invalide." }
        return file
    }

    /** Only persisted local rows may use the historical duplicate-import layout. Archives are rebased on export. */
    fun resolveStored(context: Context, gameId: String, backupId: String, value: String): File {
        val expected = File(context.filesDir, "save-backups/duplicate-import/$backupId").absoluteFile
        if (legacyId.matches(backupId) &&
            value == Uri.fromFile(expected).toString()) {
            MkxpzSavePaths.requireNoSymlinks(context.filesDir, expected)
            require(!expected.exists() || expected.isFile) { "Type de backup invalide." }
            return expected
        }
        return resolve(context, gameId, value)
    }

    /** Rebase an archive URI, checking the owner before any live file or database mutation. */
    fun rebind(context: Context, gameId: String, value: String, backupId: String? = null): File {
        val uri = Uri.parse(value)
        require(uri.scheme == "file" && uri.authority.isNullOrEmpty() && uri.query == null && uri.fragment == null) { "Emplacement de backup invalide." }
        val path = requireNotNull(uri.path)
        val marker = "/save-backups/"
        require(path.indexOf(marker) >= 0 && path.indexOf(marker) == path.lastIndexOf(marker)) { "Chemin de backup invalide." }
        val relative = path.substringAfter(marker)
        if (backupId != null && legacyId.matches(backupId) && relative == "duplicate-import/$backupId") {
            // The caller copies the uniquely referenced archive entry, never this legacy live path.
            return resolve(context, gameId, Uri.fromFile(File(directory(context, gameId), backupId)).toString())
        }
        require(relative.split('/').size == 2 && relative.substringBefore('/') == MkxpzSavePaths.ownerKey(gameId)) {
            "Ce backup n’appartient pas au jeu sélectionné."
        }
        return resolve(context, gameId, Uri.fromFile(File(context.filesDir, "save-backups/$relative")).toString())
    }
}
