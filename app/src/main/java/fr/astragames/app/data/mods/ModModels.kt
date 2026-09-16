package fr.astragames.app.data.mods

import fr.astragames.app.data.local.ModEntity
import fr.astragames.app.data.local.ModInstallationEntity
import org.json.JSONObject
import java.util.Locale

enum class ModInstallMode { OVERLAY, COPY, REPLACE }
enum class ModFileAction { ADDED, REPLACED }
enum class ModStatus { AVAILABLE, INSTALLED, INCOMPATIBLE, ERROR }

data class AstraModManifest(
    val formatVersion: Int,
    val id: String,
    val name: String,
    val version: String?,
    val author: String?,
    val description: String?,
    val engines: List<String>,
    val installMode: ModInstallMode,
    val target: String?,
    val filesRoot: String
)

data class ModCatalogItem(
    val entity: ModEntity,
    val status: ModStatus,
    val installation: ModInstallationEntity?,
    val incompatibleReason: String? = null
)

data class ZipImportPreview(
    val suggestedName: String,
    val engineHint: String?,
    val hasManifest: Boolean,
    val fileCount: Int,
    val uncompressedBytes: Long
)

data class UninstallWarning(
    val relativePath: String,
    val reason: String
)

object AstraModManifestParser {
    fun parse(json: String): AstraModManifest {
        val obj = JSONObject(json)
        val engines = obj.optJSONArray("engines")
        val engineList = mutableListOf<String>()
        if (engines != null) {
            for (index in 0 until engines.length()) engineList += engines.optString(index)
        }
        require(obj.optInt("formatVersion", 1) == 1) { "Version du manifeste non prise en charge." }
        require(obj.getString("id").matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Identifiant du mod invalide." }
        require(obj.getString("name").isNotBlank()) { "Nom du mod vide." }
        listOf("target", "filesRoot").forEach { key ->
            val path = obj.optString(key)
            require(path.isBlank() || path == "." || ZipPathGuard.sanitize(path) != null) { "Chemin du manifeste invalide : $key" }
        }
        val mode = obj.optString("installMode", "OVERLAY").uppercase(Locale.ROOT)
        return AstraModManifest(
            formatVersion = obj.optInt("formatVersion", 1),
            id = obj.getString("id"),
            name = obj.getString("name"),
            version = obj.optString("version").ifBlank { null },
            author = obj.optString("author").ifBlank { null },
            description = obj.optString("description").ifBlank { null },
            engines = engineList.map(::normalizeEngine).filter { it.isNotBlank() },
            installMode = ModInstallMode.valueOf(mode),
            target = obj.optString("target").ifBlank { null },
            filesRoot = obj.optString("filesRoot", "files").ifBlank { "files" }
        )
    }
}

fun normalizeEngine(raw: String): String {
    val value = raw.trim().uppercase(Locale.ROOT).replace(" ", "_").replace("-", "_")
    return when (value) {
        "RENPY", "REN_PY", "REN'PY", "REN’PY" -> "RENPY"
        "RPGMAKERMV", "RPG_MAKER_MV", "MV" -> "RPG_MAKER_MV"
        "RPGMAKERMZ", "RPG_MAKER_MZ", "MZ" -> "RPG_MAKER_MZ"
        "RPGMAKERXP", "RPG_MAKER_XP", "XP" -> "RPG_MAKER_XP"
        "RPGMAKERVX", "RPG_MAKER_VX", "VX" -> "RPG_MAKER_VX"
        "RPGMAKERVXACE", "RPG_MAKER_VX_ACE", "VXACE", "VX_ACE" -> "RPG_MAKER_VX_ACE"
        "WOLFRPG", "WOLF_RPG" -> "WOLF_RPG"
        "OTHER", "UNKNOWN" -> "OTHER"
        else -> value
    }
}

fun engineFolderName(engine: String) = when (engine) {
    "RENPY" -> "RenPy"
    "RPG_MAKER_MV" -> "RPGMakerMV"
    "RPG_MAKER_MZ" -> "RPGMakerMZ"
    "RPG_MAKER_XP" -> "RPGMakerXP"
    "RPG_MAKER_VX" -> "RPGMakerVX"
    "RPG_MAKER_VX_ACE" -> "RPGMakerVXAce"
    "WOLF_RPG" -> "WolfRPG"
    else -> "Other"
}
