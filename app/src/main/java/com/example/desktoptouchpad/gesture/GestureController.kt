package com.example.desktoptouchpad.gesture

import com.example.desktoptouchpad.service.TouchpadAccessibilityService
import kotlin.math.hypot

class GestureController {

    var sensitivity: Float = 1.6f
    var naturalScrolling: Boolean = true

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
        val service = TouchpadAccessibilityService.instance ?: return false
        return service.performBack()
    }

    fun onDragStart(): Boolean {
        val service = TouchpadAccessibilityService.instance ?: return false
        return service.startDrag()
    }

    fun onDragEnd() {
        TouchpadAccessibilityService.instance?.endDrag()
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
}
