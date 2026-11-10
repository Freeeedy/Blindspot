package app.blindspot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.blindspot.R
import app.blindspot.ui.MainActivity

object Notifications {
    const val CHANNEL_MONITOR = "monitoring"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_MONITOR = 1
    const val ID_STOPPED = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Monitoring status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent notification while Blindspot is monitoring"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Monitoring problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shown when monitoring stops unexpectedly"
            }
        )
    }

    private fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun monitoring(ctx: Context, title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_blindspot)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(ctx))
            .addAction(0, "Stop monitoring", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun postStopped(ctx: Context, reason: String) {
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_blindspot)
            .setContentTitle("Blindspot monitoring stopped")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID_STOPPED, n)
        } catch (_: SecurityException) {
            // Notification permission denied; the in-app banner still shows the reason.
        }
    }

    fun cancelStopped(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_STOPPED)
}
