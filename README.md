# Android Desktop Touchpad

A Samsung DeX-style phone touchpad and native Gboard text-input bridge for **Android Desktop Mode** on connected external displays — requiring **no Root, no ADB, and no Shizuku**.

---

## Features

### Desktop Trackpad & Pointer
- **Android 14 Dynamic Cursors**: Automatically detects connected secondary displays (`DisplayManager`) and renders Android 14 desktop vector cursors (`WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`) via `CursorOverlayManager`, dynamically switching between arrow, hand pointer, text I-beam, grabbing hand, all-scroll, context menu, and horizontal/vertical/diagonal freeform window resize cursors with exact hotspot alignment.
- **Multi-Touch Gestures, Action Bar & Persistent Options**:
  - **1-finger move & tap**: DeX-style velocity-accelerated pointer movement and left-click on the external screen, with an adjustable **Pointer Sensitivity** slider (`0.5×`–`3.5×`).
  - **Hold-to-Drag (Release-Locked)**: Engage drag via **double-tap & hold** (configurable **Double-Tap Interval** slider from `150 ms`–`550 ms`), **single-finger long-press**, or holding the **Click / Drag** button. Continuous stroke chaining (`StrokeDescription.continueStroke`) and drag-owner tracking ensure dragging only ends when every finger holding the drag lifts.
  - **2-finger scroll**: Smooth vertical & horizontal scrolling with a **Natural Scrolling** toggle.
  - **2-finger tap / Right Click**: Secondary / right click at the cursor position via 2-finger tap or the **Right Click** button.
  - **External-Only Navigation & 3-Finger Gestures**: **Back** and **Home** buttons plus **3-finger tap** (Back), **3-finger swipe down** (Home), and **3-finger swipe up** (App Switcher) target only the external display without minimizing the phone touchpad app. Includes a **3-Finger Gestures** toggle in **Options**, with all preferences persisted via `SharedPreferences`.
- **OLED Dim Theme**: Pure-black low-luminance OLED interface with quick **Center** and **Options** controls in the status header and `FLAG_KEEP_SCREEN_ON` active while an external display is connected.
- **Plug-and-Play Display Lifecycle & Safe Unplug Exit**:
  - Supports opening the app **before or after** connecting a USB-C / HDMI / wireless display (gracefully handling HDMI/DisplayPort link-training blanking and Mirror → Desktop display transitions).
  - Automatically detaches the external overlay, restores normal phone window focusability and soft-keyboard mode (`SHOW_MODE_AUTO`), returns focus to the phone Home screen, and closes the app (`finishAndRemoveTask()`) when the external display is unplugged so phone lockscreen / Keyguard behavior is never disrupted.

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
app/src/main/
├── java/com/example/desktoptouchpad/
│   ├── MainActivity.kt                        # Compose touchpad UI, gesture surface, options & display lifecycle
│   ├── gesture/
│   │   └── GestureController.kt               # Velocity pointer acceleration, drag ownership & persisted settings
│   ├── input/
│   │   ├── ExternalTextBridge.kt              # External text-box targeting, key dispatch & API 33+ InputMethod commit
│   │   └── ExternalTextSession.kt             # Active manual keyboard session state
│   ├── overlay/
│   │   └── CursorOverlayManager.kt            # Secondary display detection & dynamic Android 14 cursor overlay
│   ├── service/
│   │   └── TouchpadAccessibilityService.kt    # Gesture dispatch, external nav, hover cursor inspection & IME host
│   └── ui/
│       ├── PhoneKeyboardRelayBar.kt           # Native EditText Gboard relay bar & IME inset lifecycle
│       └── ProminentDisclosureDialog.kt       # Accessibility permission prominent disclosure
└── res/
    ├── drawable/                              # Android 14 cursor vectors & adaptive launcher icon layers
    └── mipmap-anydpi-v26/                     # Adaptive & monochrome themed launcher icons
```

---

## Build & Install

1. Open the project root in **Android Studio**.
2. Build the APK via the IDE (**Build → Generate App Bundles or APKs → Generate APKs**) or Gradle CLI:
   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```
   Output APK: `app/build/outputs/apk/debug/app-debug.apk`
3. Install on your phone, tap **Enable Accessibility Service**, accept the disclosure, and enable **Desktop Touchpad Service** in Android Accessibility Settings.
4. Connect an external USB-C / HDMI / wireless Desktop Mode display (either before or after launching the app). Unplugging the external display automatically exits the touchpad app.
