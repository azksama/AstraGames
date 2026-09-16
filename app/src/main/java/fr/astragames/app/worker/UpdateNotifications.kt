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
import fr.astragames.app.MainActivity

internal fun showUpdateNotification(
    context: Context,
    channelId: String,
    channelName: String,
    notificationId: Int,
    title: String,
    detail: String
): Boolean {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return false
    val system = context.getSystemService(NotificationManager::class.java)
    system.createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT))
    if (system.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return false
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val pendingIntent = PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(title)
        .setContentText(detail)
        .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()
    return try {
        manager.notify(notificationId, notification)
        true
    } catch (_: SecurityException) {
        false
    }
}
