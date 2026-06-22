package com.example.aircontimer.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.aircontimer.MainActivity
import com.example.aircontimer.R

object NotificationHelper {

    const val SERVICE_CHANNEL_ID = "aircon_timer_channel"
    private const val COMPLETION_CHANNEL_ID = "aircon_completion_channel"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            context.getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.service_channel_description)
            setShowBadge(false)
        }

        val completionChannel = NotificationChannel(
            COMPLETION_CHANNEL_ID,
            context.getString(R.string.completion_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.completion_channel_description)
        }

        manager.createNotificationChannels(listOf(serviceChannel, completionChannel))
    }

    fun showCompletionNotification(context: Context) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.completion_notification_title))
            .setContentText(context.getString(R.string.completion_notification_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(2001, notification)
    }

    fun showDebugNotification(context: Context, text: String) = Unit

    fun clearDebugNotification(context: Context) = Unit
}
