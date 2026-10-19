# Colorfit Companion

Kotlin Android companion apps for two Noise watches — the **Colorfit Vivid Call 2**
smartwatch and, new, the **ColorFit band** — built from scratch because Noise
doesn't publish a developer SDK.

> This is a personal/hobby project. Reverse-engineered protocols may break with any
> firmware update. Not affiliated with Noise.

The project builds **two apps**, installable side by side so both watches can be
connected at the same time:

| Module | App | Watch | Protocol |
|--------|-----|-------|----------|
| `:app` | Colorfit Companion (`com.colorfit.companion`) | Vivid Call 2 | NoiseFit Prime / yc.pedometer — `docs/opcode-reference.md`, `docs/findings-and-changes.md` |
| `:band` | ColorFit Band (`com.colorfit.band`) | "ColorFit" band | NoiseFit Track / MOYOUNG — `docs/band-protocol.md` |

`./gradlew :band:testDebugUnitTest` checks the band's frame builders and parsers
against bytes captured from NoiseFit Track's own session.

## Status — Phase 2 (vendor protocol)

Phase 1 ships the BLE foundation. Phase 2 adds the Noise (yc.pedometer)
vendor protocol:

- Compose UI (Material 3, dynamic colour)
- Permission flow with runtime grant UX
- BLE scanner (shows every nearby device — cheap watches rename
  themselves across firmware versions)
- Connection manager reading standard SIG services:
  - Battery Level (0x180F / 0x2A19)
  - Heart Rate Measurement (0x180D / 0x2A37) with notifications
  - Device Information (0x180A) — model, firmware, serial
- Vendor protocol layer — see `docs/opcode-reference.md` for the full
  command map. Implementations in `vendor/`.
- Foreground service so the connection survives backgrounding
- Auto-reconnect on transient drops (2s retry, no UI flicker)
- 30-second keep-alive heartbeat so cheap watches don't power-save
- Notification mirroring (Android `NotificationListenerService` →
  `0xC5` chunked text push)
- Weather via Open-Meteo — type a city name or tap "Use my location"

Phase 3 (polish): historical-data UI, settings persistence, watch-face push.

## ColorFit band app (`:band`) — new

Support for a second watch: the Noise **"ColorFit" band**, the one the
**NoiseFit Track** app (`com.crrepa.band.noise`) syncs with. It speaks a different
protocol from the Vivid Call 2 — MOYOUNG's, the company behind Da Fit — so it is
its own app, with its own icon (teal) and its own connection, and runs alongside
the Vivid Call 2 app.

The protocol was taken from NoiseFit Track (decompiled) and checked against a
Bluetooth capture of NoiseFit Track's own session with the band; see
`docs/band-protocol.md`.

What it does:

- Connects to the band and keeps it connected in the background; **Disconnect**
  stays disconnected until you reconnect, and it reconnects by itself when
  Bluetooth comes back on
- Time and timezone sync on every connect
- Battery, today's steps, distance and calories, plus the last two days' steps
- Heart rate (live while measuring), blood pressure and SpO₂ measurements
- Sleep for the last three nights (deep / light / REM / awake)
- Today's heart rate in 5-minute steps, and the band's auto-measure interval
  (off / 5 / 10 / 20 / 30 min)
- Phone notifications on the band, with per-app switches
- Weather (city lookup or your location) — sent on every connect and every 3 hours
- Find band

Before using it:

1. **Force-stop NoiseFit Track** (or uninstall it) — two apps talking to the band
   at once means double notifications and crossed commands.
2. Give **ColorFit Band** notification access.
3. Pick an auto heart-rate interval — the band keeps no heart-rate history while
   it's off.

Not yet confirmed on the band: notification push, Find band and the sleep
parser (built from the decompiled app; the capture had no data for them). This
band ignores the single-shot heart-rate command, so heart rate uses the band's
continuous measurement.

```
band/src/main/kotlin/com/colorfit/band/
├── BandApp.kt                      Application + Hilt; starts the session with the process
├── BandSession.kt                  Paced command queue, handshake, replies → UI state, reconnects
├── ble/BandGatt.kt                 GATT connection, serialized op queue, FEEA bring-up
├── ble/                            Scanner, prefs, foreground service, boot receiver
├── protocol/                       Pure Kotlin: frames, commands, parsers, notification text
├── notify/                         Notification listener + per-app filter
├── weather/WeatherService.kt       Open-Meteo fetch → band weather frames
└── ui/                             Home, notification apps, permissions, theme
```

`./gradlew :band:testDebugUnitTest` replays frames and replies captured from
NoiseFit Track's session and checks the builders and parsers byte for byte.

## Setup

1. Install Android Studio (Iguana or later).
2. Open this folder as a project. Studio will sync Gradle on first open.
   - The Gradle wrapper jar isn't checked in; Studio will fetch it automatically.
     If you ever want to build from CLI, run `gradle wrapper --gradle-version 8.7`
     once and commit `gradle/wrapper/gradle-wrapper.jar` + `gradle-wrapper.properties`.
3. Run the **app** configuration (Vivid Call 2) or the **band** configuration
   (ColorFit band) onto your phone (Android 8+). From the command line:
   `./gradlew :app:assembleDebug` / `./gradlew :band:assembleDebug`.
4. Grant the runtime permissions on the Permissions screen.
5. Press **Start scan**, pick your watch, tap **Connect**.
6. Press **Dump GATT table** — the JSON path is shown on the screen, copy it.
7. Open the file (the path lives in
   `Android/data/com.colorfit.companion.debug/files/Documents/`) and share
   it back to me.

## Architecture

```
app/src/main/kotlin/com/colorfit/companion/
├── App.kt                          Application + Hilt
├── MainActivity.kt                 Compose entry + nav graph
├── ble/
│   ├── BlePermissions.kt           Runtime permission snapshot
│   ├── BleScanner.kt               callbackFlow wrapper around BluetoothLeScanner
│   ├── BleConnectionManager.kt     Owns the BluetoothGatt, reads standard services
│   ├── BleForegroundService.kt     Keeps the connection alive in background
│   └── GattSpec.kt                 Standard SIG UUIDs + vendor registry (Phase 2)
├── data/
│   ├── StandardParsers.kt          GATT-spec 0x2A19, 0x2A37, 0x2A38 decoders
│   └── GattDumper.kt               JSON dump writer
├── di/AppModule.kt                 Reserved (Phase 2)
├── domain/WatchState.kt            UI-safe snapshot + GATT description types
└── ui/
    ├── home/HomeScreen.kt          Live dashboard + scanner + recon tools
    ├── home/HomeViewModel.kt       HiltViewModel, StateFlow pipeline
    ├── permissions/PermissionsScreen.kt  Accompanist perms flow
    └── theme/Theme.kt              Material 3 + dynamic colour
```

### Why no third-party BLE library

The Android `BluetoothGatt` API is sufficient for standard services and gives
us byte-level access for vendor protocol RE later. Pulling in
`no.nordicsemi.android:ble` is on the table for Phase 2 if queueing + reliable
writes get painful.

## Roadmap

- **Phase 0 — Protocol RE:** ✅ shipped (done via decompiled APK + app log,
  see `docs/protocol-research.md`).
- **Phase 1 — Foundation:** ✅ shipped.
- **Phase 2 — Vendor protocol:** ✅ shipped (see `vendor/` package).
- **Phase 3 — Polish:** historical-data UI, settings persistence, watch-face push.
  subscribe to notifications characteristic, send a custom notification.
- **Phase 3 — Polish:** Material 3 settings screen, Health Connect export,
  workout history viewer.
- **Second watch — ColorFit band:** ✅ added as the separate `:band` app
  (MOYOUNG protocol, `docs/band-protocol.md`).

## Legal

This project doesn't ship any Noise or MOYOUNG proprietary code. Protocol
details (byte layouts, command ids, message types, the emoji table the band
can't draw) were worked out from the vendor apps for interoperability and are
re-implemented here.
