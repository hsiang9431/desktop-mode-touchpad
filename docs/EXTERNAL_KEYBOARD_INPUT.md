# External Display Keyboard Input Architecture

This document explains how `AndroidDesktopTouchpad` delivers native phone Gboard text input (all languages, CJK composition, swipe, voice) into text boxes on a connected external display while keeping the external monitor free of onscreen keyboards.

---

## Key Platform Constraints

1. **Single Global Window Focus on Multi-Display Phones (`config_perDisplayFocusEnabled = false`)**
   - On standard Android phones without root/system privileges, only one display window holds global window and `InputMethodManagerService` (IMMS) focus at a time.
   - Typing happens locally in `RelayEditText` on the phone (`Display.DEFAULT_DISPLAY`) with zero external focus changes so Gboard never interrupts multi-character or CJK composition.
   - When an external action (**Insert**, **Insert + Enter**, or external **Clear** / **⌫**) targets an external surface via `AccessibilityService.InputMethod` (`Api33InputMethodBridge`), focus briefly moves to the external display to commit onto its `InputConnection` and immediately returns to `MainActivity` on the phone (`scheduleRestorePhoneKeyboard`). As an Android OS consequence of global single-window focus, Gboard briefly hides during that ~100ms external commit and automatically reopens on the phone.

2. **`SoftKeyboardController` Global Policy (`InputMethodManagerService`)**
   - `AccessibilityService.SoftKeyboardController.setShowMode(SHOW_MODE_HIDDEN)` sets `Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE = 1` (`mA11yRequestingNoSoftKeyboard = true` in `IMMS`).
   - Setting `SHOW_MODE_HIDDEN` blocks onscreen soft keyboards from appearing when text boxes are tapped on the external monitor.

3. **Gboard Bottom-Left Downward Hide Button**
   - On Android 11+ with edge-to-edge / gesture navigation, tapping the bottom-left downward IME hide arrow hides `WindowInsetsCompat.Type.ime()` via `InputMethodService` / `WindowInsetsController` **without** dispatching `KeyEvent.KEYCODE_BACK` to `View.onKeyPreIme` and while keeping `hasWindowFocus() == true` on `MainActivity`.

---

## Core Design

### 1. Manual Keyboard Activation Only
- Tapping on the external display (`TouchpadAccessibilityService.performTap`) records `(displayId, x, y)` in `ExternalTextBridge.onBeforeExternalTap` and **never** auto-opens the phone keyboard.
- The phone keyboard opens **only** when the user taps **Keyboard** (`ExternalTextBridge.openManualKeyboard`).

### 2. External Onscreen Keyboard Suppression (`SoftKeyboardController`)
- **When Phone Keyboard Session is Closed (`textSession == null`)** and `MainActivity` is started with an external display connected:
  - `SoftKeyboardController.showMode` is kept at **`SHOW_MODE_HIDDEN`** so clicking text fields on the external display never pops up an onscreen keyboard on the external monitor.
- **When Phone Keyboard Session is Open (`textSession != null`)**:
  - `SoftKeyboardController.showMode` stays at **`SHOW_MODE_AUTO`** so Gboard can display on the phone, and automatically re-engages `SHOW_MODE_HIDDEN` as soon as the session closes.
- **Lifecycle Cleanup**:
  - When `MainActivity` stops (`onStop`), the external display disconnects, or `TouchpadAccessibilityService` unbinds, `SHOW_MODE_AUTO` is restored so normal phone apps are unaffected.

### 3. Direct Node Action + API 33+ `InputMethod` Commit
- **Direct Node Path (`ExternalTextBridge.tryDirectAccessibilityNodeAction`)**:
  - Attempts `ACTION_SET_TEXT`, `ACTION_SET_SELECTION`, and `ACTION_IME_ENTER` (or `\n` on `node.isMultiLine == true`) when an editable `AccessibilityNodeInfo` is directly accessible.
- **`AccessibilityService.InputMethod` Path (`Api33InputMethodBridge`)**:
  - Re-focuses the selected external coordinates, commits via `AccessibilityInputConnection.commitText` (with `getSurroundingText` protection against `selectAllOnFocus` overwriting existing text and end-of-text caret placement across consecutive **Insert** calls), executes `performEditorAction` / `KEYCODE_ENTER`, and immediately restores focus to `RelayEditText` on the phone so the keyboard stays open.

### 4. Dismissing via Gboard's Downward Arrow
- `PhoneKeyboardRelayBar` observes `WindowInsets.isImeVisible` alongside `RelayEditText.hasWindowFocus()`, `lostWindowFocusWhileImeOpen`, and `isExternalFocusTransitionActive()`.
- Once Gboard has become visible in the session, if `WindowInsets.isImeVisible` transitions to `false` while `MainActivity` still holds window focus (distinguishing manual Gboard close from temporary external-commit focus shifts), the session automatically dismisses `PhoneKeyboardRelayBar` and re-engages `SHOW_MODE_HIDDEN` for the external display.
