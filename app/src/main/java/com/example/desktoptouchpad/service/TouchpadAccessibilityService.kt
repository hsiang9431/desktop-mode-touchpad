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
import kotlin.math.min
import kotlin.math.sqrt
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
    private var dragGeneration = 0
    private var dragRestartCount = 0

    private var isScrollRequested = false
    private var activeScrollStroke: GestureDescription.StrokeDescription? = null
    private var lastScrollX = 0f
    private var lastScrollY = 0f
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private var scrollDisplayId = Display.INVALID_DISPLAY
    private var scrollGeneration = 0
    private var scrollRestartCount = 0

    private var isPinchRequested = false
    private var activePinchStroke1: GestureDescription.StrokeDescription? = null
    private var activePinchStroke2: GestureDescription.StrokeDescription? = null
    private var pinchFocalX = 0f
    private var pinchFocalY = 0f
    private var currentPinchHalfSpan = 0f
    private var minPinchHalfSpan = 90f
    private var maxPinchHalfSpan = 420f
    private var initialPinchHalfSpan = 180f
    private var lastPinchP1X = 0f
    private var lastPinchP1Y = 0f
    private var lastPinchP2X = 0f
    private var lastPinchP2Y = 0f
    private var pendingPinchScale = 1f
    private var pinchDisplayId = Display.INVALID_DISPLAY
    private var pinchGeneration = 0
    private var pinchRestartCount = 0

    private enum class SliceEndMode {
        CONTINUE,
        REANCHOR,
        FINAL_RELEASE,
    }

    private var lastHoverDispatchUptimeMs = 0L
    private var cursorPositionVersion = 0L
    private var inspectedCursorPositionVersion = 0L
    private var tapAutoOpenGeneration = 0L
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

        overlayManager = CursorOverlayManager(
            service = this,
            onAllExternalDisplaysLost = { handleExternalDisplayDisconnected() },
        )
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
                    handleExternalDisplayDisconnected(notifyActivityListener = false)
                } else {
                    scheduleHoverInspection(delayMs = 80L)
                    syncSoftKeyboardMode()
                }
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
        // Intentional no-op: AccessibilityManager.interrupt() is triggered routinely by apps and
        // system UI (e.g. when starting drag-and-drop or changing focus) to silence spoken
        // feedback. Resetting drag state here would drop active drags while the finger is still down.
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
        cancelPendingTapAutoOpen()
        mainHandler.removeCallbacks(deferredHoverCheckRunnable)
        resetDragState()
        resetScrollState()
        resetPinchState()
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

    internal fun handleExternalDisplayDisconnected(notifyActivityListener: Boolean = true) {
        cancelPendingTapAutoOpen()
        mainHandler.removeCallbacks(deferredHoverCheckRunnable)
        isPhoneKeyboardAllowed = false
        resetDragState()
        resetScrollState()
        resetPinchState()
        externalTextBridge.clearAll()
        if (::overlayManager.isInitialized && overlayManager.activeDisplayId != Display.INVALID_DISPLAY) {
            overlayManager.syncTargetDisplay()
        }
        _externalDisplay.value = null
        _textSession.value = null
        applySoftKeyboardShowMode(SHOW_MODE_AUTO)
        if (notifyActivityListener) {
            onExternalDisplayUnpluggedListener?.invoke()
        }
    }

    private fun shouldSuppressExternalSoftKeyboard(): Boolean {
        return (_externalDisplay.value != null) &&
            (activeTargetDisplayId() != null) &&
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

    private fun cancelPendingTapAutoOpen() {
        tapAutoOpenGeneration++
        externalTextBridge.cancelPendingAutoOpen()
    }

    fun requestManualKeyboard(): Boolean {
        cancelPendingTapAutoOpen()
        val displayId = activeTargetDisplayId() ?: return false
        val (cursorX, cursorY) = getCursorPosition()
        return externalTextBridge.openManualKeyboard(displayId, cursorX, cursorY)
    }

    fun dismissKeyboardSession() {
        cancelPendingTapAutoOpen()
        isPhoneKeyboardAllowed = false
        externalTextBridge.dismissKeyboardSession()
    }

    fun forceInputIntoSelectedBox(
        text: String,
        sendEnterAfter: Boolean,
        closeKeyboardAfter: Boolean = false,
    ) {
        if (closeKeyboardAfter) {
            cancelPendingTapAutoOpen()
            isPhoneKeyboardAllowed = false
        }
        externalTextBridge.forceInputIntoSelectedBox(
            text = text,
            sendEnterAfter = sendEnterAfter,
            closeKeyboardAfter = closeKeyboardAfter,
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
        cursorPositionVersion++
        overlayManager.updateCursorPosition(deltaX, deltaY)
        if (!hasActiveContinuousGesture()) {
            throttledHoverInspectionOnMove()
        }
    }

    fun centerCursor() {
        if (!::overlayManager.isInitialized) return
        cursorPositionVersion++
        overlayManager.centerCursor()
        scheduleHoverInspection(delayMs = 30L)
    }

    fun getCursorPosition(): Pair<Float, Float> {
        if (!::overlayManager.isInitialized) return Pair(0f, 0f)
        return overlayManager.getCursorCoordinates()
    }

    private fun isAnotherGestureRequested(): Boolean {
        return isDragRequested || (activeDragStroke != null) || isScrollRequested || isPinchRequested
    }

    private fun clearFinishedAuxiliaryGestures() {
        if (!isScrollRequested && (activeScrollStroke != null)) {
            resetScrollState()
        }
        if (!isPinchRequested && (activePinchStroke1 != null || activePinchStroke2 != null)) {
            resetPinchState()
        }
    }

    fun hasActiveContinuousGesture(): Boolean {
        return isDragActive() || isScrollActive() || isPinchActive()
    }

    fun performTap(x: Float, y: Float): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        if (isAnotherGestureRequested()) return false
        clearFinishedAuxiliaryGestures()

        val gen = ++tapAutoOpenGeneration
        val (clampedX, clampedY) = clampToDisplay(x, y)
        val isSessionCurrentlyClosed = (_textSession.value == null)
        val isCursorStyleFresh = (inspectedCursorPositionVersion == cursorPositionVersion)
        val needsFreshCursorCheck = isSessionCurrentlyClosed &&
            !isCursorStyleFresh &&
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

        var tapCompleted = false
        var styleResolved = !needsFreshCursorCheck
        var clickedWithTextCursor = overlayManager.isTextEditingCursor()

        fun tryAutoOpenKeyboardAfterTap() {
            if (gen != tapAutoOpenGeneration) return
            if (!tapCompleted || !styleResolved) return
            if (clickedWithTextCursor &&
                (_textSession.value == null) &&
                isMainActivityStarted &&
                (activeTargetDisplayId() == displayId)
            ) {
                externalTextBridge.scheduleAutoOpenAfterExternalTap(displayId, clampedX, clampedY)
            }
        }

        if (needsFreshCursorCheck) {
            val capturedPosVersion = cursorPositionVersion
            val (dispW, dispH) = overlayManager.getDisplayDimensions()
            serviceScope.launch(Dispatchers.Default) {
                val resolvedStyle = runCatching {
                    resolveCursorStyleAt(
                        displayId = displayId,
                        x = clampedX.toInt(),
                        y = clampedY.toInt(),
                        displayWidth = dispW,
                        displayHeight = dispH,
                    )
                }.getOrNull()
                mainHandler.post {
                    if (resolvedStyle != null &&
                        ::overlayManager.isInitialized &&
                        activeTargetDisplayId() == displayId
                    ) {
                        overlayManager.setHoverCursorStyle(resolvedStyle)
                        if (capturedPosVersion == cursorPositionVersion) {
                            inspectedCursorPositionVersion = capturedPosVersion
                        }
                    }
                    if (gen == tapAutoOpenGeneration) {
                        clickedWithTextCursor = resolvedStyle?.isTextCursor ?: clickedWithTextCursor
                        styleResolved = true
                        tryAutoOpenKeyboardAfterTap()
                    }
                }
            }
        }

        externalTextBridge.onBeforeExternalTap(displayId, clampedX, clampedY)
        val path = Path().apply { moveTo(clampedX, clampedY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val dispatched = dispatchGesture(
            createGestureBuilder(displayId).addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    scheduleHoverInspection(delayMs = 135L)
                    if (gen != tapAutoOpenGeneration) return
                    tapCompleted = true
                    tryAutoOpenKeyboardAfterTap()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (gen == tapAutoOpenGeneration) {
                        cancelPendingTapAutoOpen()
                    }
                }
            },
            mainHandler,
        )
        if (!dispatched && gen == tapAutoOpenGeneration) {
            cancelPendingTapAutoOpen()
        }
        return dispatched
    }

    private fun performRawTap(displayId: Int, x: Float, y: Float): Boolean {
        if (isAnotherGestureRequested()) return false
        clearFinishedAuxiliaryGestures()
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
        if (isAnotherGestureRequested()) return false
        cancelPendingTapAutoOpen()
        clearFinishedAuxiliaryGestures()

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

    fun isDragActive(): Boolean = isDragRequested || (activeDragStroke != null)

    fun isScrollActive(): Boolean = isScrollRequested || (activeScrollStroke != null)

    fun isPinchActive(): Boolean = isPinchRequested || (activePinchStroke1 != null)

    fun startDrag(): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        if (isDragRequested) return true

        cancelPendingTapAutoOpen()
        if (_textSession.value == null) {
            suppressExternalSoftKeyboard()
        }
        resetScrollState()
        resetPinchState()

        isDragRequested = true
        overlayManager.setDraggingVisual(isDragging = true)
        _isDragging.value = true

        if ((activeDragStroke != null) && (dragDisplayId == displayId)) {
            // Re-armed before the release slice was dispatched; keep holding the existing stroke.
            return true
        }

        dragRestartCount = 0
        return beginDragStroke(displayId)
    }

    private fun beginDragStroke(displayId: Int): Boolean {
        val (rawX, rawY) = overlayManager.getCursorCoordinates()
        val (startX, startY) = clampToDisplay(rawX, rawY)

        dragGeneration++
        dragDisplayId = displayId
        lastDragX = startX
        lastDragY = startY

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
        return dispatchDragSlice(
            displayId = displayId,
            stroke = initialStroke,
            isFinalRelease = false,
            generation = dragGeneration,
        )
    }

    fun endDrag() {
        if (!isDragRequested) return
        isDragRequested = false
        scheduleHoverInspection(delayMs = 120L)
    }

    private fun dispatchDragSlice(
        displayId: Int,
        stroke: GestureDescription.StrokeDescription,
        isFinalRelease: Boolean,
        generation: Int,
    ): Boolean {
        val dispatched = dispatchGesture(
            createGestureBuilder(displayId).addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (generation != dragGeneration) return
                    if (isFinalRelease || (activeDragStroke == null) || (activeTargetDisplayId() != displayId)) {
                        resetDragState()
                        return
                    }

                    dragRestartCount = 0
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
                    dispatchDragSlice(
                        displayId = displayId,
                        stroke = nextStroke,
                        isFinalRelease = !keepHolding,
                        generation = generation,
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (generation != dragGeneration) return
                    if (!isFinalRelease &&
                        isDragRequested &&
                        (activeTargetDisplayId() == displayId) &&
                        (dragRestartCount < MAX_DRAG_RESTARTS)
                    ) {
                        dragRestartCount++
                        beginDragStroke(displayId)
                        return
                    }
                    resetDragState()
                }
            },
            mainHandler
        )

        if (!dispatched) {
            if (generation == dragGeneration) {
                resetDragState()
            }
            return false
        }
        return true
    }

    private fun resetDragState() {
        dragGeneration++
        dragRestartCount = 0
        isDragRequested = false
        activeDragStroke = null
        dragDisplayId = Display.INVALID_DISPLAY
        if (::overlayManager.isInitialized) {
            overlayManager.setDraggingVisual(isDragging = false)
        }
        _isDragging.value = false
    }

    fun startScroll(initialDeltaX: Float = 0f, initialDeltaY: Float = 0f): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        if (isDragActive() || isPinchRequested) return false
        cancelPendingTapAutoOpen()
        if (activePinchStroke1 != null || activePinchStroke2 != null) {
            resetPinchState()
        }

        if (_textSession.value == null) {
            suppressExternalSoftKeyboard()
        }

        pendingScrollX = (pendingScrollX + initialDeltaX)
            .coerceIn(-MAX_PENDING_SCROLL_PX, MAX_PENDING_SCROLL_PX)
        pendingScrollY = (pendingScrollY + initialDeltaY)
            .coerceIn(-MAX_PENDING_SCROLL_PX, MAX_PENDING_SCROLL_PX)

        isScrollRequested = true
        overlayManager.setScrollingVisual(isScrolling = true)

        if ((activeScrollStroke != null) && (scrollDisplayId == displayId)) {
            // Re-armed before the release slice was dispatched; keep holding the existing stroke.
            return true
        }

        scrollRestartCount = 0
        return beginScrollStroke(displayId)
    }

    fun enqueueScroll(deltaX: Float, deltaY: Float) {
        if (isDragActive() || isPinchRequested) return
        val displayId = activeTargetDisplayId() ?: return

        pendingScrollX = (pendingScrollX + deltaX)
            .coerceIn(-MAX_PENDING_SCROLL_PX, MAX_PENDING_SCROLL_PX)
        pendingScrollY = (pendingScrollY + deltaY)
            .coerceIn(-MAX_PENDING_SCROLL_PX, MAX_PENDING_SCROLL_PX)

        if (isScrollRequested) {
            overlayManager.setScrollingVisual(isScrolling = true)
            if (activeScrollStroke == null) {
                beginScrollStroke(displayId)
            }
        }
    }

    fun endScroll() {
        if (!isScrollRequested) return
        isScrollRequested = false
        if (activeScrollStroke == null) {
            resetScrollState()
        }
        scheduleHoverInspection(delayMs = 120L)
    }

    private fun beginScrollStroke(displayId: Int): Boolean {
        val (width, height) = overlayManager.getDisplayDimensions()
        val minCoord = SCROLL_EDGE_MARGIN_PX
        val maxX = (width - SCROLL_EDGE_MARGIN_PX).coerceAtLeast(minCoord + 1f)
        val maxY = (height - SCROLL_EDGE_MARGIN_PX).coerceAtLeast(minCoord + 1f)
        val (cursorX, cursorY) = overlayManager.getCursorCoordinates()

        val desiredDx = pendingScrollX.coerceIn(-SINGLE_SCROLL_SLICE_PX, SINGLE_SCROLL_SLICE_PX)
        val desiredDy = pendingScrollY.coerceIn(-SINGLE_SCROLL_SLICE_PX, SINGLE_SCROLL_SLICE_PX)

        val startX = when {
            cursorX + desiredDx < minCoord -> (minCoord - desiredDx + SCROLL_REANCHOR_HEADROOM_PX).coerceIn(minCoord, maxX)
            cursorX + desiredDx > maxX -> (maxX - desiredDx - SCROLL_REANCHOR_HEADROOM_PX).coerceIn(minCoord, maxX)
            else -> cursorX.coerceIn(minCoord, maxX)
        }
        val startY = when {
            cursorY + desiredDy < minCoord -> (minCoord - desiredDy + SCROLL_REANCHOR_HEADROOM_PX).coerceIn(minCoord, maxY)
            cursorY + desiredDy > maxY -> (maxY - desiredDy - SCROLL_REANCHOR_HEADROOM_PX).coerceIn(minCoord, maxY)
            else -> cursorY.coerceIn(minCoord, maxY)
        }

        val targetX = (startX + desiredDx).coerceIn(minCoord, maxX)
        val targetY = (startY + desiredDy).coerceIn(minCoord, maxY)
        pendingScrollX -= (targetX - startX)
        pendingScrollY -= (targetY - startY)

        scrollGeneration++
        scrollDisplayId = displayId
        lastScrollX = targetX
        lastScrollY = targetY

        val initialPath = Path().apply {
            moveTo(startX, startY)
            lineTo(targetX, targetY)
        }
        val initialStroke = GestureDescription.StrokeDescription(
            initialPath,
            0L,
            SCROLL_SLICE_DURATION_MS,
            /* willContinue = */ true,
        )
        activeScrollStroke = initialStroke
        return dispatchScrollSlice(
            displayId = displayId,
            stroke = initialStroke,
            endMode = SliceEndMode.CONTINUE,
            generation = scrollGeneration,
        )
    }

    private fun dispatchScrollSlice(
        displayId: Int,
        stroke: GestureDescription.StrokeDescription,
        endMode: SliceEndMode,
        generation: Int,
    ): Boolean {
        val dispatched = dispatchGesture(
            createGestureBuilder(displayId).addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (generation != scrollGeneration) return
                    if (activeTargetDisplayId() != displayId) {
                        resetScrollState()
                        return
                    }

                    when (endMode) {
                        SliceEndMode.FINAL_RELEASE -> {
                            resetScrollState()
                            return
                        }
                        SliceEndMode.REANCHOR -> {
                            if (isScrollRequested) {
                                activeScrollStroke = null
                                beginScrollStroke(displayId)
                            } else {
                                resetScrollState()
                            }
                            return
                        }
                        SliceEndMode.CONTINUE -> {
                            if (activeScrollStroke == null) {
                                resetScrollState()
                                return
                            }
                        }
                    }

                    scrollRestartCount = 0
                    val (width, height) = overlayManager.getDisplayDimensions()
                    val minCoord = SCROLL_EDGE_MARGIN_PX
                    val maxX = (width - SCROLL_EDGE_MARGIN_PX).coerceAtLeast(minCoord + 1f)
                    val maxY = (height - SCROLL_EDGE_MARGIN_PX).coerceAtLeast(minCoord + 1f)

                    val desiredDx = pendingScrollX.coerceIn(-SINGLE_SCROLL_SLICE_PX, SINGLE_SCROLL_SLICE_PX)
                    val desiredDy = pendingScrollY.coerceIn(-SINGLE_SCROLL_SLICE_PX, SINGLE_SCROLL_SLICE_PX)
                    val targetX = (lastScrollX + desiredDx).coerceIn(minCoord, maxX)
                    val targetY = (lastScrollY + desiredDy).coerceIn(minCoord, maxY)
                    val actualDx = targetX - lastScrollX
                    val actualDy = targetY - lastScrollY

                    pendingScrollX -= actualDx
                    pendingScrollY -= actualDy

                    val blockedByEdge = (abs(desiredDx - actualDx) > 2f) ||
                        (abs(desiredDy - actualDy) > 2f)

                    val nextEndMode = when {
                        !isScrollRequested -> {
                            pendingScrollX = 0f
                            pendingScrollY = 0f
                            SliceEndMode.FINAL_RELEASE
                        }
                        blockedByEdge -> SliceEndMode.REANCHOR
                        else -> SliceEndMode.CONTINUE
                    }

                    val segmentPath = Path().apply {
                        moveTo(lastScrollX, lastScrollY)
                        lineTo(targetX, targetY)
                    }
                    lastScrollX = targetX
                    lastScrollY = targetY

                    val willContinue = (nextEndMode == SliceEndMode.CONTINUE)
                    val nextStroke = try {
                        stroke.continueStroke(
                            segmentPath,
                            0L,
                            if (willContinue) SCROLL_SLICE_DURATION_MS else SCROLL_RELEASE_DURATION_MS,
                            willContinue,
                        )
                    } catch (_: Exception) {
                        resetScrollState()
                        return
                    }

                    activeScrollStroke = if (willContinue) nextStroke else null
                    dispatchScrollSlice(
                        displayId = displayId,
                        stroke = nextStroke,
                        endMode = nextEndMode,
                        generation = generation,
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (generation != scrollGeneration) return
                    if ((endMode == SliceEndMode.CONTINUE) &&
                        isScrollRequested &&
                        (activeTargetDisplayId() == displayId) &&
                        (scrollRestartCount < MAX_SCROLL_RESTARTS)
                    ) {
                        scrollRestartCount++
                        beginScrollStroke(displayId)
                        return
                    }
                    resetScrollState()
                }
            },
            mainHandler
        )

        if (!dispatched) {
            if (generation == scrollGeneration) {
                resetScrollState()
            }
            return false
        }
        return true
    }

    private fun resetScrollState() {
        scrollGeneration++
        scrollRestartCount = 0
        isScrollRequested = false
        activeScrollStroke = null
        pendingScrollX = 0f
        pendingScrollY = 0f
        scrollDisplayId = Display.INVALID_DISPLAY
        if (::overlayManager.isInitialized) {
            overlayManager.setScrollingVisual(isScrolling = false)
        }
    }

    fun startPinch(initialScaleFactor: Float = 1f): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        if (isDragActive() || isScrollRequested) return false
        cancelPendingTapAutoOpen()
        if (activeScrollStroke != null) {
            resetScrollState()
        }

        if (_textSession.value == null) {
            suppressExternalSoftKeyboard()
        }

        pendingPinchScale = (pendingPinchScale * initialScaleFactor)
            .coerceIn(MIN_PENDING_PINCH_SCALE, MAX_PENDING_PINCH_SCALE)
        isPinchRequested = true
        overlayManager.setPinchZoomVisual(
            if (initialScaleFactor < 0.998f) CursorStyle.ZOOM_OUT else CursorStyle.ZOOM_IN
        )

        if ((activePinchStroke1 != null) &&
            (activePinchStroke2 != null) &&
            (pinchDisplayId == displayId)
        ) {
            return true
        }

        pinchRestartCount = 0
        return beginPinchStroke(displayId)
    }

    fun enqueuePinchZoom(scaleFactor: Float) {
        if (isDragActive() || isScrollRequested) return
        val displayId = activeTargetDisplayId() ?: return

        pendingPinchScale = (pendingPinchScale * scaleFactor)
            .coerceIn(MIN_PENDING_PINCH_SCALE, MAX_PENDING_PINCH_SCALE)

        if (isPinchRequested) {
            when {
                scaleFactor > 1.002f -> overlayManager.setPinchZoomVisual(CursorStyle.ZOOM_IN)
                scaleFactor < 0.998f -> overlayManager.setPinchZoomVisual(CursorStyle.ZOOM_OUT)
            }
            if (activePinchStroke1 == null || activePinchStroke2 == null) {
                beginPinchStroke(displayId)
            }
        }
    }

    fun endPinch() {
        if (!isPinchRequested) return
        isPinchRequested = false
        if (activePinchStroke1 == null || activePinchStroke2 == null) {
            resetPinchState()
        }
        scheduleHoverInspection(delayMs = 120L)
    }

    private fun resolvePinchBoundsAtCursor(
        displayId: Int,
        cursorX: Float,
        cursorY: Float,
        displayWidth: Int,
        displayHeight: Int,
    ): Rect {
        val fullBounds = Rect(0, 0, displayWidth, displayHeight)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fullBounds

        val windows = windowsOnAllDisplays[displayId]
            .orEmpty()
            .filter { win ->
                win.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    win.type != AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        win.type != AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY) &&
                    win.root?.packageName?.toString() != packageName
            }
            .sortedByDescending { it.layer }

        val winBounds = Rect()
        val cx = cursorX.toInt()
        val cy = cursorY.toInt()
        for (win in windows) {
            win.getBoundsInScreen(winBounds)
            if (winBounds.isEmpty || !winBounds.contains(cx, cy)) continue
            if (winBounds.intersect(fullBounds) &&
                winBounds.width() >= MIN_PINCH_WINDOW_SIZE_PX &&
                winBounds.height() >= MIN_PINCH_WINDOW_SIZE_PX
            ) {
                return winBounds
            }
            break
        }
        return fullBounds
    }

    private fun pinchFingerPoints(focalX: Float, focalY: Float, halfSpan: Float): FloatArray {
        val offset = halfSpan * INV_SQRT_2
        return floatArrayOf(
            focalX - offset,
            focalY + offset,
            focalX + offset,
            focalY - offset,
        )
    }

    private fun beginPinchStroke(displayId: Int): Boolean {
        val (dispW, dispH) = overlayManager.getDisplayDimensions()
        val (cursorX, cursorY) = overlayManager.getCursorCoordinates()
        val targetBounds = resolvePinchBoundsAtCursor(displayId, cursorX, cursorY, dispW, dispH)

        val safeLeft = (targetBounds.left + PINCH_EDGE_MARGIN_PX).coerceAtLeast(PINCH_EDGE_MARGIN_PX)
        val safeTop = (targetBounds.top + PINCH_EDGE_MARGIN_PX).coerceAtLeast(PINCH_EDGE_MARGIN_PX)
        val safeRight = (targetBounds.right - PINCH_EDGE_MARGIN_PX)
            .coerceAtMost(dispW - PINCH_EDGE_MARGIN_PX)
            .coerceAtLeast(safeLeft + 32f)
        val safeBottom = (targetBounds.bottom - PINCH_EDGE_MARGIN_PX)
            .coerceAtMost(dispH - PINCH_EDGE_MARGIN_PX)
            .coerceAtLeast(safeTop + 32f)

        val usableMinDim = min(safeRight - safeLeft, safeBottom - safeTop).coerceAtLeast(64f)
        val rawMinHalfSpan = (overlayManager.getMinScalingSpanPx() * 0.56f).coerceAtLeast(44f)
        val computedMaxHalfSpan = min(
            usableMinDim * 0.45f / INV_SQRT_2,
            rawMinHalfSpan * 3.8f,
        ).coerceAtLeast(56f)
        val computedMinHalfSpan = min(rawMinHalfSpan, computedMaxHalfSpan * 0.42f).coerceAtLeast(24f)
        val computedInitialHalfSpan = sqrt(computedMinHalfSpan * computedMaxHalfSpan)
            .coerceIn(computedMinHalfSpan, computedMaxHalfSpan)

        val maxAxisOffset = computedMaxHalfSpan * INV_SQRT_2
        val minFocalX = (safeLeft + maxAxisOffset).coerceAtMost((safeLeft + safeRight) * 0.5f)
        val maxFocalX = (safeRight - maxAxisOffset).coerceAtLeast(minFocalX)
        val minFocalY = (safeTop + maxAxisOffset).coerceAtMost((safeTop + safeBottom) * 0.5f)
        val maxFocalY = (safeBottom - maxAxisOffset).coerceAtLeast(minFocalY)

        pinchFocalX = cursorX.coerceIn(minFocalX, maxFocalX)
        pinchFocalY = cursorY.coerceIn(minFocalY, maxFocalY)
        minPinchHalfSpan = computedMinHalfSpan
        maxPinchHalfSpan = computedMaxHalfSpan
        initialPinchHalfSpan = computedInitialHalfSpan

        val startHalfSpan = initialPinchHalfSpan
        val desiredScale = pendingPinchScale.coerceIn(MIN_SLICE_PINCH_SCALE, MAX_SLICE_PINCH_SCALE)
        val slopLead = (overlayManager.getTouchSlopPx() * 0.9f).coerceIn(8f, 24f)
        val signedLead = when {
            desiredScale > 1.002f -> slopLead
            desiredScale < 0.998f -> -slopLead
            else -> 0f
        }
        val targetHalfSpan = (startHalfSpan * desiredScale + signedLead)
            .coerceIn(minPinchHalfSpan, maxPinchHalfSpan)

        val consumedScale = if (startHalfSpan > 1f) {
            ((targetHalfSpan - signedLead).coerceAtLeast(1f) / startHalfSpan)
        } else {
            1f
        }
        if (consumedScale > 0.05f) {
            pendingPinchScale = (pendingPinchScale / consumedScale)
                .coerceIn(MIN_PENDING_PINCH_SCALE, MAX_PENDING_PINCH_SCALE)
        }

        val startPts = pinchFingerPoints(pinchFocalX, pinchFocalY, startHalfSpan)
        val targetPts = pinchFingerPoints(pinchFocalX, pinchFocalY, targetHalfSpan)

        pinchGeneration++
        pinchDisplayId = displayId
        currentPinchHalfSpan = targetHalfSpan
        lastPinchP1X = targetPts[0]
        lastPinchP1Y = targetPts[1]
        lastPinchP2X = targetPts[2]
        lastPinchP2Y = targetPts[3]

        val path1 = Path().apply {
            moveTo(startPts[0], startPts[1])
            lineTo(targetPts[0], targetPts[1])
        }
        val path2 = Path().apply {
            moveTo(startPts[2], startPts[3])
            lineTo(targetPts[2], targetPts[3])
        }

        val stroke1 = GestureDescription.StrokeDescription(
            path1,
            0L,
            PINCH_SLICE_DURATION_MS,
            /* willContinue = */ true,
        )
        val stroke2 = GestureDescription.StrokeDescription(
            path2,
            0L,
            PINCH_SLICE_DURATION_MS,
            /* willContinue = */ true,
        )
        activePinchStroke1 = stroke1
        activePinchStroke2 = stroke2

        return dispatchPinchSlice(
            displayId = displayId,
            stroke1 = stroke1,
            stroke2 = stroke2,
            endMode = SliceEndMode.CONTINUE,
            generation = pinchGeneration,
        )
    }

    private fun dispatchPinchSlice(
        displayId: Int,
        stroke1: GestureDescription.StrokeDescription,
        stroke2: GestureDescription.StrokeDescription,
        endMode: SliceEndMode,
        generation: Int,
    ): Boolean {
        val gesture = createGestureBuilder(displayId)
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (generation != pinchGeneration) return
                    if (activeTargetDisplayId() != displayId) {
                        resetPinchState()
                        return
                    }

                    when (endMode) {
                        SliceEndMode.FINAL_RELEASE -> {
                            resetPinchState()
                            return
                        }
                        SliceEndMode.REANCHOR -> {
                            if (isPinchRequested) {
                                activePinchStroke1 = null
                                activePinchStroke2 = null
                                beginPinchStroke(displayId)
                            } else {
                                resetPinchState()
                            }
                            return
                        }
                        SliceEndMode.CONTINUE -> {
                            if (activePinchStroke1 == null || activePinchStroke2 == null) {
                                resetPinchState()
                                return
                            }
                        }
                    }

                    pinchRestartCount = 0
                    val desiredScale = pendingPinchScale.coerceIn(
                        MIN_SLICE_PINCH_SCALE,
                        MAX_SLICE_PINCH_SCALE,
                    )
                    val targetHalfSpan = (currentPinchHalfSpan * desiredScale)
                        .coerceIn(minPinchHalfSpan, maxPinchHalfSpan)
                    val actualScale = if (currentPinchHalfSpan > 1f) {
                        targetHalfSpan / currentPinchHalfSpan
                    } else {
                        1f
                    }
                    if (actualScale > 0.05f) {
                        pendingPinchScale = (pendingPinchScale / actualScale)
                            .coerceIn(MIN_PENDING_PINCH_SCALE, MAX_PENDING_PINCH_SCALE)
                    }

                    val hitMinWhileZoomingOut = (targetHalfSpan <= minPinchHalfSpan + 0.5f) &&
                        (desiredScale < 0.985f)
                    val hitMaxWhileZoomingIn = (targetHalfSpan >= maxPinchHalfSpan - 0.5f) &&
                        (desiredScale > 1.015f)

                    val nextEndMode = when {
                        !isPinchRequested -> {
                            pendingPinchScale = 1f
                            SliceEndMode.FINAL_RELEASE
                        }
                        hitMinWhileZoomingOut || hitMaxWhileZoomingIn -> SliceEndMode.REANCHOR
                        else -> SliceEndMode.CONTINUE
                    }

                    val targetPts = pinchFingerPoints(pinchFocalX, pinchFocalY, targetHalfSpan)
                    val nextPath1 = Path().apply {
                        moveTo(lastPinchP1X, lastPinchP1Y)
                        lineTo(targetPts[0], targetPts[1])
                    }
                    val nextPath2 = Path().apply {
                        moveTo(lastPinchP2X, lastPinchP2Y)
                        lineTo(targetPts[2], targetPts[3])
                    }

                    currentPinchHalfSpan = targetHalfSpan
                    lastPinchP1X = targetPts[0]
                    lastPinchP1Y = targetPts[1]
                    lastPinchP2X = targetPts[2]
                    lastPinchP2Y = targetPts[3]

                    val willContinue = (nextEndMode == SliceEndMode.CONTINUE)
                    val duration = if (willContinue) {
                        PINCH_SLICE_DURATION_MS
                    } else {
                        PINCH_RELEASE_DURATION_MS
                    }

                    val nextStroke1: GestureDescription.StrokeDescription
                    val nextStroke2: GestureDescription.StrokeDescription
                    try {
                        nextStroke1 = stroke1.continueStroke(nextPath1, 0L, duration, willContinue)
                        nextStroke2 = stroke2.continueStroke(nextPath2, 0L, duration, willContinue)
                    } catch (_: Exception) {
                        resetPinchState()
                        return
                    }

                    activePinchStroke1 = if (willContinue) nextStroke1 else null
                    activePinchStroke2 = if (willContinue) nextStroke2 else null
                    dispatchPinchSlice(
                        displayId = displayId,
                        stroke1 = nextStroke1,
                        stroke2 = nextStroke2,
                        endMode = nextEndMode,
                        generation = generation,
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (generation != pinchGeneration) return
                    if ((endMode == SliceEndMode.CONTINUE) &&
                        isPinchRequested &&
                        (activeTargetDisplayId() == displayId) &&
                        (pinchRestartCount < MAX_PINCH_RESTARTS)
                    ) {
                        pinchRestartCount++
                        beginPinchStroke(displayId)
                        return
                    }
                    resetPinchState()
                }
            },
            mainHandler
        )

        if (!dispatched) {
            if (generation == pinchGeneration) {
                resetPinchState()
            }
            return false
        }
        return true
    }

    private fun resetPinchState() {
        pinchGeneration++
        pinchRestartCount = 0
        isPinchRequested = false
        activePinchStroke1 = null
        activePinchStroke2 = null
        pendingPinchScale = 1f
        pinchDisplayId = Display.INVALID_DISPLAY
        if (::overlayManager.isInitialized) {
            overlayManager.setPinchZoomVisual(null)
        }
    }

    /**
     * Executes Back strictly on the connected external display without minimizing or backing out
     * of the phone touchpad Activity on DEFAULT_DISPLAY.
     */
    fun performBack(): Boolean {
        val displayId = activeTargetDisplayId() ?: return false
        cancelPendingTapAutoOpen()
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
        cancelPendingTapAutoOpen()
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
        cancelPendingTapAutoOpen()
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
        if (isAnotherGestureRequested()) return false
        clearFinishedAuxiliaryGestures()
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
        if (isAnotherGestureRequested()) return false
        clearFinishedAuxiliaryGestures()
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
        if (isAnotherGestureRequested()) return false
        clearFinishedAuxiliaryGestures()
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
        val capturedVersion = cursorPositionVersion
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
                        if (capturedVersion == cursorPositionVersion) {
                            inspectedCursorPositionVersion = capturedVersion
                        }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
                    val hasDragStart = node.actionList.any {
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_START.id
                    }
                    if (hasDragStart) {
                        return CursorStyle.GRAB
                    }
                }

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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    !node.tooltipText.isNullOrBlank()
                ) {
                    return CursorStyle.HELP
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
        private const val MAX_DRAG_RESTARTS = 3

        private const val SCROLL_SLICE_DURATION_MS = 45L
        private const val SCROLL_RELEASE_DURATION_MS = 25L
        private const val MAX_SCROLL_RESTARTS = 3
        private const val SCROLL_EDGE_MARGIN_PX = 4f
        private const val SCROLL_REANCHOR_HEADROOM_PX = 28f
        private const val SINGLE_SCROLL_SLICE_PX = 320f
        private const val MAX_PENDING_SCROLL_PX = 960f

        private const val PINCH_SLICE_DURATION_MS = 45L
        private const val PINCH_RELEASE_DURATION_MS = 25L
        private const val MAX_PINCH_RESTARTS = 3
        private const val PINCH_EDGE_MARGIN_PX = 8f
        private const val MIN_PINCH_WINDOW_SIZE_PX = 220
        private const val MIN_SLICE_PINCH_SCALE = 0.65f
        private const val MAX_SLICE_PINCH_SCALE = 1.55f
        private const val MIN_PENDING_PINCH_SCALE = 0.15f
        private const val MAX_PENDING_PINCH_SCALE = 6.5f
        private const val INV_SQRT_2 = 0.70710677f

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

        var onExternalDisplayUnpluggedListener: (() -> Unit)? = null

        private var isMainActivityStarted: Boolean = false

        fun setMainActivityStarted(started: Boolean) {
            isMainActivityStarted = started
            instance?.syncSoftKeyboardMode()
        }

        fun notifyExternalDisplayUnplugged() {
            instance?.handleExternalDisplayDisconnected(notifyActivityListener = false)
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
