package com.example.aircontimer

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.aircontimer.databinding.ActivityMainBinding
import com.example.aircontimer.service.AutoClickService
import com.example.aircontimer.service.KeepAliveService
import com.example.aircontimer.util.AlarmScheduler
import com.example.aircontimer.util.NotificationHelper
import com.example.aircontimer.util.PreferenceManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1002
    }

    private lateinit var binding: ActivityMainBinding
    private var selectedScheduleHour = 2
    private var selectedScheduleMinute = 0

    private val timerOptions by lazy {
        (30..720 step 30).map { minutes ->
            minutes to formatTimerMinutes(minutes)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createChannels(this)
        initViews()
        loadSavedSettings()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
    }

    private fun initViews() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.contentRoot.updatePadding(
                top = statusInsets.top + dpToPx(10),
                bottom = navigationInsets.bottom + dpToPx(18)
            )
            insets
        }

        binding.tvAccessibilityHint.text =
            getString(R.string.accessibility_service_hint, getAccessibilityServiceDisplayName())

        binding.spinnerTimer.setAdapter(
            ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            timerOptions.map { it.second }
            )
        )
        binding.spinnerTimer.setOnClickListener { binding.spinnerTimer.showDropDown() }

        binding.btnScheduleTime.setOnClickListener { showScheduleTimePicker() }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnEnable.setOnClickListener { enableAutoTask() }
        binding.btnDisable.setOnClickListener { disableAutoTask() }
        binding.btnTest.setOnClickListener { runImmediateTest() }
        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnBattery.setOnClickListener { requestBatteryOptimization() }
    }

    private fun loadSavedSettings() {
        selectedScheduleHour = PreferenceManager.getScheduleHour(this)
        selectedScheduleMinute = PreferenceManager.getScheduleMinute(this)
        updateScheduleTimeButton()
        binding.etAirconName.setText(PreferenceManager.getAirconName(this))

        val timerMinutes = PreferenceManager.getTimerMinutes(this)
        val selectedIndex = timerOptions.indexOfFirst { it.first == timerMinutes }
            .takeIf { it >= 0 }
            ?: timerOptions.indexOfFirst { it.first > timerMinutes }.takeIf { it >= 0 }
            ?: 0
        binding.spinnerTimer.setText(timerOptions[selectedIndex].second, false)
    }

    private fun saveSettings() {
        val airconName = binding.etAirconName.text.toString().trim()
        if (airconName.isEmpty()) {
            showToast(R.string.error_aircon_name_required)
            return
        }
        val (scheduleHour, scheduleMinute) = readSelectedScheduleTime()

        PreferenceManager.saveAirconName(this, airconName)
        PreferenceManager.saveScheduleTime(this, scheduleHour, scheduleMinute)
        PreferenceManager.saveTimerMinutes(this, getSelectedTimerMinutes())

        showToast(R.string.settings_saved)
        updateStatusUI()
    }

    private fun enableAutoTask() {
        val airconName = binding.etAirconName.text.toString().trim()
        if (airconName.isEmpty()) {
            showToast(R.string.error_aircon_name_required)
            return
        }

        if (!isAccessibilityEnabled()) {
            Toast.makeText(
                this,
                getString(
                    R.string.accessibility_required_first,
                    getAccessibilityServiceDisplayName()
                ),
                Toast.LENGTH_LONG
            ).show()
            openAccessibilitySettings()
            return
        }
        val (scheduleHour, scheduleMinute) = readSelectedScheduleTime()

        PreferenceManager.saveAirconName(this, airconName)
        PreferenceManager.saveScheduleTime(this, scheduleHour, scheduleMinute)
        PreferenceManager.saveTimerMinutes(this, getSelectedTimerMinutes())

        val scheduleResult = AlarmScheduler.scheduleDaily(this, scheduleHour, scheduleMinute)
        val triggerTime = when (scheduleResult) {
            is AlarmScheduler.ScheduleResult.ExactAlarmPermissionRequired -> {
                requestExactAlarmPermission()
                showToast(R.string.exact_alarm_permission_required)
                updateStatusUI()
                return
            }

            is AlarmScheduler.ScheduleResult.Scheduled -> scheduleResult.triggerTime
        }

        if (!KeepAliveService.start(this)) {
            showToast(R.string.keep_alive_service_start_failed)
        }

        Toast.makeText(
            this,
            getString(
                R.string.auto_task_enabled,
                AlarmScheduler.formatTriggerTime(triggerTime)
            ),
            Toast.LENGTH_LONG
        ).show()
        updateStatusUI()
    }

    private fun disableAutoTask() {
        AlarmScheduler.cancel(this)
        KeepAliveService.stop(this)
        showToast(R.string.auto_task_disabled)
        updateStatusUI()
    }

    private fun runImmediateTest() {
        val airconName = binding.etAirconName.text.toString().trim()
        if (airconName.isEmpty()) {
            showToast(R.string.error_aircon_name_required)
            return
        }

        if (!isAccessibilityEnabled()) {
            Toast.makeText(
                this,
                getString(
                    R.string.accessibility_required_first,
                    getAccessibilityServiceDisplayName()
                ),
                Toast.LENGTH_LONG
            ).show()
            openAccessibilitySettings()
            return
        }

        PreferenceManager.saveAirconName(this, airconName)
        PreferenceManager.saveTimerMinutes(this, getSelectedTimerMinutes())

        showToast(R.string.test_will_start)
        binding.root.postDelayed({
            val intent = Intent(this, AutoClickService::class.java).apply {
                action = AutoClickService.ACTION_START_AUTO
                putExtra(AutoClickService.EXTRA_AIRCON_NAME, airconName)
                putExtra(AutoClickService.EXTRA_TIMER_MINUTES, getSelectedTimerMinutes())
            }
            startService(intent)
        }, 3000L)
    }

    private fun updateStatusUI() {
        val isEnabled = PreferenceManager.isAutoEnabled(this)
        val isAccessibilityOn = isAccessibilityEnabled()
        val nextRun = AlarmScheduler.getNextTriggerSummary(this)

        binding.tvStatus.text = when {
            isEnabled && isAccessibilityOn && nextRun != null -> {
                getString(R.string.status_enabled_with_time, nextRun)
            }
            isEnabled && isAccessibilityOn -> getString(R.string.status_enabled)
            isEnabled -> getString(
                R.string.status_waiting_accessibility,
                getAccessibilityServiceDisplayName()
            )
            else -> getString(R.string.status_disabled)
        }

        binding.btnEnable.isEnabled = !isEnabled
        binding.btnDisable.isEnabled = isEnabled
        binding.btnTest.isEnabled = isAccessibilityOn
    }

    private fun checkPermissions() {
        if (!isAccessibilityEnabled()) {
            showAccessibilityDialog()
        }

        requestNotificationPermissionIfNeeded()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                requestExactAlarmPermission()
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val accessibilityManager =
            getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val targetServiceName = AutoClickService::class.java.name

        val enabledServiceList = accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        if (enabledServiceList.any { serviceInfo ->
                val service = serviceInfo.resolveInfo.serviceInfo
                service.packageName == packageName && service.name == targetServiceName
            }
        ) {
            return true
        }

        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val fullServiceName = "$packageName/$targetServiceName"
        val shortServiceName = "$packageName/.service.AutoClickService"

        return enabledServicesSetting.split(':').any { item ->
            item.equals(fullServiceName, ignoreCase = true) ||
                item.equals(shortServiceName, ignoreCase = true)
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_dialog_title)
            .setMessage(
                getString(
                    R.string.accessibility_dialog_message,
                    getAccessibilityServiceDisplayName()
                )
            )
            .setPositiveButton(R.string.go_enable) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(
            this,
            getString(
                R.string.accessibility_open_settings_hint,
                getAccessibilityServiceDisplayName()
            ),
            Toast.LENGTH_LONG
        ).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun getAccessibilityServiceDisplayName(): String {
        return getString(R.string.accessibility_service_label)
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS
        )
    }

    private fun readSelectedScheduleTime(): Pair<Int, Int> {
        return selectedScheduleHour to selectedScheduleMinute
    }

    private fun requestBatteryOptimization() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, R.string.battery_optimization_manual_hint, Toast.LENGTH_LONG)
                    .show()
            }
    }

    private fun getSelectedTimerMinutes(): Int {
        val selectedLabel = binding.spinnerTimer.text?.toString().orEmpty()
        return timerOptions.firstOrNull { it.second == selectedLabel }?.first ?: 60
    }

    private fun showToast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun formatTimerMinutes(minutes: Int): String {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60

        return when {
            minutes == 30 -> getString(R.string.timer_30m)
            remainingMinutes == 0 -> getString(R.string.timer_hour_format, hours)
            else -> getString(R.string.timer_hour_half_format, hours, remainingMinutes)
        }
    }

    private fun showScheduleTimePicker() {
        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                selectedScheduleHour = hourOfDay
                selectedScheduleMinute = minute
                updateScheduleTimeButton()
            },
            selectedScheduleHour,
            selectedScheduleMinute,
            true
        ).show()
    }

    private fun updateScheduleTimeButton() {
        binding.btnScheduleTime.text = getString(
            R.string.schedule_time_value,
            selectedScheduleHour,
            selectedScheduleMinute
        )
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
