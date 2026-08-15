package fr.astragames.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.astragames.app.AstraApplication
import java.util.concurrent.TimeUnit

class LibraryScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val app = applicationContext as AstraApplication
        app.container.repository.scanAll()
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        private const val UNIQUE_NAME = "astra-periodic-library-scan"

        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<LibraryScanWorker>(1, TimeUnit.DAYS).build()
            manager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
