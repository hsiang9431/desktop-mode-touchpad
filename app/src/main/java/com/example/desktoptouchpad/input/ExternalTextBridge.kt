package com.example.desktoptouchpad.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PendingForceInputAction(
    val displayId: Int,
    val targetPackage: String?,
    val anchorX: Float,
    val anchorY: Float,
    val textToInsert: String? = null,
    val deleteBeforeLength: Int = 0,
    val clearAllText: Boolean = false,
    val sendEnterAfter: Boolean = false,
    val appendAtEnd: Boolean = false,
    val hadInitialSelection: Boolean = false,
    val initialCursorPos: Int = -1,
    val editorAction: Int = EditorInfo.IME_ACTION_UNSPECIFIED,
)

class ExternalTextBridge(
    private val service: AccessibilityService,
    private val dispatchInternalTap: (displayId: Int, x: Float, y: Float) -> Boolean,
    private val onSuppressExternalSoftKeyboard: () -> Unit,
    private val onAllowPhoneSoftKeyboard: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionIdCounter = AtomicLong(1L)
    private val refocusCounter = AtomicLong(1L)

    private val _session = MutableStateFlow<ExternalTextSession?>(null)
    val session: StateFlow<ExternalTextSession?> = _session.asStateFlow()

    // Last user-clicked position on the external display (quietly recorded, never opens keyboard)
    private var lastTapDisplayId: Int = Display.INVALID_DISPLAY
    private var lastTapPoint: PointF? = null

    // Snapshot of the selected external text box captured when the user opens/retaps
    private var targetNode: AccessibilityNodeInfo? = null
    private var targetDisplayId: Int = Display.INVALID_DISPLAY
    private var targetPackage: String? = null
    private var targetAnchorX: Float = 0f
    private var targetAnchorY: Float = 0f
    private var targetInitialSelStart: Int = -1
    private var targetInitialSelEnd: Int = -1
    private var targetEditorAction: Int = EditorInfo.IME_ACTION_UNSPECIFIED
    private var hasModifiedTargetInSession: Boolean = false

    private var lastExternalEditorPackage: String? = null
    private var lastExternalEditorAction: Int = EditorInfo.IME_ACTION_UNSPECIFIED

    private var pendingAction: PendingForceInputAction? = null
    private var externalTapTransitionUntilUptimeMs: Long = 0L
    private var api33Helper: Any? = null

    fun isExternalFocusTransitionActive(): Boolean {
        return (pendingAction != null) ||
            (SystemClock.uptimeMillis() < externalTapTransitionUntilUptimeMs)
    }

    private val restorePhoneKeyboardRunnable: Runnable = Runnable {
        val current = _session.value
        if (current != null) {
            onAllowPhoneSoftKeyboard()
            _session.value = current.copy(
                refocusTrigger = refocusCounter.incrementAndGet()
            )
        } else {
            onSuppressExternalSoftKeyboard()
        }
    }

    private val postTapRefreshRunnable: Runnable = Runnable {
        val current = _session.value ?: return@Runnable
        val tapPt = lastTapPoint ?: return@Runnable
        if (lastTapDisplayId == Display.INVALID_DISPLAY) return@Runnable

        captureTargetAt(
            activeExternalDisplayId = lastTapDisplayId,
            fallbackX = tapPt.x,
            fallbackY = tapPt.y
        )

        val updatedHint = extractHint(targetNode)
        val hitWin = findTopWindowAtPoint(lastTapDisplayId, tapPt.x.toInt(), tapPt.y.toInt())
        val updatedLabel = resolveTargetLabel(
            packageName = targetPackage.orEmpty(),
            windowTitle = hitWin?.title?.toString()
        )

        onAllowPhoneSoftKeyboard()
        _session.value = current.copy(
            displayId = lastTapDisplayId,
            targetLabel = updatedLabel,
            hintText = updatedHint,
            refocusTrigger = refocusCounter.incrementAndGet()
        )
    }

    private var pendingAutoOpenDisplayId: Int = Display.INVALID_DISPLAY
    private var pendingAutoOpenX: Float = 0f
    private var pendingAutoOpenY: Float = 0f

    private val autoOpenAfterTapRunnable: Runnable = Runnable {
        val displayId = pendingAutoOpenDisplayId
        pendingAutoOpenDisplayId = Display.INVALID_DISPLAY
        if ((displayId == Display.INVALID_DISPLAY) || (_session.value != null)) return@Runnable
        openManualKeyboard(
            activeExternalDisplayId = displayId,
            cursorX = pendingAutoOpenX,
            cursorY = pendingAutoOpenY,
        )
    }

    private val typeNullGraceRunnable: Runnable = Runnable {
        val action = pendingAction ?: return@Runnable
        pendingAction = null
        mainHandler.removeCallbacks(fallbackSetTextRunnable)
        if (!forceViaAccessibilityNode(action)) {
            if (executeDirectImeCommitNow(action)) {
                markTargetModifiedAfterAction(action)
            }
        }
        scheduleRestorePhoneKeyboard()
    }

    private val fallbackSetTextRunnable: Runnable = Runnable {
        val action = pendingAction ?: return@Runnable
        pendingAction = null
        mainHandler.removeCallbacks(typeNullGraceRunnable)
        if (executeDirectImeCommitIfConnected(action)) {
            markTargetModifiedAfterAction(action)
        } else {
            forceViaAccessibilityNode(action)
        }
        scheduleRestorePhoneKeyboard()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createInputMethod(): InputMethod {
        val helper = Api33InputMethodBridge(service, this)
        api33Helper = helper
        return helper.inputMethod
    }

    fun clearAll() {
        cancelPendingCallbacks()
        pendingAction = null
        targetNode = null
        targetDisplayId = Display.INVALID_DISPLAY
        targetPackage = null
        hasModifiedTargetInSession = false
        lastExternalEditorPackage = null
        lastExternalEditorAction = EditorInfo.IME_ACTION_UNSPECIFIED
        externalTapTransitionUntilUptimeMs = 0L
        lastTapDisplayId = Display.INVALID_DISPLAY
        lastTapPoint = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (api33Helper as? Api33InputMethodBridge)?.clear()
        }
        _session.value = null
    }

    fun dismissKeyboardSession() {
        cancelPendingCallbacks()
        pendingAction = null
        _session.value = null
        onSuppressExternalSoftKeyboard()
    }

    private fun closeSessionPreservingPendingAction() {
        cancelPendingAutoOpen()
        mainHandler.removeCallbacks(restorePhoneKeyboardRunnable)
        mainHandler.removeCallbacks(postTapRefreshRunnable)
        _session.value = null
        onSuppressExternalSoftKeyboard()
    }

    fun cancelPendingAutoOpen() {
        pendingAutoOpenDisplayId = Display.INVALID_DISPLAY
        mainHandler.removeCallbacks(autoOpenAfterTapRunnable)
    }

    fun scheduleAutoOpenAfterExternalTap(displayId: Int, x: Float, y: Float) {
        if (displayId == Display.INVALID_DISPLAY || _session.value != null) return
        cancelPendingAutoOpen()
        pendingAutoOpenDisplayId = displayId
        pendingAutoOpenX = x
        pendingAutoOpenY = y
        externalTapTransitionUntilUptimeMs = SystemClock.uptimeMillis() + 350L
        mainHandler.postDelayed(autoOpenAfterTapRunnable, 65L)
    }

    private fun cancelPendingCallbacks() {
        cancelPendingAutoOpen()
        mainHandler.removeCallbacks(typeNullGraceRunnable)
        mainHandler.removeCallbacks(fallbackSetTextRunnable)
        mainHandler.removeCallbacks(restorePhoneKeyboardRunnable)
        mainHandler.removeCallbacks(postTapRefreshRunnable)
    }

    /**
     * Called right BEFORE dispatching a user tap on the external display.
     * When the phone keyboard session is closed, keeps external onscreen keyboard suppressed.
     * When the phone keyboard session is open, updates the selected target box and keeps the
     * phone keyboard focused without toggling SHOW_MODE_HIDDEN (avoiding any Gboard flicker).
     */
    fun onBeforeExternalTap(displayId: Int, x: Float, y: Float) {
        if (displayId == Display.INVALID_DISPLAY) return
        cancelPendingAutoOpen()
        lastTapDisplayId = displayId
        lastTapPoint = PointF(x, y)

        if (_session.value == null) {
            onSuppressExternalSoftKeyboard()
        } else {
            externalTapTransitionUntilUptimeMs = SystemClock.uptimeMillis() + 350L
            mainHandler.removeCallbacks(postTapRefreshRunnable)
            mainHandler.postDelayed(postTapRefreshRunnable, 115L)
        }
    }

    /**
     * Called when opening the phone keyboard (either manually via the Keyboard button or
     * automatically after clicking with a text-editing cursor).
     */
    fun openManualKeyboard(
        activeExternalDisplayId: Int,
        cursorX: Float,
        cursorY: Float,
    ): Boolean {
        if (activeExternalDisplayId == Display.INVALID_DISPLAY) return false
        cancelPendingAutoOpen()

        val tapPt = lastTapPoint
        val fallbackX = if (lastTapDisplayId == activeExternalDisplayId && tapPt != null) {
            tapPt.x
        } else {
            cursorX
        }
        val fallbackY = if (lastTapDisplayId == activeExternalDisplayId && tapPt != null) {
            tapPt.y
        } else {
            cursorY
        }

        val resolvedWin = captureTargetAt(activeExternalDisplayId, fallbackX, fallbackY)
        val hint = extractHint(targetNode)
        val label = resolveTargetLabel(
            packageName = targetPackage.orEmpty(),
            windowTitle = resolvedWin?.title?.toString()
        )

        onAllowPhoneSoftKeyboard()

        _session.value = ExternalTextSession(
            sessionId = sessionIdCounter.getAndIncrement(),
            displayId = activeExternalDisplayId,
            targetLabel = label,
            hintText = hint,
            refocusTrigger = refocusCounter.incrementAndGet(),
        )
        return true
    }

    private fun captureTargetAt(
        activeExternalDisplayId: Int,
        fallbackX: Float,
        fallbackY: Float,
    ): AccessibilityWindowInfo? {
        val focusedMatch = findFocusedEditableOnDisplay(activeExternalDisplayId)
        val hitWin = findTopWindowAtPoint(activeExternalDisplayId, fallbackX.toInt(), fallbackY.toInt())
        val hitNode = hitWin?.root?.let {
            hitTestEditableNode(it, fallbackX.toInt(), fallbackY.toInt(), slopPx = 10, depth = 0)
        }

        val resolvedNode = hitNode ?: focusedMatch?.first
        val resolvedWin = if (hitNode != null) hitWin else (focusedMatch?.second ?: hitWin)

        targetNode = resolvedNode
        targetDisplayId = activeExternalDisplayId
        hasModifiedTargetInSession = false

        if (resolvedNode != null) {
            resolvedNode.refresh()
            val bounds = Rect()
            resolvedNode.getBoundsInScreen(bounds)
            val tapPt = lastTapPoint
            targetAnchorX = if (lastTapDisplayId == activeExternalDisplayId &&
                tapPt != null &&
                bounds.contains(tapPt.x.toInt(), tapPt.y.toInt())
            ) {
                tapPt.x
            } else {
                bounds.exactCenterX()
            }
            targetAnchorY = if (lastTapDisplayId == activeExternalDisplayId &&
                tapPt != null &&
                bounds.contains(tapPt.x.toInt(), tapPt.y.toInt())
            ) {
                tapPt.y
            } else {
                bounds.exactCenterY()
            }
            targetPackage = resolvedNode.packageName?.toString()
            targetInitialSelStart = resolvedNode.textSelectionStart
            targetInitialSelEnd = resolvedNode.textSelectionEnd
        } else {
            targetAnchorX = fallbackX
            targetAnchorY = fallbackY
            targetPackage = resolvedWin?.root?.packageName?.toString() ?: lastExternalEditorPackage
            targetInitialSelStart = -1
            targetInitialSelEnd = -1
        }

        targetEditorAction = if (targetPackage != null && targetPackage == lastExternalEditorPackage) {
            lastExternalEditorAction
        } else {
            EditorInfo.IME_ACTION_UNSPECIFIED
        }

        return resolvedWin
    }

    private fun extractHint(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        return node.hintText?.toString()?.takeIf { it.isNotBlank() }
            ?: (if (node.isShowingHintText) {
                node.text?.toString()?.takeIf { it.isNotBlank() }
            } else {
                null
            })
    }

    /**
     * Forces the user's typed text straight into the selected text box on the external display.
     * Uses direct AccessibilityNodeInfo actions first so the phone never loses window focus
     * and Gboard never flickers.
     */
    fun forceInputIntoSelectedBox(
        text: String,
        sendEnterAfter: Boolean,
        closeKeyboardAfter: Boolean = false,
    ) {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY) {
            if (closeKeyboardAfter) {
                dismissKeyboardSession()
            }
            return
        }
        if (text.isEmpty() && !sendEnterAfter) {
            if (closeKeyboardAfter) {
                dismissKeyboardSession()
            }
            return
        }

        val hadSelection = targetInitialSelStart in 0..<targetInitialSelEnd

        val action = PendingForceInputAction(
            displayId = displayId,
            targetPackage = targetPackage?.takeIf { it.isNotBlank() },
            anchorX = targetAnchorX,
            anchorY = targetAnchorY,
            textToInsert = text.takeIf { it.isNotEmpty() },
            deleteBeforeLength = 0,
            clearAllText = false,
            sendEnterAfter = sendEnterAfter,
            appendAtEnd = hasModifiedTargetInSession,
            hadInitialSelection = hadSelection,
            initialCursorPos = targetInitialSelEnd,
            editorAction = targetEditorAction,
        )

        if (closeKeyboardAfter) {
            closeSessionPreservingPendingAction()
        }

        executeFlickerFreeOrFallback(action)
    }

    /**
     * Clears all text inside the selected external text box without closing or flickering Gboard.
     */
    fun clearSelectedBox() {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY) return

        val action = PendingForceInputAction(
            displayId = displayId,
            targetPackage = targetPackage?.takeIf { it.isNotBlank() },
            anchorX = targetAnchorX,
            anchorY = targetAnchorY,
            clearAllText = true,
        )

        executeFlickerFreeOrFallback(action)
    }

    /**
     * Deletes one character inside the selected external text box without closing or flickering Gboard.
     */
    fun sendBackspaceToSelectedBox() {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY) return

        val action = PendingForceInputAction(
            displayId = displayId,
            targetPackage = targetPackage?.takeIf { it.isNotBlank() },
            anchorX = targetAnchorX,
            anchorY = targetAnchorY,
            deleteBeforeLength = 1,
            appendAtEnd = hasModifiedTargetInSession,
        )

        executeFlickerFreeOrFallback(action)
    }

    /**
     * Primary path: execute directly on the external AccessibilityNodeInfo while the phone
     * retains 100% of window & IME focus (zero Gboard dip/flicker).
     * Fallback path: only used for non-accessibility surfaces (e.g. raw canvas/terminal).
     */
    private fun executeFlickerFreeOrFallback(action: PendingForceInputAction) {
        // 1. If API 33+ InputMethod somehow already has an active external connection (e.g. per-display focus), use it directly.
        if (executeDirectImeCommitIfConnected(action)) {
            markTargetModifiedAfterAction(action)
            return
        }

        // 2. Primary flicker-free path: perform ACTION_SET_TEXT / ACTION_IME_ENTER directly on the external node
        // without changing SoftKeyboardController mode and without tapping away phone window focus!
        val directResult = tryDirectAccessibilityNodeAction(action)
        if (directResult == DirectNodeResult.COMPLETE) {
            return
        }

        // If text was already inserted via ACTION_SET_TEXT and only single-line Enter remained unhandled:
        val remainingAction = if (directResult == DirectNodeResult.TEXT_DONE_NEEDS_ENTER) {
            action.copy(
                textToInsert = null,
                clearAllText = false,
                deleteBeforeLength = 0,
                sendEnterAfter = true,
            )
        } else {
            action
        }

        enqueueImeConnectionFallback(remainingAction)
    }

    private fun enqueueImeConnectionFallback(action: PendingForceInputAction) {
        mainHandler.removeCallbacks(typeNullGraceRunnable)
        mainHandler.removeCallbacks(fallbackSetTextRunnable)
        mainHandler.removeCallbacks(restorePhoneKeyboardRunnable)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingAction = action
            mainHandler.postDelayed(fallbackSetTextRunnable, 360L)
            dispatchInternalTap(action.displayId, action.anchorX, action.anchorY)
        } else {
            dispatchInternalTap(action.displayId, action.anchorX, action.anchorY)
            mainHandler.postDelayed(
                {
                    forceViaAccessibilityNode(action)
                    scheduleRestorePhoneKeyboard()
                },
                95L,
            )
        }
    }

    private fun markTargetModifiedAfterAction(action: PendingForceInputAction) {
        if (action.clearAllText) {
            targetInitialSelStart = 0
            targetInitialSelEnd = 0
            hasModifiedTargetInSession = true
        } else if (!action.textToInsert.isNullOrEmpty() || action.deleteBeforeLength > 0) {
            hasModifiedTargetInSession = true
        }
    }

    private fun scheduleRestorePhoneKeyboard() {
        mainHandler.removeCallbacks(restorePhoneKeyboardRunnable)
        mainHandler.postDelayed(restorePhoneKeyboardRunnable, 35L)
    }

    internal fun onExternalInputStartedFromIme(
        packageName: String,
        inputType: Int,
        imeOptions: Int,
    ) {
        val hasEditorClass = (inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL
        if (hasEditorClass) {
            lastExternalEditorPackage = packageName
            lastExternalEditorAction = imeOptions and EditorInfo.IME_MASK_ACTION
        }

        val action = pendingAction ?: return
        if (action.targetPackage != null && action.targetPackage != packageName) {
            return
        }

        if (hasEditorClass) {
            pendingAction = null
            mainHandler.removeCallbacks(typeNullGraceRunnable)
            mainHandler.removeCallbacks(fallbackSetTextRunnable)
            val resolvedAction = if ((imeOptions and EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_UNSPECIFIED) {
                action.copy(editorAction = imeOptions and EditorInfo.IME_MASK_ACTION)
            } else {
                action
            }
            if (executeDirectImeCommitNow(resolvedAction)) {
                markTargetModifiedAfterAction(resolvedAction)
            } else {
                forceViaAccessibilityNode(resolvedAction)
            }
            scheduleRestorePhoneKeyboard()
        } else {
            mainHandler.removeCallbacks(typeNullGraceRunnable)
            mainHandler.postDelayed(typeNullGraceRunnable, 100L)
        }
    }

    private fun executeDirectImeCommitIfConnected(action: PendingForceInputAction): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val helper = api33Helper as? Api33InputMethodBridge ?: return false
        return helper.executeIfRealEditorConnected(action)
    }

    private fun executeDirectImeCommitNow(action: PendingForceInputAction): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val helper = api33Helper as? Api33InputMethodBridge ?: return false
        return helper.executeNow(action)
    }

    fun sendKeyEventToExternalIfConnected(
        keyCode: Int,
        allowedExternalPackages: Set<String>,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val helper = api33Helper as? Api33InputMethodBridge ?: return false
        return helper.sendKeyEventIfExternalConnected(keyCode, allowedExternalPackages)
    }

    private enum class DirectNodeResult {
        COMPLETE,
        TEXT_DONE_NEEDS_ENTER,
        UNSUPPORTED,
    }

    private fun tryDirectAccessibilityNodeAction(action: PendingForceInputAction): DirectNodeResult {
        val node = resolveNodeForForceSetText(action.displayId, action.anchorX, action.anchorY)
            ?: return DirectNodeResult.UNSUPPORTED
        node.refresh()

        // Appending/deleting on an already-populated masked password box cannot read existing plaintext via AccessibilityNodeInfo
        if (node.isPassword && !action.clearAllText) {
            val needsExistingPasswordText = action.appendAtEnd || action.deleteBeforeLength > 0
            if (needsExistingPasswordText) {
                return DirectNodeResult.UNSUPPORTED
            }
        }

        val currentText = if (node.isShowingHintText || node.isPassword) {
            ""
        } else {
            node.text?.toString().orEmpty()
        }

        if (action.clearAllText) {
            val setArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            val cleared = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
            if (!cleared) return DirectNodeResult.UNSUPPORTED
            targetInitialSelStart = 0
            targetInitialSelEnd = 0
            hasModifiedTargetInSession = true
            return DirectNodeResult.COMPLETE
        }

        val isMultiLineField = node.isMultiLine
        val effectiveInsertText = when {
            !action.textToInsert.isNullOrEmpty() && action.sendEnterAfter && isMultiLineField ->
                action.textToInsert + "\n"
            action.textToInsert.isNullOrEmpty() && action.sendEnterAfter && isMultiLineField ->
                "\n"
            else -> action.textToInsert
        }

        var textStepSucceeded = false

        if (!effectiveInsertText.isNullOrEmpty()) {
            val rawStart = if (!action.appendAtEnd && node.textSelectionStart >= 0) {
                node.textSelectionStart
            } else {
                targetInitialSelStart
            }
            val rawEnd = if (!action.appendAtEnd && node.textSelectionEnd >= 0) {
                node.textSelectionEnd
            } else {
                targetInitialSelEnd
            }

            val newText: String
            val newCursor: Int
            if (currentText.isEmpty()) {
                newText = effectiveInsertText
                newCursor = newText.length
            } else if (action.appendAtEnd) {
                newText = currentText + effectiveInsertText
                newCursor = newText.length
            } else {
                val s = if (rawStart in 0..currentText.length) rawStart else currentText.length
                val e = if (rawEnd in s..currentText.length) rawEnd else s
                newText = currentText.replaceRange(s, e, effectiveInsertText)
                newCursor = s + effectiveInsertText.length
            }

            val setArgs = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText,
                )
            }
            textStepSucceeded = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
            if (!textStepSucceeded) return DirectNodeResult.UNSUPPORTED

            targetInitialSelStart = newCursor
            targetInitialSelEnd = newCursor
            hasModifiedTargetInSession = true
            if (newCursor in 0..newText.length) {
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            }
        } else if (action.deleteBeforeLength > 0) {
            if (currentText.isEmpty()) {
                return DirectNodeResult.COMPLETE
            }
            val newText = currentText.dropLast(action.deleteBeforeLength.coerceAtMost(currentText.length))
            val setArgs = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText,
                )
            }
            val deleted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
            if (!deleted) return DirectNodeResult.UNSUPPORTED

            targetInitialSelStart = newText.length
            targetInitialSelEnd = newText.length
            hasModifiedTargetInSession = true
            if (newText.isNotEmpty()) {
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            }
            return DirectNodeResult.COMPLETE
        }

        if (action.sendEnterAfter && !isMultiLineField) {
            val enterHandled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.refresh()
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                false
            }
            if (!enterHandled) {
                return if (textStepSucceeded) {
                    DirectNodeResult.TEXT_DONE_NEEDS_ENTER
                } else {
                    DirectNodeResult.UNSUPPORTED
                }
            }
        }

        return DirectNodeResult.COMPLETE
    }

    private fun forceViaAccessibilityNode(action: PendingForceInputAction): Boolean {
        val res = tryDirectAccessibilityNodeAction(action)
        return res == DirectNodeResult.COMPLETE || res == DirectNodeResult.TEXT_DONE_NEEDS_ENTER
    }

    private fun resolveNodeForForceSetText(
        displayId: Int,
        anchorX: Float,
        anchorY: Float,
    ): AccessibilityNodeInfo? {
        val saved = targetNode
        if (saved != null && saved.refresh() && isEditableNode(saved)) {
            return saved
        }

        val win = findTopWindowAtPoint(displayId, anchorX.toInt(), anchorY.toInt())
        val hit = win?.root?.let {
            hitTestEditableNode(it, anchorX.toInt(), anchorY.toInt(), slopPx = 12, depth = 0)
        }
        if (hit != null) {
            targetNode = hit
            return hit
        }

        val focused = findFocusedEditableOnDisplay(displayId)?.first
        if (focused != null) {
            targetNode = focused
            return focused
        }

        return null
    }

    private fun findFocusedEditableOnDisplay(
        displayId: Int,
    ): Pair<AccessibilityNodeInfo, AccessibilityWindowInfo>? {
        val windows = getExternalWindows(displayId)
        val orderedForFocus = windows.sortedWith(
            compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                .thenByDescending { it.isActive }
                .thenByDescending { it.layer }
        )
        for (win in orderedForFocus) {
            val root = win.root ?: continue
            val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (isEditableNode(inputFocused)) {
                return Pair(inputFocused, win)
            }
            val inner = findFocusedEditableDescendant(inputFocused, depth = 0)
            if (inner != null) {
                return Pair(inner, win)
            }
        }
        return null
    }

    private fun findFocusedEditableDescendant(
        node: AccessibilityNodeInfo,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > 24) return null
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            if (isEditableNode(child) && child.isFocused) {
                return child
            }
            val sub = findFocusedEditableDescendant(child, depth + 1)
            if (sub != null) return sub
        }
        return null
    }

    private fun findTopWindowAtPoint(
        displayId: Int,
        x: Int,
        y: Int,
    ): AccessibilityWindowInfo? {
        val bounds = Rect()
        return getExternalWindows(displayId).firstOrNull { win ->
            win.getBoundsInScreen(bounds)
            bounds.contains(x, y)
        }
    }

    private fun hitTestEditableNode(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        slopPx: Int,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > 30 || !node.isVisibleToUser) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return null
        bounds.inset(-slopPx, -slopPx)
        if (!bounds.contains(x, y)) return null

        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            val childHit = hitTestEditableNode(child, x, y, slopPx, depth + 1)
            if (childHit != null) return childHit
        }

        return if (isEditableNode(node)) node else null
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled || !node.isVisibleToUser) return false
        val cls = node.className?.toString().orEmpty()
        return node.isEditable ||
            cls.contains("EditText", ignoreCase = true) ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    }

    private fun getExternalWindows(displayId: Int): List<AccessibilityWindowInfo> {
        if (displayId == Display.INVALID_DISPLAY) return emptyList()
        val rawWindows: List<AccessibilityWindowInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                service.windowsOnAllDisplays[displayId].orEmpty()
            } else {
                service.windows
            }

        return rawWindows
            .asSequence()
            .filter { win ->
                (win.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    win.type == AccessibilityWindowInfo.TYPE_SYSTEM) &&
                    win.root?.packageName?.toString() != service.packageName
            }
            .sortedByDescending { it.layer }
            .toList()
    }

    private fun resolveTargetLabel(packageName: String, windowTitle: String?): String {
        if (!windowTitle.isNullOrBlank()) {
            return windowTitle.trim()
        }
        if (packageName.isNotBlank()) {
            val pmLabel = runCatching {
                val appInfo = service.packageManager.getApplicationInfo(packageName, 0)
                service.packageManager.getApplicationLabel(appInfo).toString()
            }.getOrNull()
            if (!pmLabel.isNullOrBlank()) {
                return pmLabel
            }
            return packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
        return "Selected Text Box"
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Api33InputMethodBridge(
    private val service: AccessibilityService,
    private val bridge: ExternalTextBridge,
) {
    private var activeExternalPackage: String? = null
    private var activeExternalInputType: Int = InputType.TYPE_NULL

    val inputMethod: InputMethod = object : InputMethod(service) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            val pkg = attribute.packageName?.takeIf { it.isNotBlank() } ?: return
            if (pkg == service.packageName) {
                activeExternalPackage = null
                activeExternalInputType = InputType.TYPE_NULL
                return
            }
            activeExternalPackage = pkg
            activeExternalInputType = attribute.inputType
            bridge.onExternalInputStartedFromIme(
                packageName = pkg,
                inputType = attribute.inputType,
                imeOptions = attribute.imeOptions,
            )
        }

        override fun onFinishInput() {
            super.onFinishInput()
            activeExternalPackage = null
            activeExternalInputType = InputType.TYPE_NULL
        }
    }

    fun clear() {
        activeExternalPackage = null
        activeExternalInputType = InputType.TYPE_NULL
    }

    fun executeIfRealEditorConnected(action: PendingForceInputAction): Boolean {
        val currentPkg = activeExternalPackage ?: return false
        if ((activeExternalInputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL) {
            return false
        }
        return (action.targetPackage == null || action.targetPackage == currentPkg) &&
            executeNow(action)
    }

    fun sendKeyEventIfExternalConnected(
        keyCode: Int,
        allowedExternalPackages: Set<String>,
    ): Boolean {
        val currentPkg = activeExternalPackage ?: return false
        if (currentPkg == service.packageName) return false
        if (allowedExternalPackages.isNotEmpty() && currentPkg !in allowedExternalPackages) {
            return false
        }
        val ic = inputMethod.currentInputConnection ?: return false
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return true
    }

    fun executeNow(action: PendingForceInputAction): Boolean {
        val ic = inputMethod.currentInputConnection ?: return false

        if (action.clearAllText) {
            val surrounding = ic.getSurroundingText(10000, 10000, 0)
            if (surrounding != null) {
                val totalLen = surrounding.offset.coerceAtLeast(0) + surrounding.text.length
                if (totalLen > 0) {
                    ic.setSelection(0, totalLen)
                    ic.commitText("", 1, null)
                }
            } else {
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1, null)
            }
            ic.deleteSurroundingText(10000, 10000)
            return true
        }

        if (!action.textToInsert.isNullOrEmpty()) {
            val surrounding = ic.getSurroundingText(10000, 10000, 0)
            if (surrounding != null) {
                val totalEnd = surrounding.offset.coerceAtLeast(0) + surrounding.text.length
                if (action.appendAtEnd) {
                    ic.setSelection(totalEnd, totalEnd)
                } else if (!action.hadInitialSelection &&
                    surrounding.text.isNotEmpty() &&
                    surrounding.selectionStart == 0 &&
                    surrounding.selectionEnd == surrounding.text.length
                ) {
                    val pos = if (action.initialCursorPos in 0..totalEnd) {
                        action.initialCursorPos
                    } else {
                        totalEnd
                    }
                    ic.setSelection(pos, pos)
                }
            }
            ic.commitText(action.textToInsert, 1, null)
        }

        if (action.deleteBeforeLength > 0) {
            val surrounding = ic.getSurroundingText(10000, 10000, 0)
            if (surrounding != null && surrounding.text.isNotEmpty()) {
                val totalEnd = surrounding.offset.coerceAtLeast(0) + surrounding.text.length
                if (action.appendAtEnd ||
                    (surrounding.selectionStart == 0 && surrounding.selectionEnd == surrounding.text.length)
                ) {
                    ic.setSelection(totalEnd, totalEnd)
                }
                ic.deleteSurroundingText(action.deleteBeforeLength, 0)
            } else {
                val now = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
            }
        }

        if (action.sendEnterAfter) {
            val explicitAction = action.editorAction
            val hasExplicitAction = (explicitAction != EditorInfo.IME_ACTION_NONE) &&
                (explicitAction != EditorInfo.IME_ACTION_UNSPECIFIED) &&
                (explicitAction != EditorInfo.IME_ACTION_DONE)
            if (hasExplicitAction) {
                ic.performEditorAction(explicitAction)
            } else {
                val now = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
            }
        }
        return true
    }
}
