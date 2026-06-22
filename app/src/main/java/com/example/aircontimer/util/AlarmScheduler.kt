package com.example.aircontimer.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.aircontimer.receiver.AlarmReceiver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    private const val REQUEST_CODE = 1001
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    sealed interface ScheduleResult {
        data class Scheduled(val triggerTime: Long) : ScheduleResult
        data object ExactAlarmPermissionRequired : ScheduleResult
    }

    fun scheduleDaily(context: Context, hour: Int, minute: Int): ScheduleResult {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            PreferenceManager.clearNextTriggerTime(context)
            PreferenceManager.setAutoEnabled(context, false)
            return ScheduleResult.ExactAlarmPermissionRequired
        }

        val triggerTime = calculateNextTriggerTime(hour, minute)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime,
            buildPendingIntent(context)
        )

        PreferenceManager.saveScheduleTime(context, hour, minute)
        PreferenceManager.saveNextTriggerTime(context, triggerTime)
        PreferenceManager.setAutoEnabled(context, true)

        Log.d(TAG, "Scheduled next trigger at ${dateFormatter.format(triggerTime)}")
        return ScheduleResult.Scheduled(triggerTime)
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()

        PreferenceManager.clearNextTriggerTime(context)
        PreferenceManager.setAutoEnabled(context, false)
    }

    fun getNextTriggerSummary(context: Context): String? {
        val triggerTime = PreferenceManager.getNextTriggerTime(context)
        if (triggerTime <= 0L) {
            return null
        }
        return formatTriggerTime(triggerTime)
    }

    fun formatTriggerTime(triggerTime: Long): String {
        return dateFormatter.format(triggerTime)
    }

    private fun calculateNextTriggerTime(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (!trigger.after(now)) {
            trigger.add(Calendar.DAY_OF_YEAR, 1)
        }

        return trigger.timeInMillis
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_AIRCON_ON
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
