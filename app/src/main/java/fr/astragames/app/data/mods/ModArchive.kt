package fr.astragames.app.data.mods

import fr.astragames.app.core.filesystem.copyBoundedTo
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

object ZipPathGuard {
    const val MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024
    const val MAX_FILES = 4000

    fun sanitize(entryName: String): String? {
        val path = entryName.replace('\\', '/')
        if (path.startsWith('/') || path.any { it.code < 32 || it == ':' }) return null
        val parts = path.trimEnd('/').split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }
}

/** Extract once into private staging; enforce limits while reading, before allocation or game writes. */
internal object ModArchive {
    fun extract(input: InputStream, destination: File): File {
        val paths = mutableSetOf<String>()
        var total = 0L
        var files = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(paths.size < ZipPathGuard.MAX_FILES) { "Trop de fichiers dans le ZIP." }
                val path = ZipPathGuard.sanitize(entry.name) ?: error("Chemin ZIP refuse : ${entry.name}")
                require(paths.add(path.lowercase(Locale.ROOT))) { "Chemin ZIP duplique : $path" }
                val target = File(destination, path)
                require(target.canonicalFile.toPath().startsWith(destination.canonicalFile.toPath())) { "Chemin ZIP invalide." }
                if (entry.isDirectory) {
                    // ZIP directory entries can contain data too; do not let closeEntry
                    // inflate an unlimited payload outside the archive budget.
                    total += zip.copyBoundedTo(DISCARD, ZipPathGuard.MAX_UNCOMPRESSED_BYTES - total)
                    check(target.isDirectory || target.mkdirs()) { "Dossier ZIP invalide." }
                } else {
                    files++
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Dossier ZIP invalide." }
                    target.outputStream().buffered().use { output ->
                        total += zip.copyBoundedTo(output, ZipPathGuard.MAX_UNCOMPRESSED_BYTES - total)
                    }
                }
            }
        }
        require(files > 0) { "Le ZIP ne contient aucun fichier." }
        return contentRoot(destination)
    }

    private val DISCARD = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }

    fun contentRoot(directory: File): File {
        var root = directory
        val contentFolders = setOf("game", "www", "js", "data", "img", "audio", "files", "fonts", "movies")
        repeat(8) {
            if (File(root, "astra-mod.json").isFile) return root
            val only = root.listFiles()?.singleOrNull() ?: return root
            if (!only.isDirectory || only.name.lowercase(Locale.ROOT) in contentFolders) return root
            root = only
        }
        return root
    }
}
