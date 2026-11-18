package fr.astragames.app.worker

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameUpdateScheduleTest {
    @Test fun systemNotificationReflectsThePermissionAndContainsTheVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "fixture-game-updates-${UUID.randomUUID()}"
        val id = 41_005
        val permitted = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        try {
            val posted = showUpdateNotification(context, channel, "Test Astra", id, "Version disponible", "Exemple (2.0)")
            assertEquals(permitted, posted)
            val limit = SystemClock.elapsedRealtime() + 3_000
            while (posted && manager.activeNotifications.none { it.id == id } && SystemClock.elapsedRealtime() < limit) {
                SystemClock.sleep(25)
            }
            val notification = manager.activeNotifications.singleOrNull { it.id == id }?.notification
            if (permitted) {
                assertEquals("Exemple (2.0)", notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT))
                assertNotNull(notification?.contentIntent)
            } else assertNull(notification)
            Log.i("AstraUpdateTest", "Android notifications permitted=$permitted posted=$posted")
        } finally {
            manager.cancel(id)
            manager.deleteNotificationChannel(channel)
        }
    }

    @Test fun repeatedLaunchKeepsPendingWorkAndChangingIntervalReplacesItsSchedule() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkManager.getInstance(context)
        val name = "fixture-updates-${UUID.randomUUID()}"
        val launchName = "$name-launch"
        val pending = OneTimeWorkRequestBuilder<GameUpdatesWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        fun active(key: String) = manager.getWorkInfosForUniqueWork(key).get(10, TimeUnit.SECONDS).filterNot { it.state.isFinished }
        try {
            manager.enqueueUniqueWork(launchName, ExistingWorkPolicy.KEEP, pending).result.get(10, TimeUnit.SECONDS)
            GameUpdatesWorker.schedule(context, "LAUNCH", name).result.get(10, TimeUnit.SECONDS)
            assertEquals(pending.id, active(launchName).single().id)
            GameUpdatesWorker.schedule(context, "DAY_3", name).result.get(10, TimeUnit.SECONDS)
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(pending.id).get(10, TimeUnit.SECONDS)?.state)
            val periodic = active(name).single()
            assertEquals(TimeUnit.DAYS.toMillis(3), periodic.periodicityInfo?.repeatIntervalMillis)
            assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
            GameUpdatesWorker.schedule(context, "DAY_15", name).result.get(10, TimeUnit.SECONDS)
            val updated = active(name).single()
            assertEquals(periodic.id, updated.id)
            assertEquals(TimeUnit.DAYS.toMillis(15), updated.periodicityInfo?.repeatIntervalMillis)
        } finally {
            manager.cancelUniqueWork(launchName).result.get(10, TimeUnit.SECONDS)
            manager.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
        }
    }
}
