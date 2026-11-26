# External Display Keyboard Input Architecture

This document explains how `AndroidDesktopTouchpad` delivers native phone Gboard text input (all languages, CJK composition, swipe, voice) into text boxes on a connected external display without flickering, losing focus, or showing an unwanted onscreen keyboard on the external monitor.

---

## Key Platform Constraints Solved

1. **Single Global Window Focus on Multi-Display Phones**
   - On most Android devices (`config_perDisplayFocusEnabled = false`), only one display window holds global window/IME focus at a time.
   - Syncing text on every keystroke or tapping the external display on every submit steals window focus away from `MainActivity` on the phone, causing Gboard to close and reopen repeatedly.

2. **SoftKeyboardController Global Policy (`InputMethodManagerService`)**
   - `AccessibilityService.SoftKeyboardController.setShowMode(SHOW_MODE_HIDDEN)` sets `Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE = 1` (`mA11yRequestingNoSoftKeyboard = true` in `IMMS`).
   - Setting `SHOW_MODE_HIDDEN` blocks onscreen soft keyboards from appearing when text boxes are tapped on the external monitor, **and** immediately hides any currently visible soft keyboard if toggled while Gboard is open on the phone.

3. **Gboard Bottom-Left Downward Hide Button**
   - On Android 11+ with edge-to-edge / gesture navigation, tapping the bottom-left downward IME hide arrow hides `WindowInsetsCompat.Type.ime()` via `InputMethodService` / `WindowInsetsController` **without** dispatching `KeyEvent.KEYCODE_BACK` to `View.onKeyPreIme` and while keeping `hasWindowFocus() == true` on `MainActivity`.

---

## Core Design Invariants

### 1. Manual Keyboard Activation Only
- Tapping on the external display (`TouchpadAccessibilityService.performTap`) records `(displayId, x, y)` in `ExternalTextBridge.onBeforeExternalTap` and **never** auto-opens the phone keyboard.
- The phone keyboard opens **only** when the user taps **Keyboard** (`ExternalTextBridge.openManualKeyboard`), which snapshots the focused or hit-tested `AccessibilityNodeInfo` on the external display before phone Gboard takes focus.

### 2. Steady `SoftKeyboardController` Mode (Zero Flicker)
- **When Phone Keyboard Session is Closed (`textSession == null`)** and `MainActivity` is started with an external display connected:
  - `SoftKeyboardController.showMode` is kept at **`SHOW_MODE_HIDDEN`** so clicking text fields on the external display never pops up an onscreen keyboard on the external monitor.
- **When Phone Keyboard Session is Open (`textSession != null`)**:
  - `SoftKeyboardController.showMode` stays steadily at **`SHOW_MODE_AUTO`** for the entire session — never pulsing `SHOW_MODE_HIDDEN` during **Insert**, **Insert + Enter**, **Clear**, or **⌫**.
- **Lifecycle Cleanup**:
  - When `MainActivity` stops (`onStop`), the external display disconnects, or `TouchpadAccessibilityService` unbinds, `SHOW_MODE_AUTO` is restored so normal phone apps are unaffected.

### 3. Flicker-Free Direct Insertion + API 33+ `InputMethod` Fallback
- **Primary Path (`ExternalTextBridge.tryDirectAccessibilityNodeAction`)**:
  - Executes `ACTION_SET_TEXT`, `ACTION_SET_SELECTION`, and `ACTION_IME_ENTER` (or appends `\n` when `node.isMultiLine == true`) directly on the external `AccessibilityNodeInfo` over Accessibility IPC while `MainActivity` retains 100% of window and IME focus on the phone.
  - Tracks `hasModifiedTargetInSession` and cursor offsets so multiple consecutive **Insert** actions append cleanly rather than overwriting previous text.
  - Action buttons use `Modifier.focusProperties { canFocus = false }` and `editText.text?.clear()` in-place so `RelayEditText` never loses View focus or restarts Gboard's input session.
- **Fallback Path (`Api33InputMethodBridge` / `AccessibilityService.InputMethod`)**:
  - Used only when a surface exposes no `AccessibilityNodeInfo` supporting `ACTION_SET_TEXT` (e.g. raw canvas/terminal apps or appending to masked password fields). Uses `AccessibilityInputConnection.commitText`, `getSurroundingText` (`selectAllOnFocus` protection), and `performEditorAction` / `KEYCODE_ENTER`, then immediately restores phone Gboard focus.

### 4. Dismissing via Gboard's Downward Arrow
- `PhoneKeyboardRelayBar` observes `WindowInsets.isImeVisible` alongside `RelayEditText.hasWindowFocus()`, `lostWindowFocusWhileImeOpen`, and `isExternalFocusTransitionActive()`.
- Once Gboard has become visible in the session, if `WindowInsets.isImeVisible` transitions to `false` while `MainActivity` still holds window focus (no external focus transition), the session automatically dismisses `PhoneKeyboardRelayBar` and re-engages `SHOW_MODE_HIDDEN` for the external display.
