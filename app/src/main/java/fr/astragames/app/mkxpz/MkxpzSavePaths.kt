package fr.astragames.app.mkxpz

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/** The only private game paths save tools and catalogue archives may address. */
object MkxpzSavePaths {
    private val saveDirectoryName = Regex("[sS][aA][vV][eE]")
    fun ownerKey(gameId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(gameId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun isSaveFileName(name: String, engine: String): Boolean {
        if (name.isBlank() || name.any { it == '/' || it == '\\' || it == ':' || it == '%' || it.code < 32 || it.code == 127 }) return false
        val extension = when (engine) {
            "RPG_MAKER_XP" -> ".rxdata"
            "RPG_MAKER_VX" -> ".rvdata"
            "RPG_MAKER_VX_ACE" -> ".rvdata2"
            else -> return false
        }
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("save") && lower.endsWith(extension)
    }

    fun validRelative(relative: String, engine: String, directory: Boolean): Boolean {
        if (MkxpzRuntime.rgssVersion(engine) == null) return false
        if (directory) return relative == "." || saveDirectoryName.matches(relative)
        val parts = relative.split('/')
        return parts.size in 1..2 && (parts.size == 1 || saveDirectoryName.matches(parts.first())) &&
            isSaveFileName(parts.last(), engine)
    }

    fun resolve(context: Context, gameId: String, engine: String, relative: String, directory: Boolean): File {
        require(validRelative(relative, engine, directory)) { "Chemin de sauvegarde MKXP-Z non reconnu." }
        val root = MkxpzStorage(context, gameId).gameDirectory.absoluteFile
        val file = if (relative == ".") root else File(root, relative)
        requireNoSymlinks(context.filesDir, file)
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Sauvegarde MKXP-Z hors du jeu." }
        if (file.exists()) require(if (directory) file.isDirectory else file.isFile) { "Type de sauvegarde MKXP-Z invalide." }
        return file
    }

    /** Null means outside this game's directory; an unsafe path inside it is rejected. */
    fun relative(context: Context, gameId: String, engine: String, path: File, directory: Boolean): String? {
        val root = MkxpzStorage(context, gameId).gameDirectory.absoluteFile.toPath()
        val absolute = path.absoluteFile.toPath()
        if (!absolute.normalize().startsWith(root)) return null
        require(absolute == absolute.normalize()) { "Chemin de sauvegarde MKXP-Z non normalisé." }
        val relative = root.relativize(absolute).toString().replace(File.separatorChar, '/').ifEmpty { "." }
        resolve(context, gameId, engine, relative, directory)
        return relative
    }

    /** Check each app-controlled component, including dangling and in-root links. */
    fun requireNoSymlinks(root: File, target: File) {
        val base = root.absoluteFile.toPath().normalize()
        val path = target.absoluteFile.toPath()
        require(path == path.normalize() && path.startsWith(base)) { "Chemin privé hors de la zone autorisée." }
        var current = base
        require(!Files.isSymbolicLink(current)) { "Lien symbolique privé refusé." }
        base.relativize(path).forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Lien symbolique privé refusé." }
        }
    }
}
