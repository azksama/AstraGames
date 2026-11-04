package fr.astragames.app.data.saves

import android.content.Context
import android.net.Uri
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.SaveBackupEntity
import fr.astragames.app.mkxpz.MkxpzRuntime
import fr.astragames.app.mkxpz.MkxpzSavePaths
import fr.astragames.app.mkxpz.MkxpzStorage
import java.io.File

enum class SaveStorage { MKXPZ_PRIVATE, SOURCE }

/** Null activity means the external launcher's save-location policy is not known to Astra. */
data class SaveProvenance(val storage: SaveStorage, val activeForCurrentLauncher: Boolean?)

data class SaveBackupDetails(val backup: SaveBackupEntity, val provenance: SaveProvenance?)

/** Presentation only: SaveManager still authorizes every read, edit and restoration separately. */
internal class SaveProvenanceResolver(private val context: Context) {
    fun describe(game: GameEntity, sourceUri: String): SaveProvenance? {
        val nativeSelected = game.launcher == MkxpzRuntime.LAUNCHER && MkxpzRuntime.rgssVersion(game.engine) != null
        val uri = Uri.parse(sourceUri)
        if (uri.scheme == "file" && uri.path != null) {
            val file = File(requireNotNull(uri.path))
            val root = MkxpzStorage(context, game.id).gameDirectory.absoluteFile.toPath()
            if (file.absoluteFile.toPath().normalize().startsWith(root)) {
                val recognized = nativeSelected && runCatching {
                    MkxpzSavePaths.relative(context, game.id, game.engine, file, directory = false) != null
                }.getOrDefault(false)
                return SaveProvenance(SaveStorage.MKXPZ_PRIVATE, recognized)
            }
        }
        if (MkxpzRuntime.rgssVersion(game.engine) == null) return null
        return SaveProvenance(SaveStorage.SOURCE, if (nativeSelected) false else null)
    }
}
