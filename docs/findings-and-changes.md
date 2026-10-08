# Findings and changes — protocol audit, fixes and tooling

Work done against the Noise Colorfit **Vivid Call 2** (firmware `RH281LDCBV005877`)
on a Moto G32 (Android 13). Everything here was checked against one of three
sources; each claim says which.

| Tag | Source |
|-----|--------|
| **[java]** | Decompiled NoiseFit Prime 1.1.20, `com.yc.pedometer.*` (mainly `sdk/BluetoothLeService`, `sdk/WriteCommandToBLE`, `sdk/DataProcessing`) |
| **[capture]** | The original app's own log of its traffic with this watch (`20260915.log`, ~47 MB): every command it wrote (`APK--->BLE…`) and every reply (`BLE--->APK…`) |
| **[phone]** | This app's own TX/RX log read from the phone over `adb logcat` (tag `BleGatt`) |

Nothing here was tested by pressing buttons on the watch itself; the watch-side
behaviour is inferred from what it sent back.

---

## 1. How the original app talks to the watch

### Transport [java][capture]

Two vendor GATT services, each a write + notify pair:

| Channel | Service | Write | Notify | Used for |
|---------|---------|-------|--------|----------|
| main ("BLE4") | `55ff` | `33f1` | `33f2` | almost every command, ≤20-byte frames |
| secondary ("BLE5") | `56ff` | `34f1` | `34f2` | only a few long commands: city name (`CB FF`), music title (`3A`), quick replies (`46 FA`), sport list |

The original sends to `34f1` **only** through `writeCharaBle5()`. Everything else
goes to `33f1`. Replies to both come back on `33f2` [capture].

### Connection bring-up [java][capture]

1. `connectGatt(autoConnect=false, TRANSPORT_LE)`, 35 s timeout.
2. 800 ms after connect: `discoverServices()`.
3. Read `33f1` → 20-byte capability blob (see §4).
4. Enable notify on `33f2`; read `34f1` (first 2 bytes = max payload, `00F4` = 244);
   enable notify on `34f2`; `requestMtu(247)`.
5. Auth: write `33 01`; a bound watch replies `33 04 01`. Only then does the
   command queue start.

### Command discipline [java][capture]

Writes go through a busy lock, one at a time. The original also waits for the
watch's reply (3 s timeout, one retry) before sending the next command. See §3
for why this matters.

### Post-auth sync order seen in the capture

`A3` time → `A1` version → `38 01…` (BLE5) device name → `BB` → `26 01` dial
config → `BE 01/02` → `A9` profile → `A2` battery → `DB AA` push masks →
`B2 FA` steps → `31 01` sleep → `F7 FA` 24 h heart rate → `34 FA` SpO₂ history →
settings (`D3/D4/41`, `3F`, `A0`, `51`, `44`, `46`).

---

## 2. Wire formats confirmed

### Notifications (any app) [java][capture]

```
phone→watch on 33f1:
  C5 00 <type> <len> <16 bytes of text>     first chunk
  C5 01 <18 bytes>                          … one chunk per 18 bytes
  C5 nn <remainder>                         last chunk, NOT padded
  C5 FD                                     "render it"
  AB 00 00 00 01 01 00 00                   vibrate
watch→phone: C5 <idx> after each chunk, C5 FD <type> <len> after the terminator, AB 00 after vibrate
```

* Message = `[appType, byteLength] + UTF-16BE("Title:Body")`, ≤160 bytes
  (`GBUtils.hexStringToBytess` + `string2unicode`). The 2-byte header was missing
  from this app before; without it the watch reads the first character as type+length.
* Emoji, control chars and U+2005 are dropped (`string2unicode`).
* Real example (Telegram, `0x18`): `C5 00 18 4E 0054 0065 …` [capture].
* App type byte comes from `StatusbarMsgNotificationListener.getAppType()`; it
  picks the icon. Telegram (24) and Gmail (19) are confirmed in the capture; the
  rest are from the Java. Full table: `NotificationAppId.kt`.
* The original drops a notification that arrives <200 ms after the previous one
  from the same app (`isMsgTooFast`) or repeats identical text.

### Push-display masks (`DB AA`) [java][capture]

20-byte reply; masks are 3-byte big-endian words counted from the end:
`mask1 = bytes[17..19]`, `mask2 = bytes[14..16]`. Captured:
`DB AA 00…00 000001 FFFFFF` → mask1 `FFFFFF`, mask2 `000001`.
The old parser read 4-byte little-endian words from bytes 1–8.

### Weather [java][capture][phone]

* `CB 01` (19 B, or 21 B with humidity+UV when the watch advertises it) today;
  `CB 02` days 2–5; `CB 03` days 6–7; each acked with `CB <sub>`.
* `CB FF <len> <UTF-8 name>` (BLE5, ≤100 B) sets the place name; acked `CB FF`.
* **Condition codes are 1-based** (`weatherConditionCode()`): 1 sunny, 2 cloudy,
  3 overcast, 4 shower, 5 thunder, 6 sleet, 7 light rain, 8 heavy rain, 9 snow,
  10 sand/dust, 11 fog/haze, 12 windy. The old table was 0-based, so every icon
  was one step off.
* **Temperatures use sign-magnitude** (`getAbsolute()`): magnitude in 7 bits,
  bit 7 set for below zero (−5 °C → `0x85`). The old code dropped the sign.

### Steps [java][capture]

`B2 FA` → many `B2 yy yy mm dd hh ss ss …` (one per hour, steps big-endian), then
`B2 FD`. The watch never sends a daily total; the day's figure is the sum of the
hour buckets. Live updates arrive as unsolicited `B1` frames of the same shape.
Distance and calories are computed on the phone.

### Sleep [java][capture]

Band-algorithm watches (`isSupportFunction_Fourth(262144)`, as this one is):
`31 01` → per day `31 01 yy yy mm dd <count>`, then `32 …` data chunks, finally
`31 02`. Data chunks are 6-byte sections
`[start_h, start_m, state+1, ?, duration_hi, duration_lo]`.
**Unverified on real data** — see §6.

### 24-hour heart rate [java][capture][phone]

Request `F7 FA` (+ 6 zero bytes = "never synced"). Replies:
`F7 yy yy mm dd hh <12 samples, 10 min apart>`, valid when 40 < bpm < 200,
`FF` = no sample; `F7 FD` ends. Live heart rate is `E5 11 00 <bpm>` once per second.

### Auth [java][capture]

`33 01` → `33 04 01` (bound). Other replies: `33 04 02` id stored,
`33 04 03/04` confirm pairing on the watch, `33 04 05` user cancelled. The `D5`
password flow exists in the SDK but the capture shows it is **not** used on this
watch (157 × `33 01`, 117 × `33 04 01`, zero `D5`).

### `SYNC_WORD` = `0xAA`, not `0xFA` [java][capture]

`TransportLayerPacket.SYNC_WORD = -86`. Query frames are `DB AA`, `FD AA`, `51 AA`.
The code had `0xFA`, which turned `DB AA` into `DB FA`.

### Blood pressure: not supported by this watch [java][capture]

Capability word 1 on this watch is `0x4BE1DC`; the blood-pressure bit
(`0x100000`) is not set, and the firmware prefix `RH` is "heart rate only" in
`SyncParameterUtils`. The original app logs `isSupportBP= false` on every
connect and the capture contains zero `C7`/`C8` frames. The `C7 11` / `C7 00`
commands the app sent match the original, so there was nothing to fix. The UI
for it was removed (§5).

### Capability words [java][capture]

`33f1` read: `08084632ED2C3947756FFFFAD921005F784BE1DC` →
word1 `4BE1DC`, w2 `005F78`, w3 `FAD921`, w4 `756FFF`, w5 `2C3947`, w6 `4632ED`,
w7 `0808`. Words are indexed from the end of the blob. Relevant bits:
word1 `4` full charset (UTF-16 text), word4 `262144` band sleep, word4 `8192`
timestamped sync, word6 `2048` 21-byte weather, word5 `4` account-id auth.

---

## 3. Bugs found and fixed

| # | Bug | Evidence | Fix |
|---|-----|----------|-----|
| 1 | Notification text had no `[type, length]` header | [java] `hexStringToBytess`, [capture] `C5 00 18 4E …` | `VendorFrame.pushNotificationText` |
| 2 | No per-app type, so no app icon | [java] `getAppType` | `NotificationAppId.typeForPackage` |
| 3 | Chunks fired without waiting for `C5 <idx>` acks | [capture] ack after each chunk | `request()` awaits each ack |
| 4 | Query sub-byte `0xFA` instead of `0xAA` | [java] `SYNC_WORD = -86` | `VendorOpcodes` |
| 5 | Auth used `D5` password | [capture] only `33 01 / 33 04 01` | `accountIdQuery()`; `D5` only if word1 bit 1 |
| 6 | Sleep sent `B3 FA`, HR history `E6 FA` | [capture] `31 01`, `F7 FA` | capability-driven `syncAllSleep` / `sync24HourRate` |
| 7 | `F9 FA` keep-alive; `F9` never appears in the capture | [capture] | keep-alive is now `A2` (battery) |
| 8 | `DB` masks parsed from the wrong bytes | [capture] frame layout | `be24()` at 17 and 14 |
| 9 | Commands went to `34f1` | [java] `writeChara` vs `writeCharaBle5`; [phone] `B2/31` unanswered there | `writeVendorCommand(ble5 = false)` default |
| 10 | Ten commands written back-to-back; watch answered a few and ignored `B2 FA`, `31 01`, `E5 11` | [phone] unpaced run: no `B2`/`31` replies; paced run: all answered, in order | `request()` + `commandMutex` (one command in flight, wait for reply or timeout) |
| 11 | Frames handled concurrently, so multi-frame streams could interleave | design | single `Channel` consumer |
| 12 | Weather icon codes 0-based; negative temperatures lost sign | [java] | `WeatherConditionCode`, `absTemp` |
| 13 | No weather place name | [java] `syncWeatherEnglishCityName` | `weatherCityName`, sent on BLE5 |
| 14 | "Sync history" button sent the old commands | code review | goes through `syncHistory()` |
| 15 | Docs said UTF-16LE and `31 01` on `34f1` | [java][capture] | `docs/opcode-reference.md` corrected |

Evidence for #10: three runs on the phone. Run A wrote everything to `34f1`
unpaced → `B2`/`31` unanswered. Run B wrote to `33f1`, still unpaced → still
unanswered. Run C wrote to `33f1` **and** waited for each reply → all answered.
So the channel was not the deciding factor; the pacing was.

---

## 4. The "no data / reconnect loop" problem — an Android-side block

Symptom: app connects, shows nothing, and the link drops and reconnects every
~30 s (GATT status 19).

Found with the phone's logcat [phone]:

```
op notify vendor 000033f2-…  accepted=false
    error=java.lang.SecurityException: Need BLUETOOTH PRIVILEGED permission
op read caps 000033f1-…      accepted=false  (same error)
```

* Android refused the app any access to the **`55ff` service** (`33f1`/`33f2`) —
  the main data channel. `56ff` (`34f1`/`34f2`) was fine. With `33f2` notify not
  enabled, the watch's replies to nearly every command never reach the app.
* After the `SecurityException` on a read/write, `BluetoothGatt` leaves its
  internal busy flag set, so every later write is rejected. The watch hears
  nothing and hangs up after 30 s — the reconnect loop.
* **Cause is not proven.** The phone's Bluetooth stack has `isRestrictedSrvc` /
  `restrictedHandles` (found by scanning its dex strings; the list itself was not
  recoverable). My best explanation is a stale restricted-handle entry in the
  stack. Restarting Bluetooth cleared it — after that the capability read, `33f2`
  notifications and all replies worked with no `SecurityException`.
* The original app (`com.noisefit.prime`) and **Da Fit** (`com.crrepa.band.dafit`)
  are also installed and were seen holding GATT clients/running; they may
  interfere. Not proven either way.

What the app does about it now (`BleConnectionManager`):

* Each queued op can name its service. On a `SecurityException` the service is
  added to `restrictedServices` and later ops on it are skipped, so one denial
  can't poison the queue. Writes then fall back to `34f1`.
* The UI error list says: *"Android is blocking the watch's main data channel
  (55FF)… Turn Bluetooth off and on, then reconnect."*
* `VendorCapabilities` falls back to this watch's captured words when the
  `33f1` read is blocked, instead of assuming nothing is supported (which would
  pick GB2312 text and the legacy sync commands).

---

## 5. Features added / removed

* **Watch notifications screen** (bell icon, top right of Home): per-app switch
  for which notifications are mirrored. Apps with a watch icon default **on**;
  anything else appears after its first notification and defaults **off**.
  `NotificationFilter` (SharedPreferences), `NotificationAppsScreen`,
  `NotificationRelayService` (consults the filter, throttles like the original).
  The switches only control what is pushed to the watch; phone notifications are
  unaffected.
* **Sleep and Heart-rate-history cards** on Home (`ui/home/HistoryCards.kt`),
  fed by the synced history list. Sleep: last 7 nights with total, deep/light
  split and a proportion bar. Heart rate: one row per day (lowest–highest, average)
  plus a bar chart of the latest day's 2-hour averages. Repeated syncs are
  de-duplicated. Until the watch sends sleep data the sleep card says so.
* **Blood pressure UI removed** (tile + Start/Stop BP buttons) — the watch has
  no BP sensor (§2). The frame builders/parser are left in place, unused.
* **Weather place name** pushed to the watch.
* **Debug logging** (debug builds only, Timber tag `BleGatt`): every TX/RX frame
  with the characteristic's short UUID, each GATT op's accept/timeout, connection
  state and the service table.

---

## 6. Verified vs not verified

**Verified on the phone log [phone]:** auth `33 04 01`; time/profile/battery/
version/push-mask replies; step history (`B2`) through to `B2 FD`; sleep request
answered with day headers and `31 02`; 24 h HR history through `F7 FD`; weather
`CB 01/02/03/FF` all acked; notification path unchanged from the capture's format.

**Not verified:**

* **Sleep records.** The watch reported every night 27 Sep–3 Oct with zero sleep
  data, so the `32` data-chunk parser has never seen a real record. The stage
  numbering (0 deep, 1 light, 2 awake) is an assumption.
* **Steps since 29 Sep.** The watch's last step bucket was 29 Sep; if it was
  worn since, something is still wrong.
* **Live heart rate auto-start.** `E5 11` got no reply within its 800 ms window
  in the last run; the Start HR button is the fallback.
* **Notification delivery on the watch face** after the header/type fix was not
  observed end-to-end (the log shows the frames; the display wasn't checked).
* **First-time pairing** (`33 04 03/04`). This watch was already bound.
* **GB2312 text path** (watches without the full charset).
* The restart-Bluetooth workaround may need repeating if the block returns.

---

## 7. Files changed

`ble/BleConnectionManager.kt`, `vendor/VendorConnection.kt`,
`vendor/VendorFrame.kt`, `vendor/VendorResponse.kt`, `vendor/VendorOpcodes.kt`,
`vendor/VendorCapabilities.kt`, `vendor/VendorGattSpec.kt`,
`vendor/NotificationAppId.kt`, `vendor/NotificationRelayService.kt`,
`vendor/WeatherService.kt`, `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`,
`MainActivity.kt`, `AndroidManifest.xml` (package-visibility `<queries>`),
`docs/opcode-reference.md`.
New: `vendor/NotificationFilter.kt`, `ui/notifications/NotificationAppsScreen.kt`,
`ui/home/HistoryCards.kt`, this file.

## 8. How to reproduce the checks

```bash
# build and install (debug)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# watch the traffic (TX = phone→watch, RX = watch→phone)
adb logcat -c && adb logcat -s BleGatt:V Home:V Weather:V
```

The original app's traffic log lives in the decompiled folder
(`cache/NoiseFitPrime/logs/AllLogs/20260915.log`); grep `APK--->BLE4|5 =` for
commands and `BLE--->APK4|5 =` for replies.

## 9. Publishing

Pushed to `github.com/manish2k10/Android-App-for-Colorfit-Vivid-Call-2` (`main`,
commit `2ba455e`, authored as `manish2k10`). The first push failed with 403
because the global `~/.gitconfig` pins `credential.https://github.com.username`
to a different GitHub account; the repo-local override
`credential.https://github.com.username = manish2k10` fixed it without touching
the global config.

## 10. Background survival ("keep running always")

The app was getting killed after a few minutes in the background (reported on a
Moto G32 — Motorola is aggressive about background cleanup). Causes and fixes:

* **Not battery-optimization exempt.** The dominant cause. With the app
  battery-optimized, Doze/App-Standby and the OEM cleanup kill the process and
  drop the BLE link regardless of the foreground service. Added
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, a `PowerSettings` helper, and a
  warning card (`BackgroundPermissionCard`) that fires
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and disappears once granted
  (re-checked on `ON_RESUME`).
* **`startService()` instead of `startForegroundService()`.** Illegal from the
  background on API 26+. All starts now go through
  `ContextCompat.startForegroundService`, wrapped in `runCatching` to swallow
  the API-31+ background-start refusal, with
  `FOREGROUND_SERVICE_IMMEDIATE` so the service is promoted at once.
* **Service only alive while actively connected.** On a cold launch with
  auto-reconnect, nothing held the process up. The FGS is now started whenever
  a watch is bonded — at app launch (`App.onCreate`) and after reboot/update
  (`BootReceiver` on `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`) — and only torn
  down on "Forget watch". `WatchPrefs` is the shared "is a watch bonded?"
  check so these entry points don't need the Hilt graph.
* **Sticky restart.** `START_STICKY` is kept; a null-intent restart re-asserts
  foreground. A deliberate Stop returns `START_NOT_STICKY` so it isn't revived.

OEM auto-start / "Background restriction" toggles (Motorola et al.) can't be
set by any reliable public intent — the card links to App info and spells out
the manual steps.

New files: `ble/WatchPrefs.kt`, `ble/PowerSettings.kt`, `ble/BootReceiver.kt`.
Touched: `AndroidManifest.xml`, `App.kt`, `BleForegroundService.kt`,
`HomeViewModel.kt`, `HomeScreen.kt`.
