package fr.astragames.app.windows

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** The selected private snapshot is separate from the source hash used for safe publication. */
internal object WolfRestoredSavePaths {
    private val folders = setOf("save", "saves", "savedata", "savefiles", "save_mtool")
    private val excluded = setOf("exe", "dll", "so", "rb", "rbw", "js", "py", "bat", "cmd", "ps1", "sh", "ini", "lnk", "url", "tmp", "log")

    private fun valid(path: String): Boolean {
        val parts = path.split('/')
        if (parts.size > 63 || parts.any { it.isBlank() || it == "." || it == ".." || it.startsWith(".astra-") ||
                it.any { ch -> ch == '\\' || ch == ':' || ch == '%' || ch.code < 32 || ch.code == 127 } }) return false
        val extension = parts.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension !in excluded && (extension in setOf("sav", "save") ||
            parts.dropLast(1).any { it.lowercase(Locale.ROOT) in folders })
    }

    fun read(mode: JSONObject, directory: File, source: String): Set<String> {
        if (!mode.optBoolean("restoredArchive") || mode.optString("source") != source) return emptySet()
        require(mode.optString("mode") == "COPY") { "Mode du snapshot Wolf invalide." }
        require(!mode.has("restoredOwner") || mode.getString("restoredOwner") == directory.name) {
            "Le snapshot Wolf appartient à un autre jeu."
        }
        if (mode.has("restoredPaths")) {
            val paths = mode.getJSONArray("restoredPaths")
            return buildSet {
                for (index in 0 until paths.length()) {
                    val path = paths.getString(index)
                    require(valid(path)) { "Chemin du snapshot Wolf invalide." }
                    add(path)
                }
            }
        }
        // Upgrade restores made before explicit path protection. Their private saves are
        // still authoritative even when their bytes equal the old source baseline.
        val game = File(directory, "game")
        return game.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .map { it.relativeTo(game).invariantSeparatorsPath }.filter(::valid).toSet()
    }

    fun write(mode: JSONObject, directory: File, paths: Set<String>): JSONObject {
        require(paths.all(::valid)) { "Chemin du snapshot Wolf invalide." }
        return mode.put("restoredOwner", directory.name).put("restoredPaths", JSONArray(paths.sorted()))
    }
}
