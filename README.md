# Colorfit Companion

A Kotlin Android companion app for the **Noise Colorfit Vivid Call 2** smartwatch,
built from scratch because Noise doesn't publish a developer SDK.

> This is a personal/hobby project. Reverse-engineered protocols may break with any
> firmware update. Not affiliated with Noise.

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

## Setup

1. Install Android Studio (Iguana or later).
2. Open this folder as a project. Studio will sync Gradle on first open.
   - The Gradle wrapper jar isn't checked in; Studio will fetch it automatically.
     If you ever want to build from CLI, run `gradle wrapper --gradle-version 8.7`
     once and commit `gradle/wrapper/gradle-wrapper.jar` + `gradle-wrapper.properties`.
3. Run the **app** configuration onto your phone (Android 8+).
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

## Legal

This project doesn't ship any Noise proprietary code or data. The watch's BLE
advertising name is filtered on for usability, no other vendor identifiers
are hard-coded.
