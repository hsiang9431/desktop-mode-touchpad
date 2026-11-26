package com.example.desktoptouchpad

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.desktoptouchpad.gesture.GestureController
import com.example.desktoptouchpad.overlay.CursorOverlayManager
import com.example.desktoptouchpad.overlay.ExternalDisplayInfo
import com.example.desktoptouchpad.service.TouchpadAccessibilityService
import com.example.desktoptouchpad.ui.PhoneKeyboardRelayBar
import com.example.desktoptouchpad.ui.ProminentDisclosureDialog
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

private val OledDimColorScheme = darkColorScheme(
    primary = Color(0xFF89B4FA),
    onPrimary = Color(0xFF09090E),
    secondary = Color(0xFF7F849C),
    background = Color.Black,
    surface = Color(0xFF09090E),
    onSurface = Color(0xFF9399B2),
    surfaceVariant = Color(0xFF181825),
    onSurfaceVariant = Color(0xFF6C7086),
    outline = Color(0xFF313244),
)

class MainActivity : ComponentActivity() {

    private val gestureController = GestureController()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager

    private var canSuppressFocusOnInput: Boolean = false
    private var isTouchDown: Boolean = false
    private var isInputActivityActive: Boolean = false
    private var appliedWindowFocusable: Boolean = true
    private var desiredKeepScreenOn: Boolean = false
    private var isActivityStarted: Boolean = false
    private var isUnplugExitArmed: Boolean = false
    private var isUnplugConfirmationPending: Boolean = false
    private var isExitingFromUnplug: Boolean = false
    private var isScreenOffReceiverRegistered: Boolean = false

    private val clearInputActivityRunnable: Runnable = Runnable {
        if (isDestroyed || isFinishing) return@Runnable
        if (isTouchDown || TouchpadAccessibilityService.isDragging.value) {
            mainHandler.postDelayed(clearInputActivityRunnable, INPUT_IDLE_CLEAR_DELAY_MS)
            return@Runnable
        }
        isInputActivityActive = false
        syncWindowFlagsIfInteractive()
    }

    private val armUnplugExitRunnable = Runnable {
        if (!isDestroyed && !isFinishing &&
            CursorOverlayManager.hasConnectedExternalDisplay(displayManager)
        ) {
            isUnplugExitArmed = true
        }
    }

    private val confirmUnplugExitRunnable = Runnable {
        isUnplugConfirmationPending = false
        if (isDestroyed || isFinishing) return@Runnable
        if (CursorOverlayManager.hasConnectedExternalDisplay(displayManager)) {
            syncWindowFlagsIfInteractive()
            scheduleArmUnplugExitIfNeeded()
        } else if (isUnplugExitArmed) {
            exitAppOnDisplayUnplugged()
        }
    }

    private val serviceUnplugCallback: () -> Unit = {
        onPotentialExternalDisplayUnplugged()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (CursorOverlayManager.hasConnectedExternalDisplay(displayManager)) {
                onExternalDisplayDetected()
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY &&
                !CursorOverlayManager.hasConnectedExternalDisplay(displayManager)
            ) {
                onPotentialExternalDisplayUnplugged()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (CursorOverlayManager.hasConnectedExternalDisplay(displayManager)) {
                onExternalDisplayDetected()
            }
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                // Guarantee phone window focusability and soft keyboard showMode are restored
                // immediately as the screen turns off so Keyguard/lockscreen is never blocked.
                restoreNormalPhoneWindowFlags()
                TouchpadAccessibilityService.setMainActivityStarted(false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (CursorOverlayManager.hasConnectedExternalDisplay(displayManager)) {
            onExternalDisplayDetected()
        } else if (isUnplugExitArmed) {
            onPotentialExternalDisplayUnplugged()
        }

        isActivityStarted = true
        TouchpadAccessibilityService.setMainActivityStarted(true)
        syncWindowFlagsIfInteractive()
    }

    override fun onResume() {
        super.onResume()
        if (!isExitingFromUnplug) {
            syncWindowFlagsIfInteractive()
        }
    }

    override fun onPause() {
        val powerManager = getSystemService(PowerManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true) {
            restoreNormalPhoneWindowFlags()
            TouchpadAccessibilityService.setMainActivityStarted(false)
        }
        super.onPause()
    }

    override fun onStop() {
        isActivityStarted = false
        restoreNormalPhoneWindowFlags()
        if (!isChangingConfigurations) {
            TouchpadAccessibilityService.setMainActivityStarted(false)
        }
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(armUnplugExitRunnable)
        mainHandler.removeCallbacks(confirmUnplugExitRunnable)
        mainHandler.removeCallbacks(clearInputActivityRunnable)
        if (::displayManager.isInitialized) {
            runCatching { displayManager.unregisterDisplayListener(displayListener) }
        }
        if (isScreenOffReceiverRegistered) {
            isScreenOffReceiverRegistered = false
            runCatching { unregisterReceiver(screenOffReceiver) }
        }
        if (TouchpadAccessibilityService.onExternalDisplayUnpluggedListener === serviceUnplugCallback) {
            TouchpadAccessibilityService.onExternalDisplayUnpluggedListener = null
        }
        restoreNormalPhoneWindowFlags()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        when (ev?.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE,
            -> {
                isTouchDown = true
                onActiveInputDetected()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                isTouchDown = false
                scheduleClearInputActivityIfNeeded()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        when (ev?.actionMasked) {
            MotionEvent.ACTION_SCROLL,
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE,
            MotionEvent.ACTION_HOVER_MOVE,
            -> {
                onActiveInputDetected()
                if (!isTouchDown) {
                    scheduleClearInputActivityIfNeeded()
                }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        gestureController.attachContext(this)

        displayManager = getSystemService(DisplayManager::class.java)
        if (CursorOverlayManager.findExternalDisplay(displayManager) != null) {
            // Display was already active before opening the app.
            isUnplugExitArmed = true
        } else if (CursorOverlayManager.hasConnectedExternalDisplay(displayManager)) {
            scheduleArmUnplugExitIfNeeded()
        }

        displayManager.registerDisplayListener(displayListener, mainHandler)
        TouchpadAccessibilityService.onExternalDisplayUnpluggedListener = serviceUnplugCallback

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        isScreenOffReceiverRegistered = true

        setContent {
            MaterialTheme(colorScheme = OledDimColorScheme) {
                TouchpadScreen(
                    gestureController = gestureController,
                    onExternalDisplayConnected = {
                        onExternalDisplayDetected()
                    },
                    onSetKeepScreenOn = { keepOn ->
                        desiredKeepScreenOn = keepOn
                        applyKeepScreenOn(keepOn)
                    },
                    onSetAllowNonFocusableOnInput = { allowed ->
                        canSuppressFocusOnInput = allowed
                        if (!allowed) {
                            clearInputActivityState()
                        } else if (isTouchDown) {
                            mainHandler.removeCallbacks(clearInputActivityRunnable)
                            isInputActivityActive = true
                        }
                        if (isActivityStarted) {
                            syncWindowFlagsIfInteractive()
                        }
                    },
                    onOpenSettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
        }
    }

    private fun onExternalDisplayDetected() {
        if (isExitingFromUnplug) return
        if (isUnplugConfirmationPending) {
            isUnplugConfirmationPending = false
            mainHandler.removeCallbacks(confirmUnplugExitRunnable)
            syncWindowFlagsIfInteractive()
        }
        scheduleArmUnplugExitIfNeeded()
    }

    private fun scheduleArmUnplugExitIfNeeded() {
        if (isUnplugExitArmed) return
        mainHandler.removeCallbacks(armUnplugExitRunnable)
        mainHandler.postDelayed(armUnplugExitRunnable, UNPLUG_ARM_STABILIZE_MS)
    }

    private fun onPotentialExternalDisplayUnplugged() {
        mainHandler.removeCallbacks(armUnplugExitRunnable)
        if (!isUnplugExitArmed || isExitingFromUnplug) return
        if (CursorOverlayManager.hasConnectedExternalDisplay(displayManager)) return

        // Restore normal phone window flags immediately so locking right after unplugging is safe,
        // then confirm after a short debounce that no Desktop Mode replacement display appeared.
        isUnplugConfirmationPending = true
        restoreNormalPhoneWindowFlags()
        TouchpadAccessibilityService.instance?.syncSoftKeyboardMode()

        mainHandler.removeCallbacks(confirmUnplugExitRunnable)
        mainHandler.postDelayed(confirmUnplugExitRunnable, UNPLUG_CONFIRM_DELAY_MS)
    }

    private fun onActiveInputDetected() {
        if (!canSuppressFocusOnInput) return
        mainHandler.removeCallbacks(clearInputActivityRunnable)
        if (!isInputActivityActive) {
            isInputActivityActive = true
            syncWindowFlagsIfInteractive()
        }
    }

    private fun scheduleClearInputActivityIfNeeded() {
        if (!isInputActivityActive) return
        mainHandler.removeCallbacks(clearInputActivityRunnable)
        mainHandler.postDelayed(clearInputActivityRunnable, INPUT_IDLE_CLEAR_DELAY_MS)
    }

    private fun clearInputActivityState() {
        isTouchDown = false
        isInputActivityActive = false
        mainHandler.removeCallbacks(clearInputActivityRunnable)
    }

    private fun applyWindowFocusable(focusable: Boolean) {
        if (isExitingFromUnplug && !focusable) return
        if (appliedWindowFocusable != focusable) {
            appliedWindowFocusable = focusable
            if (focusable) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
        }
    }

    private fun applyKeepScreenOn(keepOn: Boolean) {
        if (isExitingFromUnplug && keepOn) return
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun restoreNormalPhoneWindowFlags() {
        clearInputActivityState()
        appliedWindowFocusable = true
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun syncWindowFlagsIfInteractive() {
        if (isExitingFromUnplug || isUnplugConfirmationPending || !isActivityStarted) {
            if (isUnplugConfirmationPending || isExitingFromUnplug) {
                restoreNormalPhoneWindowFlags()
            }
            return
        }
        val powerManager = getSystemService(PowerManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true) {
            restoreNormalPhoneWindowFlags()
            return
        }
        val shouldWindowBeFocusable = !(canSuppressFocusOnInput && isInputActivityActive)
        applyWindowFocusable(shouldWindowBeFocusable)
        applyKeepScreenOn(desiredKeepScreenOn)
    }

    private fun exitAppOnDisplayUnplugged() {
        if (isExitingFromUnplug || isFinishing || isDestroyed) return
        isExitingFromUnplug = true
        isUnplugConfirmationPending = false
        mainHandler.removeCallbacks(armUnplugExitRunnable)
        mainHandler.removeCallbacks(confirmUnplugExitRunnable)
        val wasStarted = isActivityStarted
        isActivityStarted = false

        restoreNormalPhoneWindowFlags()

        runCatching {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }

        TouchpadAccessibilityService.setMainActivityStarted(false)
        TouchpadAccessibilityService.notifyExternalDisplayUnplugged()

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val powerManager = getSystemService(PowerManager::class.java)
        val canNavigateHome = wasStarted &&
            (keyguardManager?.isKeyguardLocked != true) &&
            (powerManager?.isInteractive != false)

        if (canNavigateHome) {
            runCatching {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                startActivity(homeIntent, options.toBundle())
            }
        }

        finishAndRemoveTask()
    }

    companion object {
        /** Stabilization window after plugging in before unplug-to-exit is armed. */
        private const val UNPLUG_ARM_STABILIZE_MS = 1800L

        /** Debounce window to ignore transient Mirror -> Desktop display swaps while connected. */
        private const val UNPLUG_CONFIRM_DELAY_MS = 650L

        /** Idle timeout after input ends before clearing FLAG_NOT_FOCUSABLE again. */
        private const val INPUT_IDLE_CLEAR_DELAY_MS = 600L
    }
}

@Composable
fun TouchpadScreen(
    gestureController: GestureController,
    onExternalDisplayConnected: () -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetAllowNonFocusableOnInput: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    val isServiceActive by TouchpadAccessibilityService.isConnected.collectAsState()
    val externalDisplay by TouchpadAccessibilityService.externalDisplay.collectAsState()
    val isDragging by TouchpadAccessibilityService.isDragging.collectAsState()
    val textSession by TouchpadAccessibilityService.textSession.collectAsState()

    LaunchedEffect(externalDisplay) {
        if (externalDisplay != null) {
            onExternalDisplayConnected()
        }
    }

    var showDisclosure by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableFloatStateOf(gestureController.sensitivity) }
    var tapHoldDelayMs by remember { mutableLongStateOf(gestureController.tapHoldDelayMs) }
    var naturalScrolling by remember { mutableStateOf(gestureController.naturalScrolling) }
    var threeFingerGesturesEnabled by remember {
        mutableStateOf(gestureController.threeFingerGesturesEnabled)
    }

    val isDesktopReady = isServiceActive && externalDisplay != null
    val activeTextSession = textSession
    val isKeyboardRelayVisible = isDesktopReady && activeTextSession != null

    // Keep FLAG_NOT_FOCUSABLE cleared when idle, and only allow setting it during active touchpad
    // input when Desktop Mode is ready and neither Gboard relay nor a dialog needs phone focus.
    val allowNonFocusableOnInput = isDesktopReady && !isKeyboardRelayVisible && !showDisclosure

    SideEffect {
        onSetAllowNonFocusableOnInput(allowNonFocusableOnInput)
    }

    DisposableEffect(Unit) {
        onDispose {
            onSetAllowNonFocusableOnInput(false)
        }
    }

    DisposableEffect(isDesktopReady) {
        onSetKeepScreenOn(isDesktopReady)
        onDispose { onSetKeepScreenOn(false) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val compactKeyboardHeight = isKeyboardRelayVisible && maxHeight < 270.dp

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!compactKeyboardHeight) {
                    StatusHeaderCard(
                        isServiceActive = isServiceActive,
                        externalDisplay = externalDisplay,
                        showOptions = showOptions,
                        onEnableServiceClick = { showDisclosure = true },
                        onCenterCursor = { gestureController.onCenterCursor() },
                        onToggleOptions = { showOptions = !showOptions }
                    )

                    if (showOptions && !isKeyboardRelayVisible) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TouchpadOptionsPanel(
                            sensitivity = sensitivity,
                            tapHoldDelayMs = tapHoldDelayMs,
                            naturalScrolling = naturalScrolling,
                            threeFingerGesturesEnabled = threeFingerGesturesEnabled,
                            onSensitivityChange = { value ->
                                sensitivity = value
                                gestureController.sensitivity = value
                            },
                            onTapHoldDelayChange = { valueMs ->
                                tapHoldDelayMs = valueMs
                                gestureController.tapHoldDelayMs = valueMs
                            },
                            onNaturalScrollingChange = { enabled ->
                                naturalScrolling = enabled
                                gestureController.naturalScrolling = enabled
                            },
                            onThreeFingerGesturesChange = { enabled ->
                                threeFingerGesturesEnabled = enabled
                                gestureController.threeFingerGesturesEnabled = enabled
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                DeXTouchpadSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    gestureController = gestureController,
                    isDesktopReady = isDesktopReady,
                    isDragging = isDragging,
                    threeFingerGesturesEnabled = threeFingerGesturesEnabled,
                    compactHint = isKeyboardRelayVisible
                )

                if (isKeyboardRelayVisible) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PhoneKeyboardRelayBar(
                        session = activeTextSession,
                        oledSaverMode = true,
                        onDismissKeyboard = {
                            gestureController.onDismissKeyboard()
                        }
                    )
                }

                if (!compactKeyboardHeight) {
                    Spacer(modifier = Modifier.height(8.dp))

                    TrackpadButtonBar(
                        enabled = isDesktopReady,
                        isDragging = isDragging,
                        isKeyboardOpen = isKeyboardRelayVisible,
                        gestureController = gestureController,
                        onPreparePhoneFocusForKeyboard = {
                            onSetAllowNonFocusableOnInput(false)
                        }
                    )
                }
            }
        }
    }

    if (showDisclosure) {
        ProminentDisclosureDialog(
            onConfirm = {
                showDisclosure = false
                onOpenSettings()
            },
            onDismiss = {
                showDisclosure = false
            }
        )
    }
}

@Composable
private fun StatusHeaderCard(
    isServiceActive: Boolean,
    externalDisplay: ExternalDisplayInfo?,
    showOptions: Boolean,
    onEnableServiceClick: () -> Unit,
    onCenterCursor: () -> Unit,
    onToggleOptions: () -> Unit
) {
    val isDesktopReady = isServiceActive && externalDisplay != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF09090E)),
        border = BorderStroke(1.dp, Color(0xFF1E1E2E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    val statusText = when {
                        isServiceActive && externalDisplay != null -> stringResource(
                            R.string.service_status_active,
                            externalDisplay.name,
                            externalDisplay.width,
                            externalDisplay.height
                        )
                        isServiceActive -> stringResource(R.string.service_status_waiting_display)
                        else -> stringResource(R.string.service_status_inactive)
                    }
                    val statusColor = when {
                        isServiceActive && externalDisplay != null -> Color(0xFF6C7086)
                        isServiceActive -> Color(0xFFA6997A)
                        else -> Color(0xFFB86B82)
                    }

                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7F849C)
                    )
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallControlChip(
                        label = stringResource(R.string.btn_center_cursor),
                        active = false,
                        enabled = isDesktopReady,
                        onClick = onCenterCursor
                    )
                    SmallControlChip(
                        label = stringResource(R.string.btn_settings),
                        active = showOptions,
                        enabled = true,
                        onClick = onToggleOptions
                    )
                }
            }

            if (!isServiceActive) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onEnableServiceClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF89B4FA))
                ) {
                    Text(
                        text = stringResource(R.string.enable_service_btn),
                        color = Color(0xFF09090E),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallControlChip(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.height(32.dp),
        border = BorderStroke(
            1.dp,
            when {
                active -> Color(0xFF89B4FA)
                enabled -> Color(0xFF313244)
                else -> Color(0xFF1E1E2E)
            }
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) Color(0x2689B4FA) else Color.Transparent,
            disabledContainerColor = Color.Transparent
        )
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = when {
                active -> Color(0xFF89B4FA)
                enabled -> Color(0xFF7F849C)
                else -> Color(0xFF45475A)
            }
        )
    }
}

@Composable
private fun TouchpadOptionsPanel(
    sensitivity: Float,
    tapHoldDelayMs: Long,
    naturalScrolling: Boolean,
    threeFingerGesturesEnabled: Boolean,
    onSensitivityChange: (Float) -> Unit,
    onTapHoldDelayChange: (Long) -> Unit,
    onNaturalScrollingChange: (Boolean) -> Unit,
    onThreeFingerGesturesChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF09090E)),
        border = BorderStroke(1.dp, Color(0xFF1E1E2E))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.label_sensitivity),
                    color = Color(0xFF9399B2),
                    fontSize = 13.sp
                )
                Text(
                    text = "${"%.1f".format(sensitivity)}x",
                    color = Color(0xFF89B4FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            val sensitivitySliderState = rememberSliderState(
                value = sensitivity,
                trackRange = 0.6f..3.5f
            )
            Slider(
                state = sensitivitySliderState,
                onValueChange = { value ->
                    sensitivitySliderState.value = value
                    onSensitivityChange(value)
                }
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.label_tap_hold_delay),
                    color = Color(0xFF9399B2),
                    fontSize = 13.sp
                )
                Text(
                    text = "$tapHoldDelayMs ms",
                    color = Color(0xFF89B4FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            val tapHoldSliderState = rememberSliderState(
                value = tapHoldDelayMs.toFloat(),
                trackRange = GestureController.MIN_TAP_HOLD_DELAY_MS.toFloat()..
                    GestureController.MAX_TAP_HOLD_DELAY_MS.toFloat()
            )
            Slider(
                state = tapHoldSliderState,
                onValueChange = { rawValue ->
                    tapHoldSliderState.value = rawValue
                    val steppedMs = ((rawValue / 10f).roundToLong() * 10L).coerceIn(
                        GestureController.MIN_TAP_HOLD_DELAY_MS,
                        GestureController.MAX_TAP_HOLD_DELAY_MS
                    )
                    onTapHoldDelayChange(steppedMs)
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.label_natural_scroll),
                    color = Color(0xFF9399B2),
                    fontSize = 13.sp
                )
                Switch(
                    checked = naturalScrolling,
                    onCheckedChange = onNaturalScrollingChange
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.label_three_finger_gestures),
                    color = Color(0xFF9399B2),
                    fontSize = 13.sp
                )
                Switch(
                    checked = threeFingerGesturesEnabled,
                    onCheckedChange = onThreeFingerGesturesChange
                )
            }
        }
    }
}

@Composable
private fun DeXTouchpadSurface(
    modifier: Modifier,
    gestureController: GestureController,
    isDesktopReady: Boolean,
    isDragging: Boolean,
    threeFingerGesturesEnabled: Boolean,
    compactHint: Boolean
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    val borderColor = when {
        isDragging -> Color(0xFF89B4FA)
        isDesktopReady -> Color(0xFF181825)
        else -> Color(0xFF313244)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black)
            .border(1.5.dp, borderColor, RoundedCornerShape(22.dp))
            .pointerInput(gestureController) {
                val tapSlopPx = 8.dp.toPx()
                val scrollSlopPx = 4.dp.toPx()
                val threeFingerSwipeThresholdPx = 44.dp.toPx()
                val multiFingerTapTimeoutMs = 230L

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    firstDown.consume()

                    val downPos = firstDown.position
                    val downTime = firstDown.uptimeMillis
                    val tapHoldDelayMs = gestureController.tapHoldDelayMs
                    var lastEventTime = downTime
                    var maxPointerCount = 1
                    var maxSingleFingerDisplacement = 0f
                    var totalTwoFingerDistance = 0f
                    var totalThreeFingerDistance = 0f
                    var isMovingCursor = false
                    var isTapDragActive = false
                    var dragStartFailed = false
                    var dragPointerId = firstDown.id
                    var threeFingerSwipeTriggered = false

                    var previousCentroid: Offset? = null
                    var threeFingerStartCentroid: Offset? = null

                    try {
                        while (true) {
                            val pendingHoldThresholdMs: Long? = when {
                                isTapDragActive || dragStartFailed || gestureController.isDragActive -> null
                                maxPointerCount != 1 -> null
                                !isMovingCursor && maxSingleFingerDisplacement <= tapSlopPx -> tapHoldDelayMs
                                else -> null
                            }

                            val event = if (pendingHoldThresholdMs != null) {
                                val elapsed = (SystemClock.uptimeMillis() - downTime).coerceAtLeast(0L)
                                val remaining = pendingHoldThresholdMs - elapsed
                                if (remaining <= 0L) {
                                    null
                                } else {
                                    withTimeoutOrNull(remaining) {
                                        awaitPointerEvent(PointerEventPass.Main)
                                    }
                                }
                            } else {
                                awaitPointerEvent(PointerEventPass.Main)
                            }

                            if (event == null) {
                                // Finger remained stationary past the tap-and-hold threshold; start
                                // drag immediately and keep it active until all fingers lift.
                                if (!gestureController.isDragActive) {
                                    if (gestureController.onDragStart(GestureController.DragOwner.TOUCHPAD)) {
                                        isTapDragActive = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else {
                                        dragStartFailed = true
                                    }
                                } else {
                                    dragStartFailed = true
                                }
                                continue
                            }

                            val activeChanges = event.changes.filter { it.pressed }

                            if (activeChanges.isEmpty()) {
                                lastEventTime = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastEventTime
                                event.changes.forEach { it.consume() }
                                break
                            }

                            val eventTime = activeChanges.maxOf { it.uptimeMillis }
                            val dtMs = (eventTime - lastEventTime).coerceAtLeast(4L)
                            lastEventTime = eventTime

                            if (isTapDragActive) {
                                // Once dragging, never end until every finger lifts from the touchpad.
                                val tracked = activeChanges.firstOrNull { it.id == dragPointerId }
                                    ?: activeChanges.first().also { dragPointerId = it.id }
                                if (tracked.previousPressed) {
                                    val delta = tracked.position - tracked.previousPosition
                                    val stepDist = hypot(delta.x, delta.y)
                                    if (stepDist > 0f) {
                                        gestureController.onMove(delta.x, delta.y, dtMs)
                                    }
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val pointerCount = activeChanges.size
                            if (pointerCount > maxPointerCount) {
                                maxPointerCount = pointerCount
                                previousCentroid = null
                            }

                            if (pointerCount == 1 && maxPointerCount == 1) {
                                val change = activeChanges.first()
                                val delta = change.position - change.previousPosition
                                val stepDist = hypot(delta.x, delta.y)
                                val displacementFromDown = hypot(
                                    change.position.x - downPos.x,
                                    change.position.y - downPos.y
                                )
                                if (displacementFromDown > maxSingleFingerDisplacement) {
                                    maxSingleFingerDisplacement = displacementFromDown
                                }

                                val heldDuration = eventTime - downTime

                                if (!gestureController.isDragActive &&
                                    !dragStartFailed &&
                                    !isMovingCursor &&
                                    maxSingleFingerDisplacement <= tapSlopPx &&
                                    heldDuration >= tapHoldDelayMs
                                ) {
                                    if (gestureController.onDragStart(GestureController.DragOwner.TOUCHPAD)) {
                                        isTapDragActive = true
                                        dragPointerId = change.id
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else {
                                        dragStartFailed = true
                                    }
                                }

                                if (maxSingleFingerDisplacement > tapSlopPx ||
                                    isMovingCursor ||
                                    isTapDragActive ||
                                    gestureController.isDragActive
                                ) {
                                    isMovingCursor = true
                                    if (stepDist > 0f) {
                                        gestureController.onMove(delta.x, delta.y, dtMs)
                                    }
                                }
                            } else if (pointerCount >= 2 && !gestureController.isDragActive) {
                                var sumX = 0f
                                var sumY = 0f
                                for (change in activeChanges) {
                                    sumX += change.position.x
                                    sumY += change.position.y
                                }
                                val currentCentroid = Offset(
                                    x = sumX / pointerCount,
                                    y = sumY / pointerCount
                                )

                                val prev = previousCentroid
                                if (pointerCount == 2 && maxPointerCount == 2 && prev != null) {
                                    val scrollDelta = currentCentroid - prev
                                    val stepScroll = hypot(scrollDelta.x, scrollDelta.y)
                                    totalTwoFingerDistance += stepScroll
                                    if (totalTwoFingerDistance > scrollSlopPx && stepScroll > 0f) {
                                        gestureController.onTwoFingerScroll(scrollDelta.x, scrollDelta.y)
                                    }
                                } else if (pointerCount == 3 && maxPointerCount == 3) {
                                    val start3 = threeFingerStartCentroid
                                    if (start3 == null) {
                                        threeFingerStartCentroid = currentCentroid
                                    } else {
                                        if (prev != null) {
                                            val step3 = currentCentroid - prev
                                            totalThreeFingerDistance += hypot(step3.x, step3.y)
                                        }
                                        val totalDelta = currentCentroid - start3
                                        if (!threeFingerSwipeTriggered &&
                                            abs(totalDelta.y) >= threeFingerSwipeThresholdPx &&
                                            abs(totalDelta.y) > abs(totalDelta.x) * 1.2f
                                        ) {
                                            threeFingerSwipeTriggered = true
                                            val handled = if (totalDelta.y > 0f) {
                                                gestureController.onThreeFingerSwipeDown()
                                            } else {
                                                gestureController.onThreeFingerSwipeUp()
                                            }
                                            if (handled) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        }
                                    }
                                }
                                previousCentroid = currentCentroid
                            }

                            event.changes.forEach { it.consume() }
                        }
                    } finally {
                        if (isTapDragActive) {
                            gestureController.onDragEnd(GestureController.DragOwner.TOUCHPAD)
                        }
                    }

                    val gestureDuration = (lastEventTime - downTime).coerceAtLeast(0L)

                    if (!isTapDragActive && !threeFingerSwipeTriggered && !gestureController.isDragActive) {
                        when (maxPointerCount) {
                            1 -> if (!isMovingCursor &&
                                !dragStartFailed &&
                                maxSingleFingerDisplacement <= tapSlopPx &&
                                gestureDuration < tapHoldDelayMs
                            ) {
                                if (gestureController.onTap()) {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }

                            2 -> if (totalTwoFingerDistance <= tapSlopPx * 1.5f &&
                                gestureDuration <= multiFingerTapTimeoutMs + 50L
                            ) {
                                if (gestureController.onSecondaryTap()) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }

                            3 -> if (totalThreeFingerDistance <= tapSlopPx * 2.0f &&
                                gestureDuration <= multiFingerTapTimeoutMs + 80L
                            ) {
                                if (gestureController.onThreeFingerTap()) {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.touchpad_title),
                color = Color(0xFF45475A),
                fontSize = if (compactHint) 12.sp else 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (!compactHint) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (threeFingerGesturesEnabled) {
                            R.string.touchpad_hint
                        } else {
                            R.string.touchpad_hint_no_three_finger
                        }
                    ),
                    color = Color(0xFF313244),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun TrackpadButtonBar(
    enabled: Boolean,
    isDragging: Boolean,
    isKeyboardOpen: Boolean,
    gestureController: GestureController,
    onPreparePhoneFocusForKeyboard: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    val buttonBg = Color(0xFF09090E)
    val buttonBorder = Color(0xFF1E1E2E)
    val textColor = if (enabled) Color(0xFF6C7086) else Color(0xFF313244)

    val keyboardBg = if (enabled && isKeyboardOpen) Color(0x2689B4FA) else buttonBg
    val keyboardBorder = if (enabled && isKeyboardOpen) Color(0xFF89B4FA) else buttonBorder
    val keyboardTextColor = if (enabled && isKeyboardOpen) Color(0xFF89B4FA) else textColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1.22f)
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(if (isDragging) Color(0x2689B4FA) else buttonBg)
                .border(
                    1.dp,
                    if (isDragging) Color(0xFF89B4FA) else buttonBorder,
                    RoundedCornerShape(14.dp)
                )
                .pointerInput(enabled, gestureController) {
                    if (!enabled) return@pointerInput
                    val holdThresholdMs = 180L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val downTime = down.uptimeMillis
                        var holdDragStarted = false
                        var upTime = downTime

                        try {
                            while (true) {
                                val elapsed = (SystemClock.uptimeMillis() - downTime).coerceAtLeast(0L)
                                val remaining = holdThresholdMs - elapsed
                                if (remaining <= 0L) {
                                    if (gestureController.onDragStart(GestureController.DragOwner.CLICK_BUTTON)) {
                                        holdDragStarted = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    break
                                }

                                val event = withTimeoutOrNull(remaining) {
                                    awaitPointerEvent(PointerEventPass.Main)
                                }
                                if (event == null) {
                                    if (gestureController.onDragStart(GestureController.DragOwner.CLICK_BUTTON)) {
                                        holdDragStarted = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    break
                                }

                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    upTime = event.changes.maxOfOrNull { it.uptimeMillis } ?: upTime
                                    event.changes.forEach { it.consume() }
                                    if (upTime - downTime < holdThresholdMs) {
                                        if (gestureController.onTap()) {
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                    }
                                    return@awaitEachGesture
                                }

                                upTime = pressed.maxOf { it.uptimeMillis }
                                event.changes.forEach { it.consume() }
                            }

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val pressed = event.changes.filter { it.pressed }
                                event.changes.forEach { it.consume() }
                                if (pressed.isEmpty()) break
                            }
                        } finally {
                            if (holdDragStarted) {
                                gestureController.onDragEnd(GestureController.DragOwner.CLICK_BUTTON)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.btn_left_click),
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }

        TrackpadActionButton(
            modifier = Modifier.weight(1.08f),
            label = stringResource(R.string.btn_right_click),
            enabled = enabled,
            backgroundColor = buttonBg,
            borderColor = buttonBorder,
            textColor = textColor,
            onClick = {
                if (gestureController.onSecondaryTap()) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        )

        TrackpadActionButton(
            modifier = Modifier.weight(0.96f),
            label = stringResource(R.string.btn_keyboard),
            enabled = enabled,
            backgroundColor = keyboardBg,
            borderColor = keyboardBorder,
            textColor = keyboardTextColor,
            onClick = {
                if (isKeyboardOpen) {
                    gestureController.onDismissKeyboard()
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                } else {
                    onPreparePhoneFocusForKeyboard()
                    if (gestureController.onRequestKeyboard()) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            }
        )

        TrackpadActionButton(
            modifier = Modifier.weight(0.72f),
            label = stringResource(R.string.btn_back),
            enabled = enabled,
            backgroundColor = buttonBg,
            borderColor = buttonBorder,
            textColor = textColor,
            onClick = {
                if (gestureController.onBack()) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        )

        TrackpadActionButton(
            modifier = Modifier.weight(0.72f),
            label = stringResource(R.string.btn_home),
            enabled = enabled,
            backgroundColor = buttonBg,
            borderColor = buttonBorder,
            textColor = textColor,
            onClick = {
                if (gestureController.onHome()) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        )
    }
}

@Composable
private fun TrackpadActionButton(
    modifier: Modifier,
    label: String,
    enabled: Boolean,
    backgroundColor: Color,
    borderColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = backgroundColor,
            disabledContainerColor = backgroundColor
        )
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
