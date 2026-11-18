package fr.astragames.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.astragames.app.AstraApplication
import fr.astragames.app.core.metadata.F95Session
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Vérifie les fiches F95Zone et Ryuugames liées et signale les nouvelles versions. */
class GameUpdatesWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as AstraApplication
        val settings = app.container.settings.settings.first()
        app.container.f95Zone.setSession(
            if (settings.f95SessionXfUser != null && settings.f95SessionXfSession != null)
                F95Session(settings.f95SessionUser, settings.f95SessionXfUser, settings.f95SessionXfSession)
            else null
        )
        val result = app.container.gameVersionChecker.check(includeUnversioned = false, notify = ::notifyUpdates)
        if (result.failures > 0) Result.retry() else Result.success()
    } catch (_: java.io.IOException) {
        Result.retry()
    } catch (_: android.database.sqlite.SQLiteException) {
        Result.retry()
    }

    private fun notifyUpdates(items: List<Pair<String, String>>): Boolean {
        val detail = items.take(3).joinToString(", ") { "${it.first} (${it.second})" } + if (items.size > 3) " +${items.size - 3}" else ""
        return showUpdateNotification(applicationContext, CHANNEL_ID, "Mises à jour de jeux", NOTIFICATION_ID, "Nouvelle version disponible", detail)
    }

    companion object {
        private const val UNIQUE_NAME = "astra-game-updates"
        private const val CHANNEL_ID = "game_updates"
        private const val NOTIFICATION_ID = 2_107

        internal fun schedule(context: Context, interval: String = "DAY_7", workName: String = UNIQUE_NAME): Operation {
            val manager = WorkManager.getInstance(context)
            if (interval == "LAUNCH") {
                manager.cancelUniqueWork(workName)
                return manager.enqueueUniqueWork(
                    "$workName-launch",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<GameUpdatesWorker>().setConstraints(networkConstraints()).build()
                )
            }
            manager.cancelUniqueWork("$workName-launch")
            val days = when (interval) {
                "DAY_1" -> 1L
                "DAY_3" -> 3L
                "DAY_15" -> 15L
                "DAY_30" -> 30L
                else -> 7L
            }
            return manager.enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<GameUpdatesWorker>(days, TimeUnit.DAYS).setConstraints(networkConstraints()).build()
            )
        }
    }
}
