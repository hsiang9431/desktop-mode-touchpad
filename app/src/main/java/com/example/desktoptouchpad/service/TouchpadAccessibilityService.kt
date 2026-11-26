package com.example.desktoptouchpad.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.example.desktoptouchpad.input.ExternalTextBridge
import com.example.desktoptouchpad.input.ExternalTextSession
import com.example.desktoptouchpad.overlay.CursorOverlayManager
import com.example.desktoptouchpad.overlay.ExternalDisplayInfo
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
        info.eventTypes = info.eventTypes or
            AccessibilityEvent.TYPE_VIEW_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val displayId = activeTargetDisplayId() ?: return
        externalTextBridge.onAccessibilityEventQuiet(event, displayId)
    }

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
    }

    fun centerCursor() {
        if (!::overlayManager.isInitialized) return
        overlayManager.centerCursor()
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
        return dispatchGesture(createGestureBuilder(displayId).addStroke(stroke).build(), null, null)
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
        val (clampedX, clampedY) = clampToDisplay(x, y)
        val path = Path().apply { moveTo(clampedX, clampedY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS)
        return dispatchGesture(createGestureBuilder(displayId).addStroke(stroke).build(), null, null)
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

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

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
