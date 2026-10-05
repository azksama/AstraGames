package fr.astragames.app.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import fr.astragames.app.AstraApplication
import fr.astragames.app.MainActivity
import java.util.concurrent.TimeUnit

class AppUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as AstraApplication).updates
        if (!manager.state.value.automatic) return Result.success()
        manager.check(downloadAutomatically = true)
        if (manager.notificationNeeded() && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            val notifications = applicationContext.getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Mises à jour d’Astra", NotificationManager.IMPORTANCE_DEFAULT))
            val pending = PendingIntent.getActivity(applicationContext, 811, Intent(applicationContext, MainActivity::class.java)
                .putExtra("openAppUpdates", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notifications.notify(811, NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Astra ${manager.state.value.release?.version}").setContentText("Mise à jour prête à installer.")
                .setContentIntent(pending).setAutoCancel(true).build())
            manager.markNotified()
        }
        // Network errors keep the normal periodic cadence instead of a tight retry loop.
        return Result.success()
    }
    companion object {
        private const val CHANNEL = "astra_app_updates"
        fun schedule(context: Context, enabled: Boolean, wifiOnly: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) { work.cancelUniqueWork(CHANNEL); return }
            work.enqueueUniquePeriodicWork(CHANNEL, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AppUpdateWorker>(12, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build())
        }
    }
}
