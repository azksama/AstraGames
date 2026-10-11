package fr.astragames.app.launcher

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.saves.documentDir
import java.io.File

/** The official plugins discover lowercase .pck files in the game folder themselves. */
internal object GodotLaunchValidation {
    fun error(context: Context, game: GameEntity, profile: LaunchProfileEntity?): String? {
        if (LaunchProfileResolver.engine(game, profile) !in setOf("GODOT_3", "GODOT_4") ||
            LaunchProfileResolver.launcherType(game, profile) == "EXTERNAL") return null
        val path = LaunchProfileResolver.physicalPath(game, profile) ?: return null
        val source = if (path == game.physicalPath && Uri.parse(game.documentUri).scheme == "content") runCatching {
            documentDir(context, Uri.parse(game.documentUri))?.takeIf { it.canRead() }
        }.getOrNull() else null
        if (source == null && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() &&
            runCatching { File(path).canonicalFile.toPath().startsWith(Environment.getExternalStorageDirectory().canonicalFile.toPath()) }.getOrDefault(false)) {
            return null // Scoped storage can hide non-media files even from a successful directory listing.
        }
        val names = if (source != null) runCatching {
            source.listFiles().filterNot { it.isDirectory }.mapNotNull { it.name }
        }.getOrNull() else File(path).listFiles()?.filter { it.isFile }?.map { it.name }
        // File.canRead() can be true while scoped storage denies listFiles(). An unreadable folder
        // is not an empty folder: let JoiPlay use its own storage permission in this case.
        if (names == null) return null
        val packs = names.filter { it.endsWith(".pck", true) }
        return when {
            packs.isEmpty() -> "Le plugin Godot JoiPlay nécessite un pack .pck séparé dans le dossier du jeu. Exportez le projet ou extrayez le pack intégré à l’exécutable avant le lancement."
            packs.size > 1 -> "Ce dossier contient plusieurs packs .pck. Le plugin Godot JoiPlay les choisit automatiquement ; placez le jeu et son pack principal dans un dossier dédié pour lancer le bon jeu."
            !packs.single().endsWith(".pck") -> "Le plugin Godot JoiPlay attend l’extension .pck en minuscules. Renommez l’extension du pack avant le lancement."
            else -> null
        }
    }
}
