package com.example.desktoptouchpad.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.text.InputType
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.example.desktoptouchpad.R
import com.example.desktoptouchpad.input.ExternalTextSession
import com.example.desktoptouchpad.service.TouchpadAccessibilityService
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private class RelayEditText(context: Context) : EditText(context) {
    var appliedSessionId: Long = -1L
    var appliedTextColor: Int = 0
    var appliedHintColor: Int = 0
    var isClosingKeyboard: Boolean = false
    var lostWindowFocusWhileImeOpen: Boolean = false
    var pendingShowSoftInputOnWindowFocus: Boolean = false
    var onBackPreIme: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_BACK) && (event?.action == KeyEvent.ACTION_UP)) {
            onBackPreIme?.invoke()
        }
        return super.onKeyPreIme(keyCode, event)
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun showImeOnFocusedEditText(activity: Activity?, editText: RelayEditText) {
    if (editText.isClosingKeyboard) return
    TouchpadAccessibilityService.instance?.allowPhoneSoftKeyboard()
    if (!editText.isFocused) {
        editText.requestFocus()
    }
    editText.postDelayed(
        {
            if (!editText.isAttachedToWindow || editText.isClosingKeyboard) return@postDelayed
            activity?.window?.let { win ->
                WindowCompat.getInsetsController(win, editText).show(WindowInsetsCompat.Type.ime())
            }
            val imm = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, 0)
        },
        25L,
    )
}

private fun requestPhoneSoftKeyboard(activity: Activity?, editText: RelayEditText) {
    if (editText.isClosingKeyboard) return
    TouchpadAccessibilityService.instance?.allowPhoneSoftKeyboard()
    if (editText.hasWindowFocus()) {
        editText.pendingShowSoftInputOnWindowFocus = false
        showImeOnFocusedEditText(activity, editText)
        return
    }

    editText.pendingShowSoftInputOnWindowFocus = true
    if (activity != null) {
        val refocusIntent = Intent(activity, activity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        }
        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = Display.DEFAULT_DISPLAY
        }
        runCatching {
            activity.startActivity(refocusIntent, options.toBundle())
        }
    }
}

private fun hidePhoneSoftKeyboard(activity: Activity?, editText: RelayEditText?) {
    if (editText == null) return
    editText.isClosingKeyboard = true
    editText.pendingShowSoftInputOnWindowFocus = false
    val imm = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(editText.windowToken, 0)
    activity?.window?.let { win ->
        WindowCompat.getInsetsController(win, editText).hide(WindowInsetsCompat.Type.ime())
    }
    editText.clearFocus()
}

@Composable
fun PhoneKeyboardRelayBar(
    session: ExternalTextSession,
    oledSaverMode: Boolean,
    onDismissKeyboard: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val defaultPlaceholder = stringResource(R.string.keyboard_placeholder)
    val latestOnDismiss by rememberUpdatedState(onDismissKeyboard)
    val isImeVisible = WindowInsets.isImeVisible

    var editTextRef by remember { mutableStateOf<RelayEditText?>(null) }
    var hasImeShownInSession by remember(session.sessionId) { mutableStateOf(false) }

    LaunchedEffect(session.sessionId, session.refocusTrigger, editTextRef) {
        val view = editTextRef ?: return@LaunchedEffect
        view.isClosingKeyboard = false
        TouchpadAccessibilityService.instance?.allowPhoneSoftKeyboard()
        delay(30.milliseconds)
        requestPhoneSoftKeyboard(activity, view)
    }

    // Detect when the user manually hides Gboard using Gboard's bottom-left downward arrow
    // (or system back gesture), which hides IME insets while keeping phone window focus.
    LaunchedEffect(isImeVisible, editTextRef) {
        val view = editTextRef ?: return@LaunchedEffect
        if (isImeVisible) {
            if (view.hasWindowFocus()) {
                hasImeShownInSession = true
                view.lostWindowFocusWhileImeOpen = false
            }
        } else if (hasImeShownInSession && !view.isClosingKeyboard) {
            delay(65.milliseconds)
            val rootImeStillHidden = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            val externalTransitionActive =
                TouchpadAccessibilityService.instance?.isExternalFocusTransitionActive() == true

            if (!view.isClosingKeyboard &&
                rootImeStillHidden &&
                view.hasWindowFocus() &&
                !view.lostWindowFocusWhileImeOpen &&
                !externalTransitionActive
            ) {
                hidePhoneSoftKeyboard(activity, view)
                latestOnDismiss()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            hidePhoneSoftKeyboard(activity, editTextRef)
            TouchpadAccessibilityService.instance?.suppressExternalSoftKeyboard()
        }
    }

    val cardBg = if (oledSaverMode) Color(0xFF09090E) else Color(0xFF1E1E2E)
    val fieldBg = if (oledSaverMode) Color(0xFF050508) else Color(0xFF141420)
    val borderColor = if (oledSaverMode) Color(0xFF1E1E2E) else Color(0xFF313244)
    val titleColor = if (oledSaverMode) Color(0xFF7F849C) else Color(0xFFCDD6F4)
    val inputTextColor = if (oledSaverMode) Color(0xFFBAC2DE) else Color(0xFFCDD6F4)
    val hintTextColor = Color(0xFF6C7086)

    fun submitInput(sendEnterAfter: Boolean) {
        val view = editTextRef ?: return
        val typedText = view.text?.toString().orEmpty()
        if (typedText.isEmpty() && !sendEnterAfter) return
        view.text?.clear()
        TouchpadAccessibilityService.instance?.forceInputIntoSelectedBox(
            text = typedText,
            sendEnterAfter = sendEnterAfter,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.keyboard_target_header, session.targetLabel),
                    color = titleColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )

                RelayActionMiniButton(
                    label = stringResource(R.string.btn_kb_hide),
                    oledSaver = oledSaverMode,
                    onClick = {
                        hidePhoneSoftKeyboard(activity, editTextRef)
                        onDismissKeyboard()
                    },
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(fieldBg)
                    .border(1.dp, Color(0xFF313244), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx ->
                        RelayEditText(ctx).apply {
                            background = null
                            setPadding(0, 0, 0, 0)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            gravity = Gravity.CENTER_VERTICAL or Gravity.START
                            inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                            setHorizontallyScrolling(false)
                            maxLines = 3
                            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                            isVerticalScrollBarEnabled = true
                            isFocusable = true
                            isFocusableInTouchMode = true

                            val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                                if (isClosingKeyboard) return@OnWindowFocusChangeListener
                                if (hasFocus) {
                                    if (pendingShowSoftInputOnWindowFocus) {
                                        pendingShowSoftInputOnWindowFocus = false
                                        showImeOnFocusedEditText(ctx.findActivity(), this)
                                    }
                                } else {
                                    lostWindowFocusWhileImeOpen = true
                                    postDelayed(
                                        {
                                            val act = ctx.findActivity()
                                            val isActivityVisible = (act as? LifecycleOwner)
                                                ?.lifecycle
                                                ?.currentState
                                                ?.isAtLeast(Lifecycle.State.STARTED) != false
                                            if (isAttachedToWindow &&
                                                !isClosingKeyboard &&
                                                isActivityVisible &&
                                                !hasWindowFocus()
                                            ) {
                                                requestPhoneSoftKeyboard(act, this)
                                            }
                                        },
                                        90L,
                                    )
                                }
                            }
                            viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
                            tag = focusListener

                            onBackPreIme = {
                                isClosingKeyboard = true
                                latestOnDismiss()
                            }

                            setOnEditorActionListener { _, _, keyEvent ->
                                val isHardwareEnterDown = (keyEvent != null) &&
                                    (keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) &&
                                    (keyEvent.action == KeyEvent.ACTION_DOWN)
                                if ((keyEvent == null) || isHardwareEnterDown) {
                                    submitInput(sendEnterAfter = false)
                                    true
                                } else {
                                    true
                                }
                            }

                            editTextRef = this
                        }
                    },
                    update = { view ->
                        val desiredTextColor = inputTextColor.toArgb()
                        if (view.appliedTextColor != desiredTextColor) {
                            view.appliedTextColor = desiredTextColor
                            view.setTextColor(desiredTextColor)
                        }
                        val desiredHintColor = hintTextColor.toArgb()
                        if (view.appliedHintColor != desiredHintColor) {
                            view.appliedHintColor = desiredHintColor
                            view.setHintTextColor(desiredHintColor)
                        }

                        view.hint = session.hintText ?: defaultPlaceholder
                        if (view.appliedSessionId != session.sessionId) {
                            view.appliedSessionId = session.sessionId
                            view.text?.clear()
                        }
                    },
                    onRelease = { view ->
                        view.isClosingKeyboard = true
                        val listener = view.tag as? ViewTreeObserver.OnWindowFocusChangeListener
                        if ((listener != null) && view.viewTreeObserver.isAlive) {
                            view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                        }
                        if (editTextRef === view) {
                            editTextRef = null
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RelayActionMiniButton(
                    modifier = Modifier.weight(0.65f),
                    label = stringResource(R.string.btn_kb_backspace),
                    oledSaver = oledSaverMode,
                    onClick = {
                        val view = editTextRef ?: return@RelayActionMiniButton
                        if (!view.text.isNullOrEmpty()) {
                            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                        } else {
                            TouchpadAccessibilityService.instance?.sendBackspaceToSelectedBox()
                        }
                    },
                )

                RelayActionMiniButton(
                    modifier = Modifier.weight(0.85f),
                    label = stringResource(R.string.btn_kb_clear),
                    oledSaver = oledSaverMode,
                    onClick = {
                        val view = editTextRef ?: return@RelayActionMiniButton
                        if (!view.text.isNullOrEmpty()) {
                            view.text?.clear()
                        } else {
                            TouchpadAccessibilityService.instance?.clearSelectedBox()
                        }
                    },
                )

                RelayActionMiniButton(
                    modifier = Modifier.weight(0.9f),
                    label = stringResource(R.string.btn_kb_insert),
                    oledSaver = oledSaverMode,
                    onClick = { submitInput(sendEnterAfter = false) },
                )

                RelayActionMiniButton(
                    modifier = Modifier.weight(1.45f),
                    label = stringResource(R.string.btn_kb_insert_enter),
                    oledSaver = oledSaverMode,
                    onClick = { submitInput(sendEnterAfter = true) },
                )
            }
        }
    }
}

@Composable
private fun RelayActionMiniButton(
    label: String,
    oledSaver: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (oledSaver) Color(0xFF313244) else Color(0xFF45475A)
    val textColor = if (oledSaver) Color(0xFF9399B2) else Color(0xFFCDD6F4)

    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        modifier = modifier
            .height(28.dp)
            .focusProperties { canFocus = false },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
        )
    }
}
