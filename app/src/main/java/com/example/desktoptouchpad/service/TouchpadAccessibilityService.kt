package com.example.desktoptouchpad.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.example.desktoptouchpad.input.ExternalTextBridge
import com.example.desktoptouchpad.input.ExternalTextSession
import com.example.desktoptouchpad.overlay.CursorOverlayManager
import com.example.desktoptouchpad.overlay.CursorStyle
import com.example.desktoptouchpad.overlay.ExternalDisplayInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TouchpadAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var overlayManager: CursorOverlayManager

    private var isPhoneKeyboardAllowed = false

    private val showModeListener = SoftKeyboardController.OnShowModeChangedListener { _, showMode ->
        if (shouldSuppressExternalSoftKeyboard() && (showMode != SHOW_MODE_HIDDEN)) {
            applySoftKeyboardShowMode(SHOW_MODE_HIDDEN)
        }
    }

    private val externalTextBridge: ExternalTextBridge by lazy(LazyThreadSafetyMode.NONE) {
        ExternalTextBridge(
            service = this,
            dispatchInternalTap = { displayId, x, y -> performRawTap(displayId, x, y) },
            onSuppressExternalSoftKeyboard = { suppressExternalSoftKeyboard() },
            onAllowPhoneSoftKeyboard = { allowPhoneSoftKeyboard() },
        )
    }

    private var isDragRequested = false
    private var activeDragStroke: GestureDescription.StrokeDescription? = null
    private var lastDragX = 0f
    private var lastDragY = 0f
    private var dragDisplayId = Display.INVALID_DISPLAY

    private var isScrollInFlight = false
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f

    private var lastHoverDispatchUptimeMs = 0L
    private val isHoverInspectionRunning = AtomicBoolean(false)
    private val deferredHoverCheckRunnable = Runnable {
        dispatchHoverInspectionNow()
    }

    private enum class SystemNavButton {
        BACK,
        HOME,
        RECENTS,
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod {
        return externalTextBridge.createInputMethod()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        configureAccessibilityFlags()

        overlayManager = CursorOverlayManager(this)
        overlayManager.start()
        instance = this
        _isConnected.value = true

        runCatching {
            softKeyboardController.addOnShowModeChangedListener(showModeListener, mainHandler)
        }

        serviceScope.launch {
            overlayManager.displayInfo.collect { info ->
                _externalDisplay.value = info
                if (info == null) {
                    isPhoneKeyboardAllowed = false
                    resetDragState()
                    pendingScrollX = 0f
                    pendingScrollY = 0f
                    externalTextBridge.clearAll()
                } else {
                    scheduleHoverInspection(delayMs = 80L)
                }
                syncSoftKeyboardMode()
            }
        }

        serviceScope.launch {
            externalTextBridge.session.collect { session ->
                _textSession.value = session
                isPhoneKeyboardAllowed = (session != null)
                syncSoftKeyboardMode()
            }
        }
    }

    private fun configureAccessibilityFlags() {
        val info = serviceInfo ?: return
        var desiredFlags = info.flags or
            AccessibilityServiceInfo.DEFAULT or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            desiredFlags = desiredFlags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
        }
        info.flags = desiredFlags
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        resetDragState()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun cleanup() {
        mainHandler.removeCallbacks(deferredHoverCheckRunnable)
        resetDragState()
        isPhoneKeyboardAllowed = false
        externalTextBridge.clearAll()
        runCatching {
            softKeyboardController.removeOnShowModeChangedListener(showModeListener)
            softKeyboardController.showMode = SHOW_MODE_AUTO
        }
        if (::overlayManager.isInitialized) {
            overlayManager.stop()
        }
        instance = null
        _isConnected.value = false
        _externalDisplay.value = null
        _textSession.value = null
    }

    private fun shouldSuppressExternalSoftKeyboard(): Boolean {
        return (_externalDisplay.value != null) &&
            isMainActivityStarted &&
            !isPhoneKeyboardAllowed &&
            (_textSession.value == null)
    }

    private fun applySoftKeyboardShowMode(desiredMode: Int) {
        runCatching {
            if (softKeyboardController.showMode != desiredMode) {
                softKeyboardController.showMode = desiredMode
            }
        }
    }

    internal fun syncSoftKeyboardMode() {
        val desired = if (shouldSuppressExternalSoftKeyboard()) {
            SHOW_MODE_HIDDEN
        } else {
            SHOW_MODE_AUTO
        }
        applySoftKeyboardShowMode(desired)
    }

    fun suppressExternalSoftKeyboard() {
        if (_textSession.value != null) return
        isPhoneKeyboardAllowed = false
        syncSoftKeyboardMode()
    }

    fun allowPhoneSoftKeyboard() {
        if (_externalDisplay.value != null) {
            isPhoneKeyboardAllowed = true
        }
        applySoftKeyboardShowMode(SHOW_MODE_AUTO)
    }

    fun requestManualKeyboard(): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        val (cursorX, cursorY) = getCursorPosition()
        return externalTextBridge.openManualKeyboard(displayId, cursorX, cursorY)
    }

    fun dismissKeyboardSession() {
        isPhoneKeyboardAllowed = false
        externalTextBridge.dismissKeyboardSession()
    }

    fun forceInputIntoSelectedBox(
        text: String,
        sendEnterAfter: Boolean,
    ) {
        externalTextBridge.forceInputIntoSelectedBox(
            text = text,
            sendEnterAfter = sendEnterAfter,
        )
    }

    fun clearSelectedBox() {
        externalTextBridge.clearSelectedBox()
    }

    fun sendBackspaceToSelectedBox() {
        externalTextBridge.sendBackspaceToSelectedBox()
    }

    fun isExternalFocusTransitionActive(): Boolean {
        return externalTextBridge.isExternalFocusTransitionActive()
    }

    fun moveCursor(deltaX: Float, deltaY: Float) {
        if (!::overlayManager.isInitialized) return
        overlayManager.updateCursorPosition(deltaX, deltaY)
        if (!isDragRequested && activeDragStroke == null) {
            throttledHoverInspectionOnMove()
        }
    }

    fun centerCursor() {
        if (!::overlayManager.isInitialized) return
        overlayManager.centerCursor()
        scheduleHoverInspection(delayMs = 30L)
    }

    fun getCursorPosition(): Pair<Float, Float> {
        if (!::overlayManager.isInitialized) return Pair(0f, 0f)
        return overlayManager.getCursorCoordinates()
    }

    fun performTap(x: Float, y: Float): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        if (isDragRequested || (activeDragStroke != null)) return false

        val (clampedX, clampedY) = clampToDisplay(x, y)
        externalTextBridge.onBeforeExternalTap(displayId, clampedX, clampedY)
        val path = Path().apply { moveTo(clampedX, clampedY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val dispatched = dispatchGesture(
            createGestureBuilder(displayId).addStroke(stroke).build(),
            null,
            null
        )
        if (dispatched) {
            scheduleHoverInspection(delayMs = 180L)
        }
        return dispatched
    }

    private fun performRawTap(displayId: Int, x: Float, y: Float): Boolean {
        if (isDragRequested || (activeDragStroke != null)) return false
        if (_textSession.value == null) {
            suppressExternalSoftKeyboard()
        }
        val (clampedX, clampedY) = clampToDisplay(x, y)
        val path = Path().apply { moveTo(clampedX, clampedY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        return dispatchGesture(createGestureBuilder(displayId).addStroke(stroke).build(), null, null)
    }

    fun performContextClick(x: Float, y: Float): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        if (isDragRequested || (activeDragStroke != null)) return false

        if (_textSession.value == null) {
            suppressExternalSoftKeyboard()
        }
        overlayManager.notifyContextClickVisual()
        val (clampedX, clampedY) = clampToDisplay(x, y)
        val path = Path().apply { moveTo(clampedX, clampedY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS)
        val dispatched = dispatchGesture(
            createGestureBuilder(displayId).addStroke(stroke).build(),
            null,
            null
        )
        if (dispatched) {
            scheduleHoverInspection(delayMs = LONG_PRESS_DURATION_MS + 120L)
        }
        return dispatched
    }

    fun startDrag(): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        if (isDragRequested || (activeDragStroke != null)) return true

        if (_textSession.value == null) {
            suppressExternalSoftKeyboard()
        }
        pendingScrollX = 0f
        pendingScrollY = 0f

        val (rawX, rawY) = overlayManager.getCursorCoordinates()
        val (startX, startY) = clampToDisplay(rawX, rawY)

        isDragRequested = true
        dragDisplayId = displayId
        lastDragX = startX
        lastDragY = startY
        overlayManager.setDraggingVisual(isDragging = true)
        _isDragging.value = true

        val initialPath = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, startY)
        }
        val initialStroke = GestureDescription.StrokeDescription(
            initialPath,
            0L,
            DRAG_SLICE_DURATION_MS,
            /* willContinue = */ true,
        )
        activeDragStroke = initialStroke
        dispatchDragSlice(displayId, initialStroke, isFinalRelease = false)
        return true
    }

    fun endDrag() {
        isDragRequested = false
        scheduleHoverInspection(delayMs = 120L)
    }

    private fun dispatchDragSlice(
        displayId: Int,
        stroke: GestureDescription.StrokeDescription,
        isFinalRelease: Boolean,
    ) {
        val dispatched = dispatchGesture(
            createGestureBuilder(displayId).addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (isFinalRelease || (activeDragStroke == null) || (activeTargetDisplayId() != displayId)) {
                        resetDragState()
                        return
                    }

                    val (rawX, rawY) = overlayManager.getCursorCoordinates()
                    val (targetX, targetY) = clampToDisplay(rawX, rawY)
                    val segmentPath = Path().apply {
                        moveTo(lastDragX, lastDragY)
                        lineTo(targetX, targetY)
                    }
                    lastDragX = targetX
                    lastDragY = targetY

                    val keepHolding = isDragRequested
                    val nextStroke = try {
                        stroke.continueStroke(
                            segmentPath,
                            0L,
                            if (keepHolding) DRAG_SLICE_DURATION_MS else DRAG_RELEASE_DURATION_MS,
                            keepHolding,
                        )
                    } catch (_: Exception) {
                        resetDragState()
                        return
                    }

                    activeDragStroke = if (keepHolding) nextStroke else null
                    dispatchDragSlice(displayId, nextStroke, isFinalRelease = !keepHolding)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    resetDragState()
                }
            },
            mainHandler
        )

        if (!dispatched) {
            resetDragState()
        }
    }

    private fun resetDragState() {
        isDragRequested = false
        activeDragStroke = null
        dragDisplayId = Display.INVALID_DISPLAY
        if (::overlayManager.isInitialized) {
            overlayManager.setDraggingVisual(isDragging = false)
        }
        _isDragging.value = false
    }

    fun enqueueScroll(deltaX: Float, deltaY: Float) {
        if (isDragRequested || (activeDragStroke != null)) return
        if (activeTargetDisplayId() == null) return

        overlayManager.notifyScrollingVisual()
        pendingScrollX = (pendingScrollX + deltaX).coerceIn(-MAX_SCROLL_STEP_PX, MAX_SCROLL_STEP_PX)
        pendingScrollY = (pendingScrollY + deltaY).coerceIn(-MAX_SCROLL_STEP_PX, MAX_SCROLL_STEP_PX)
        flushPendingScrollIfIdle()
    }

    private fun flushPendingScrollIfIdle() {
        if (isScrollInFlight || isDragRequested || (activeDragStroke != null)) return
        val displayId = activeTargetDisplayId()
        if (displayId == null) {
            pendingScrollX = 0f
            pendingScrollY = 0f
            return
        }

        if (hypot(pendingScrollX, pendingScrollY) < MIN_SCROLL_DISPATCH_PX) {
            return
        }

        val stepX = pendingScrollX.coerceIn(-SINGLE_SCROLL_STROKE_PX, SINGLE_SCROLL_STROKE_PX)
        val stepY = pendingScrollY.coerceIn(-SINGLE_SCROLL_STROKE_PX, SINGLE_SCROLL_STROKE_PX)
        pendingScrollX -= stepX
        pendingScrollY -= stepY

        val (width, height) = overlayManager.getDisplayDimensions()
        val maxX = (width - 2).coerceAtLeast(2).toFloat()
        val maxY = (height - 2).coerceAtLeast(2).toFloat()
        val (cursorX, cursorY) = overlayManager.getCursorCoordinates()

        val startX = when {
            cursorX + stepX < 1f -> (1f - stepX).coerceIn(1f, maxX)
            cursorX + stepX > maxX -> (maxX - stepX).coerceIn(1f, maxX)
            else -> cursorX.coerceIn(1f, maxX)
        }
        val startY = when {
            cursorY + stepY < 1f -> (1f - stepY).coerceIn(1f, maxY)
            cursorY + stepY > maxY -> (maxY - stepY).coerceIn(1f, maxY)
            else -> cursorY.coerceIn(1f, maxY)
        }
        val endX = (startX + stepX).coerceIn(1f, maxX)
        val endY = (startY + stepY).coerceIn(1f, maxY)

        if (hypot(endX - startX, endY - startY) < 1f) {
            return
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, SCROLL_DURATION_MS)
        isScrollInFlight = true

        val dispatched = dispatchGesture(
            createGestureBuilder(displayId).addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    isScrollInFlight = false
                    flushPendingScrollIfIdle()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    isScrollInFlight = false
                    pendingScrollX = 0f
                    pendingScrollY = 0f
                }
            },
            mainHandler
        )

        if (!dispatched) {
            isScrollInFlight = false
        }
    }

    /**
     * Executes Back strictly on the connected external display without minimizing or backing out
     * of the phone touchpad Activity on DEFAULT_DISPLAY.
     */
    fun performBack(): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        if (_textSession.value == null) {
            suppressExternalSoftKeyboard()
        }

        // 1. Click external display's own SystemUI / Taskbar Back button if present
        if (clickSystemNavButtonOnDisplay(displayId, SystemNavButton.BACK)) {
            scheduleHoverInspection(delayMs = 220L)
            return true
        }

        // 2. If WindowManager's top focused display is verified to be the external display
        // (and NOT the phone screen), InputDispatcher routes GLOBAL_ACTION_BACK to displayId.
        if (isExternalDisplayTopFocused(displayId) && !isDefaultDisplayFocused()) {
            val handled = performGlobalAction(GLOBAL_ACTION_BACK)
            if (handled) {
                scheduleHoverInspection(delayMs = 220L)
                return true
            }
        }

        // 3. Send KEYCODE_BACK directly via API 33+ AccessibilityInputConnection if connected to an external app
        val externalPackages = getPackagesOnDisplay(displayId)
        if (externalTextBridge.sendKeyEventToExternalIfConnected(KeyEvent.KEYCODE_BACK, externalPackages)) {
            scheduleHoverInspection(delayMs = 220L)
            return true
        }

        // 4. Fallback: inject an edge-back swipe gesture strictly bound to displayId
        val dispatched = dispatchEdgeBackGestureOnDisplay(displayId)
        if (dispatched) {
            scheduleHoverInspection(delayMs = 260L)
        }
        return dispatched
    }

    /**
     * Executes Home strictly on the connected external display (never calling GLOBAL_ACTION_HOME,
     * which Android routes to DEFAULT_DISPLAY and would minimize the phone app).
     */
    fun performHome(): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        if (_textSession.value == null) {
            suppressExternalSoftKeyboard()
        }

        // 1. Click external display's own SystemUI / Taskbar Home button if present
        if (clickSystemNavButtonOnDisplay(displayId, SystemNavButton.HOME)) {
            scheduleHoverInspection(delayMs = 250L)
            return true
        }

        // 2. Launch secondary display Home intent explicitly targeted at displayId
        if (launchSecondaryHomeOnDisplay(displayId)) {
            scheduleHoverInspection(delayMs = 280L)
            return true
        }

        // 3. Fallback: bottom-edge swipe up gesture strictly bound to displayId
        val dispatched = dispatchBottomHomeSwipeOnDisplay(displayId)
        if (dispatched) {
            scheduleHoverInspection(delayMs = 280L)
        }
        return dispatched
    }

    /**
     * Triggers App Switcher / Overview / Taskbar strictly on the connected external display.
     */
    fun performAppSwitch(): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        if (_textSession.value == null) {
            suppressExternalSoftKeyboard()
        }

        // 1. Click external display's own SystemUI / Taskbar Recents / Overview button if present
        if (clickSystemNavButtonOnDisplay(displayId, SystemNavButton.RECENTS)) {
            scheduleHoverInspection(delayMs = 260L)
            return true
        }

        // 2. Fallback: bottom-edge swipe-up-and-hold Overview / Taskbar gesture strictly on displayId
        val dispatched = dispatchOverviewSwipeHoldOnDisplay(displayId)
        if (dispatched) {
            scheduleHoverInspection(delayMs = 460L)
        }
        return dispatched
    }

    private fun clickSystemNavButtonOnDisplay(displayId: Int, target: SystemNavButton): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val displayWindows = windowsOnAllDisplays[displayId].orEmpty()
        if (displayWindows.isEmpty()) return false

        val candidateWindows = displayWindows.filter { win ->
            val pkg = win.root?.packageName?.toString().orEmpty()
            win.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                pkg == "com.android.systemui" ||
                pkg.contains("launcher", ignoreCase = true) ||
                pkg.contains("desktop", ignoreCase = true)
        }.sortedByDescending { it.layer }

        for (win in candidateWindows) {
            val root = win.root ?: continue
            val matchedNode = findNavNodeRecursive(root, target, depth = 0) ?: continue
            if (clickNodeOrBoundsOnDisplay(displayId, matchedNode)) {
                return true
            }
        }
        return false
    }

    private fun findNavNodeRecursive(
        node: AccessibilityNodeInfo,
        target: SystemNavButton,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > 18 || !node.isVisibleToUser) return null

        val viewId = node.viewIdResourceName.orEmpty()
        val idShort = viewId.substringAfterLast('/', "").lowercase()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        val pkg = node.packageName?.toString().orEmpty()
        val isSystemUiOrTaskbarNode = pkg == "com.android.systemui" ||
            viewId.contains("taskbar", ignoreCase = true) ||
            viewId.contains("nav", ignoreCase = true) ||
            idShort in KNOWN_SYSTEM_NAV_IDS

        val isMatch = when (target) {
            SystemNavButton.BACK -> {
                idShort == "back" ||
                    idShort == "back_button" ||
                    idShort == "nav_back" ||
                    (isSystemUiOrTaskbarNode && desc == "back")
            }
            SystemNavButton.HOME -> {
                idShort == "home" ||
                    idShort == "home_button" ||
                    idShort == "nav_home" ||
                    (isSystemUiOrTaskbarNode && desc == "home")
            }
            SystemNavButton.RECENTS -> {
                idShort == "recent_apps" ||
                    idShort == "overview" ||
                    idShort == "recents" ||
                    idShort == "recents_button" ||
                    idShort == "nav_recents" ||
                    (isSystemUiOrTaskbarNode && (desc == "overview" || desc == "recent apps" || desc == "recents"))
            }
        }

        if (isMatch) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNavNodeRecursive(child, target, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun clickNodeOrBoundsOnDisplay(displayId: Int, node: AccessibilityNodeInfo): Boolean {
        if (node.isEnabled && node.isClickable &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            return true
        }
        val parent = node.parent
        if (parent != null && parent.isEnabled && parent.isClickable &&
            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            return true
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            return performRawTap(displayId, bounds.exactCenterX(), bounds.exactCenterY())
        }
        return false
    }

    private fun isExternalDisplayTopFocused(displayId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val externalWindows = windowsOnAllDisplays[displayId].orEmpty()
        return externalWindows.any { win ->
            win.isFocused && win.root?.packageName?.toString() != packageName
        }
    }

    private fun isDefaultDisplayFocused(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val phoneWindows = windowsOnAllDisplays[Display.DEFAULT_DISPLAY].orEmpty()
        return phoneWindows.any { it.isFocused }
    }

    private fun getPackagesOnDisplay(displayId: Int): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
        return windowsOnAllDisplays[displayId]
            .orEmpty()
            .mapNotNull { it.root?.packageName?.toString() }
            .filter { it.isNotBlank() && it != packageName }
            .toSet()
    }

    private fun launchSecondaryHomeOnDisplay(displayId: Int): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY) {
            return false
        }
        val pm = packageManager
        val defaultHomePkg = runCatching {
            val primaryHome = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(primaryHome, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        }.getOrNull()

        val secondaryHomeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(CATEGORY_SECONDARY_HOME_COMPAT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val candidates = runCatching {
            pm.queryIntentActivities(secondaryHomeIntent, 0)
        }.getOrDefault(emptyList())

        if (candidates.isEmpty()) {
            return false
        }

        val chosen = candidates.firstOrNull {
            defaultHomePkg != null && it.activityInfo?.packageName == defaultHomePkg
        } ?: candidates.firstOrNull { info ->
            val pkg = info.activityInfo?.packageName.orEmpty()
            val cls = info.activityInfo?.name.orEmpty()
            !pkg.contains("settings", ignoreCase = true) &&
                !cls.contains("FallbackHome", ignoreCase = true)
        } ?: candidates.first()

        val activityInfo = chosen.activityInfo ?: return false
        secondaryHomeIntent.component = ComponentName(activityInfo.packageName, activityInfo.name)

        return runCatching {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            startActivity(secondaryHomeIntent, options.toBundle())
            true
        }.getOrDefault(false)
    }

    private fun dispatchEdgeBackGestureOnDisplay(displayId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val (width, height) = overlayManager.getDisplayDimensions()
        val startX = 2f
        val endX = (width * 0.22f).coerceIn(90f, 240f).coerceAtMost((width - 2).toFloat())
        val midY = (height * 0.5f).coerceIn(10f, (height - 10).coerceAtLeast(10).toFloat())
        val path = Path().apply {
            moveTo(startX, midY)
            lineTo(endX, midY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 110L)
        return dispatchGesture(createGestureBuilder(displayId).addStroke(stroke).build(), null, null)
    }

    private fun dispatchBottomHomeSwipeOnDisplay(displayId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val (width, height) = overlayManager.getDisplayDimensions()
        val centerX = (width * 0.5f).coerceAtLeast(2f)
        val startY = (height - 2).coerceAtLeast(10).toFloat()
        val endY = (height * 0.58f).coerceIn(4f, startY - 20f)
        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 105L)
        return dispatchGesture(createGestureBuilder(displayId).addStroke(stroke).build(), null, null)
    }

    private fun dispatchOverviewSwipeHoldOnDisplay(displayId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val (width, height) = overlayManager.getDisplayDimensions()
        val centerX = (width * 0.5f).coerceAtLeast(2f)
        val startY = (height - 2).coerceAtLeast(20).toFloat()
        val holdY = (height * 0.68f).coerceIn(10f, startY - 20f)

        val swipeUpPath = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, holdY)
        }
        val swipeStroke = GestureDescription.StrokeDescription(
            swipeUpPath,
            0L,
            140L,
            /* willContinue = */ true,
        )

        return dispatchGesture(
            createGestureBuilder(displayId).addStroke(swipeStroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (activeTargetDisplayId() != displayId) return
                    val holdPath = Path().apply {
                        moveTo(centerX, holdY)
                        lineTo(centerX, holdY)
                    }
                    val holdStroke = runCatching {
                        swipeStroke.continueStroke(holdPath, 0L, 280L, false)
                    }.getOrNull() ?: return
                    dispatchGesture(
                        createGestureBuilder(displayId).addStroke(holdStroke).build(),
                        null,
                        mainHandler
                    )
                }
            },
            mainHandler
        )
    }

    private fun throttledHoverInspectionOnMove() {
        val now = SystemClock.uptimeMillis()
        mainHandler.removeCallbacks(deferredHoverCheckRunnable)
        if (now - lastHoverDispatchUptimeMs >= HOVER_THROTTLE_MS) {
            dispatchHoverInspectionNow()
        } else {
            mainHandler.postDelayed(deferredHoverCheckRunnable, HOVER_SETTLE_DELAY_MS)
        }
    }

    private fun scheduleHoverInspection(delayMs: Long) {
        mainHandler.removeCallbacks(deferredHoverCheckRunnable)
        mainHandler.postDelayed(deferredHoverCheckRunnable, delayMs)
    }

    private fun dispatchHoverInspectionNow() {
        if (!::overlayManager.isInitialized) return
        val displayId = activeTargetDisplayId() ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!isHoverInspectionRunning.compareAndSet(false, true)) return

        lastHoverDispatchUptimeMs = SystemClock.uptimeMillis()
        val (cursorX, cursorY) = overlayManager.getCursorCoordinates()
        val (dispW, dispH) = overlayManager.getDisplayDimensions()

        serviceScope.launch(Dispatchers.Default) {
            try {
                val style = resolveCursorStyleAt(
                    displayId = displayId,
                    x = cursorX.toInt(),
                    y = cursorY.toInt(),
                    displayWidth = dispW,
                    displayHeight = dispH,
                )
                mainHandler.post {
                    if (::overlayManager.isInitialized && activeTargetDisplayId() == displayId) {
                        overlayManager.setHoverCursorStyle(style)
                    }
                }
            } catch (_: Exception) {
                // Ignore transient window tree races while windows animate
            } finally {
                isHoverInspectionRunning.set(false)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun resolveCursorStyleAt(
        displayId: Int,
        x: Int,
        y: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): CursorStyle {
        val rawWindows = windowsOnAllDisplays[displayId].orEmpty()
        if (rawWindows.isEmpty()) return CursorStyle.ARROW

        val windows = rawWindows
            .filter { win ->
                win.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        win.type != AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY) &&
                    win.root?.packageName?.toString() != packageName
            }
            .sortedByDescending { it.layer }

        val bounds = Rect()
        val expandedBounds = Rect()

        for (win in windows) {
            win.getBoundsInScreen(bounds)
            if (bounds.isEmpty) continue

            if (win.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER && bounds.contains(x, y)) {
                return if (bounds.width() <= bounds.height()) {
                    CursorStyle.RESIZE_HORIZONTAL
                } else {
                    CursorStyle.RESIZE_VERTICAL
                }
            }

            val isFreeformAppWindow = win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                !win.isInPictureInPictureMode &&
                (bounds.width() < displayWidth - 24 || bounds.height() < displayHeight - 84)

            if (isFreeformAppWindow) {
                expandedBounds.set(bounds)
                expandedBounds.inset(-FREEFORM_RESIZE_OUTSET_PX, -FREEFORM_RESIZE_OUTSET_PX)
                if (expandedBounds.contains(x, y)) {
                    val resizeStyle = detectFreeformBorderResize(bounds, x, y)
                    if (resizeStyle != null) {
                        return resizeStyle
                    }
                }
            }

            if (!bounds.contains(x, y)) continue

            val root = win.root ?: return CursorStyle.ARROW
            return inspectNodeHierarchyForCursor(
                root = root,
                x = x,
                y = y,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )
        }

        return CursorStyle.ARROW
    }

    private fun detectFreeformBorderResize(bounds: Rect, x: Int, y: Int): CursorStyle? {
        val nearLeft = abs(x - bounds.left) <= FREEFORM_EDGE_THRESHOLD_PX
        val nearRight = abs(x - bounds.right) <= FREEFORM_EDGE_THRESHOLD_PX
        val nearTop = abs(y - bounds.top) <= FREEFORM_EDGE_THRESHOLD_PX
        val nearBottom = abs(y - bounds.bottom) <= FREEFORM_EDGE_THRESHOLD_PX

        if (!nearLeft && !nearRight && !nearTop && !nearBottom) return null

        val cornerLeft = abs(x - bounds.left) <= FREEFORM_CORNER_THRESHOLD_PX
        val cornerRight = abs(x - bounds.right) <= FREEFORM_CORNER_THRESHOLD_PX
        val cornerTop = abs(y - bounds.top) <= FREEFORM_CORNER_THRESHOLD_PX
        val cornerBottom = abs(y - bounds.bottom) <= FREEFORM_CORNER_THRESHOLD_PX

        return when {
            (cornerLeft && cornerTop) || (cornerRight && cornerBottom) -> CursorStyle.RESIZE_DIAG_NW_SE
            (cornerRight && cornerTop) || (cornerLeft && cornerBottom) -> CursorStyle.RESIZE_DIAG_NE_SW
            nearLeft || nearRight -> CursorStyle.RESIZE_HORIZONTAL
            else -> CursorStyle.RESIZE_VERTICAL
        }
    }

    private fun inspectNodeHierarchyForCursor(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): CursorStyle {
        val hitChain = ArrayList<AccessibilityNodeInfo>(12)
        collectHitNodes(root, x, y, depth = 0, outChain = hitChain)
        if (hitChain.isEmpty()) return CursorStyle.ARROW

        // Inspect from deepest leaf node upward to nearest interactive container
        for (i in hitChain.lastIndex downTo 0) {
            val node = hitChain[i]
            if (!node.isEnabled) continue

            val cls = node.className?.toString().orEmpty()
            val isEditable = node.isEditable ||
                cls.contains("EditText", ignoreCase = true) ||
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            if (isEditable) {
                return CursorStyle.TEXT
            }

            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)
            val isBoundedControl = nodeBounds.width() in 4..(displayWidth * 3 / 4) &&
                nodeBounds.height() in 4..(displayHeight / 2)

            if (isBoundedControl) {
                val isClickableControl = node.isClickable ||
                    node.isCheckable ||
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                if (isClickableControl) {
                    return CursorStyle.HAND
                }

                val hasContextClick = node.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK.id
                }
                if (hasContextClick) {
                    return CursorStyle.CONTEXT_MENU
                }
            }
        }

        return CursorStyle.ARROW
    }

    private fun collectHitNodes(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        depth: Int,
        outChain: MutableList<AccessibilityNodeInfo>,
    ): Boolean {
        if (depth > 28 || !node.isVisibleToUser) return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || !bounds.contains(x, y)) return false

        outChain.add(node)
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            if (collectHitNodes(child, x, y, depth + 1, outChain)) {
                return true
            }
        }
        return true
    }

    private fun activeTargetDisplayId(): Int? {
        if (!::overlayManager.isInitialized) return null
        val id = overlayManager.activeDisplayId
        return if (id != Display.INVALID_DISPLAY) id else null
    }

    private fun clampToDisplay(x: Float, y: Float): Pair<Float, Float> {
        val (width, height) = overlayManager.getDisplayDimensions()
        val maxX = (width - 1).coerceAtLeast(1).toFloat()
        val maxY = (height - 1).coerceAtLeast(1).toFloat()
        return Pair(x.coerceIn(0f, maxX), y.coerceIn(0f, maxY))
    }

    private fun createGestureBuilder(displayId: Int): GestureDescription.Builder {
        val builder = GestureDescription.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setDisplayId(displayId)
        }
        return builder
    }

    companion object {
        private const val TAP_DURATION_MS = 45L
        private const val LONG_PRESS_DURATION_MS = 520L
        private const val DRAG_SLICE_DURATION_MS = 55L
        private const val DRAG_RELEASE_DURATION_MS = 25L
        private const val SCROLL_DURATION_MS = 65L
        private const val MIN_SCROLL_DISPATCH_PX = 4f
        private const val SINGLE_SCROLL_STROKE_PX = 260f
        private const val MAX_SCROLL_STEP_PX = 640f

        private const val HOVER_THROTTLE_MS = 90L
        private const val HOVER_SETTLE_DELAY_MS = 65L
        private const val FREEFORM_RESIZE_OUTSET_PX = 8
        private const val FREEFORM_EDGE_THRESHOLD_PX = 10
        private const val FREEFORM_CORNER_THRESHOLD_PX = 18

        private const val CATEGORY_SECONDARY_HOME_COMPAT = "android.intent.category.SECONDARY_HOME"

        private val KNOWN_SYSTEM_NAV_IDS = setOf(
            "back",
            "back_button",
            "nav_back",
            "home",
            "home_button",
            "nav_home",
            "recent_apps",
            "overview",
            "recents",
            "recents_button",
            "nav_recents",
        )

        var instance: TouchpadAccessibilityService? = null
            private set

        private var isMainActivityStarted: Boolean = false

        fun setMainActivityStarted(started: Boolean) {
            isMainActivityStarted = started
            instance?.syncSoftKeyboardMode()
        }

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _externalDisplay = MutableStateFlow<ExternalDisplayInfo?>(null)
        val externalDisplay: StateFlow<ExternalDisplayInfo?> = _externalDisplay.asStateFlow()

        private val _isDragging = MutableStateFlow(false)
        val isDragging: StateFlow<Boolean> = _isDragging.asStateFlow()

        private val _textSession = MutableStateFlow<ExternalTextSession?>(null)
        val textSession: StateFlow<ExternalTextSession?> = _textSession.asStateFlow()
    }
}
