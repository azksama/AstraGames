package fr.astragames.app.worker

import fr.astragames.app.core.runCatchingCancellable
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.astragames.app.AstraApplication
import fr.astragames.app.core.metadata.F95Session
import fr.astragames.app.data.local.GameEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Vérifie périodiquement les versions des threads F95Zone liés et notifie les nouvelles mises à jour. */
class GameUpdatesWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AstraApplication
        val settings = app.container.settings.settings.first()
        app.container.f95Zone.setSession(
            if (settings.f95SessionXfUser != null && settings.f95SessionXfSession != null)
                F95Session(settings.f95SessionUser, settings.f95SessionXfUser, settings.f95SessionXfSession)
            else null
        )
        val games = app.container.repository.games.first().filter { !it.f95Url.isNullOrBlank() && it.version != null }
        val found = mutableListOf<Pair<GameEntity, String>>()
        val latest = settings.f95LatestVersions.split('|').mapNotNull { entry ->
            val id = entry.substringBefore(':')
            val version = entry.substringAfter(':', "")
            if (id in games.map { it.id } && version.isNotBlank()) id to version else null
        }.toMap().toMutableMap()
        var failures = 0
        games.forEach { game ->
            val url = game.f95Url ?: return@forEach
            val version = runCatchingCancellable { app.container.f95Zone.fetchVersion(url) }.getOrNull()
            if (version == null) failures++ else latest.remove(game.id)
            if (version != null && version != game.version) {
                latest[game.id] = version
                found += game to version
            }
            delay(1_000)
        }
        if (latest.isEmpty()) {
            app.container.settings.setF95LatestVersions("")
            return if (failures > 0) Result.retry() else Result.success()
        }
        app.container.settings.setF95LatestVersions(latest.entries.joinToString("|") { "${it.key}:${it.value}" })
        val notified = settings.f95NotifiedUpdates.split("|").filter(String::isNotBlank).toSet()
        val newOnes = found.filter { (game, version) -> "${game.id}:$version" !in notified }
        if (newOnes.isNotEmpty()) {
            val fingerprint = newOnes.joinToString("|") { "${it.first.id}:${it.second}" }
            if (notifyUpdates(newOnes.map { it.first.title to it.second })) {
                app.container.settings.setF95NotifiedUpdates((notified + fingerprint).joinToString("|"))
            }
        }
        return if (failures > 0) Result.retry() else Result.success()
    }

    private fun notifyUpdates(items: List<Pair<String, String>>): Boolean {
        val detail = items.take(3).joinToString(", ") { "${it.first} (${it.second})" } + if (items.size > 3) " +${items.size - 3}" else ""
        return showUpdateNotification(applicationContext, CHANNEL_ID, "Mises à jour de jeux", NOTIFICATION_ID, "Nouvelle version disponible", detail)
    }

    companion object {
        private const val UNIQUE_NAME = "astra-game-updates"
        private const val CHANNEL_ID = "game_updates"
        private const val NOTIFICATION_ID = 2_107

        fun schedule(context: Context, interval: String = "DAY_7") {
            val manager = WorkManager.getInstance(context)
            if (interval == "LAUNCH") {
                manager.cancelUniqueWork(UNIQUE_NAME)
                manager.enqueueUniqueWork(
                    "$UNIQUE_NAME-launch",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<GameUpdatesWorker>().setConstraints(networkConstraints()).build()
                )
                return
            }
            manager.cancelUniqueWork("$UNIQUE_NAME-launch")
            val days = when (interval) {
                "DAY_1" -> 1L
                "DAY_3" -> 3L
                "DAY_15" -> 15L
                "DAY_30" -> 30L
                else -> 7L
            }
            manager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<GameUpdatesWorker>(days, TimeUnit.DAYS).setConstraints(networkConstraints()).build()
            )
        }
    }
}
