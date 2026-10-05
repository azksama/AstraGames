package fr.astragames.app.data.backup

import fr.astragames.app.core.filesystem.copyBoundedTo
import fr.astragames.app.data.mods.ZipPathGuard
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Only catalog and managed assets are accepted; names are checked before any write. */
internal object BackupArchive {
    const val DATABASE_ENTRY = "database/astra_games.db"
    const val MAX_BYTES = 256L * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    val DIRECTORIES = listOf("covers", "save-backups", "mod-backups")

    fun write(output: OutputStream, files: Sequence<Pair<String, File>>, maxBytes: Long = MAX_BYTES, maxEntries: Int = MAX_ENTRIES) {
        require(maxBytes >= 0 && maxEntries >= 0)
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            files.forEach { (name, file) ->
                require(ZipPathGuard.sanitize(name) == name && allowedFile(name)) { "Chemin de sauvegarde invalide." }
                require(seen.size < maxEntries && seen.add(name.lowercase(Locale.ROOT))) { "Archive dupliquée ou trop volumineuse." }
                require(file.isFile) { "Le fichier à sauvegarder est introuvable." }
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().buffered().use { total += it.copyBoundedTo(zip, maxBytes - total) }
                zip.closeEntry()
            }
        }
    }

    fun extract(input: InputStream, destination: File, maxBytes: Long = MAX_BYTES, maxEntries: Int = MAX_ENTRIES) {
        require(maxBytes >= 0 && maxEntries >= 0)
        val root = destination.canonicalFile.toPath()
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = ZipPathGuard.sanitize(entry.name) ?: error("Chemin de sauvegarde invalide.")
                require(seen.size < maxEntries && seen.add(path.lowercase(Locale.ROOT))) { "Archive dupliquée ou trop volumineuse." }
                val allowedDirectory = path == "database" || DIRECTORIES.any { path == it || path.startsWith("$it/") }
                require(if (entry.isDirectory) allowedDirectory else allowedFile(path)) { "Contenu de sauvegarde non reconnu : $path" }
                if (entry.isDirectory) {
                    total += zip.copyBoundedTo(DISCARD, maxBytes - total)
                } else {
                    val output = File(destination, path)
                    require(output.canonicalFile.toPath().startsWith(root)) { "Archive non sûre." }
                    check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs()) { "Dossier de restauration inaccessible." }
                    output.outputStream().buffered().use { total += zip.copyBoundedTo(it, maxBytes - total) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun allowedFile(path: String) = path == DATABASE_ENTRY || DIRECTORIES.any { path.startsWith("$it/") }

    private val DISCARD = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }
}
