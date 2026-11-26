package com.example.desktoptouchpad.gesture

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.desktoptouchpad.service.TouchpadAccessibilityService
import kotlin.math.hypot

class GestureController {

    private var prefs: SharedPreferences? = null

    var sensitivity: Float = DEFAULT_SENSITIVITY
        set(value) {
            field = value
            prefs?.edit { putFloat(KEY_SENSITIVITY, value) }
        }

    var doubleTapIntervalMs: Long = DEFAULT_DOUBLE_TAP_INTERVAL_MS
        set(value) {
            field = value.coerceIn(MIN_DOUBLE_TAP_INTERVAL_MS, MAX_DOUBLE_TAP_INTERVAL_MS)
            prefs?.edit { putLong(KEY_DOUBLE_TAP_INTERVAL_MS, field) }
        }

    var naturalScrolling: Boolean = DEFAULT_NATURAL_SCROLLING
        set(value) {
            field = value
            prefs?.edit { putBoolean(KEY_NATURAL_SCROLLING, value) }
        }

    var threeFingerGesturesEnabled: Boolean = DEFAULT_THREE_FINGER_GESTURES
        set(value) {
            field = value
            prefs?.edit { putBoolean(KEY_THREE_FINGER_GESTURES, value) }
        }

    fun attachContext(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        sensitivity = sp.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
        doubleTapIntervalMs = sp.getLong(KEY_DOUBLE_TAP_INTERVAL_MS, DEFAULT_DOUBLE_TAP_INTERVAL_MS)
            .coerceIn(MIN_DOUBLE_TAP_INTERVAL_MS, MAX_DOUBLE_TAP_INTERVAL_MS)
        naturalScrolling = sp.getBoolean(KEY_NATURAL_SCROLLING, DEFAULT_NATURAL_SCROLLING)
        threeFingerGesturesEnabled =
            sp.getBoolean(KEY_THREE_FINGER_GESTURES, DEFAULT_THREE_FINGER_GESTURES)
    }

    fun onMove(dx: Float, dy: Float, dtMillis: Long = 16L) {
        val service = TouchpadAccessibilityService.instance ?: return
        val dt = dtMillis.coerceIn(4L, 64L).toFloat()
        val velocityPxPerMs = hypot(dx, dy) / dt

        // DeX-style pointer acceleration: sub-pixel precision when slow, fast sweep across 1080p/4K monitors
        val accelerationGain = 0.85f + (velocityPxPerMs * 0.85f).coerceAtMost(2.15f)
        val multiplier = sensitivity * accelerationGain

        service.moveCursor(dx * multiplier, dy * multiplier)
    }

    fun onTap(): Boolean {
        val service = TouchpadAccessibilityService.instance ?: return false
        val (x, y) = service.getCursorPosition()
        return service.performTap(x, y)
    }

    fun onSecondaryTap(): Boolean {
        val service = TouchpadAccessibilityService.instance ?: return false
        val (x, y) = service.getCursorPosition()
        return service.performContextClick(x, y)
    }

    fun onThreeFingerTap(): Boolean {
        if (!threeFingerGesturesEnabled) return false
        val service = TouchpadAccessibilityService.instance ?: return false
        return service.performBack()
    }

    fun onThreeFingerSwipeDown(): Boolean {
        if (!threeFingerGesturesEnabled) return false
        val service = TouchpadAccessibilityService.instance ?: return false
        return service.performHome()
    }

    fun onThreeFingerSwipeUp(): Boolean {
        if (!threeFingerGesturesEnabled) return false
        val service = TouchpadAccessibilityService.instance ?: return false
        return service.performAppSwitch()
    }

    /** Identifies which touch surface is holding the injected drag pointer down. */
    enum class DragOwner { TOUCHPAD, CLICK_BUTTON }

    private val dragOwners = mutableSetOf<DragOwner>()

    val isDragActive: Boolean
        get() = TouchpadAccessibilityService.instance?.isDragActive() == true

    fun onDragStart(owner: DragOwner): Boolean {
        val service = TouchpadAccessibilityService.instance ?: return false
        if (!service.isDragActive()) {
            dragOwners.clear()
        }
        if (!service.startDrag()) return false
        dragOwners += owner
        return true
    }

    fun onDragEnd(owner: DragOwner) {
        if (!dragOwners.remove(owner)) return
        if (dragOwners.isEmpty()) {
            TouchpadAccessibilityService.instance?.endDrag()
        }
    }

    fun onTwoFingerScroll(scrollDx: Float, scrollDy: Float) {
        val service = TouchpadAccessibilityService.instance ?: return
        val direction = if (naturalScrolling) 1.0f else -1.0f
        val scrollGain = 1.75f * direction
        service.enqueueScroll(scrollDx * scrollGain, scrollDy * scrollGain)
    }

    fun onBack(): Boolean = TouchpadAccessibilityService.instance?.performBack() ?: false

    fun onHome(): Boolean = TouchpadAccessibilityService.instance?.performHome() ?: false

    fun onCenterCursor() {
        TouchpadAccessibilityService.instance?.centerCursor()
    }

    fun onRequestKeyboard(): Boolean {
        return TouchpadAccessibilityService.instance?.requestManualKeyboard() ?: false
    }

    fun onDismissKeyboard() {
        TouchpadAccessibilityService.instance?.dismissKeyboardSession()
    }

    companion object {
        private const val PREFS_NAME = "touchpad_prefs"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_DOUBLE_TAP_INTERVAL_MS = "double_tap_interval_ms"
        private const val KEY_NATURAL_SCROLLING = "natural_scrolling"
        private const val KEY_THREE_FINGER_GESTURES = "three_finger_gestures"

        const val DEFAULT_SENSITIVITY = 1.6f
        const val DEFAULT_DOUBLE_TAP_INTERVAL_MS = 300L
        const val MIN_DOUBLE_TAP_INTERVAL_MS = 150L
        const val MAX_DOUBLE_TAP_INTERVAL_MS = 550L
        const val DEFAULT_NATURAL_SCROLLING = true
        const val DEFAULT_THREE_FINGER_GESTURES = true
    }
}
