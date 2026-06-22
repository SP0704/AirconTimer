package com.example.aircontimer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.aircontimer.service.AutoClickService
import com.example.aircontimer.service.KeepAliveService
import com.example.aircontimer.util.AlarmScheduler
import com.example.aircontimer.util.PreferenceManager

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "AlarmReceiver"
        const val ACTION_AIRCON_ON = "com.example.aircontimer.ACTION_AIRCON_ON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AIRCON_ON) {
            return
        }

        Log.d(TAG, "Received scheduled trigger")

        val hour = PreferenceManager.getScheduleHour(context)
        val minute = PreferenceManager.getScheduleMinute(context)
        when (AlarmScheduler.scheduleDaily(context, hour, minute)) {
            is AlarmScheduler.ScheduleResult.ExactAlarmPermissionRequired -> {
                Log.w(TAG, "Exact alarm permission missing, next daily trigger was not scheduled")
            }

            is AlarmScheduler.ScheduleResult.Scheduled -> Unit
        }

        KeepAliveService.start(context)

        val serviceIntent = Intent(context, AutoClickService::class.java).apply {
            action = AutoClickService.ACTION_START_AUTO
            putExtra(AutoClickService.EXTRA_AIRCON_NAME, PreferenceManager.getAirconName(context))
            putExtra(
                AutoClickService.EXTRA_TIMER_MINUTES,
                PreferenceManager.getTimerMinutes(context)
            )
        }
        context.startService(serviceIntent)
    }
}
