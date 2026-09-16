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
import fr.astragames.app.launcher.JoiPlayCatalogProvider
import fr.astragames.app.launcher.JoiPlayRuntimeManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class JoiPlayUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AstraApplication
        val settings = app.container.settings.settings.first()
        var raw = settings.joiPlayCatalogJson
        val stale = raw.isBlank() || System.currentTimeMillis() - settings.joiPlayCatalogFetchedAt >= JoiPlayCatalogProvider.CACHE_DURATION_MS
        if (stale) {
            val fetched = runCatchingCancellable { app.container.joiPlayCatalog.fetchRaw() }
            if (fetched.isSuccess) {
                raw = fetched.getOrThrow()
                app.container.settings.cacheJoiPlayCatalog(raw)
            } else if (raw.isBlank()) {
                return Result.retry()
            }
        }

        val catalog = app.container.joiPlayCatalog.parse(raw)
        val updates = JoiPlayRuntimeManager().inspect(applicationContext, emptySet(), catalog)
            .filter { it.installed && it.updateAvailable }
        val fingerprint = updates.joinToString("|") { "${it.key}:${it.latestVersion}" }
        if (updates.isNotEmpty() && fingerprint != settings.joiPlayNotifiedVersions) {
            if (notifyUpdates(updates.map { it.name })) {
                app.container.settings.setJoiPlayNotifiedVersions(fingerprint)
            }
        } else if (updates.isEmpty() && settings.joiPlayNotifiedVersions.isNotEmpty()) {
            app.container.settings.setJoiPlayNotifiedVersions("")
        }
        return Result.success()
    }

    private fun notifyUpdates(names: List<String>): Boolean {
        val detail = names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else ""
        return showUpdateNotification(applicationContext, CHANNEL_ID, "Mises à jour JoiPlay", NOTIFICATION_ID, "Mise à jour JoiPlay disponible", detail)
    }

    companion object {
        private const val UNIQUE_NAME = "astra-joiplay-update-check"
        private const val CHANNEL_ID = "joiplay_updates"
        private const val NOTIFICATION_ID = 2_106

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<JoiPlayUpdateWorker>(1, TimeUnit.DAYS).setConstraints(networkConstraints()).build()
            )
            manager.enqueueUniqueWork(
                "$UNIQUE_NAME-initial",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<JoiPlayUpdateWorker>().setConstraints(networkConstraints()).build()
            )
        }
    }
}
