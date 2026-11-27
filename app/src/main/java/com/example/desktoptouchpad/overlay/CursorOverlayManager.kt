package com.example.desktoptouchpad.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.PointerIcon
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.annotation.DrawableRes
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

const val POINTER_ICON_TYPE_HANDWRITING_COMPAT = 1022
const val POINTER_ICON_TYPE_SPOT_HOVER_COMPAT = 2000
const val POINTER_ICON_TYPE_SPOT_TOUCH_COMPAT = 2001
const val POINTER_ICON_TYPE_SPOT_ANCHOR_COMPAT = 2002

enum class CursorStyle(
    @DrawableRes val drawableRes: Int,
    val hotspotXFraction: Float,
    val hotspotYFraction: Float,
    val pointerIconType: Int,
) {
    ARROW(R.drawable.ic_cursor, 4.5f / 24f, 3.5f / 24f, PointerIcon.TYPE_ARROW),
    CONTEXT_MENU(R.drawable.ic_cursor_context_menu, 4.5f / 24f, 3.5f / 24f, PointerIcon.TYPE_CONTEXT_MENU),
    HAND(R.drawable.ic_cursor_hand, 9.5f / 24f, 2.5f / 24f, PointerIcon.TYPE_HAND),
    HELP(R.drawable.ic_cursor_help, 4.5f / 24f, 3.5f / 24f, PointerIcon.TYPE_HELP),
    WAIT(R.drawable.ic_cursor_wait, 12f / 24f, 12f / 24f, PointerIcon.TYPE_WAIT),
    CELL(R.drawable.ic_cursor_cell, 12f / 24f, 12f / 24f, PointerIcon.TYPE_CELL),
    CROSSHAIR(R.drawable.ic_cursor_crosshair, 12f / 24f, 12f / 24f, PointerIcon.TYPE_CROSSHAIR),
    TEXT(R.drawable.ic_cursor_text, 12f / 24f, 11f / 24f, PointerIcon.TYPE_TEXT),
    VERTICAL_TEXT(R.drawable.ic_cursor_vertical_text, 12f / 24f, 12f / 24f, PointerIcon.TYPE_VERTICAL_TEXT),
    ALIAS(R.drawable.ic_cursor_alias, 11.5f / 24f, 11.5f / 24f, PointerIcon.TYPE_ALIAS),
    COPY(R.drawable.ic_cursor_copy, 8.5f / 24f, 7.5f / 24f, PointerIcon.TYPE_COPY),
    NO_DROP(R.drawable.ic_cursor_nodrop, 8.5f / 24f, 7.5f / 24f, PointerIcon.TYPE_NO_DROP),
    ALL_SCROLL(R.drawable.ic_cursor_all_scroll, 12f / 24f, 12f / 24f, PointerIcon.TYPE_ALL_SCROLL),
    RESIZE_HORIZONTAL(
        R.drawable.ic_cursor_resize_horizontal,
        12f / 24f,
        12f / 24f,
        PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,
    ),
    RESIZE_VERTICAL(
        R.drawable.ic_cursor_resize_vertical,
        12f / 24f,
        12f / 24f,
        PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW,
    ),
    RESIZE_DIAG_NE_SW(
        R.drawable.ic_cursor_resize_diag_ne_sw,
        12f / 24f,
        12f / 24f,
        PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW,
    ),
    RESIZE_DIAG_NW_SE(
        R.drawable.ic_cursor_resize_diag_nw_se,
        12f / 24f,
        12f / 24f,
        PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW,
    ),
    ZOOM_IN(R.drawable.ic_cursor_zoom_in, 10.5f / 24f, 9.5f / 24f, PointerIcon.TYPE_ZOOM_IN),
    ZOOM_OUT(R.drawable.ic_cursor_zoom_out, 10.5f / 24f, 9.5f / 24f, PointerIcon.TYPE_ZOOM_OUT),
    GRAB(R.drawable.ic_cursor_grab, 9.5f / 24f, 4.5f / 24f, PointerIcon.TYPE_GRAB),
    GRABBING(R.drawable.ic_cursor_grabbing, 8.5f / 24f, 7.5f / 24f, PointerIcon.TYPE_GRABBING),
    HANDWRITING(
        R.drawable.ic_cursor_handwriting,
        8.25f / 24f,
        23.75f / 24f,
        POINTER_ICON_TYPE_HANDWRITING_COMPAT,
    ),
    SPOT_HOVER(R.drawable.ic_cursor_spot_hover, 12f / 24f, 12f / 24f, POINTER_ICON_TYPE_SPOT_HOVER_COMPAT),
    SPOT_TOUCH(R.drawable.ic_cursor_spot_touch, 12f / 24f, 12f / 24f, POINTER_ICON_TYPE_SPOT_TOUCH_COMPAT),
    SPOT_ANCHOR(R.drawable.ic_cursor_spot_anchor, 12f / 24f, 12f / 24f, POINTER_ICON_TYPE_SPOT_ANCHOR_COMPAT);

    val isResizeCursor: Boolean
        get() = this == RESIZE_HORIZONTAL ||
            this == RESIZE_VERTICAL ||
            this == RESIZE_DIAG_NW_SE ||
            this == RESIZE_DIAG_NE_SW

    companion object {
        @Suppress("unused")
        fun fromPointerIconType(type: Int): CursorStyle {
            return entries.firstOrNull { it.pointerIconType == type } ?: ARROW
        }
    }
}

class CursorOverlayManager(
    private val service: AccessibilityService,
    private val onAllExternalDisplaysLost: (() -> Unit)? = null,
) {

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
    private var cursorSizePx = 28
    private var hotspotOffsetXPx = 5
    private var hotspotOffsetYPx = 4
    private var minScalingSpanPx = 170f
    private var touchSlopPx = 16f

    private var appliedStyle: CursorStyle = CursorStyle.ARROW
    private var hoverStyle: CursorStyle = CursorStyle.ARROW
    private var dragResizeStyle: CursorStyle? = null
    private var pinchZoomStyle: CursorStyle? = null
    private var isDraggingActive = false
    private var isScrollingActive = false
    private var isContextClickActive = false

    private val clearContextVisualRunnable = Runnable {
        isContextClickActive = false
        refreshEffectiveCursorStyle()
    }

    var activeDisplayId: Int = Display.INVALID_DISPLAY
        private set

    private val _displayInfo = MutableStateFlow<ExternalDisplayInfo?>(null)
    val displayInfo: StateFlow<ExternalDisplayInfo?> = _displayInfo.asStateFlow()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            syncTargetDisplay()
        }

        override fun onDisplayRemoved(displayId: Int) {
            val wasActive = (displayId == activeDisplayId)
            if (wasActive) {
                detachOverlay()
            }
            syncTargetDisplay()
            if (wasActive &&
                activeDisplayId == Display.INVALID_DISPLAY &&
                !hasConnectedExternalDisplay(displayManager)
            ) {
                onAllExternalDisplaysLost?.invoke()
            }
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

    private fun findExternalDisplay(): Display? = findExternalDisplay(displayManager)

    private fun attachToDisplay(display: Display) {
        val overlayContext = createOverlayContext(display)
        val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (width, height) = readDisplayBounds(wm, display)

        displayWidth = width
        displayHeight = height
        cursorX = width / 2f
        cursorY = height / 2f

        val density = overlayContext.resources.displayMetrics.density.coerceAtLeast(1f)
        val viewConfig = ViewConfiguration.get(overlayContext)
        minScalingSpanPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            viewConfig.scaledMinimumScalingSpan.toFloat().coerceAtLeast(48f)
        } else {
            170f * density
        }
        touchSlopPx = viewConfig.scaledTouchSlop.toFloat().coerceAtLeast(8f)
        cursorSizePx = (28f * density).roundToInt().coerceAtLeast(28)
        appliedStyle = CursorStyle.ARROW
        hoverStyle = CursorStyle.ARROW
        dragResizeStyle = null
        pinchZoomStyle = null
        isDraggingActive = false
        isScrollingActive = false
        isContextClickActive = false
        updateHotspotOffsetsForStyle(appliedStyle)

        val imageView = ImageView(overlayContext).apply {
            setImageResource(appliedStyle.drawableRes)
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

    private fun updateHotspotOffsetsForStyle(style: CursorStyle) {
        hotspotOffsetXPx = (cursorSizePx * style.hotspotXFraction).roundToInt()
        hotspotOffsetYPx = (cursorSizePx * style.hotspotYFraction).roundToInt()
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

    private fun createOverlayLayoutParams(sizePx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.roundToInt() - hotspotOffsetXPx
            y = cursorY.roundToInt() - hotspotOffsetYPx
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

    private fun tryFallbackDisplayContext(display: Display, sizePx: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val fallbackContext = service.createDisplayContext(display)
            val fallbackWm =
                fallbackContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val fallbackView = ImageView(fallbackContext).apply {
                setImageResource(appliedStyle.drawableRes)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            val fallbackParams = createOverlayLayoutParams(sizePx)
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
        if (display == null) {
            detachOverlay()
            syncTargetDisplay()
            if (activeDisplayId == Display.INVALID_DISPLAY &&
                !hasConnectedExternalDisplay(displayManager)
            ) {
                onAllExternalDisplaysLost?.invoke()
            }
            return
        }
        if (display.state == Display.STATE_OFF) {
            // Monitor may blank briefly during HDMI/DP link training or mode-switching on plug-in.
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

    fun setHoverCursorStyle(style: CursorStyle) {
        if (hoverStyle == style) return
        hoverStyle = style
        refreshEffectiveCursorStyle()
    }

    fun setDraggingVisual(isDragging: Boolean) {
        if (isDraggingActive == isDragging) return
        isDraggingActive = isDragging
        dragResizeStyle = if (isDragging && hoverStyle.isResizeCursor) {
            hoverStyle
        } else {
            null
        }
        refreshEffectiveCursorStyle()
    }

    fun setScrollingVisual(isScrolling: Boolean) {
        if (isScrollingActive == isScrolling) return
        isScrollingActive = isScrolling
        refreshEffectiveCursorStyle()
    }

    fun setPinchZoomVisual(zoomStyle: CursorStyle?) {
        val normalized = zoomStyle?.takeIf {
            it == CursorStyle.ZOOM_IN || it == CursorStyle.ZOOM_OUT
        }
        if (pinchZoomStyle == normalized) return
        pinchZoomStyle = normalized
        refreshEffectiveCursorStyle()
    }

    fun notifyContextClickVisual() {
        mainHandler.removeCallbacks(clearContextVisualRunnable)
        if (!isContextClickActive) {
            isContextClickActive = true
            refreshEffectiveCursorStyle()
        }
        mainHandler.postDelayed(clearContextVisualRunnable, 320L)
    }

    private fun refreshEffectiveCursorStyle() {
        if (activeDisplayId == Display.INVALID_DISPLAY) return
        val desired = when {
            isDraggingActive -> dragResizeStyle ?: CursorStyle.GRABBING
            isContextClickActive -> CursorStyle.CONTEXT_MENU
            pinchZoomStyle != null -> pinchZoomStyle!!
            isScrollingActive -> CursorStyle.ALL_SCROLL
            else -> hoverStyle
        }
        if (desired == appliedStyle) return
        appliedStyle = desired
        updateHotspotOffsetsForStyle(desired)
        cursorView?.let { view ->
            (view.drawable as? Animatable)?.stop()
            view.setImageResource(desired.drawableRes)
            (view.drawable as? Animatable)?.start()
        }
        applyCursorLayout()
    }

    private fun applyCursorLayout() {
        val view = cursorView ?: return
        val layoutParams = params ?: return
        layoutParams.x = cursorX.roundToInt() - hotspotOffsetXPx
        layoutParams.y = cursorY.roundToInt() - hotspotOffsetYPx
        try {
            windowManager?.updateViewLayout(view, layoutParams)
        } catch (_: Exception) {
            detachOverlay()
        }
    }

    fun getCursorCoordinates(): Pair<Float, Float> = Pair(cursorX, cursorY)

    fun getDisplayDimensions(): Pair<Int, Int> = Pair(displayWidth, displayHeight)

    fun getMinScalingSpanPx(): Float = minScalingSpanPx

    fun getTouchSlopPx(): Float = touchSlopPx

    private fun detachOverlay() {
        mainHandler.removeCallbacks(clearContextVisualRunnable)
        cursorView?.let { view ->
            (view.drawable as? Animatable)?.stop()
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
                // View was already detached
            }
        }
        cursorView = null
        params = null
        windowManager = null
        isDraggingActive = false
        isScrollingActive = false
        isContextClickActive = false
        dragResizeStyle = null
        pinchZoomStyle = null
        hoverStyle = CursorStyle.ARROW
        appliedStyle = CursorStyle.ARROW
        activeDisplayId = Display.INVALID_DISPLAY
        _displayInfo.value = null
    }

    companion object {
        private fun isExternalCandidateDisplay(display: Display): Boolean {
            if (display.displayId == Display.DEFAULT_DISPLAY) return false
            val flags = display.flags
            val isPresentation = (flags and Display.FLAG_PRESENTATION) != 0
            val isPublicSecondary = (flags and Display.FLAG_PRIVATE) == 0
            return isPresentation || isPublicSecondary
        }

        /**
         * Returns an active (non-off) external display ready to host the cursor overlay.
         */
        fun findExternalDisplay(displayManager: DisplayManager): Display? {
            val secondaryDisplays = displayManager.displays.filter { display ->
                isExternalCandidateDisplay(display) && display.state != Display.STATE_OFF
            }
            return secondaryDisplays.firstOrNull { (it.flags and Display.FLAG_PRESENTATION) != 0 }
                ?: secondaryDisplays.firstOrNull()
        }

        /**
         * Returns true if any external display is physically/logically connected, even if its
         * screen state is temporarily [Display.STATE_OFF] during plug-in handshake or mode-set.
         */
        fun hasConnectedExternalDisplay(displayManager: DisplayManager): Boolean {
            return displayManager.displays.any { isExternalCandidateDisplay(it) }
        }
    }
}
