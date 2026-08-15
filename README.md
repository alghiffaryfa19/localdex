# LocalDex

Run Samsung DeX **entirely on your phone** — no dock, no cable, no computer, no root.

![The DeX desktop running on-device with several app windows open](docs/images/dex-on-device.jpg)

LocalDex creates a hidden second display on your phone, lets Samsung run the DeX desktop on it, and shows it fullscreen with full mouse-style interaction. Curious how? See the [technical breakdown](docs/TECHNICAL.md).

## Requirements

- Samsung device on **One UI 8 or later** (desktop on virtual displays)
- Android 11+ (wireless debugging)
- Wi-Fi network (wireless debugging needs an active Wi-Fi connection; no data leaves the device — the connection is phone-to-itself)

## Setup

1. Install the APK (`app/build/outputs/apk/debug/app-debug.apk`).
2. Enable **Developer options** (Settings → About phone → Software information →
   tap *Build number* 7 times).
3. Open LocalDex and follow the checklist:
   - Allow notifications (used to enter the pairing code).
   - Tap **Start Pairing** — LocalDex opens Developer options; go to
     **Wireless debugging → Pair device with pairing code**, then type the
     6-digit code into the LocalDex notification's reply field.
4. Back in LocalDex: pick a display spec and tap **Start DeX**.

Pairing is one-time. After the first successful connection LocalDex grants itself `WRITE_SECURE_SETTINGS` (over its own ADB shell) so it can switch wireless debugging back on automatically after a reboot or network change.

## Usage

- **Display spec** is `WIDTHxHEIGHT/DPI` (default `1920x1440/240`). Lower DPI = more desktop room; higher DPI = bigger UI.
- **Input is a virtual mouse**: one finger = click and drag (drag window handles to move windows), two fingers = scroll wheel. **Back** (gesture or key) is forwarded to DeX.
- The translucent **✕ button** stops the session (with confirmation). **Tap-and-hold to drag it** anywhere — it starts in the top-right corner.
- Leave the viewer with Home; the session keeps running. Return (or stop) via the **LocalDex notification** — the notification's *Stop* action always ends the session, even if the viewer is gone.

## Viewing DeX from a computer

The virtual display is a normal Android display, so a computer with adb access to the phone can open and interact with the *same* DeX desktop with plain scrcpy:

```sh
scrcpy --display-id=<N>
```

The display id `N` is shown in the LocalDex main screen and in the notification while a session is running (e.g. *"LocalDex is running (display 7)"*).

Alternatively, a computer can create its own DeX virtual display without LocalDex at all: `scrcpy --new-display=1920x1080/240`. 

## Windowed apps / window controls

Apps open as floating windows with a **drag handle** at the top: drag the handle to move, drag edges to resize, tap the handle for minimize / maximize / split-screen. Known quirk: the **split-screen** option in the handle menu moves the app into split-screen on the phone's main screen, not on the DeX display.

## How it works

The interesting parts — the on-device ADB connection, the bundled scrcpy server and custom client, and the one-line trick that brings resizable windows back on One UI 8.5+ — are covered in the [technical breakdown](docs/TECHNICAL.md). Built on [libadb-android](https://github.com/MuntashirAkon/libadb-android) (ADB layer ported from [anyapk](https://github.com/sam1am/anyapk)) and [scrcpy](https://github.com/Genymobile/scrcpy).

## Building

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the install is blocked with `INSTALL_FAILED_VERIFICATION_FAILURE`:

```sh
adb shell settings put global verifier_verify_adb_installs 0
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put global verifier_verify_adb_installs 1
```

## Notes & limitations

- Wireless debugging turns itself off on reboot; LocalDex re-enables it automatically once it holds `WRITE_SECURE_SETTINGS` (see Setup), otherwise flip it on manually in Developer options.
- Audio stays on the phone (not routed through the session).
- One session at a time.