package fr.astragames.app.data.backup

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Best-effort rollback attempts every file and never removes the preserved originals. */
internal object BackupRollback {
    fun restore(
        changedFiles: List<Pair<File, File?>>,
        copyFile: (File, File) -> Unit = { source, target -> source.copyTo(target, overwrite = true) },
        deleteFile: (File) -> Boolean = File::delete
    ): List<IOException> {
        val failures = mutableListOf<IOException>()
        changedFiles.asReversed().forEach { (destination, original) ->
            try {
                if (original == null) {
                    if (destination.exists()) {
                        check(deleteFile(destination) || !destination.exists()) { "Le nouveau fichier n’a pas pu être supprimé." }
                        check(!destination.exists()) { "Le nouveau fichier existe encore après suppression." }
                    }
                } else {
                    check(original.isFile) { "La copie originale est introuvable." }
                    copyFile(original, destination)
                    check(destination.isFile && original.length() == destination.length() &&
                        MessageDigest.isEqual(digest(original), digest(destination))) { "Le fichier restauré ne correspond pas à l’original." }
                }
            } catch (failure: Exception) {
                failures += IOException("Échec du retour arrière pour ${destination.absolutePath}", failure)
            }
        }
        return failures
    }

    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }
}
