# Android Desktop Touchpad

A Samsung DeX-style phone touchpad and native Gboard text-input bridge for **Android Desktop Mode** on connected external displays — requiring **no Root, no ADB, and no Shizuku**.

---

## Features

### Desktop Trackpad & Pointer
- **External Display Cursor Overlay**: Automatically detects connected secondary displays (`DisplayManager`) and renders a hardware-accelerated pointer overlay (`WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`) via `CursorOverlayManager`.
- **Multi-Touch Gestures**:
  - **1-finger move & tap**: Velocity-accelerated pointer movement and left-click on the external screen.
  - **Double-tap & hold / Left-button hold**: Continuous stroke drag-and-drop (`StrokeDescription.continueStroke`).
  - **2-finger scroll**: Smooth vertical & horizontal scrolling with configurable natural scroll direction.
  - **2-finger tap / Context button**: Long-press / secondary click at cursor position.
  - **3-finger tap**: System Back action.
- **OLED Burn-In Saver**: One-tap pure-black low-luminance mode with `FLAG_KEEP_SCREEN_ON` active while an external display is connected.

### Native Phone Keyboard Input for External Display
- **Full Native Gboard Support**: Type uninterrupted in **any language** (English, CJK composition, emojis, swipe typing, voice dictation, clipboard) on your phone and send directly into your selected text box on the external monitor.
- **Manual On-Demand Activation**: Never pops up unexpectedly while clicking around. Opens only when you tap **Keyboard**.
- **External Onscreen Keyboard Suppression**: Automatically suppresses the onscreen soft keyboard on the external display (`SoftKeyboardController.SHOW_MODE_HIDDEN`) while letting Gboard run on the phone when requested.
- **Persistent Session Controls**:
  - **Insert**: Commits your typed text into the selected external text box (appending cleanly across consecutive inserts) and automatically keeps/restores Gboard open on the phone.
  - **Insert + Enter**: Inserts text and submits Enter/Search on single-line fields or appends a `\n` newline in multi-line editors.
  - **Clear**: Clears unsubmitted draft text in the phone input bar first; when empty, clears all text in the selected external text box.
  - **⌫ (Backspace)**: Deletes draft characters locally or deletes characters inside the external text box when the draft bar is empty.
  - **Hide / Gboard Downward Arrow**: Closes cleanly via **Hide**, toggling **Keyboard**, system Back, or Gboard's bottom-left downward hide button (`WindowInsets.isImeVisible`).

> **Note on Multi-Display IME Focus**: Because standard Android phones enforce a single global focused window across displays (`config_perDisplayFocusEnabled = false`), committing actions (**Insert**, **Insert + Enter**, external **Clear** / **⌫**) into external surfaces that require active IME focus briefly shifts focus to the external display and immediately restores focus to the phone, causing Gboard to briefly dip and reopen while keeping your keyboard session active.

See [`docs/EXTERNAL_KEYBOARD_INPUT.md`](docs/EXTERNAL_KEYBOARD_INPUT.md) for detailed architecture and platform notes.

---

## Project Structure

```text
app/src/main/java/com/example/desktoptouchpad/
├── MainActivity.kt                        # Compose touchpad screen, controls & options
├── gesture/
│   └── GestureController.kt               # Velocity pointer acceleration & gesture routing
├── input/
│   ├── ExternalTextBridge.kt              # External text-box targeting, insertion & API 33+ InputMethod commit
│   └── ExternalTextSession.kt             # Active manual keyboard session state
├── overlay/
│   └── CursorOverlayManager.kt            # Secondary display detection & pointer overlay window
├── service/
│   └── TouchpadAccessibilityService.kt    # Gesture dispatch, InputMethod host & SoftKeyboardController management
└── ui/
    ├── PhoneKeyboardRelayBar.kt           # Native EditText Gboard relay bar & IME inset lifecycle
    └── ProminentDisclosureDialog.kt       # Accessibility permission prominent disclosure
```

---

## Build & Install

1. Open the project root in **Android Studio**.
2. Build the debug APK:
   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```
   Output APK: `app/build/outputs/apk/debug/app-debug.apk`
3. Install on your phone, tap **Enable Accessibility Service**, accept the disclosure, and enable **Desktop Touchpad Service** in Android Accessibility Settings.
4. Connect an external USB-C / HDMI / wireless Desktop Mode display.
