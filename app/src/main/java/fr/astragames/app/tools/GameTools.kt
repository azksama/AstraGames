package fr.astragames.app.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.vector.ImageVector
import fr.astragames.app.data.local.GameEntity

/** Abstraction d'un outil contextuel proposé depuis la fiche d'un jeu. */
interface GameTool {
    val id: String
    val title: String
    val description: String?
    val icon: ImageVector
    val section: String

    fun isAvailable(game: GameEntity): Boolean
}

object SaveFolderTool : GameTool {
    override val id = "save_folder"
    override val title = "Dossier des sauvegardes"
    override val description = "Voir, ouvrir ou définir les emplacements de sauvegarde"
    override val icon = Icons.Default.Folder
    override val section = "Sauvegardes"
    override fun isAvailable(game: GameEntity) = true
}

object SaveEditorTool : GameTool {
    override val id = "save_editor"
    override val title = "Éditer une sauvegarde"
    override val description = "Modifier argent, niveau, variables et switches"
    override val icon = Icons.Default.Edit
    override val section = "Sauvegardes"
    override fun isAvailable(game: GameEntity) = game.engine in SUPPORTED_ENGINES

    val SUPPORTED_ENGINES = setOf(
        "RENPY", "RPG_MAKER_MV", "RPG_MAKER_MZ",
        "RPG_MAKER_XP", "RPG_MAKER_VX", "RPG_MAKER_VX_ACE"
    )
}

object SaveBackupTool : GameTool {
    override val id = "save_backups"
    override val title = "Backups"
    override val description = "Restaurer ou supprimer les backups de sauvegardes"
    override val icon = Icons.Default.History
    override val section = "Sauvegardes"
    override fun isAvailable(game: GameEntity) = true
}

object ModsManagerTool : GameTool {
    override val id = "mods"
    override val title = "Gérer les mods"
    override val description = "Installer ou désinstaller des mods compatibles"
    override val icon = Icons.Default.Build
    override val section = "Mods"
    override fun isAvailable(game: GameEntity) = true
}

object OpenGameFolderTool : GameTool {
    override val id = "open_folder"
    override val title = "Ouvrir le dossier du jeu"
    override val description = null
    override val icon = Icons.AutoMirrored.Filled.OpenInNew
    override val section = "Jeu"
    override fun isAvailable(game: GameEntity) = true
}

object RescanGameTool : GameTool {
    override val id = "rescan"
    override val title = "Rescanner le jeu"
    override val description = "Vérifier la présence et mettre à jour la fiche"
    override val icon = Icons.Default.Refresh
    override val section = "Jeu"
    override fun isAvailable(game: GameEntity) = true
}

/** Registre des outils disponibles sur la fiche d'un jeu. */
object GameToolsRegistry {
    private val tools = listOf(
        SaveFolderTool, SaveEditorTool, SaveBackupTool,
        ModsManagerTool, OpenGameFolderTool, RescanGameTool
    )

    fun availableFor(game: GameEntity): List<GameTool> = tools.filter { it.isAvailable(game) }

    fun sectionsFor(game: GameEntity): List<Pair<String, List<GameTool>>> =
        availableFor(game).groupBy { it.section }.entries.map { it.key to it.value }
}
