package fr.astragames.app.launcher

import android.content.Context
import fr.astragames.app.core.model.GameEngine

data class JoiPlayRuntimeInfo(
    val key: String,
    val name: String,
    val packageNames: List<String>,
    val engines: Set<GameEngine>,
    val installedPackage: String?,
    val versionName: String?,
    val required: Boolean,
    val downloadUrl: String
) {
    val installed: Boolean get() = installedPackage != null
}

class JoiPlayRuntimeManager {
    @Suppress("DEPRECATION")
    fun inspect(context: Context, libraryEngines: Set<GameEngine>): List<JoiPlayRuntimeInfo> = DEFINITIONS.map { definition ->
        val installed = definition.packages.firstNotNullOfOrNull { packageName ->
            runCatching { packageName to context.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        }
        JoiPlayRuntimeInfo(
            key = definition.key,
            name = definition.name,
            packageNames = definition.packages,
            engines = definition.engines,
            installedPackage = installed?.first,
            versionName = installed?.second,
            required = definition.engines.isEmpty() || definition.engines.any(libraryEngines::contains),
            downloadUrl = definition.downloadUrl
        )
    }

    private data class Definition(
        val key: String,
        val name: String,
        val packages: List<String>,
        val engines: Set<GameEngine>,
        val downloadUrl: String = "https://joiplay.cyou/"
    )

    companion object {
        private val DEFINITIONS = listOf(
            Definition("joiplay", "JoiPlay", listOf(JoiPlayLauncher.PACKAGE), emptySet()),
            Definition(
                "rpgmaker", "Plugin RPG Maker",
                listOf("cyou.joiplay.runtime.rpgmaker"),
                setOf(GameEngine.RPG_MAKER_MV, GameEngine.RPG_MAKER_MZ, GameEngine.RPG_MAKER_VX_ACE, GameEngine.RPG_MAKER_VX, GameEngine.RPG_MAKER_XP)
            ),
            Definition(
                "renpy", "Plugin Ren’Py",
                listOf("cyou.joiplay.runtime.renpy8", "cyou.joiplay.runtime.renpy"),
                setOf(GameEngine.RENPY)
            )
        )
    }
}
