package com.ahmedmunna.callrecorder.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ahmedmunna.callrecorder.MainActivity
import com.ahmedmunna.callrecorder.R

object Notifier {
    private fun contentIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun recording(ctx: Context, text: String, stopAction: PendingIntent?): Notification =
        NotificationCompat.Builder(ctx, Notifications.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording call")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent(ctx))
            .apply { if (stopAction != null) addAction(0, "Stop", stopAction) }
            .build()

    @SuppressLint("MissingPermission")
    fun alert(ctx: Context, id: Int, title: String, text: String) {
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val n = NotificationCompat.Builder(ctx, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    const val ID_FAILURE = Notifications.ID_ALERT_BASE + 1
    const val ID_LOW_STORAGE = Notifications.ID_ALERT_BASE + 2
    const val ID_STORAGE_LIMIT = Notifications.ID_ALERT_BASE + 3
    const val ID_CLEANUP = Notifications.ID_ALERT_BASE + 4
    const val ID_BACKUP = Notifications.ID_ALERT_BASE + 5
    const val ID_MISSED = Notifications.ID_ALERT_BASE + 6
}
