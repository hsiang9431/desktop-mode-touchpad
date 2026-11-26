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
import android.view.accessibility.AccessibilityEvent
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

    // Last user-clicked position & quietly observed editable node on the external display
    private var lastTapDisplayId: Int = Display.INVALID_DISPLAY
    private var lastTapPoint: PointF? = null
    private var lastObservedExternalEditable: AccessibilityNodeInfo? = null
    private var lastObservedDisplayId: Int = Display.INVALID_DISPLAY

    // Snapshot of the selected external text box captured when the user taps/opens
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

    private val quietPreCaptureAfterTapRunnable: Runnable = Runnable {
        if (_session.value != null) return@Runnable
        val tapPt = lastTapPoint ?: return@Runnable
        val displayId = lastTapDisplayId
        if (displayId == Display.INVALID_DISPLAY) return@Runnable

        // Capture the editable AccessibilityNodeInfo while the external display STILL holds window focus!
        val hitMatch = findEditableAtPointAcrossWindows(displayId, tapPt.x.toInt(), tapPt.y.toInt(), slopPx = 12)
        val focusedMatch = findFocusedEditableOnDisplay(displayId)
        val candidate = hitMatch?.first ?: focusedMatch?.first
        if (candidate != null) {
            lastObservedExternalEditable = candidate
            lastObservedDisplayId = displayId
        }
    }

    private val postTapRefreshRunnable: Runnable = Runnable {
        val current = _session.value ?: return@Runnable
        val tapPt = lastTapPoint ?: return@Runnable
        if (lastTapDisplayId == Display.INVALID_DISPLAY) return@Runnable

        val resolvedWin = captureTargetAt(
            activeExternalDisplayId = lastTapDisplayId,
            fallbackX = tapPt.x,
            fallbackY = tapPt.y,
        )

        val updatedHint = extractHint(targetNode)
        val updatedLabel = resolveTargetLabel(
            packageName = targetPackage.orEmpty(),
            windowTitle = resolvedWin?.title?.toString(),
        )

        onAllowPhoneSoftKeyboard()
        _session.value = current.copy(
            displayId = lastTapDisplayId,
            targetLabel = updatedLabel,
            hintText = updatedHint,
            refocusTrigger = refocusCounter.incrementAndGet(),
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
        lastObservedExternalEditable = null
        lastObservedDisplayId = Display.INVALID_DISPLAY
        targetPackage = null
        hasModifiedTargetInSession = false
        lastTapDisplayId = Display.INVALID_DISPLAY
        lastTapPoint = null
        _session.value = null
    }

    fun dismissKeyboardSession() {
        cancelPendingCallbacks()
        pendingAction = null
        _session.value = null
        onSuppressExternalSoftKeyboard()
    }

    private fun cancelPendingCallbacks() {
        mainHandler.removeCallbacks(typeNullGraceRunnable)
        mainHandler.removeCallbacks(fallbackSetTextRunnable)
        mainHandler.removeCallbacks(restorePhoneKeyboardRunnable)
        mainHandler.removeCallbacks(postTapRefreshRunnable)
        mainHandler.removeCallbacks(quietPreCaptureAfterTapRunnable)
    }

    /**
     * Quietly caches any editable node focused/clicked on the external display while the external
     * display has focus (e.g. Chrome Omnibox url_bar, WebView <input>, Compose TextField).
     * NEVER opens or dismisses the keyboard automatically.
     */
    fun onAccessibilityEventQuiet(event: AccessibilityEvent, activeExternalDisplayId: Int) {
        if (activeExternalDisplayId == Display.INVALID_DISPLAY) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == service.packageName || pkg == "com.android.systemui") return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            type != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return
        }

        val src = event.source ?: return
        val srcWinDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            src.window?.displayId ?: Display.INVALID_DISPLAY
        } else {
            activeExternalDisplayId
        }
        if (srcWinDisplayId != activeExternalDisplayId && srcWinDisplayId != Display.INVALID_DISPLAY) {
            return
        }

        if (isEditableNode(src)) {
            lastObservedExternalEditable = src
            lastObservedDisplayId = activeExternalDisplayId
            if (_session.value != null && targetNode == null) {
                targetNode = src
            }
        }
    }

    /**
     * Called right BEFORE dispatching a user tap on the external display.
     * Never auto-opens the keyboard; quietly snapshots the editable node after the tap lands.
     */
    fun onBeforeExternalTap(displayId: Int, x: Float, y: Float) {
        if (displayId == Display.INVALID_DISPLAY) return
        lastTapDisplayId = displayId
        lastTapPoint = PointF(x, y)

        if (_session.value == null) {
            onSuppressExternalSoftKeyboard()
            mainHandler.removeCallbacks(quietPreCaptureAfterTapRunnable)
            mainHandler.postDelayed(quietPreCaptureAfterTapRunnable, 110L)
        } else {
            externalTapTransitionUntilUptimeMs = SystemClock.uptimeMillis() + 350L
            mainHandler.removeCallbacks(postTapRefreshRunnable)
            mainHandler.postDelayed(postTapRefreshRunnable, 115L)
        }
    }

    /**
     * Called ONLY when the user explicitly taps the Keyboard button on the phone.
     */
    fun openManualKeyboard(
        activeExternalDisplayId: Int,
        cursorX: Float,
        cursorY: Float,
    ): Boolean {
        if (activeExternalDisplayId == Display.INVALID_DISPLAY) return false

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
            windowTitle = resolvedWin?.title?.toString(),
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
        val hitMatch = findEditableAtPointAcrossWindows(
            displayId = activeExternalDisplayId,
            x = fallbackX.toInt(),
            y = fallbackY.toInt(),
            slopPx = 14,
        )
        val focusedMatch = findFocusedEditableOnDisplay(activeExternalDisplayId)
        val preCaptured = lastObservedExternalEditable?.takeIf {
            lastObservedDisplayId == activeExternalDisplayId &&
                it.refresh() &&
                isEditableNode(it)
        }

        val resolvedNode = hitMatch?.first ?: focusedMatch?.first ?: preCaptured
        val resolvedWin = hitMatch?.second
            ?: focusedMatch?.second
            ?: resolvedNode?.window
            ?: findTopApplicationWindowAtPoint(activeExternalDisplayId, fallbackX.toInt(), fallbackY.toInt())

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

    fun forceInputIntoSelectedBox(
        text: String,
        sendEnterAfter: Boolean,
    ) {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY) return
        if (text.isEmpty() && !sendEnterAfter) return

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

        executeFlickerFreeOrFallback(action)
    }

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

    private fun executeFlickerFreeOrFallback(action: PendingForceInputAction) {
        if (executeDirectImeCommitIfConnected(action)) {
            markTargetModifiedAfterAction(action)
            return
        }

        val directResult = tryDirectAccessibilityNodeAction(action)
        if (directResult == DirectNodeResult.COMPLETE) {
            return
        }

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

    private enum class DirectNodeResult {
        COMPLETE,
        TEXT_DONE_NEEDS_ENTER,
        UNSUPPORTED,
    }

    private fun tryDirectAccessibilityNodeAction(action: PendingForceInputAction): DirectNodeResult {
        val node = resolveNodeForForceSetText(action.displayId, action.anchorX, action.anchorY)
            ?: return DirectNodeResult.UNSUPPORTED
        node.refresh()

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

        val preCaptured = lastObservedExternalEditable
        if (preCaptured != null &&
            lastObservedDisplayId == displayId &&
            preCaptured.refresh() &&
            isEditableNode(preCaptured)
        ) {
            targetNode = preCaptured
            return preCaptured
        }

        val hit = findEditableAtPointAcrossWindows(displayId, anchorX.toInt(), anchorY.toInt(), slopPx = 14)?.first
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
        for (win in windows) {
            val root = win.root ?: continue
            val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inputFocused != null) {
                if (isEditableNode(inputFocused)) {
                    return Pair(inputFocused, win)
                }
                val budget = intArrayOf(800)
                val inner = findFocusedEditableDescendant(inputFocused, depth = 0, budget = budget)
                if (inner != null) {
                    return Pair(inner, win)
                }
            }
        }
        return null
    }

    private fun findFocusedEditableDescendant(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: IntArray,
    ): AccessibilityNodeInfo? {
        if (depth > 90 || budget[0] <= 0) return null
        budget[0]--

        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            if (isEditableNode(child) && (child.isFocused || child.isAccessibilityFocused)) {
                return child
            }
            val sub = findFocusedEditableDescendant(child, depth + 1, budget)
            if (sub != null) return sub
        }
        return null
    }

    private fun findTopApplicationWindowAtPoint(
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

    /**
     * Searches ALL application windows first (bypassing full-screen SystemUI TYPE_SYSTEM overlays)
     * and returns the deepest/smallest editable AccessibilityNodeInfo containing (x, y).
     */
    private fun findEditableAtPointAcrossWindows(
        displayId: Int,
        x: Int,
        y: Int,
        slopPx: Int,
    ): Pair<AccessibilityNodeInfo, AccessibilityWindowInfo>? {
        val winBounds = Rect()
        for (win in getExternalWindows(displayId)) {
            win.getBoundsInScreen(winBounds)
            if (!winBounds.contains(x, y)) continue
            val root = win.root ?: continue
            val budget = intArrayOf(900)
            val hit = hitTestEditableNode(root, x, y, slopPx, depth = 0, budget = budget)
            if (hit != null) {
                return Pair(hit, win)
            }
        }
        return null
    }

    private fun hitTestEditableNode(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        slopPx: Int,
        depth: Int,
        budget: IntArray,
    ): AccessibilityNodeInfo? {
        if (depth > 90 || budget[0] <= 0) return null
        budget[0]--

        // Do NOT abort child traversal when a wrapper container (e.g. HTML <div> or unclipped ViewGroup)
        // has empty/non-matching bounds; always inspect children in reverse z-order first.
        for (i in node.childCount - 1 downTo 0) {
            if (budget[0] <= 0) break
            val child = node.getChild(i) ?: continue
            val childHit = hitTestEditableNode(child, x, y, slopPx, depth + 1, budget)
            if (childHit != null) return childHit
        }

        if (!isEditableNode(node)) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return null
        bounds.inset(-slopPx, -slopPx)
        return if (bounds.contains(x, y)) node else null
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        return node.isEditable ||
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

        // Prioritize TYPE_APPLICATION windows above TYPE_SYSTEM windows so full-screen
        // SystemUI Desktop Mode overlays never shadow the actual app window underneath!
        return rawWindows
            .asSequence()
            .filter { win ->
                val pkg = win.root?.packageName?.toString()
                (win.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    win.type == AccessibilityWindowInfo.TYPE_SYSTEM) &&
                    pkg != service.packageName &&
                    pkg != "com.android.systemui"
            }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
                    .thenByDescending { it.isFocused }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.layer },
            )
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

    fun executeIfRealEditorConnected(action: PendingForceInputAction): Boolean {
        val currentPkg = activeExternalPackage ?: return false
        if ((activeExternalInputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL) {
            return false
        }
        return (action.targetPackage == null || action.targetPackage == currentPkg) &&
            executeNow(action)
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
