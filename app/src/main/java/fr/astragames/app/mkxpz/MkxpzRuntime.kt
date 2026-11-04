package fr.astragames.app.mkxpz

import android.content.Context
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.core.model.LaunchResult
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.launcher.LaunchProfileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object MkxpzRuntime {
    const val LAUNCHER = "ASTRA_MKXPZ"
    private val launchLock = Mutex()
    val libraries = arrayOf("c++_shared", "SDL2", "SDL2_image", "SDL2_ttf", "SDL2_sound", "openal", "ruby", "mkxp-z")
    fun rgssVersion(engine: String): Int? = when (engine) {
        GameEngine.RPG_MAKER_XP.name -> 1
        GameEngine.RPG_MAKER_VX.name -> 2
        GameEngine.RPG_MAKER_VX_ACE.name -> 3
        else -> null
    }

    fun unavailableReason(context: Context): String? {
        val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        // The bundled NDK 29 runtime supports both sizes; do not assume larger future pages work.
        if (pageSize != 4096L && pageSize != 16384L) return "Ce prototype MKXP-Z prend en charge les pages de 4 et 16 Kio. La taille détectée ($pageSize octets) n'est pas prise en charge ; le lancement est désactivé."
        val absent = libraries.filterNot { File(context.applicationInfo.nativeLibraryDir, "lib$it.so").isFile }
        return if (absent.isEmpty()) null else
            "MKXP-Z indisponible pour cette installation (${android.os.Build.SUPPORTED_ABIS.joinToString()}) : ${absent.joinToString()}. Installez un APK contenant le moteur pour cette ABI."
    }

    suspend fun launch(context: Context, game: GameEntity, profile: LaunchProfileEntity?): LaunchResult = launchLock.withLock {
        val version = rgssVersion(LaunchProfileResolver.engine(game, profile))
            ?: return@withLock LaunchResult.Failure("MKXP-Z prend en charge les modes XP, VX et VX Ace uniquement.")
        unavailableReason(context)?.let { return@withLock LaunchResult.Failure(it) }
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        if (manager.runningAppProcesses.orEmpty().any { it.processName == "${context.packageName}:mkxpz" }) {
            return@withLock LaunchResult.Failure("Une session MKXP-Z est déjà ouverte. Quittez-la avant de relancer un jeu.")
        }
        try {
            val bootstrap = withContext(Dispatchers.IO) {
                MkxpzStorage(context, game.id).prepare(game, profile, version)
            }
            // The SDL process deliberately has no AppContainer/DataStore. Send only the
            // launch policy; credentials and authentication stay in the main process.
            val settings = (context.applicationContext as fr.astragames.app.AstraApplication)
                .container.settings.settings.first()
            val lockOnReturn = settings.lockOnBackground && (settings.lockPinEnabled || settings.lockBiometricEnabled)
            context.startActivity(MkxpzActivity.intent(context, bootstrap, lockOnReturn))
            LaunchResult.Success
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LaunchResult.Failure("Lancement MKXP-Z impossible : ${e.message}")
        }
    }
}
