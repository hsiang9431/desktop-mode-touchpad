package com.example.desktoptouchpad.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import com.example.desktoptouchpad.R
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExternalDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int
)

class CursorOverlayManager(private val service: AccessibilityService) {

    private val displayManager =
        service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var cursorView: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    private var cursorX = 0f
    private var cursorY = 0f
    private var displayWidth = 1920
    private var displayHeight = 1080
    private var hotspotOffsetPx = 2

    var activeDisplayId: Int = Display.INVALID_DISPLAY
        private set

    private val _displayInfo = MutableStateFlow<ExternalDisplayInfo?>(null)
    val displayInfo: StateFlow<ExternalDisplayInfo?> = _displayInfo.asStateFlow()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            syncTargetDisplay()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == activeDisplayId) {
                detachOverlay()
            }
            syncTargetDisplay()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == activeDisplayId) {
                refreshActiveDisplayBounds()
            } else {
                syncTargetDisplay()
            }
        }
    }

    fun start() {
        displayManager.registerDisplayListener(displayListener, mainHandler)
        syncTargetDisplay()
    }

    fun stop() {
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (_: IllegalArgumentException) {
            // Listener was not registered
        }
        detachOverlay()
    }

    fun syncTargetDisplay() {
        val targetDisplay = findExternalDisplay()
        if (targetDisplay == null) {
            if (activeDisplayId != Display.INVALID_DISPLAY) {
                detachOverlay()
            }
            return
        }

        if (targetDisplay.displayId == activeDisplayId && cursorView != null) {
            refreshActiveDisplayBounds()
            return
        }

        detachOverlay()
        attachToDisplay(targetDisplay)
    }

    private fun findExternalDisplay(): Display? {
        val secondaryDisplays = displayManager.displays.filter { display ->
            display.displayId != Display.DEFAULT_DISPLAY &&
                display.state != Display.STATE_OFF
        }
        return secondaryDisplays.firstOrNull { (it.flags and Display.FLAG_PRESENTATION) != 0 }
            ?: secondaryDisplays.firstOrNull { (it.flags and Display.FLAG_PRIVATE) == 0 }
            ?: secondaryDisplays.firstOrNull()
    }

    private fun attachToDisplay(display: Display) {
        val overlayContext = createOverlayContext(display)
        val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (width, height) = readDisplayBounds(wm, display)

        displayWidth = width
        displayHeight = height
        cursorX = width / 2f
        cursorY = height / 2f

        val density = overlayContext.resources.displayMetrics.density.coerceAtLeast(1f)
        val cursorSizePx = (28f * density).roundToInt().coerceAtLeast(28)
        hotspotOffsetPx = (cursorSizePx * (1.5f / 24f)).roundToInt()

        val imageView = ImageView(overlayContext).apply {
            setImageResource(R.drawable.ic_cursor)
            scaleType = ImageView.ScaleType.FIT_XY
        }

        val layoutParams = createOverlayLayoutParams(cursorSizePx)

        val added = tryAddOverlayView(wm, imageView, layoutParams) ||
            tryFallbackDisplayContext(display, cursorSizePx)

        if (added) {
            activeDisplayId = display.displayId
            _displayInfo.value = ExternalDisplayInfo(
                displayId = display.displayId,
                name = display.name ?: "External Display",
                width = displayWidth,
                height = displayHeight
            )
        }
    }

    private fun createOverlayContext(display: Display): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service.createWindowContext(
                display,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                null
            )
        } else {
            service.createDisplayContext(display)
        }
    }

    private fun createOverlayLayoutParams(cursorSizePx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            cursorSizePx,
            cursorSizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.roundToInt() - hotspotOffsetPx
            y = cursorY.roundToInt() - hotspotOffsetPx
        }
    }

    private fun tryAddOverlayView(
        wm: WindowManager,
        view: ImageView,
        layoutParams: WindowManager.LayoutParams
    ): Boolean {
        return try {
            wm.addView(view, layoutParams)
            windowManager = wm
            cursorView = view
            params = layoutParams
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryFallbackDisplayContext(display: Display, cursorSizePx: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val fallbackContext = service.createDisplayContext(display)
            val fallbackWm =
                fallbackContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val fallbackView = ImageView(fallbackContext).apply {
                setImageResource(R.drawable.ic_cursor)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            val fallbackParams = createOverlayLayoutParams(cursorSizePx)
            fallbackWm.addView(fallbackView, fallbackParams)
            windowManager = fallbackWm
            cursorView = fallbackView
            params = fallbackParams
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshActiveDisplayBounds() {
        val display = displayManager.getDisplay(activeDisplayId)
        if (display == null || display.state == Display.STATE_OFF) {
            detachOverlay()
            syncTargetDisplay()
            return
        }
        val wm = windowManager ?: return
        val (newWidth, newHeight) = readDisplayBounds(wm, display)
        if (newWidth != displayWidth || newHeight != displayHeight) {
            displayWidth = newWidth
            displayHeight = newHeight
            cursorX = cursorX.coerceIn(0f, (displayWidth - 1).coerceAtLeast(1).toFloat())
            cursorY = cursorY.coerceIn(0f, (displayHeight - 1).coerceAtLeast(1).toFloat())
            applyCursorLayout()
            _displayInfo.value = ExternalDisplayInfo(
                displayId = display.displayId,
                name = display.name ?: "External Display",
                width = displayWidth,
                height = displayHeight
            )
        }
    }

    private fun readDisplayBounds(wm: WindowManager, display: Display): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Pair(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            Pair(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
        }
    }

    fun updateCursorPosition(deltaX: Float, deltaY: Float) {
        if (activeDisplayId == Display.INVALID_DISPLAY) return
        val maxX = (displayWidth - 1).coerceAtLeast(1).toFloat()
        val maxY = (displayHeight - 1).coerceAtLeast(1).toFloat()
        cursorX = (cursorX + deltaX).coerceIn(0f, maxX)
        cursorY = (cursorY + deltaY).coerceIn(0f, maxY)
        applyCursorLayout()
    }

    fun centerCursor() {
        if (activeDisplayId == Display.INVALID_DISPLAY) return
        cursorX = displayWidth / 2f
        cursorY = displayHeight / 2f
        applyCursorLayout()
    }

    fun setDraggingVisual(isDragging: Boolean) {
        cursorView?.apply {
            scaleX = if (isDragging) 0.9f else 1.0f
            scaleY = if (isDragging) 0.9f else 1.0f
            alpha = if (isDragging) 0.85f else 1.0f
        }
    }

    private fun applyCursorLayout() {
        val view = cursorView ?: return
        val layoutParams = params ?: return
        layoutParams.x = cursorX.roundToInt() - hotspotOffsetPx
        layoutParams.y = cursorY.roundToInt() - hotspotOffsetPx
        try {
            windowManager?.updateViewLayout(view, layoutParams)
        } catch (_: Exception) {
            detachOverlay()
        }
    }

    fun getCursorCoordinates(): Pair<Float, Float> = Pair(cursorX, cursorY)

    fun getDisplayDimensions(): Pair<Int, Int> = Pair(displayWidth, displayHeight)

    private fun detachOverlay() {
        cursorView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
                // View was already detached
            }
        }
        cursorView = null
        params = null
        windowManager = null
        activeDisplayId = Display.INVALID_DISPLAY
        _displayInfo.value = null
    }
}
