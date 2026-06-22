package com.example.aircontimer.util

import android.content.Context
import android.content.SharedPreferences

object PreferenceManager {

    private const val PREFS_NAME = "aircon_timer_prefs"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_SCHEDULE_HOUR = "schedule_hour"
    private const val KEY_SCHEDULE_MINUTE = "schedule_minute"
    private const val KEY_AIRCON_NAME = "aircon_name"
    private const val KEY_TIMER_MINUTES = "timer_minutes"
    private const val KEY_REMOTE_PACKAGE = "remote_package"
    private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAutoEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_ENABLED, false)
    }

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
    }

    fun getScheduleHour(context: Context): Int {
        return getPrefs(context).getInt(KEY_SCHEDULE_HOUR, 2)
    }

    fun getScheduleMinute(context: Context): Int {
        return getPrefs(context).getInt(KEY_SCHEDULE_MINUTE, 0)
    }

    fun saveScheduleTime(context: Context, hour: Int, minute: Int) {
        getPrefs(context).edit()
            .putInt(KEY_SCHEDULE_HOUR, hour)
            .putInt(KEY_SCHEDULE_MINUTE, minute)
            .apply()
    }

    fun getAirconName(context: Context): String {
        return getPrefs(context)
            .getString(KEY_AIRCON_NAME, "\u534e\u51cc\u7a7a\u8c03\u9065\u63a7\u5668")
            .orEmpty()
            .ifBlank { "\u534e\u51cc\u7a7a\u8c03\u9065\u63a7\u5668" }
    }

    fun saveAirconName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_AIRCON_NAME, name).apply()
    }

    fun getTimerMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_TIMER_MINUTES, 60)
    }

    fun saveTimerMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_TIMER_MINUTES, minutes).apply()
    }

    fun getRemotePackage(context: Context): String? {
        return getPrefs(context).getString(KEY_REMOTE_PACKAGE, null)
    }

    fun saveRemotePackage(context: Context, packageName: String) {
        getPrefs(context).edit().putString(KEY_REMOTE_PACKAGE, packageName).apply()
    }

    fun getNextTriggerTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_NEXT_TRIGGER_TIME, 0L)
    }

    fun saveNextTriggerTime(context: Context, triggerTime: Long) {
        getPrefs(context).edit().putLong(KEY_NEXT_TRIGGER_TIME, triggerTime).apply()
    }

    fun clearNextTriggerTime(context: Context) {
        getPrefs(context).edit().remove(KEY_NEXT_TRIGGER_TIME).apply()
    }
}
