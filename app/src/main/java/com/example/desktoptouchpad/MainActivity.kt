package com.example.desktoptouchpad

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
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
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.desktoptouchpad.overlay.ExternalDisplayInfo
import com.example.desktoptouchpad.service.TouchpadAccessibilityService
import com.example.desktoptouchpad.ui.PhoneKeyboardRelayBar
import com.example.desktoptouchpad.ui.ProminentDisclosureDialog
import kotlin.math.hypot

class MainActivity : ComponentActivity() {

    private val gestureController = GestureController()

    override fun onStart() {
        super.onStart()
        TouchpadAccessibilityService.setMainActivityStarted(true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            TouchpadAccessibilityService.setMainActivityStarted(false)
        }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                TouchpadScreen(
                    gestureController = gestureController,
                    onSetKeepScreenOn = { keepOn ->
                        if (keepOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    },
                    onOpenSettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
        }
    }
}

@Composable
fun TouchpadScreen(
    gestureController: GestureController,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    val isServiceActive by TouchpadAccessibilityService.isConnected.collectAsState()
    val externalDisplay by TouchpadAccessibilityService.externalDisplay.collectAsState()
    val isDragging by TouchpadAccessibilityService.isDragging.collectAsState()
    val textSession by TouchpadAccessibilityService.textSession.collectAsState()

    var showDisclosure by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var oledSaverMode by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableFloatStateOf(gestureController.sensitivity) }
    var naturalScrolling by remember { mutableStateOf(gestureController.naturalScrolling) }

    val isDesktopReady = isServiceActive && externalDisplay != null
    val activeTextSession = textSession
    val isKeyboardRelayVisible = isDesktopReady && activeTextSession != null

    DisposableEffect(isDesktopReady) {
        onSetKeepScreenOn(isDesktopReady)
        onDispose { onSetKeepScreenOn(false) }
    }

    val backgroundColor = if (oledSaverMode) Color.Black else Color(0xFF11111B)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
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
                        oledSaverMode = oledSaverMode,
                        showOptions = showOptions,
                        onEnableServiceClick = { showDisclosure = true },
                        onCenterCursor = { gestureController.onCenterCursor() },
                        onToggleOptions = { showOptions = !showOptions },
                        onToggleOledSaver = { oledSaverMode = !oledSaverMode }
                    )

                    if (showOptions && !oledSaverMode && !isKeyboardRelayVisible) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TouchpadOptionsPanel(
                            sensitivity = sensitivity,
                            naturalScrolling = naturalScrolling,
                            onSensitivityChange = { value ->
                                sensitivity = value
                                gestureController.sensitivity = value
                            },
                            onNaturalScrollingChange = { enabled ->
                                naturalScrolling = enabled
                                gestureController.naturalScrolling = enabled
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
                    oledSaverMode = oledSaverMode,
                    compactHint = isKeyboardRelayVisible
                )

                if (isKeyboardRelayVisible) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PhoneKeyboardRelayBar(
                        session = activeTextSession,
                        oledSaverMode = oledSaverMode,
                        onDismissKeyboard = {
                            gestureController.onDismissKeyboard()
                        }
                    )
                }

                if (!compactKeyboardHeight) {
                    Spacer(modifier = Modifier.height(8.dp))

                    TrackpadButtonBar(
                        enabled = isDesktopReady,
                        oledSaverMode = oledSaverMode,
                        isDragging = isDragging,
                        isKeyboardOpen = isKeyboardRelayVisible,
                        gestureController = gestureController
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
    oledSaverMode: Boolean,
    showOptions: Boolean,
    onEnableServiceClick: () -> Unit,
    onCenterCursor: () -> Unit,
    onToggleOptions: () -> Unit,
    onToggleOledSaver: () -> Unit
) {
    val cardColor = when {
        oledSaverMode -> Color(0xFF09090E)
        externalDisplay != null && isServiceActive -> Color(0xFF1E1E2E)
        isServiceActive -> Color(0xFF2A2433)
        else -> Color(0xFF31222C)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = if (oledSaverMode) BorderStroke(1.dp, Color(0xFF1E1E2E)) else null
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
                Column(modifier = Modifier.weight(1f)) {
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
                        oledSaverMode -> Color(0xFF6C7086)
                        isServiceActive && externalDisplay != null -> Color(0xFFA6E3A1)
                        isServiceActive -> Color(0xFFF9E2AF)
                        else -> Color(0xFFF38BA8)
                    }

                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (oledSaverMode) Color(0xFF7F849C) else Color(0xFFCDD6F4)
                    )
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isServiceActive && externalDisplay != null) {
                        SmallControlChip(
                            label = stringResource(R.string.btn_center_cursor),
                            active = false,
                            oledSaver = oledSaverMode,
                            onClick = onCenterCursor
                        )
                    }
                    SmallControlChip(
                        label = stringResource(R.string.btn_oled_saver),
                        active = oledSaverMode,
                        oledSaver = oledSaverMode,
                        onClick = onToggleOledSaver
                    )
                    if (!oledSaverMode) {
                        SmallControlChip(
                            label = stringResource(R.string.btn_settings),
                            active = showOptions,
                            oledSaver = false,
                            onClick = onToggleOptions
                        )
                    }
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
                        color = Color(0xFF11111B),
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
    oledSaver: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.height(32.dp),
        border = BorderStroke(
            1.dp,
            when {
                active -> Color(0xFF89B4FA)
                oledSaver -> Color(0xFF313244)
                else -> Color(0xFF45475A)
            }
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) Color(0x3389B4FA) else Color.Transparent
        )
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (oledSaver) Color(0xFF7F849C) else Color(0xFFCDD6F4)
        )
    }
}

@Composable
private fun TouchpadOptionsPanel(
    sensitivity: Float,
    naturalScrolling: Boolean,
    onSensitivityChange: (Float) -> Unit,
    onNaturalScrollingChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.label_sensitivity),
                    color = Color(0xFFCDD6F4),
                    fontSize = 13.sp
                )
                Text(
                    text = "${"%.1f".format(sensitivity)}x",
                    color = Color(0xFF89B4FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            val sliderState = rememberSliderState(
                value = sensitivity,
                trackRange = 0.6f..3.5f
            )
            Slider(
                state = sliderState,
                onValueChange = { value ->
                    sliderState.value = value
                    onSensitivityChange(value)
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.label_natural_scroll),
                    color = Color(0xFFCDD6F4),
                    fontSize = 13.sp
                )
                Switch(
                    checked = naturalScrolling,
                    onCheckedChange = onNaturalScrollingChange
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
    oledSaverMode: Boolean,
    compactHint: Boolean
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    val surfaceColor = when {
        oledSaverMode -> Color.Black
        isDragging -> Color(0xFF23273B)
        else -> Color(0xFF1E1E2E)
    }
    val borderColor = when {
        isDragging -> Color(0xFF89B4FA)
        oledSaverMode -> Color(0xFF181825)
        isDesktopReady -> Color(0xFF313244)
        else -> Color(0xFF45475A)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(surfaceColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(22.dp))
            .pointerInput(gestureController) {
                val tapSlopPx = 8.dp.toPx()
                val scrollSlopPx = 4.dp.toPx()
                val tapTimeoutMs = 230L
                val doubleTapWindowMs = 300L
                val longPressDragMs = 420L

                var lastSingleTapUpTime = 0L

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    firstDown.consume()

                    val downTime = firstDown.uptimeMillis
                    var lastEventTime = downTime
                    var maxPointerCount = 1
                    var totalSingleFingerDistance = 0f
                    var totalTwoFingerDistance = 0f
                    var isMovingCursor = false
                    var isTapDragActive = false

                    val isQuickSecondTouch = (downTime - lastSingleTapUpTime) in 1L..doubleTapWindowMs

                    var previousCentroid: Offset? = null

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val activeChanges = event.changes.filter { it.pressed }

                        if (activeChanges.isEmpty()) {
                            break
                        }

                        val pointerCount = activeChanges.size
                        if (pointerCount > maxPointerCount) {
                            maxPointerCount = pointerCount
                            previousCentroid = null
                        }

                        val eventTime = activeChanges.maxOf { it.uptimeMillis }
                        val dtMs = (eventTime - lastEventTime).coerceAtLeast(4L)
                        lastEventTime = eventTime

                        if (pointerCount == 1 && maxPointerCount == 1) {
                            val change = activeChanges.first()
                            val delta = change.position - change.previousPosition
                            val stepDist = hypot(delta.x, delta.y)
                            totalSingleFingerDistance += stepDist

                            val heldDuration = eventTime - downTime

                            if (!isTapDragActive) {
                                val triggerDoubleTapDrag =
                                    isQuickSecondTouch && (totalSingleFingerDistance > tapSlopPx || heldDuration > 170L)
                                val triggerLongPressDrag =
                                    !isMovingCursor && heldDuration >= longPressDragMs && totalSingleFingerDistance <= tapSlopPx

                                if (triggerDoubleTapDrag || triggerLongPressDrag) {
                                    if (gestureController.onDragStart()) {
                                        isTapDragActive = true
                                        lastSingleTapUpTime = 0L
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            }

                            if (totalSingleFingerDistance > tapSlopPx * 0.45f || isMovingCursor || isTapDragActive) {
                                isMovingCursor = true
                                if (stepDist > 0f) {
                                    gestureController.onMove(delta.x, delta.y, dtMs)
                                }
                            }
                        } else if (pointerCount >= 2) {
                            if (isTapDragActive) {
                                gestureController.onDragEnd()
                                isTapDragActive = false
                            }

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
                            if (prev != null && pointerCount == 2) {
                                val scrollDelta = currentCentroid - prev
                                val stepScroll = hypot(scrollDelta.x, scrollDelta.y)
                                totalTwoFingerDistance += stepScroll
                                if (totalTwoFingerDistance > scrollSlopPx && stepScroll > 0f) {
                                    gestureController.onTwoFingerScroll(scrollDelta.x, scrollDelta.y)
                                }
                            }
                            previousCentroid = currentCentroid
                        }

                        event.changes.forEach { it.consume() }
                    }

                    val gestureDuration = lastEventTime - downTime

                    if (isTapDragActive) {
                        gestureController.onDragEnd()
                    } else {
                        when {
                            maxPointerCount == 1 &&
                                totalSingleFingerDistance <= tapSlopPx &&
                                gestureDuration <= tapTimeoutMs -> {
                                if (gestureController.onTap()) {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                                lastSingleTapUpTime = lastEventTime
                            }

                            maxPointerCount == 2 &&
                                totalTwoFingerDistance <= tapSlopPx * 1.5f &&
                                gestureDuration <= tapTimeoutMs + 50L -> {
                                if (gestureController.onSecondaryTap()) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                lastSingleTapUpTime = 0L
                            }

                            maxPointerCount == 3 &&
                                gestureDuration <= tapTimeoutMs + 80L -> {
                                if (gestureController.onThreeFingerTap()) {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                                lastSingleTapUpTime = 0L
                            }

                            else -> {
                                lastSingleTapUpTime = 0L
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
            if (oledSaverMode) {
                Text(
                    text = stringResource(R.string.touchpad_oled_hint),
                    color = Color(0xFF313244),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = stringResource(R.string.touchpad_title),
                    color = Color(0xFF6C7086),
                    fontSize = if (compactHint) 13.sp else 16.sp,
                    fontWeight = FontWeight.Medium
                )
                if (!compactHint) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.touchpad_hint),
                        color = Color(0xFF585B70),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackpadButtonBar(
    enabled: Boolean,
    oledSaverMode: Boolean,
    isDragging: Boolean,
    isKeyboardOpen: Boolean,
    gestureController: GestureController
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    val buttonBg = if (oledSaverMode) Color(0xFF09090E) else Color(0xFF1E1E2E)
    val buttonBorder = if (oledSaverMode) Color(0xFF1E1E2E) else Color(0xFF313244)
    val textColor = when {
        !enabled -> Color(0xFF45475A)
        oledSaverMode -> Color(0xFF6C7086)
        else -> Color(0xFFCDD6F4)
    }

    val keyboardBg = if (enabled && isKeyboardOpen) Color(0x3389B4FA) else buttonBg
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
                .weight(1.35f)
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(if (isDragging) Color(0x3389B4FA) else buttonBg)
                .border(
                    1.dp,
                    if (isDragging) Color(0xFF89B4FA) else buttonBorder,
                    RoundedCornerShape(14.dp)
                )
                .pointerInput(enabled, gestureController) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val downTime = down.uptimeMillis
                        var holdDragStarted = false
                        var upTime = downTime

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            upTime = pressed.maxOf { it.uptimeMillis }
                            if (!holdDragStarted && upTime - downTime >= 180L) {
                                if (gestureController.onDragStart()) {
                                    holdDragStarted = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }

                        if (holdDragStarted) {
                            gestureController.onDragEnd()
                        } else if (upTime - downTime < 180L) {
                            if (gestureController.onTap()) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
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
            modifier = Modifier.weight(0.9f),
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
            modifier = Modifier.weight(1.0f),
            label = stringResource(R.string.btn_keyboard),
            enabled = enabled,
            backgroundColor = keyboardBg,
            borderColor = keyboardBorder,
            textColor = keyboardTextColor,
            onClick = {
                if (isKeyboardOpen) {
                    gestureController.onDismissKeyboard()
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                } else if (gestureController.onRequestKeyboard()) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        )

        TrackpadActionButton(
            modifier = Modifier.weight(0.75f),
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
            modifier = Modifier.weight(0.75f),
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
