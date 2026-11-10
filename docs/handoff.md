# Handoff — Colorfit Companion (`:app`), reconnect work

Written 2026-11-10 to continue in a new chat. Read this first, then
`docs/findings-and-changes.md` for the protocol background. A shorter,
shareable version of the same handoff is the Claude doc
"Colorfit Companion — reconnect fixes handoff"
(https://claude.ai/artifact/8pRCMSgb8ZFsE5GvFabqeA).

## 1. Where things stand

The companion app now reconnects by itself after Bluetooth is toggled and after
a reboot; both were confirmed on the phone by the user. The work is in two
commits on `main`, **not pushed** (`main` is 2 ahead of `origin/main`):

| Commit | What |
|--------|------|
| `daa76a1` | Reconnect after Bluetooth comes back; show the real link state (all code changes) |
| `89e6efd` | README: TODO for the Cancel button |

| Item | State |
|------|-------|
| Reconnect when Bluetooth comes back on | Fixed, confirmed on hardware |
| Reconnect after reboot | Fixed, confirmed on hardware |
| Watch name "Unknown" after auto-reconnect | Fixed, confirmed on hardware |
| Notification stuck on "Scanning for watch…" | Fixed, confirmed after an app restart; **not yet across a real reboot** |
| "Cancel" on the reconnecting card disables auto-reconnect | **Open** (README TODO) |
| Push to `origin/main` | Not done |

## 2. The project in one screen

- Two Android apps in one Gradle project (Kotlin, Compose, Hilt):
  - `:app` → `com.colorfit.companion` (debug: `com.colorfit.companion.debug`),
    Noise **Colorfit Vivid Call 2**, MAC `78:02:B7:B9:FD:55`, name on air
    "Vivid Call 2_FD55". Protocol: NoiseFit Prime / `com.yc.pedometer`.
  - `:band` → `com.colorfit.band`, Noise "ColorFit" band (MOYOUNG),
    `F5:4D:A1:6D:22:C8`. Not touched in this work.
- Other watches that show up in scans and must not be confused with the above:
  Colorfit Pro 2 `E9:0E:90:5E:D5:BE`, a Da Fit watch `03:99:6B:44:04:A8`.
- Phone: Moto G32, Android 13, adb serial `ZD222K9NZD`. As of 2026-10-29 it runs
  the companion debug build with all the fixes (installed 10-29 ~14:52).
- Remote: `github.com/manish2k10/Android-App-for-Colorfit-Vivid-Call-2`. Pushing
  needs the repo-local `credential.https://github.com.username = manish2k10`
  (already set; the global git config points at another account). Ask before
  pushing.
- Key files (`app/src/main/kotlin/com/colorfit/companion/`):
  - `vendor/VendorConnection.kt` — vendor protocol state machine, handshake,
    **auto-reconnect** (`scheduleReconnect`, `reconnect`, Bluetooth-state
    receiver, `ReconnectStatus`), `user_disconnected` flag.
  - `ble/BleConnectionManager.kt` — owns `BluetoothGatt`, serialized op queue,
    bring-up after service discovery (MTU, device name, device info, battery,
    notify-enables, capability reads, then "ready").
  - `ble/BleForegroundService.kt` — keeps the process alive; notification text
    follows `connection.state` + `reconnectStatus`.
  - `ble/BootReceiver.kt`, `App.kt` — start the service at boot / launch when a
    watch is remembered.
  - `ui/home/HomeScreen.kt`, `HomeViewModel.kt` — connection card, reconnecting
    card, scan card.
  - `FileLoggingTree.kt` — debug-only rolling log file (see §6).

## 3. What was wrong (found from the phone's log, 2026-10-29)

The app was never killed; it sat idle with its foreground service up.

| Problem | Evidence | Fix in `daa76a1` |
|---------|----------|------------------|
| A reconnect that found Bluetooth off returned for good; nothing listened for Bluetooth coming back | 10-27 23:02 drop → "Bluetooth is off (adapter state 10)". 10-28 12:44 boot → launch retry ran before Bluetooth was up, same message. No attempt for 25 h, even after Bluetooth was toggled on 10-29 13:43 | `ACTION_STATE_CHANGED` receiver: off → status `WAITING_FOR_BLUETOOTH`, cancel retry, `ble.close()`; on → reconnect after 2 s |
| A failed manual connect retried the previous watch | 10-27 01:01 connect to `78:02…` failed (status 62); retry went to `E9:0E…` (Pro 2) | `connect(device)` saves the MAC immediately |
| Reconnecting card always said "Reconnecting…" | UI only checked "has a saved MAC" | `ReconnectStatus` (`IDLE`, `RETRYING`, `WAITING_FOR_BLUETOOTH`, `STOPPED`) drives the card |
| Name "Unknown" after auto-reconnect | Watch isn't paired in Android settings, so `getRemoteDevice(mac).name` is null | Read GAP Device Name `2A00` during bring-up when the name is null |
| Notification stuck on "Scanning for watch…" | Text only changed on explicit service intents; auto-reconnects sent none | Service collects the state flows; `ACTION_CONNECTED/DISCONNECTED` removed, callers use `start()` |

Earlier cause (10-27), still present: the persisted `user_disconnected` flag
(set by `disconnect()`, which both **Disconnect** and the reconnect card's
**Cancel** call) blocks all auto-reconnect across reboots until the user taps
Connect/Retry. It was `false` on 10-29, so not the cause that time.

## 4. Open items (in rough priority order)

1. **Cancel on the reconnecting card sets the sticky flag.** `HomeScreen.kt`
   `ConnectionCard` shows "Cancel" while reconnecting and calls
   `viewModel::disconnect` → `VendorConnection.disconnect()` →
   `setUserDisconnected(true)`. Needs a decision from the user: Cancel stops only
   this attempt, or it means "stay disconnected" with a clear label. Ask before
   implementing.
2. **Push** `daa76a1` + `89e6efd` (only when the user says so).
3. **Notification across a real reboot** — user to check it goes
   "Reconnecting to your watch…" → "Connected to Vivid Call 2_FD55" without
   opening the app.
4. **"Connected to Unknown" for ~2 s** until the `2A00` read lands. Fallback to
   the MAC instead of "Unknown" in `BleConnectionManager.onConnectionStateChange`
   (`deviceName = g.device.name ?: it.deviceName ?: "Unknown"`).
5. **Retry delays stretch in Doze**: the 10 s `delay()` took 49 s–2 min with the
   screen off (coroutine delays don't wake the phone). Harmless, slow.
6. **Unconfirmed gaps from `docs/findings-and-changes.md` §12** ("connected, no
   data after a long disconnection"): a failed or missing `onServicesDiscovered`
   leaves the link up with no handshake and no retry; `onDescriptorWrite`
   ignores its status. The third gap listed there (Bluetooth-off dead end) is
   now fixed — §12 should be updated to say so.
7. **Docs not updated for this work**: `docs/findings-and-changes.md` has no
   section for the 10-29 fixes, and the README still says "Auto-reconnect on
   transient drops (2s retry…)" (it is 10 s).
8. An **11-hour gap** on 10-27 (09:47–20:35) with an `autoConnect=true` attempt
   pending the whole time is unexplained; the log can't tell whether the watch
   was out of range.

## 5. How to build, install, run

```bash
./gradlew :app:assembleDebug
/c/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
/c/platform-tools/adb shell monkey -p com.colorfit.companion.debug -c android.intent.category.LAUNCHER 1
```

The app module has no unit tests. `:band` has `./gradlew :band:testDebugUnitTest`.

Working conventions with this user:
- Don't install or run the app unasked — compile and hand back; the user tests on
  the real watch. When they ask ("update the app on the device and run"), adb
  install + launch + reading the log/screenshot is fine.
- Commit or push only when asked. Commit messages: short imperative subject, a
  body explaining the why with evidence, ending with the
  `Co-Authored-By: Claude …` line.
- Source files are a mix of CRLF and LF; keep each file's existing endings.
- Byte formats: trust the decompiled original app and its traffic capture over
  `docs/protocol-research.md` (see Claude's memory note on the protocol sources).

## 6. How to debug

Debug builds write every Timber line to `files/ble-log.txt` (rotated to
`ble-log.1.txt` at ~2 MB) — days of history, unlike logcat's few minutes.
In Git Bash set `MSYS_NO_PATHCONV=1` before using `/sdcard` paths.

| What | Command (`adb` = `/c/platform-tools/adb`) |
|------|---------|
| Pull the log | `adb shell run-as com.colorfit.companion.debug cat files/ble-log.txt > ble-log.txt` |
| Saved reconnect state | `adb shell run-as com.colorfit.companion.debug cat shared_prefs/colorfit_vendor_prefs.xml` (`user_disconnected`, `last_connected_mac`) |
| Installed build | `adb shell dumpsys package com.colorfit.companion.debug \| grep lastUpdateTime` |
| Why the process died | `adb shell dumpsys activity exit-info com.colorfit.companion.debug` |
| Bluetooth on/off history | `adb shell dumpsys bluetooth_manager` ("Enable log") |
| Notification text | `adb shell dumpsys notification --noredact` |
| Last boot | `adb shell uptime` |
| Screenshot | `adb exec-out screencap -p > screen.png` |

Log lines to search for: `I/Vendor: reconnect to`, `link down`,
`Bluetooth on, reconnecting`, `Bluetooth turning off`,
`W/Vendor: reconnect: Bluetooth is off`, `phase READY`,
`I/BleGatt: connection state=<n> status=<n>` (8 = signal lost, 62 = couldn't set
up the link), `I/App: process start`.

## 7. Starting prompt for the next chat

> Read `docs/handoff.md` and your memory notes for this project. Then
> [the task — e.g. "fix open item 1, the Cancel button; ask me which behaviour I
> want first"].
