package com.example.aircontimer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.aircontimer.service.KeepAliveService
import com.example.aircontimer.util.AlarmScheduler
import com.example.aircontimer.util.PreferenceManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        if (!PreferenceManager.isAutoEnabled(context)) {
            return
        }

        val hour = PreferenceManager.getScheduleHour(context)
        val minute = PreferenceManager.getScheduleMinute(context)
        Log.d(TAG, "Re-scheduling daily alarm after boot: $hour:$minute")
        when (AlarmScheduler.scheduleDaily(context, hour, minute)) {
            is AlarmScheduler.ScheduleResult.ExactAlarmPermissionRequired -> {
                Log.w(TAG, "Exact alarm permission missing after boot, skipped re-schedule")
            }

            is AlarmScheduler.ScheduleResult.Scheduled -> {
                KeepAliveService.start(context)
            }
        }
    }
}
