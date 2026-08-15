package fr.astragames.app.launcher

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.core.model.CompatibilityCheck
import fr.astragames.app.core.model.CompatibilitySeverity
import fr.astragames.app.core.model.GameCompatibilityReport
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import java.io.File

class CompatibilityDiagnostic(private val launcher: JoiPlayLauncher) {
    fun inspect(context: Context, game: GameEntity, profile: LaunchProfileEntity?): GameCompatibilityReport {
        val checks = mutableListOf<CompatibilityCheck>()
        val engine = LaunchProfileResolver.engine(game, profile)
        val path = LaunchProfileResolver.physicalPath(game, profile)
        val executable = LaunchProfileResolver.executable(game, profile)
        val launcherType = LaunchProfileResolver.launcherType(game, profile)

        checks += when {
            engine == GameEngine.UNKNOWN.name && launcherType == "EXTERNAL" -> warning("Moteur", "Moteur inconnu, lancement délégué à l'application externe")
            engine == GameEngine.UNKNOWN.name -> error("Moteur", "Moteur inconnu : choisissez-le dans le profil de lancement")
            JoiPlayPayloadBuilder.typeFor(engine) == null && launcherType != "EXTERNAL" -> error("Moteur", "${engine.readable()} n'est pas pris en charge par JoiPlay")
            else -> ok("Moteur", engine.readable())
        }

        val uriAccessible = runCatching {
            val uri = Uri.parse(game.documentUri)
            when (uri.scheme) {
                "content" -> DocumentFile.fromSingleUri(context, uri)?.let { it.exists() && it.canRead() } == true
                "file" -> uri.path?.let(::File)?.exists() == true
                else -> false
            }
        }.getOrDefault(false)
        checks += when {
            uriAccessible -> ok("Dossier", "Le dossier source est accessible à Astra")
            path?.let(::File)?.exists() == true -> warning("Dossier", "Le chemin physique existe, mais l'autorisation Android du dossier doit être renouvelée")
            else -> error("Dossier", "Dossier inaccessible : rescanner ou sélectionner à nouveau la source")
        }

        checks += when {
            path.isNullOrBlank() -> error("Chemin JoiPlay", "Aucun chemin physique disponible")
            executable.isNullOrBlank() -> error("Fichier d'entrée", "Aucun exécutable ou fichier d'entrée configuré")
            File(path, executable).exists() -> ok("Fichier d'entrée", executable)
            uriAccessible -> warning("Fichier d'entrée", "$executable n'est pas vérifiable directement par Android ; utilisez Tester")
            else -> error("Fichier d'entrée", "$executable est introuvable dans $path")
        }

        checks += if (launcherType == "EXTERNAL") {
            if (profile?.customAction.isNullOrBlank()) error("Lanceur", "Action Android externe manquante")
            else ok("Lanceur", listOfNotNull(profile?.packageName, profile?.customAction).joinToString(" • "))
        } else {
            when {
                launcher.resolveJoiPlayIntent(context, engine) != null -> ok("Runtime JoiPlay", "Runtime ${engine.readable()} détecté")
                engine == GameEngine.RENPY.name -> error("Runtime JoiPlay", "Plugin Ren'Py absent ou incompatible")
                else -> error("Runtime JoiPlay", "Runtime requis absent ou incompatible")
            }
        }

        if (game.missing) checks += warning("État du catalogue", "Le dernier scan avait marqué ce jeu comme absent")
        val blocking = checks.count { it.severity == CompatibilitySeverity.ERROR }
        return GameCompatibilityReport(
            gameId = game.id,
            canLaunch = blocking == 0,
            summary = if (blocking == 0) "Configuration prête à être testée" else "$blocking problème(s) bloquant(s) détecté(s)",
            checks = checks
        )
    }

    private fun ok(label: String, detail: String) = CompatibilityCheck(label, detail, CompatibilitySeverity.OK)
    private fun warning(label: String, detail: String) = CompatibilityCheck(label, detail, CompatibilitySeverity.WARNING)
    private fun error(label: String, detail: String) = CompatibilityCheck(label, detail, CompatibilitySeverity.ERROR)
}

private fun String.readable() = lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
