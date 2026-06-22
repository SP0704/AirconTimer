package com.example.aircontimer.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.aircontimer.util.NotificationHelper
import com.example.aircontimer.util.PreferenceManager
import java.io.File

class AutoClickService : AccessibilityService() {

    companion object {
        const val TAG = "AutoClickService"
        const val ACTION_START_AUTO = "com.example.aircontimer.ACTION_START_AUTO"
        const val EXTRA_AIRCON_NAME = "aircon_name"
        const val EXTRA_TIMER_MINUTES = "timer_minutes"

        private val POSSIBLE_PACKAGES = listOf(
            "com.vivo.remote",
            "com.vivo.smartremote",
            "com.android.remote",
            "com.iqoo.remote"
        )

        private val POWER_LABELS = listOf(
            "\u7535\u6e90",
            "\u7a7a\u8c03\u5f00\u5173",
            "power"
        )
        private val TIMER_LABELS = listOf(
            "\u5b9a\u65f6",
            "\u5012\u8ba1\u65f6",
            "timer"
        )
        private const val TIMER_SEEKBAR_ID = "com.vivo.vhome:id/hour_data"
        private const val CONFIRM_BUTTON_ID = "android:id/button1"
        private val CONFIRM_LABELS = listOf(
            "\u786e\u5b9a",
            "\u786e\u8ba4",
            "\u5b8c\u6210"
        )
        private const val EVENT_PROGRESS_DELAY_MS = 260L
        private const val TIMER_EVENT_PROGRESS_DELAY_MS = 120L
        private const val REMOTE_APP_OPEN_DELAY_MS = 1500L
        private const val WAIT_REMOTE_HOME_DELAY_MS = 350L
        private const val AFTER_AIRCON_CARD_CLICK_DELAY_MS = 900L
        private const val AFTER_POWER_CLICK_DELAY_MS = 620L
        private const val AFTER_TIMER_CLICK_DELAY_MS = 920L
        private const val FIRST_TIMER_CHANGE_TIMEOUT_MS = 820L
        private const val TIMER_CHANGE_TIMEOUT_MS = 900L
        private const val TIMER_POST_CHANGE_DELAY_MS = 140L
        private const val TIMER_SETTLE_DELAY_MS = 320L
        private const val TIMER_CONFIRM_DELAY_MS = 260L
        private const val RETRY_DELAY_MS = 700L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentStep = Step.IDLE
    private var targetAirconName = ""
    private var timerMinutes = 60
    private var retryCount = 0
    private var timerScrollAttempts = 0
    private var pendingTargetConfirmation = false
    private var awaitingTimerChange = false
    private var timerBeforeScrollMinutes: Int? = null
    private var timerWaitStartedAt = 0L

    private enum class Step {
        IDLE,
        WAIT_REMOTE_HOME,
        CLICK_AIRCON_CARD,
        CLICK_POWER,
        CLICK_TIMER,
        SET_TIMER_TIME,
        CONFIRM_TIMER,
        FINISHED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 150
            packageNames = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || currentStep == Step.IDLE || currentStep == Step.FINISHED) {
            return
        }

        val delay = if (currentStep == Step.SET_TIMER_TIME && awaitingTimerChange) {
            TIMER_EVENT_PROGRESS_DELAY_MS
        } else {
            EVENT_PROGRESS_DELAY_MS
        }
        queueProgress(delay)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        if (currentStep == Step.IDLE || currentStep == Step.FINISHED) {
            resetState()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_AUTO) {
            targetAirconName = intent.getStringExtra(EXTRA_AIRCON_NAME)
                ?.takeIf { it.isNotBlank() }
                ?: PreferenceManager.getAirconName(this)
            timerMinutes = intent.getIntExtra(
                EXTRA_TIMER_MINUTES,
                PreferenceManager.getTimerMinutes(this)
            ).coerceIn(30, 720)
            startAutomation()
        }
        return START_STICKY
    }

    private fun startAutomation() {
        clearDebugTrace()
        Log.d(TAG, "Start automation: target=$targetAirconName timer=$timerMinutes")
        debugStatus("开始执行：目标“$targetAirconName”，定时 ${timerMinutes} 分钟")
        retryCount = 0
        timerScrollAttempts = 0
        pendingTargetConfirmation = false
        awaitingTimerChange = false
        timerBeforeScrollMinutes = null
        timerWaitStartedAt = 0L
        currentStep = Step.WAIT_REMOTE_HOME
        openRemoteApp()
        handler.removeCallbacksAndMessages(null)
        queueProgress(REMOTE_APP_OPEN_DELAY_MS)
    }

    private fun openRemoteApp() {
        val packageManager = packageManager
        val candidatePackages = buildList {
            PreferenceManager.getRemotePackage(this@AutoClickService)?.let { add(it) }
            addAll(POSSIBLE_PACKAGES)
        }.distinct()

        for (pkg in candidatePackages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(launchIntent) }
                .onSuccess {
                    PreferenceManager.saveRemotePackage(this, pkg)
                    Log.d(TAG, "Opened remote package: $pkg")
                    debugStatus("已打开遥控应用：$pkg")
                    return
                }
                .onFailure { Log.w(TAG, "Failed opening $pkg", it) }
        }

        Log.e(TAG, "Unable to open any supported remote package")
        debugStatus("未能打开支持的遥控应用")
    }

    private val progressRunnable = Runnable { handleCurrentStep() }

    private fun handleCurrentStep() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            scheduleRetry("root window unavailable")
            return
        }

        val success = when (currentStep) {
            Step.WAIT_REMOTE_HOME -> {
                debugStatus("已进入等待遥控主页，准备查找空调卡片")
                currentStep = Step.CLICK_AIRCON_CARD
                retryCount = 0
                queueProgress(WAIT_REMOTE_HOME_DELAY_MS)
                true
            }

            Step.CLICK_AIRCON_CARD -> clickAirconCard(rootNode)
            Step.CLICK_POWER -> clickPowerButton(rootNode)
            Step.CLICK_TIMER -> clickTimerButton(rootNode)
            Step.SET_TIMER_TIME -> setTimerTime(rootNode)
            Step.CONFIRM_TIMER -> confirmTimer(rootNode)
            Step.IDLE, Step.FINISHED -> true
        }

        rootNode.recycle()

        if (!success) {
            scheduleRetry("step=$currentStep")
        }
    }

    private fun clickAirconCard(rootNode: AccessibilityNodeInfo): Boolean {
        val candidate = rootNode
            .findAccessibilityNodeInfosByText(targetAirconName)
            .firstNotNullOfOrNull { node -> findClickableParent(node) ?: node.takeIf { it.isClickable } }

        if (candidate != null && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            debugStatus("已点击空调卡片：$targetAirconName")
            advanceStep(Step.CLICK_POWER, AFTER_AIRCON_CARD_CLICK_DELAY_MS)
            return true
        }

        val fuzzyMatch = findNodeByTextRecursive(rootNode, targetAirconName)
        val clickable = findClickableParent(fuzzyMatch)
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            debugStatus("已模糊匹配并点击空调卡片：$targetAirconName")
            advanceStep(Step.CLICK_POWER, AFTER_AIRCON_CARD_CLICK_DELAY_MS)
            return true
        }

        return false
    }

    private fun clickPowerButton(rootNode: AccessibilityNodeInfo): Boolean {
        return clickByLabel(rootNode, POWER_LABELS) {
            debugStatus("已点击电源按钮")
            advanceStep(Step.CLICK_TIMER, AFTER_POWER_CLICK_DELAY_MS)
        }
    }

    private fun clickTimerButton(rootNode: AccessibilityNodeInfo): Boolean {
        return clickByLabel(rootNode, TIMER_LABELS) {
            debugStatus("已点击定时按钮")
            advanceStep(Step.SET_TIMER_TIME, AFTER_TIMER_CLICK_DELAY_MS)
        }
    }

    private fun setTimerTime(rootNode: AccessibilityNodeInfo): Boolean {
        val targetLabels = timerLabelsForMinutes(timerMinutes)
        val currentCentered = findCenteredTimerMinutes(rootNode)

        if (awaitingTimerChange) {
            val timeoutMs = if (timerScrollAttempts <= 1) {
                FIRST_TIMER_CHANGE_TIMEOUT_MS
            } else {
                TIMER_CHANGE_TIMEOUT_MS
            }
            val changed = currentCentered != null && currentCentered != timerBeforeScrollMinutes
            if (changed) {
                debugStatus(
                    "检测到滚轮值变化：${
                        timerBeforeScrollMinutes?.let { describeMinutes(it) } ?: "未知"
                    } -> ${describeMinutes(currentCentered)}"
                )
                awaitingTimerChange = false
                timerBeforeScrollMinutes = null
                timerWaitStartedAt = 0L
                queueProgress(TIMER_POST_CHANGE_DELAY_MS)
                return true
            } else {
                val waitElapsed = SystemClock.uptimeMillis() - timerWaitStartedAt
                if (waitElapsed < timeoutMs) {
                    debugStatus(
                        "滚轮手势已发出，等待界面变化（${waitElapsed}ms），当前仍为 ${
                            currentCentered?.let { describeMinutes(it) } ?: "未知"
                        }"
                    )
                    queueProgress(TIMER_EVENT_PROGRESS_DELAY_MS)
                    return true
                }

                debugStatus(
                    "等待滚轮变化超时，准备重试；当前仍为 ${
                        currentCentered?.let { describeMinutes(it) } ?: "未知"
                    }"
                )
                awaitingTimerChange = false
                timerBeforeScrollMinutes = null
                timerWaitStartedAt = 0L
            }
        }

        if (isTargetTimerSelected(rootNode)) {
            if (!pendingTargetConfirmation) {
                pendingTargetConfirmation = true
                debugStatus("滚轮已到目标值：${describeMinutes(timerMinutes)}，等待停稳后再确认")
                queueProgress(TIMER_SETTLE_DELAY_MS)
                return true
            }

            debugStatus("滚轮中间已稳定在目标值：${describeMinutes(timerMinutes)}，准备点确定")
            timerScrollAttempts = 0
            pendingTargetConfirmation = false
            advanceStep(Step.CONFIRM_TIMER, TIMER_CONFIRM_DELAY_MS)
            return true
        }
        pendingTargetConfirmation = false

        if (selectVisibleTimerOption(rootNode, targetLabels)) {
            debugStatus("已点击可见的定时文本，等待滚轮刷新")
            queueProgress(TIMER_SETTLE_DELAY_MS)
            return true
        }

        timerScrollAttempts += 1
        val direction = resolveTimerScrollDirection(rootNode, timerMinutes)
        val pickerNodes = getAllNodes(rootNode).filter { node ->
            node.isScrollable || isNonSeekBarPickerNode(node)
        }

        val performedScrollOnPicker = pickerNodes.any { node ->
            when (direction) {
                ScrollDirection.FORWARD -> {
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
                        performPickerGesture(node, ScrollDirection.FORWARD)
                }

                ScrollDirection.BACKWARD -> {
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ||
                        performPickerGesture(node, ScrollDirection.BACKWARD)
                }
            }
        }

        val performedScroll = performedScrollOnPicker ||
            performTimerWheelGesture(rootNode, direction)

        debugStatus(
            "滚轮尝试 ${timerScrollAttempts} 次：当前中间值 ${
                currentCentered?.let { describeMinutes(it) } ?: "未知"
            }，目标 ${describeMinutes(timerMinutes)}，方向 ${
                if (direction == ScrollDirection.FORWARD) "向下翻到更大值" else "向上翻到更小值"
            }，手势结果=${if (performedScroll) "已发送" else "未发送"}"
        )

        if (performedScroll) {
            awaitingTimerChange = true
            timerBeforeScrollMinutes = currentCentered
            timerWaitStartedAt = SystemClock.uptimeMillis()
            val timeoutMs = if (timerScrollAttempts <= 1) {
                FIRST_TIMER_CHANGE_TIMEOUT_MS
            } else {
                TIMER_CHANGE_TIMEOUT_MS
            }
            queueProgress(timeoutMs)
            return true
        }

        return false
    }

    private fun confirmTimer(rootNode: AccessibilityNodeInfo): Boolean {
        val confirmed = clickConfirmButton(rootNode) {
            debugStatus("已点击确定，流程完成")
            finishAutomation()
        }
        if (!confirmed) {
            debugStatus("未找到确定按钮，直接结束流程")
            finishAutomation()
        }
        return true
    }

    private fun clickConfirmButton(
        rootNode: AccessibilityNodeInfo,
        onSuccess: () -> Unit
    ): Boolean {
        val buttonById = rootNode.findAccessibilityNodeInfosByViewId(CONFIRM_BUTTON_ID)
            .firstOrNull()
        val clickableById = findClickableParent(buttonById) ?: buttonById?.takeIf { it.isClickable }
        if (clickableById != null && clickableById.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            onSuccess()
            return true
        }
        if (buttonById != null && performNodeCenterTap(buttonById)) {
            onSuccess()
            return true
        }

        return clickByLabel(rootNode, CONFIRM_LABELS, onSuccess)
    }

    private fun clickByLabel(
        rootNode: AccessibilityNodeInfo,
        labels: List<String>,
        onSuccess: () -> Unit
    ): Boolean {
        for (label in labels) {
            val directNode = rootNode.findAccessibilityNodeInfosByText(label)
                .firstNotNullOfOrNull { node -> findClickableParent(node) ?: node.takeIf { it.isClickable } }
            if (directNode != null && directNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                onSuccess()
                return true
            }
        }

        val matchingNode = getAllNodes(rootNode).firstOrNull { node ->
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            labels.any { label ->
                text.contains(label, ignoreCase = true) ||
                    description.contains(label, ignoreCase = true)
            }
        }

        val clickable = findClickableParent(matchingNode) ?: matchingNode?.takeIf { it.isClickable }
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            onSuccess()
            return true
        }
        if (matchingNode != null && performNodeCenterTap(matchingNode)) {
            onSuccess()
            return true
        }

        return false
    }

    private fun advanceStep(nextStep: Step, delayMs: Long) {
        currentStep = nextStep
        retryCount = 0
        handler.removeCallbacks(progressRunnable)
        handler.postDelayed(progressRunnable, delayMs)
    }

    private fun scheduleRetry(reason: String) {
        retryCount += 1
        debugStatus("步骤 $currentStep 重试 $retryCount 次：$reason")
        if (retryCount > maxRetriesForCurrentStep()) {
            Log.e(TAG, "Automation aborted at $currentStep: $reason")
            debugStatus("流程中止：步骤 $currentStep，原因：$reason")
            finishAutomation()
            return
        }

        Log.w(TAG, "Retry $retryCount for $currentStep: $reason")
        queueProgress(RETRY_DELAY_MS)
    }

    private fun finishAutomation() {
        currentStep = Step.FINISHED
        handler.removeCallbacks(progressRunnable)
        NotificationHelper.showCompletionNotification(this)
        handler.postDelayed({ resetState() }, 4000L)
    }

    private fun resetState() {
        handler.removeCallbacksAndMessages(null)
        currentStep = Step.IDLE
        retryCount = 0
        timerScrollAttempts = 0
        pendingTargetConfirmation = false
        awaitingTimerChange = false
        timerBeforeScrollMinutes = null
        timerWaitStartedAt = 0L
    }

    private fun timerLabelsForMinutes(minutes: Int): List<String> {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60

        return when {
            minutes == 30 -> listOf("30分钟", "半小时", "0.5小时")
            remainingMinutes == 0 -> listOf(
                "${hours}小时",
                "${hours}小时0分",
                "${hours}小时0分钟",
                "${minutes}分钟"
            )
            else -> listOf(
                "${hours}小时${remainingMinutes}分钟",
                "${hours}.5小时",
                "${minutes}分钟"
            )
        }.distinct()
    }

    private fun selectVisibleTimerOption(
        rootNode: AccessibilityNodeInfo,
        targetLabels: List<String>
    ): Boolean {
        val directMatch = targetLabels.firstNotNullOfOrNull { label ->
            rootNode.findAccessibilityNodeInfosByText(label)
                .firstNotNullOfOrNull { node ->
                    findClickableParent(node) ?: node.takeIf { it.isClickable }
                }
        }
        if (directMatch != null && directMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        val parsedMatch = getAllNodes(rootNode).firstOrNull { node ->
            extractTimerMinutes(node.text?.toString()) == timerMinutes ||
                extractTimerMinutes(node.contentDescription?.toString()) == timerMinutes
        }
        val clickable = findClickableParent(parsedMatch) ?: parsedMatch?.takeIf { it.isClickable }
        return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun isTargetTimerSelected(rootNode: AccessibilityNodeInfo): Boolean {
        val selectedMinutes = findCenteredTimerMinutes(rootNode) ?: return false
        return selectedMinutes == timerMinutes
    }

    private fun findCenteredTimerMinutes(rootNode: AccessibilityNodeInfo): Int? {
        val timerNodes = getAllNodes(rootNode).mapNotNull { node ->
            val minutes = extractTimerMinutes(node.text?.toString())
                ?: extractTimerMinutes(node.contentDescription?.toString())
                ?: return@mapNotNull null

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.height() <= 0 || bounds.width() <= 0) {
                return@mapNotNull null
            }
            TimerNode(minutes, bounds, node.text?.toString().orEmpty())
        }

        if (timerNodes.isEmpty()) {
            return null
        }

        val minTop = timerNodes.minOf { it.bounds.top }
        val maxBottom = timerNodes.maxOf { it.bounds.bottom }
        val wheelCenterY = (minTop + maxBottom) / 2f

        return timerNodes.minByOrNull { candidate ->
            kotlin.math.abs(candidate.bounds.centerY() - wheelCenterY)
        }?.minutes
    }

    private fun resolveTimerScrollDirection(
        rootNode: AccessibilityNodeInfo,
        targetMinutes: Int
    ): ScrollDirection {
        val currentSelected = findCenteredTimerMinutes(rootNode)
        if (currentSelected != null && currentSelected != targetMinutes) {
            return if (targetMinutes > currentSelected) {
                ScrollDirection.FORWARD
            } else {
                ScrollDirection.BACKWARD
            }
        }

        val visibleMinutes = getAllNodes(rootNode)
            .flatMap { node ->
                listOfNotNull(
                    extractTimerMinutes(node.text?.toString()),
                    extractTimerMinutes(node.contentDescription?.toString())
                )
            }
            .distinct()
            .sorted()

        if (visibleMinutes.isEmpty()) {
            return if (timerScrollAttempts % 2 == 0) {
                ScrollDirection.FORWARD
            } else {
                ScrollDirection.BACKWARD
            }
        }

        if (targetMinutes < visibleMinutes.first()) {
            return ScrollDirection.BACKWARD
        }

        if (targetMinutes > visibleMinutes.last()) {
            return ScrollDirection.FORWARD
        }

        val nearest = visibleMinutes.minByOrNull { kotlin.math.abs(it - targetMinutes) } ?: targetMinutes
        return if (targetMinutes >= nearest) {
            ScrollDirection.FORWARD
        } else {
            ScrollDirection.BACKWARD
        }
    }

    private fun extractTimerMinutes(rawText: String?): Int? {
        val text = rawText?.replace(" ", "")?.lowercase().orEmpty()
        if (text.isBlank()) {
            return null
        }

        if (text.contains("\u534a\u5c0f\u65f6")) {
            return 30
        }

        val hourMinuteMatch = Regex("""(\d+(?:\.\d+)?)\s*\u5c0f\u65f6(?:(\d+)\s*\u5206)?""")
            .find(text)
        if (hourMinuteMatch != null) {
            val hours = hourMinuteMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val minutes = hourMinuteMatch.groupValues[2].toIntOrNull() ?: 0
            return (hours * 60).toInt() + minutes
        }

        val minuteMatch = Regex("""(\d+)\s*\u5206(?:\u949f)?""").find(text)
        if (minuteMatch != null) {
            return minuteMatch.groupValues[1].toIntOrNull()
        }

        return null
    }

    private fun isNonSeekBarPickerNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return className.contains("Picker", ignoreCase = true) ||
            className.contains("NumberPicker", ignoreCase = true) ||
            className.contains("RecyclerView", ignoreCase = true) ||
            className.contains("ListView", ignoreCase = true)
    }

    private fun performPickerGesture(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection
    ): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false
        }

        val centerX = bounds.centerX().toFloat()
        val topY = (bounds.top + bounds.height() * 0.28f)
        val bottomY = (bounds.bottom - bounds.height() * 0.28f)
        val path = Path().apply {
            when (direction) {
                ScrollDirection.FORWARD -> {
                    moveTo(centerX, bottomY)
                    lineTo(centerX, topY)
                }

                ScrollDirection.BACKWARD -> {
                    moveTo(centerX, topY)
                    lineTo(centerX, bottomY)
                }
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 220L))
            .build()
        return dispatchGestureWithContinuation(gesture)
    }

    private fun performTimerWheelGesture(
        rootNode: AccessibilityNodeInfo,
        direction: ScrollDirection
    ): Boolean {
        findTimerSeekBarNode(rootNode)?.let { seekBarNode ->
            Log.d(TAG, "Use seekbar step gesture for timer wheel")
            val currentMinutes = extractTimerMinutes(seekBarNode.text?.toString())
                ?: extractTimerMinutes(seekBarNode.contentDescription?.toString())
            if (performSeekBarStepGesture(seekBarNode, direction, currentMinutes, timerMinutes)) {
                return true
            }
        }

        val timerNodes = getAllNodes(rootNode).mapNotNull { node ->
            val minutes = extractTimerMinutes(node.text?.toString())
                ?: extractTimerMinutes(node.contentDescription?.toString())
                ?: return@mapNotNull null

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return@mapNotNull null
            }
            TimerNode(
                minutes = minutes,
                bounds = bounds,
                debugText = node.text?.toString().orEmpty()
            )
        }

        if (timerNodes.size < 2) {
            return false
        }

        val minLeft = timerNodes.minOf { it.bounds.left }
        val maxRight = timerNodes.maxOf { it.bounds.right }
        val minTop = timerNodes.minOf { it.bounds.top }
        val maxBottom = timerNodes.maxOf { it.bounds.bottom }

        val centerX = (minLeft + maxRight) / 2f
        val dragHeight = (maxBottom - minTop).coerceAtLeast(160)
        val startY: Float
        val endY: Float

        when (direction) {
            ScrollDirection.FORWARD -> {
                startY = maxBottom - dragHeight * 0.20f
                endY = minTop + dragHeight * 0.20f
            }

            ScrollDirection.BACKWARD -> {
                startY = minTop + dragHeight * 0.20f
                endY = maxBottom - dragHeight * 0.20f
            }
        }

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 260L))
            .build()
        if (dispatchGestureWithContinuation(gesture)) {
            return true
        }

        return performDialogAnchoredWheelGesture(rootNode, direction)
    }

    private fun performDialogAnchoredWheelGesture(
        rootNode: AccessibilityNodeInfo,
        direction: ScrollDirection
    ): Boolean {
        val confirmNode = CONFIRM_LABELS.firstNotNullOfOrNull { label ->
            rootNode.findAccessibilityNodeInfosByText(label).firstOrNull()
        } ?: return false

        val confirmBounds = Rect()
        confirmNode.getBoundsInScreen(confirmBounds)
        if (confirmBounds.width() <= 0 || confirmBounds.height() <= 0) {
            return false
        }

        val centerX = confirmBounds.centerX().toFloat()
        val wheelBottom = confirmBounds.top - confirmBounds.height() * 1.2f
        val wheelTop = confirmBounds.top - confirmBounds.height() * 4.8f
        if (wheelBottom <= wheelTop) {
            return false
        }

        val path = Path().apply {
            when (direction) {
                ScrollDirection.FORWARD -> {
                    moveTo(centerX, wheelBottom)
                    lineTo(centerX, wheelTop)
                }

                ScrollDirection.BACKWARD -> {
                    moveTo(centerX, wheelTop)
                    lineTo(centerX, wheelBottom)
                }
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
            .build()
        return dispatchGestureWithContinuation(gesture)
    }

    private fun performSeekBarStepGesture(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection,
        currentMinutes: Int?,
        targetMinutes: Int
    ): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false
        }

        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        val itemOffset = (bounds.height() * 0.21f).coerceIn(88f, 118f)
        val tapY = when (direction) {
            ScrollDirection.FORWARD -> (centerY + itemOffset).coerceAtMost(bounds.bottom - 48f)
            ScrollDirection.BACKWARD -> (centerY - itemOffset).coerceAtLeast(bounds.top + 48f)
        }

        val tapPath = Path().apply {
            moveTo(centerX, tapY)
        }
        val tapGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0L, 70L))
            .build()
        if (dispatchGestureWithContinuation(tapGesture)) {
            return true
        }

        val fallbackDistance = 24f
        val path = Path().apply {
            when (direction) {
                ScrollDirection.FORWARD -> {
                    moveTo(centerX, centerY + fallbackDistance / 2f)
                    lineTo(centerX, centerY - fallbackDistance / 2f)
                }

                ScrollDirection.BACKWARD -> {
                    moveTo(centerX, centerY - fallbackDistance / 2f)
                    lineTo(centerX, centerY + fallbackDistance / 2f)
                }
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 180L))
            .build()
        return dispatchGestureWithContinuation(gesture)
    }

    private fun performNodeCenterTap(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false
        }

        val path = Path().apply {
            moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 70L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findTimerSeekBarNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = runCatching {
            rootNode.findAccessibilityNodeInfosByViewId(TIMER_SEEKBAR_ID).firstOrNull()
        }.getOrNull()
        if (byId != null) {
            return byId
        }

        return getAllNodes(rootNode).firstOrNull { node ->
            node.className?.toString()?.contains("SeekBar", ignoreCase = true) == true
        }
    }

    private fun maxRetriesForCurrentStep(): Int {
        return when (currentStep) {
            Step.SET_TIMER_TIME -> (timerMinutes / 30) + 8
            else -> 4
        }
    }

    private enum class ScrollDirection {
        FORWARD,
        BACKWARD
    }

    private data class TimerNode(
        val minutes: Int,
        val bounds: Rect,
        val debugText: String
    )

    private fun describeMinutes(minutes: Int): String {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return when {
            minutes == 30 -> "30分钟"
            remainingMinutes == 0 -> "${hours}小时"
            else -> "${hours}小时${remainingMinutes}分钟"
        }
    }

    private fun debugStatus(message: String) {
        Log.d(TAG, message)
        appendDebugTrace(message)
    }

    private fun queueProgress(delayMs: Long) {
        handler.removeCallbacks(progressRunnable)
        handler.postDelayed(progressRunnable, delayMs)
    }

    private fun dispatchGestureWithContinuation(gesture: GestureDescription): Boolean {
        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Gesture completed")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Gesture cancelled")
                }
            },
            null
        )
    }

    private fun clearDebugTrace() {
        runCatching {
            File(filesDir, "auto_debug_trace.txt").writeText("")
        }
    }

    private fun appendDebugTrace(message: String) {
        runCatching {
            File(filesDir, "auto_debug_trace.txt").appendText(message + "\n")
        }
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }

        val nodeText = node.text?.toString().orEmpty()
        val nodeDescription = node.contentDescription?.toString().orEmpty()
        if (nodeText.contains(text, ignoreCase = true) ||
            nodeDescription.contains(text, ignoreCase = true)
        ) {
            return node
        }

        for (index in 0 until node.childCount) {
            val found = findNodeByTextRecursive(node.getChild(index), text)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun getAllNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) {
                return
            }
            nodes += node
            for (index in 0 until node.childCount) {
                walk(node.getChild(index))
            }
        }

        walk(root)
        return nodes
    }
}
