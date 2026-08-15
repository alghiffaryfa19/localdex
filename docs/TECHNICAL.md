# LocalDex — Technical Breakdown

How LocalDex runs Samsung DeX on-device with no computer, no root, and no
floating overlay window.

## Architecture

```
LocalDex app ──(ADB over TLS, loopback)──▶ adbd
    │  1. push scrcpy-server v4.1 to /data/local/tmp
    │  2. app_process … com.genymobile.scrcpy.Server new_display=1920x1440/240
    │  3. wm set-display-windowing-mode -d <id> 5   (force freeform)
    │  4. open ADB streams to localabstract:scrcpy_<scid>
    ▼
scrcpy server ──▶ creates virtual display ──▶ One UI runs the desktop on it
    │  H.264 video ──▶ MediaCodec ──▶ SurfaceView (fullscreen viewer)
    ◀─ mouse / scroll / key control messages (scrcpy control protocol)
```

The app never shells out to an `adb` binary — it speaks the ADB protocol
directly to `adbd` on localhost.

## The ADB layer

Ported from [anyapk](https://github.com/sam1am/anyapk):

- [libadb-android](https://github.com/MuntashirAkon/libadb-android) implements
  the ADB wire protocol and TLS.
- Wireless-debugging **pairing** discovers the pairing port via mDNS
  (`_adb-tls-pairing._tcp`) and takes the 6-digit code through a notification
  inline reply (you can't leave the Settings screen during pairing — the code
  regenerates).
- A custom **Conscrypt** build is required: the SPAKE2 pairing secret is
  derived from the TLS exporter, which stock TLS stacks don't expose to apps.
- After the first successful connection the app grants itself
  `WRITE_SECURE_SETTINGS` via `pm grant` (allowed for this permission because
  it's declared `development`), so it can re-enable wireless debugging after a
  reboot or network change without a trip to Settings.

## The display: virtual, with freeform forced

The session starts the bundled scrcpy server with `new_display=<WxH/DPI>`,
which creates a trusted virtual display owned by shell. Virtual displays have
two big advantages over the `overlay_display_devices` approach: **no preview
window on the main screen**, and **no global settings modified**.

But on Android 16 QPR2+ (the base of One UI 8.5/9), freeform windowing no
longer activates on app-created virtual displays
([scrcpy #6143](https://github.com/Genymobile/scrcpy/issues/6143)) — every app
launches fullscreen with no window controls. The fix comes from
`DisplayWindowSettings.getWindowingModeLocked()` in AOSP: the **per-display
persisted windowing mode is checked before every desktop-mode heuristic**
(`mIsPc`, `isPublicSecondaryDisplayWithDesktopModeForceEnabled()`, …), and
there is a shell command that sets it:

```sh
wm set-display-windowing-mode -d <displayId> 5   # 5 = WINDOWING_MODE_FREEFORM
```

LocalDex parses the display id from the server's `New display: … (id=N)` log
line and runs this automatically. Verified on One UI 9: the display comes up
`fullscreen` and flips to `freeform`, after which apps open as movable,
resizable windows with AOSP drag-handle decor.

### Why not an overlay display?

`settings put global overlay_display_devices "WxH/DPI"` also creates a
secondary display, and DeX treats it like an external monitor with its full
window management. Two dealbreakers, both verified in AOSP source and on
device:

1. **The preview window can't be hidden.** `OverlayDisplayWindow` draws a
   preview of the display on the main screen: position is clamped on-screen
   (`x = max(0, min(x, screenWidth - width))`), alpha (0.8) and scale (0.5)
   are hard-coded, there is no setting or property to hide or move it, and
   Samsung builds it non-touchable.
2. **Caption drags crash SystemUI.** On One UI 9, dragging a window by its
   caption on the overlay display kills the `wmshell.main` thread with an NPE
   (`DesktopModeVisualIndicator` → `DesktopStateImpl` constructed with a null
   display context) — with both touchscreen and mouse-style injection. The
   freeform-forced virtual display does not trigger this path.

## The scrcpy client

The server binary is the official
[scrcpy release](https://github.com/Genymobile/scrcpy/releases) v4.1, bundled
as `app/src/main/assets/scrcpy-server` and pushed to
`/data/local/tmp/localdex-scrcpy-server.jar` each session. The client half of
the protocol is implemented in `app/src/main/java/com/localdex/scrcpy/`:

- **Server start** (over an ADB `shell:` stream):
  `CLASSPATH=… app_process / com.genymobile.scrcpy.Server 4.1 scid=<8hex>
  log_level=info video=true audio=false control=true video_codec=h264
  tunnel_forward=true send_device_meta=false send_dummy_byte=false
  new_display=<spec>`. The version argument must exactly match the bundled
  server. With `tunnel_forward=true` the server listens on the abstract socket
  `scrcpy_<scid>`; the app opens two ADB streams to
  `localabstract:scrcpy_<scid>` (video, then control) — the same mechanism
  `adb forward` uses.
- **Video stream framing** (v4.1): a 4-byte codec id (`"h264"`), then packets
  with 12-byte headers. If the first byte's MSB is set it's a *session packet*
  (4B flags, 4B width, 4B height — sent at capture start/rotation); otherwise
  8B pts+flags (bit 62 = codec config, bit 61 = key frame) followed by a 4B
  payload size. Config packets (SPS/PPS) are queued to a `MediaCodec` H.264
  decoder with `BUFFER_FLAG_CODEC_CONFIG`; frames render straight to the
  viewer's `SurfaceView`.
- **Control messages**: `INJECT_TOUCH_EVENT` (32 bytes), `INJECT_KEYCODE`
  (14 bytes), `INJECT_SCROLL_EVENT` (21 bytes; scroll values are i16
  fixed-point over the range [-16, 16]). Device messages arriving on the
  control socket (clipboard etc.) are drained and discarded.

## Input model: virtual mouse

The viewer does not forward touches as finger events — it emulates scrcpy's
"sdk mouse" (`pointerId = -1`, a hover move before each press, and
`actionButton`/`buttons` state):

- one finger = mouse click / drag (window handles, sliders, selection)
- two fingers = scroll wheel (a mouse-drag in a list selects instead of
  scrolling, so scrolling gets its own gesture)
- Back (key or gesture) = `KEYCODE_BACK` injected to the DeX display

A desktop is mouse-oriented anyway, and this matches how desktop scrcpy —
the reference client for this display — behaves.

## adb equivalents

Every step maps to a plain adb command, useful for debugging from a computer:

```sh
adb pair 127.0.0.1:<port> <code>                  # pairing (app: SPAKE2 via libadb)
adb connect 127.0.0.1:<port>                      # connect (app: mDNS + TLS)
adb push scrcpy-server /data/local/tmp/…          # app: head -c trick over exec:
adb shell CLASSPATH=… app_process / com.genymobile.scrcpy.Server 4.1 …
adb shell wm set-display-windowing-mode -d <N> 5  # the freeform fix
adb forward tcp:27183 localabstract:scrcpy_<scid> # app: direct ADB streams
scrcpy --display-id=<N>                           # view the same desktop from a PC
```

## Known quirks

- The window drag-handle menu's **split-screen** button targets the phone's
  built-in screen, not the DeX display (stock handle-menu behavior).
- The freeform decor is AOSP's drag-handle style, not the classic DeX title
  bar — DeX's own decor only appears on displays it fully manages (external /
  overlay), where the SystemUI drag crash lives.
- Windows launched *before* the freeform forcing lands stay fullscreen; the
  app applies the mode before the viewer becomes interactive, so in practice
  everything the user opens is freeform.
