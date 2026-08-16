package fr.astragames.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.astragames.app.AstraApplication
import fr.astragames.app.MainActivity
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
            val fetched = runCatching { app.container.joiPlayCatalog.fetchRaw() }
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
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mises à jour JoiPlay", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else ""
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Mise à jour JoiPlay disponible")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Nouvelles versions disponibles : $detail"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
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
                PeriodicWorkRequestBuilder<JoiPlayUpdateWorker>(1, TimeUnit.DAYS).build()
            )
            manager.enqueueUniqueWork(
                "$UNIQUE_NAME-initial",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<JoiPlayUpdateWorker>().build()
            )
        }
    }
}
