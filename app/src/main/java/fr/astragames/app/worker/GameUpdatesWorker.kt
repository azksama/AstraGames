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
        settings.f95SessionXfUser?.let { user ->
            settings.f95SessionXfSession?.let { session ->
                app.container.f95Zone.setSession(F95Session(settings.f95SessionUser, user, session))
            }
        }
        val games = app.container.repository.games.first().filter { !it.f95Url.isNullOrBlank() && it.version != null }
        val found = mutableListOf<Pair<GameEntity, String>>()
        val latest = mutableMapOf<String, String>()
        games.forEach { game ->
            val url = game.f95Url ?: return@forEach
            val version = runCatching { app.container.f95Zone.fetchVersion(url) }.getOrNull()
            if (version != null && version != game.version) {
                latest[game.id] = version
                found += game to version
            }
            delay(1_000)
        }
        if (latest.isEmpty()) {
            app.container.settings.setF95LatestVersions("")
            return Result.success()
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
        return Result.success()
    }

    private fun notifyUpdates(items: List<Pair<String, String>>): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mises à jour de jeux", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = items.take(3).joinToString(", ") { "${it.first} (${it.second})" } + if (items.size > 3) " +${items.size - 3}" else ""
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Nouvelle version disponible")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
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
                    OneTimeWorkRequestBuilder<GameUpdatesWorker>().build()
                )
                return
            }
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
                PeriodicWorkRequestBuilder<GameUpdatesWorker>(days, TimeUnit.DAYS).build()
            )
        }
    }
}