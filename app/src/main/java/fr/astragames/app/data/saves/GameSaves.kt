package fr.astragames.app.data.saves

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSaveLocationEntity
import java.util.Locale
import java.util.UUID

/** Une sauvegarde détectée pour un jeu. */
data class GameSave(
    val uri: String,
    val name: String,
    val slot: Int?,
    val engine: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val title: String? = null,
    val screenshotUri: String? = null,
    val auto: Boolean = false
)

/** Données d'une sauvegarde, lisibles par l'éditeur. */
sealed interface SaveData {
    /** Sauvegarde JSON (RPG Maker MV/MZ), éditable. */
    data class JsonSave(val json: org.json.JSONObject) : SaveData
    /** Extraction lecture seule (pickle Ren'Py, marshal RGSS). */
    data class RawSave(val entries: List<SaveEntry>) : SaveData
}

/** Une valeur reconnue dans une sauvegarde. */
data class SaveEntry(
    val path: String,
    val type: SaveEntryType,
    val displayValue: String,
    val editable: Boolean,
    val favoriteKey: String = path
)

enum class SaveEntryType { STRING, INT, FLOAT, BOOLEAN, SWITCH, VARIABLE, LIST, OBJECT, UNKNOWN }

/** Détection et énumération des sauvegardes sur le disque via SAF. */
class SaveFinder(private val context: Context) {

    fun locations(game: GameEntity, rootUri: Uri): List<GameSaveLocationEntity> {
        val root = documentDir(context, rootUri) ?: return emptyList()
        val found = mutableListOf<GameSaveLocationEntity>()
        fun walk(folder: DocumentFile, path: String, depth: Int) {
            if (depth > 4) return
            val children = runCatching { folder.listFiles().toList() }.getOrDefault(emptyList())
            val name = folder.name.orEmpty().lowercase(Locale.ROOT)
            val type = when {
                children.any { isSaveFile(it.name.orEmpty(), game.engine) } -> typeFor(game.engine)
                name == "saves" && children.any { it.name.orEmpty().endsWith(".save") } -> "RENPY"
                else -> null
            }
            if (type != null) found += GameSaveLocationEntity(
                id = UUID.randomUUID().toString(),
                gameId = game.id,
                uri = folder.uri.toString(),
                type = type,
                displayName = path.ifBlank { folder.name.orEmpty() },
                autoDetected = true,
                enabled = true,
                addedAt = System.currentTimeMillis()
            )
            children.filter(DocumentFile::isDirectory).forEach { walk(it, listOf(path, it.name.orEmpty()).filter(String::isNotBlank).joinToString("/"), depth + 1) }
        }
        walk(root, "", 0)
        return found
    }

    fun saves(game: GameEntity, locations: List<GameSaveLocationEntity>): List<GameSave> {
        val result = mutableListOf<GameSave>()
        locations.filter { it.enabled }.forEach { location ->
            val folder = documentDir(context, Uri.parse(location.uri)) ?: return@forEach
            runCatching { folder.listFiles().toList() }.getOrDefault(emptyList())
                .filter { it.isFile && isSaveFile(it.name.orEmpty(), game.engine) }
                .forEach { file ->
                    val name = file.name.orEmpty()
                    result += GameSave(
                        uri = file.uri.toString(),
                        name = name,
                        slot = name.filter(Char::isDigit).take(3).toIntOrNull(),
                        engine = game.engine,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        auto = name.startsWith("auto", ignoreCase = true)
                    )
                }
        }
        return result.distinctBy { it.uri }.sortedWith(compareBy({ it.slot ?: Int.MAX_VALUE }, { it.name }))
    }

    private fun typeFor(engine: String) = when (engine) {
        "RENPY" -> "RENPY"
        "RPG_MAKER_MV" -> "RPG_MAKER"
        "RPG_MAKER_MZ" -> "RPG_MAKER"
        "RPG_MAKER_XP", "RPG_MAKER_VX", "RPG_MAKER_VX_ACE" -> "RPG_MAKER"
        "WOLF_RPG" -> "WOLF_RPG"
        else -> "CUSTOM"
    }

    private fun isSaveFile(name: String, engine: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return when (engine) {
            "RPG_MAKER_MV", "RPG_MAKER_MZ" -> lower.endsWith(".rpgsave") || lower.endsWith(".rmmzsave")
            "RPG_MAKER_XP" -> lower.endsWith(".rxdata") && lower.startsWith("save", ignoreCase = true)
            "RPG_MAKER_VX" -> lower.endsWith(".rvdata") && lower.startsWith("save", ignoreCase = true)
            "RPG_MAKER_VX_ACE" -> lower.endsWith(".rvdata2") && lower.startsWith("save", ignoreCase = true)
            "RENPY" -> lower.endsWith(".save") && !lower.equals("persistent")
            else -> lower.endsWith(".save") || lower.endsWith(".sav") || lower.endsWith(".rpgsave")
        }
    }
}
