package fr.astragames.app.ui

import fr.astragames.app.AstraApplication
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.translation.GameTranslationManager
import fr.astragames.app.translation.TranslationAnalysis
import fr.astragames.app.translation.TranslationProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

internal data class GameTranslationState(
    val gameId: String? = null,
    val busy: Boolean = false,
    val analysis: TranslationAnalysis? = null,
    val progress: TranslationProgress? = null,
    val message: String? = null,
    val error: String? = null
)

internal class GameTranslationController(
    private val app: AstraApplication,
    private val scope: CoroutineScope,
    private val manager: GameTranslationManager = GameTranslationManager(app)
) {
    private val mutableState = MutableStateFlow(GameTranslationState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun analyze(game: GameEntity) = run(game, "Analyse des textes") {
        mutableState.update { it.copy(analysis = manager.analyze(game)) }
    }

    fun translate(game: GameEntity, source: String, target: String, wifiOnly: Boolean) = run(game, "Analyse des textes") {
        val installations = app.container.dao.observeInstallationsForGame(game.id).first()
        check(installations.isEmpty()) { "Désinstallez les mods avant de traduire ce jeu." }
        val result = manager.translate(game, source, target, wifiOnly) { progress ->
            mutableState.update { it.copy(progress = progress) }
        }
        mutableState.update { it.copy(analysis = result, message = if (result.installed) "Traduction appliquée" else "Aucun texte modifié") }
    }

    fun restore(game: GameEntity) = run(game, "Restauration des originaux") {
        manager.restore(game)
        mutableState.update { it.copy(analysis = manager.analyze(game), message = "Originaux restaurés") }
    }

    fun cancel() { job?.cancel() }

    private fun run(game: GameEntity, phase: String, block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        val previous = mutableState.value.takeIf { it.gameId == game.id }
        mutableState.value = GameTranslationState(game.id, busy = true, analysis = previous?.analysis, progress = TranslationProgress(phase))
        job = scope.launch {
            try { block() }
            catch (cancel: CancellationException) {
                mutableState.update { it.copy(message = "Interrompu. Les traductions en cache seront réutilisées.") }
                throw cancel
            }
            catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.message ?: "Traduction impossible") }
            }
            finally {
                withContext(NonCancellable) {
                    // Refresh the journal state even if applying or restoring was interrupted.
                    runCatching { manager.hasBackup(game) }.onSuccess { backup ->
                        if (backup) mutableState.update { it.copy(analysis = (it.analysis ?: TranslationAnalysis(0, 0, 0, true)).copy(installed = true)) }
                    }
                }
                mutableState.update { it.copy(busy = false, progress = null) }
            }
        }
    }
}
