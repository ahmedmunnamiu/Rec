package com.ahmedmunna.callrecorder.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_PLAYBACK = "playback"
    const val ID_RECORDING = 1001
    const val ID_ALERT_BASE = 2000

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Active recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a call is being recorded"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Recording alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Recording failures, low storage and backup results"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
