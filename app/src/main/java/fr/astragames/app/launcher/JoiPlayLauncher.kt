package fr.astragames.app.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.core.model.LaunchResult
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import org.json.JSONObject

object JoiPlayPayloadBuilder {
    fun typeFor(engine: String): String? = when (runCatching { GameEngine.valueOf(engine) }.getOrNull()) {
        GameEngine.RPG_MAKER_MV -> "rpgmmv"
        GameEngine.RPG_MAKER_MZ -> "rpgmmz"
        GameEngine.RPG_MAKER_VX_ACE -> "rpgmvxace"
        GameEngine.RPG_MAKER_VX -> "rpgmvx"
        GameEngine.RPG_MAKER_XP -> "rpgmxp"
        GameEngine.HTML5 -> "html"
        GameEngine.TYRANO -> "tyrano"
        GameEngine.CONSTRUCT -> "construct"
        GameEngine.TWINE -> "twine"
        GameEngine.ELECTRON -> "electron"
        GameEngine.RENPY -> "renpy"
        else -> null
    }

    fun actionFor(engine: String): String? = typeFor(engine)?.let { "cyou.joiplay.runtime.$it.run" }

    fun actionsFor(engine: String): List<String> = if (engine == GameEngine.RENPY.name) {
        listOf("cyou.joiplay.runtime.renpy8.run", "cyou.joiplay.runtime.renpy.run")
    } else listOfNotNull(actionFor(engine))

    internal fun trustedPackagesFor(engine: String): Set<String> = when (typeFor(engine)) {
        "renpy" -> setOf("cyou.joiplay.runtime.renpy", "cyou.joiplay.runtime.renpy8")
        "rpgmmv", "rpgmmz", "rpgmvxace", "rpgmvx", "rpgmxp" ->
            setOf("cyou.joiplay.runtime.rpgmaker", JoiPlayLauncher.PACKAGE)
        "html", "tyrano", "construct", "twine", "electron" -> setOf(JoiPlayLauncher.PACKAGE)
        else -> emptySet()
    }

    fun build(game: GameEntity, profile: LaunchProfileEntity? = null): String? {
        val type = typeFor(LaunchProfileResolver.engine(game, profile)) ?: return null
        val folder = LaunchProfileResolver.physicalPath(game, profile) ?: return null
        return JSONObject().apply {
            put("title", game.title)
            put("id", "astra${game.id.filter(Char::isLetterOrDigit)}")
            put("folder", folder)
            put("execFile", LaunchProfileResolver.executable(game, profile).orEmpty())
            put("type", type)
            put("scoped", false)
            put("playCount", game.playCount)
            put("isFolder", false)
        }.toString()
    }
}

object LaunchProfileResolver {
    fun engine(game: GameEntity, profile: LaunchProfileEntity?) = profile?.engineOverride?.takeIf(String::isNotBlank) ?: game.engine
    fun executable(game: GameEntity, profile: LaunchProfileEntity?) = profile?.executableName?.takeIf(String::isNotBlank) ?: game.executableName
    fun physicalPath(game: GameEntity, profile: LaunchProfileEntity?) = profile?.physicalPath?.takeIf(String::isNotBlank) ?: game.physicalPath
    fun launcherType(game: GameEntity, profile: LaunchProfileEntity?) = profile?.launcherType?.takeIf(String::isNotBlank) ?: game.launcher
}

class JoiPlayLauncher : GameLauncher {
    override fun supports(game: GameEntity) = game.engine == GameEngine.WOLF_RPG.name || JoiPlayPayloadBuilder.typeFor(game.engine) != null

    override suspend fun launch(context: Context, game: GameEntity): LaunchResult {
        return launch(context, game, null)
    }

    suspend fun launch(context: Context, game: GameEntity, profile: LaunchProfileEntity?): LaunchResult {
        if (LaunchProfileResolver.launcherType(game, profile) == "EXTERNAL") return launchExternal(context, game, profile)
        if (LaunchProfileResolver.engine(game, profile) == GameEngine.WOLF_RPG.name) {
            val executable = LaunchProfileResolver.executable(game, profile) ?: "Game.exe"
            val runtime = fr.astragames.app.windows.WolfGameOptions(context, game.id).read().runtime
            val intent = if (runtime == fr.astragames.app.windows.WolfRuntimeMode.WINLATOR)
                fr.astragames.app.windows.WolfRuntimeActivity.intent(context, game.id, game.documentUri, executable, game.title)
            else fr.astragames.app.wolfnative.WolfNativeActivity.intent(context, game.id, game.documentUri, executable, game.title)
            return start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val payload = JoiPlayPayloadBuilder.build(game, profile)
            ?: return LaunchResult.Failure("Le chemin physique du jeu n'est pas accessible à JoiPlay")
        val effectiveEngine = LaunchProfileResolver.engine(game, profile)
        val resolvedIntent = resolveJoiPlayIntent(context, effectiveEngine)
            ?: return LaunchResult.Failure(
                if (effectiveEngine == GameEngine.RENPY.name) "Installez ou mettez à jour le plugin Ren'Py de JoiPlay"
                else "JoiPlay ou le runtime requis n'est pas installé"
            )
        val intent = resolvedIntent.apply {
            putExtra("game", payload)
            putExtra("settings", "{}")
            profile?.arguments?.takeIf(String::isNotBlank)?.let { putExtra("arguments", it) }
            putStringArrayListExtra("preloadScripts", arrayListOf())
            putStringArrayListExtra("postloadScripts", arrayListOf())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, intent)
    }

    fun resolveJoiPlayIntent(context: Context, engine: String): Intent? =
        JoiPlayPayloadBuilder.actionsFor(engine).firstNotNullOfOrNull { action ->
            val base = Intent(action).addCategory(Intent.CATEGORY_DEFAULT)
            val trusted = JoiPlayPayloadBuilder.trustedPackagesFor(engine)
            val target = context.packageManager.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo }
                .filter { it.exported && it.enabled && it.packageName in trusted }
                .maxByOrNull { it.packageName }
            target?.let { base.setComponent(ComponentName(it.packageName, it.name)) }
        }

    private fun launchExternal(context: Context, game: GameEntity, profile: LaunchProfileEntity?): LaunchResult {
        val action = profile?.customAction?.takeIf(String::isNotBlank)
            ?: return LaunchResult.Failure("Renseignez l'action Android du lanceur externe")
        val intent = Intent(action).apply {
            profile.packageName?.takeIf(String::isNotBlank)?.let(::setPackage)
            putExtra("gamePath", LaunchProfileResolver.physicalPath(game, profile))
            putExtra("executable", LaunchProfileResolver.executable(game, profile))
            putExtra("arguments", profile.arguments)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, intent)
    }

    private fun start(context: Context, intent: Intent): LaunchResult = try {
            context.startActivity(intent)
            LaunchResult.Success
        } catch (_: ActivityNotFoundException) {
            LaunchResult.Failure("L'application ou le runtime configuré n'est pas installé")
        } catch (error: SecurityException) {
            LaunchResult.Failure(error.message ?: "Le lanceur a refusé le lancement")
        }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
    }.isSuccess

    companion object { const val PACKAGE = "cyou.joiplay.joiplay" }
}
