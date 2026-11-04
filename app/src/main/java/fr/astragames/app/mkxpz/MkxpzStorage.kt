package fr.astragames.app.mkxpz

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.launcher.LaunchProfileResolver
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Import once, never overwrite a running game's files or its saves with the source. */
class MkxpzStorage(private val context: Context, gameId: String) {
    val directory = File(context.filesDir, "mkxpz-games/" + MessageDigest.getInstance("SHA-256")
        .digest(gameId.toByteArray()).joinToString("") { "%02x".format(it) })
    val gameDirectory = File(directory, "game")
    val isImported: Boolean get() = File(directory, "import-complete").isFile

    fun prepare(game: GameEntity, profile: LaunchProfileEntity?, version: Int): File {
        require(version in 1..3)
        val executable = LaunchProfileResolver.executable(game, profile) ?: "Game.exe"
        require(executable == File(executable).name && !executable.contains('\\')) { "Le fichier d'entrée doit être dans le dossier du jeu." }
        val execName = executable.substringBeforeLast('.')
        require(execName.isNotBlank() && execName != "." && execName != "..")
        directory.mkdirs()
        val marker = File(directory, "import-complete")
        if (!marker.isFile) {
            val source = LaunchProfileResolver.physicalPath(game, profile)?.let(::File)
                ?.takeIf { it.isDirectory && it.canRead() }?.let(DocumentFile::fromFile)
                ?: documentDir(context, Uri.parse(game.documentUri))
                ?: error("Dossier source inaccessible. Réautorisez-le dans Astra.")
            require(source.uri != Uri.fromFile(gameDirectory)) { "Le dossier source est la copie interne." }
            val expectedIni = "$execName.ini"
            val sourceIni = findIniName(source.listFiles().mapNotNull { it.name }, expectedIni)
            // An interrupted import may already have normalized GAME.INI to Game.ini.
            // Reuse the single private INI whatever its case; never copy the source back
            // over it or recreate a second case variant on retry. Reject real collisions
            // before copying any further files, including in an unfinished private copy.
            val privateIni = findIniName(gameDirectory.listFiles().orEmpty().map { it.name }, expectedIni)
            copyMissing(source, gameDirectory, 0, sourceIni, privateIni ?: expectedIni)
            prepareNativeIni(execName)
            File(directory, "import-source.txt").writeText(source.uri.toString())
            marker.writeText("1")
        }
        prepareNativeIni(execName)
        val bootstrap = File(directory, "bootstrap").apply { mkdirs() }
        val config = JSONObject().apply {
            put("rubyLoadpath", org.json.JSONArray(MkxpzRuby.loadPaths(context)))
            put("gameFolder", gameDirectory.absolutePath)
            put("execName", execName)
            put("rgssVersion", version)
            put("windowTitle", game.title)
            put("RTP", org.json.JSONArray())
            put("enableSettings", false)
            put("JITEnable", false)
            put("fixedAspectRatio", true)
            put("vsync", true)
        }
        File(bootstrap, "mkxp.json").writeText(config.toString(2))
        return bootstrap
    }

    private fun findIniName(names: Iterable<String>, expected: String): String? {
        val matches = names.filter { it.equals(expected, ignoreCase = true) }.sorted()
        require(matches.size <= 1) { "Fichier INI ambigu pour $expected : ${matches.joinToString(", ")}." }
        return matches.singleOrNull()
    }

    private fun prepareNativeIni(execName: String) {
        val expected = "$execName.ini"
        val name = findIniName(gameDirectory.listFiles().orEmpty().map { it.name }, expected)
        val candidate = name?.let { File(gameDirectory, it) }
        require(candidate?.isFile == true) { "$expected absent de la copie privée du jeu." }
        if (name != expected) {
            // Config::readGameINI uses fopen(execName + ".ini") before the VFS.
            // Keep execName (also used for RGSS archives) and change only the private INI name.
            // Request no replacement; ambiguous case variants were rejected above.
            java.nio.file.Files.move(candidate.toPath(), File(gameDirectory, expected).toPath())
        }
    }

    private fun copyMissing(source: DocumentFile, target: File, depth: Int,
                            sourceIni: String? = null, privateIni: String? = null) {
        require(depth <= 48) { "Dossier de jeu trop profond." }
        check(target.mkdirs() || target.isDirectory)
        for (child in source.listFiles()) {
            val name = requireNotNull(child.name)
            require(name != "." && name != ".." && '/' !in name && '\\' !in name) { "Nom de fichier non sûr." }
            val dest = File(target, if (depth == 0 && name == sourceIni) requireNotNull(privateIni) else name)
            if (child.uri.scheme == "file") {
                val original = File(requireNotNull(child.uri.path))
                require(!java.nio.file.Files.isSymbolicLink(original.toPath())) { "Lien symbolique non importé : $name" }
                require(!original.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "Import récursif de la copie interne refusé." }
            }
            if (child.isDirectory) copyMissing(child, dest, depth + 1)
            else if (child.isFile && !dest.exists()) {
                require(context.filesDir.usableSpace > child.length() + 64L * 1024 * 1024) { "Espace insuffisant pour la copie privée." }
                val temporary = File.createTempFile("import-", ".part", directory)
                try {
                    context.contentResolver.openInputStream(child.uri).use { input ->
                        requireNotNull(input) { "Lecture impossible : $name" }
                        temporary.outputStream().use { input.copyTo(it) }
                    }
                    check(temporary.renameTo(dest)) { "Import impossible : $name" }
                } finally { temporary.delete() }
            }
        }
    }
}
