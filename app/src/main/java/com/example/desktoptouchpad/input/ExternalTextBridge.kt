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

    var isExternalActionInFlight: Boolean = false
        private set

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
    private var api33Helper: Any? = null

    private val restorePhoneKeyboardRunnable: Runnable = Runnable {
        isExternalActionInFlight = false
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

        isExternalActionInFlight = false
        onAllowPhoneSoftKeyboard()
        _session.value = current.copy(
            displayId = lastTapDisplayId,
            targetLabel = updatedLabel,
            hintText = updatedHint,
            refocusTrigger = refocusCounter.incrementAndGet()
        )
    }

    private val typeNullGraceRunnable: Runnable = Runnable {
        val action = pendingAction ?: return@Runnable
        pendingAction = null
        mainHandler.removeCallbacks(fallbackSetTextRunnable)
        // First try AccessibilityNodeInfo ACTION_SET_TEXT if an editable node exists;
        // otherwise commit directly onto the active connection (e.g. terminal / canvas app).
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
        isExternalActionInFlight = false
        targetNode = null
        targetPackage = null
        hasModifiedTargetInSession = false
        lastTapDisplayId = Display.INVALID_DISPLAY
        lastTapPoint = null
        _session.value = null
    }

    fun dismissKeyboardSession() {
        cancelPendingCallbacks()
        pendingAction = null
        isExternalActionInFlight = false
        _session.value = null
        onSuppressExternalSoftKeyboard()
    }

    private fun cancelPendingCallbacks() {
        mainHandler.removeCallbacks(typeNullGraceRunnable)
        mainHandler.removeCallbacks(fallbackSetTextRunnable)
        mainHandler.removeCallbacks(restorePhoneKeyboardRunnable)
        mainHandler.removeCallbacks(postTapRefreshRunnable)
    }

    /**
     * Called right BEFORE dispatching a user tap on the external display.
     * Suppresses the external onscreen keyboard, records tap coordinates, and if the
     * phone keyboard relay bar is already open, refreshes the selected box & restores
     * Gboard focus on the phone right after the tap lands.
     */
    fun onBeforeExternalTap(displayId: Int, x: Float, y: Float) {
        if (displayId == Display.INVALID_DISPLAY) return
        onSuppressExternalSoftKeyboard()
        lastTapDisplayId = displayId
        lastTapPoint = PointF(x, y)

        if (_session.value != null) {
            isExternalActionInFlight = true
            mainHandler.removeCallbacks(postTapRefreshRunnable)
            mainHandler.postDelayed(postTapRefreshRunnable, 135L)
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
            windowTitle = resolvedWin?.title?.toString()
        )

        isExternalActionInFlight = false
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

        // Prefer hitNode if user specifically tapped an editable node at fallbackX/Y;
        // otherwise use the currently input-focused editable node on that display.
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
     * Forces the user's typed text straight into the selected text box on the external display,
     * while keeping the phone keyboard bar open and suppressing any onscreen keyboard on the external display.
     */
    fun forceInputIntoSelectedBox(
        text: String,
        sendEnterAfter: Boolean,
    ) {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY) return
        if (text.isEmpty() && !sendEnterAfter) return

        val hadSelection = targetInitialSelStart >= 0 &&
            targetInitialSelEnd > targetInitialSelStart

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

        enqueueForceAction(action)
    }

    /**
     * Clears all text inside the selected external text box while keeping the phone keyboard open.
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

        enqueueForceAction(action)
    }

    /**
     * Deletes one character inside the selected external text box while keeping the phone keyboard open.
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

        enqueueForceAction(action)
    }

    private fun enqueueForceAction(action: PendingForceInputAction) {
        mainHandler.removeCallbacks(typeNullGraceRunnable)
        mainHandler.removeCallbacks(fallbackSetTextRunnable)
        mainHandler.removeCallbacks(restorePhoneKeyboardRunnable)

        // Block any onscreen keyboard on the external display before touching/focusing it
        isExternalActionInFlight = true
        onSuppressExternalSoftKeyboard()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (executeDirectImeCommitIfConnected(action)) {
                markTargetModifiedAfterAction(action)
                scheduleRestorePhoneKeyboard()
                return
            }

            pendingAction = action
            mainHandler.postDelayed(fallbackSetTextRunnable, 420L)

            // Wait 55ms so InputMethodManagerService applies SHOW_MODE_HIDDEN first,
            // then tap the external text box so it gains focus without showing an external IME.
            mainHandler.postDelayed(
                {
                    if (pendingAction === action) {
                        if (executeDirectImeCommitIfConnected(action)) {
                            pendingAction = null
                            mainHandler.removeCallbacks(fallbackSetTextRunnable)
                            markTargetModifiedAfterAction(action)
                            scheduleRestorePhoneKeyboard()
                        } else {
                            dispatchInternalTap(action.displayId, action.anchorX, action.anchorY)
                        }
                    }
                },
                55L
            )
        } else {
            // API 26-32 fallback: focus external text box with SHOW_MODE_HIDDEN and apply ACTION_SET_TEXT
            mainHandler.postDelayed(
                {
                    dispatchInternalTap(action.displayId, action.anchorX, action.anchorY)
                    mainHandler.postDelayed(
                        {
                            forceViaAccessibilityNode(action)
                            scheduleRestorePhoneKeyboard()
                        },
                        110L
                    )
                },
                55L
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
        mainHandler.postDelayed(restorePhoneKeyboardRunnable, 50L)
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
            // Real text input connection on the external app is ready! Commit immediately!
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
            // Window container reported TYPE_NULL first (common in Chrome WebView ~30ms before
            // the inner <input> reports its real InputConnection). Give the real <input> 110ms!
            mainHandler.removeCallbacks(typeNullGraceRunnable)
            mainHandler.postDelayed(typeNullGraceRunnable, 110L)
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

    private fun forceViaAccessibilityNode(action: PendingForceInputAction): Boolean {
        val node = resolveNodeForForceSetText(action.displayId, action.anchorX, action.anchorY)
            ?: return false
        node.refresh()

        var handled = false
        val currentText = if (node.isShowingHintText || node.isPassword) {
            ""
        } else {
            node.text?.toString().orEmpty()
        }

        if (action.clearAllText) {
            val setArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            handled = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
            if (handled) {
                targetInitialSelStart = 0
                targetInitialSelEnd = 0
                hasModifiedTargetInSession = true
            }
        } else if (!action.textToInsert.isNullOrEmpty()) {
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
                newText = action.textToInsert
                newCursor = newText.length
            } else if (action.appendAtEnd) {
                newText = currentText + action.textToInsert
                newCursor = newText.length
            } else {
                val s = if (rawStart in 0..currentText.length) rawStart else currentText.length
                val e = if (rawEnd in s..currentText.length) rawEnd else s
                newText = currentText.replaceRange(s, e, action.textToInsert)
                newCursor = s + action.textToInsert.length
            }

            val setArgs = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
                )
            }
            handled = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
            if (handled) {
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
            }
        } else if (action.deleteBeforeLength > 0 && currentText.isNotEmpty()) {
            val newText = currentText.dropLast(action.deleteBeforeLength.coerceAtMost(currentText.length))
            val setArgs = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
                )
            }
            handled = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
            if (handled) {
                targetInitialSelStart = newText.length
                targetInitialSelEnd = newText.length
                hasModifiedTargetInSession = true
            }
        }

        if (action.sendEnterAfter && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)) {
            node.refresh()
            handled = node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            ) || handled
        }

        return handled
    }

    private fun resolveNodeForForceSetText(
        displayId: Int,
        anchorX: Float,
        anchorY: Float,
    ): AccessibilityNodeInfo? {
        val focused = findFocusedEditableOnDisplay(displayId)?.first
        if (focused != null) return focused

        val saved = targetNode
        if (saved != null && saved.refresh() && isEditableNode(saved)) {
            return saved
        }

        val win = findTopWindowAtPoint(displayId, anchorX.toInt(), anchorY.toInt())
        val root = win?.root ?: return null
        return hitTestEditableNode(root, anchorX.toInt(), anchorY.toInt(), slopPx = 12, depth = 0)
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
                imeOptions = attribute.imeOptions
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
        if (action.targetPackage != null && action.targetPackage != currentPkg) {
            return false
        }
        return executeNow(action)
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
                    // Prevent selectAllOnFocus (e.g. browser URL bars) from overwriting existing text
                    // when re-focused unless the text was already selected when keyboard opened.
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
            val hasExplicitAction = explicitAction != EditorInfo.IME_ACTION_NONE &&
                explicitAction != EditorInfo.IME_ACTION_UNSPECIFIED &&
                explicitAction != EditorInfo.IME_ACTION_DONE
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
