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
- **Full Native Gboard Support**: Type in **any language** (English, CJK composition, emojis, swipe typing, voice dictation, clipboard) on your phone and insert directly into your selected text box on the external monitor.
- **Manual On-Demand Activation**: Never pops up unexpectedly while clicking around. Opens only when you tap **Keyboard**.
- **External Onscreen Keyboard Suppression**: Automatically suppresses the onscreen soft keyboard on the external display (`SoftKeyboardController.SHOW_MODE_HIDDEN`) while letting Gboard run smoothly on the phone when requested.
- **Flicker-Free Persistent Session**:
  - **Insert**: Inserts your typed text into the selected external text box (appending cleanly across multiple consecutive inserts) while keeping Gboard open without focus flicker.
  - **Insert + Enter**: Inserts text and submits Enter/Search on single-line fields or appends a `\n` newline in multi-line editors.
  - **Clear**: Clears unsubmitted draft text in the phone input bar first; when empty, clears all text in the selected external text box.
  - **⌫ (Backspace)**: Deletes draft characters locally or deletes characters inside the external text box when the draft bar is empty.
  - **Hide / Gboard Downward Arrow**: Closes cleanly via **Hide**, toggling **Keyboard**, system Back, or Gboard's bottom-left downward hide button (`WindowInsets.isImeVisible`).

See [`docs/EXTERNAL_KEYBOARD_INPUT.md`](docs/EXTERNAL_KEYBOARD_INPUT.md) for detailed architecture and design invariants.

---

## Project Structure

```text
app/src/main/java/com/example/desktoptouchpad/
├── MainActivity.kt                        # Compose touchpad screen, controls & options
├── gesture/
│   └── GestureController.kt               # Velocity pointer acceleration & gesture routing
├── input/
│   ├── ExternalTextBridge.kt              # External text-box targeting, flicker-free insertion & API 33+ InputMethod fallback
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
