# Protocol research — Noise Colorfit Vivid Call 2

Source: `C:\Users\user\com.noisefit.prime\decomplied\` (jadx output of
NoiseFit Prime 1.1.20, October 2024, package `com.noisefit.prime`).
The app uses a third-party Chinese pedometer SDK at
`com.yc.pedometer.sdk.*` (likely 宇诚 / Yucheng or a sister vendor — same
family as HBandSDK / VeepooSDK). The watch itself is one of many BLE
smartwatches built on this shared SDK; the Colorfit Vivid Call 2 is one
specific firmware target.

## GATT topology

| Role | Service UUID | Char UUID | Notes |
|------|--------------|-----------|-------|
| Vendor data — legacy | `000055ff-…` | write `000033f1-…`, notify `000033f2-…` | BLE 4.x channel, 20-byte MTU |
| Vendor data — BLE 5  | `000056ff-…` | write `000034f1-…`, notify `000034f2-…` | 240-byte MTU, multi-day weather & dial push |
| "BP" secondary | `0000fff0-…` | write `0000fff6-…` | some long payloads route here regardless of variant |
| OTA (Dialog SPOTA) | `0000fef5-…` + 5 chars | | firmware push |
| OTA (Realtek RK)   | `0000d0ff-…` / `0000ffd3-…` | | alternate OTA |
| Alipay | `000057ff-…` | `000035f1-…` / `000035f2-…` | irrelevant for Colorfit |

Standard SIG services we still need:
- `0x180F` Battery + `0x2A19` Battery Level (one-shot read)
- `0x180D` Heart Rate + `0x2A37` HR Measurement (subscribe + CCCD enable)
- `0x180A` Device Information (`0x2A24`/`0x2A26`/`0x2A29` etc.)

## Command opcodes (phone → watch)

Single-byte unless noted. Decoded from `WriteCommandToBLE.java`.

| Hex | Command | Format |
|-----|---------|--------|
| `0xA1` | Read version | + `0x01` = DSP version |
| `0xA2` | Read battery | query → response `0xA2` + percent |
| `0xA3` | Sync time | `[A3, yr_hi, yr_lo, month+1, day, h, m, s]` |
| `0xA9` | User profile | 19-byte payload |
| `0xAA` | Get step/sleep status | query |
| `0xAB` | Short vibration | `[AB, 0,0,0, 1, type, sub, flag]` |
| `0xAD` | Delete all data | |
| `0xAF` | Language text push | chunked, 20-byte packets |
| `0xB2 0xFA` | Sync steps (legacy) | full sync |
| `0xB3 0xFA` | Sync sleep (legacy) | |
| `0xBA 0x01/0x02` | UV test / read | |
| `0xBB` | Has content push | query |
| `0xBD` | HR-headset sport | `[BD, sub, type]` |
| `0xC1 0x04` | Off-hook | answer call |
| `0xC4 0x01/0x03` | Open/close shake | DND toggle |
| `0xC5` | SMS text push | chunked, 20-byte packets, 18 bytes payload |
| `0xC6` | QQ/WeChat text push | chunked |
| `0xC7 sub` | BP control | `sub` ∈ {0x01..0x05} |
| `0xC8 0xFA` | Sync BP | |
| `0xCA` | Weather (current day) | 14-byte |
| `0xCB 0x01` | Weather (multi-day) | 19- or 21-byte |
| `0xD3` | Sedentary remind | 7- or 12-byte |
| `0xD5 0x01/0x02` | Password / captcha | default `"1234"` |
| `0xDB SYNC` | Query push display | bitfield of which apps watch accepts |
| `0xDF` | HV screen brightness | |
| `0xE5 0x11/0x00` | HR start/stop | |
| `0xE6 0xFA` | Sync HR history | |
| `0xE9 0x01/SYNC/0x00` | Body composition | |
| `0xF3` | `…` | |
| `0xF4 0xFA` | Sync sport types | |
| `0xF7 SYNC` | 24h HR query | |
| `0xF9 SYNC` | Query interface | tells us which protocol variant |
| `0xFB 0x01/0x00/-3` | HR calibration | start / stop / reset |
| `0xFC 0x01/0x00/-3` | Turn-wrist calibration | |
| `0xFF` | `…` | |

BLE-5 channel variants (write to `0x34F1` instead of `0x33F1`):
- `0xEB 0x02 0xFA` — sync all steps (with timestamp)
- `0x31 0x01` — sync all sleep (with timestamp)
- `0xEB/0xEC …` — other long commands

Sub-opcode constants:
- `0xFA` (`-6`) = "give me everything" full sync
- `0xFF` (`-1`) = reset / default
- `0x00` = stop
- `0x01` = start
- `TransportLayerPacket.SYNC_WORD` = query / current status — decompiles
  to `0xFA` (Realtek BBpro library constant)

## Response opcodes (watch → phone)

Dispatched on hex of `bytes[0..1]`. Most are symmetric with commands.

| Hex | Sub | Meaning |
|-----|-----|---------|
| `A2` | | battery percent |
| `A1` | … | BLE version / DSP version |
| `AA` | | step+sleep status |
| `A3` | | sync-time ack |
| `B1` | | live steps (realtime) |
| `B2` | … | historical steps; `B2 FD` = end of stream |
| `B3` | … | historical sleep; `B3 FD` = end |
| `C1`/`C3`/`C4` | | call/DND acks |
| `D1` | | `…` |
| `D5` | `00/01` | auth success / captcha required |
| `E5` | | live HR |
| `E6` | | historical HR |
| `F7` | `FD`/`03`/`04` | 24h HR end / real-time / max-min-avg |
| `F9` | | interface bitfield |
| `FB`/`FC` | | calibration responses |
| `FD` | | general "all done" marker |
| `34` | | SpO2 measurement |

## Notification app bitfield

From `PushMessageUtil.java` + `StatusbarMsgNotificationListener.isContinuePush()`.

There are **three** separate 32-bit masks (`isPushMessageDisplay1/2/3`), and the
same bit value means a different app in each group. A bare bit is therefore not
a unique app id — it only means something together with its group:

| App | Group | Bit | | App | Group | Bit |
|---|---|---|---|---|---|---|
| SMS | 1 | 1 | | Telegram | **2** | 1 |
| QQ | 1 | 2 | | Truecaller | 2 | 2 |
| WeChat | 1 | 4 | | Paytm | 2 | 4 |
| WhatsApp | 1 | 128 | | Zalo | 2 | 8 |
| Instagram | 1 | 8192 | | Outlook | 2 | 64 |
| Skype | 1 | 1024 | | PhonePe | 2 | 1024 |
| Twitter | 1 | 64 | | WhatsApp Business | **3** | 16 |

Bits ANDed with the watch's reply tell us whether the user enabled that app on
the watch side. `vendor/NotificationAppId.kt` flattens these into one namespace
for convenience, so its constants collide (`BIT_TELEGRAM == BIT_SMS == 1`) —
branch on the package name, not the bit, when you need to tell apps apart.

## Auth flow

After `STATE_CONNECTED` + service discovery:
1. App sends `[D5, 01, '1','2','3','4']` (8-byte password, ASCII).
2. Watch responds `D5 00` (accept) or `D5 01 …` (rejected, captcha
   required — bytes after `01` are the on-watch 4-digit display code).
3. On accept, app pushes `0xA3` (time), `0xA9` (profile), `0xF9 SYNC`
   (capability query), `0xDB SYNC` (push-display bitfield).

Default password is hardcoded to `"1234"` in the SDK. Most watches ship
with this; the captcha path triggers on first pair or after factory reset.

## Notification text push format

20-byte packet, 18 bytes of UTF-16LE text per chunk:

```
[opcode, chunk_index, text_byte_0, …, text_byte_17]
```

`opcode` = `0xC5` (SMS-style) or `0xC6` (QQ/WeChat). Up to 160 bytes
total (≈ 80 ASCII chars, 40 CJK chars).

The title is prepended on the phone side as `"AppName · title"`, then
sent as the body. The watch renders one chunk per packet with a brief
scroll animation; subsequent chunks replace the previous line.

## Weather push

Open-Meteo (free, no API key) → 7-day forecast → `VendorFrame.weatherPackets(...)`.

Verified against `WriteCommandToBLE.syncWeatherToBLE*`. Weather is **three
packets**, and temperatures are **single bytes** passed through `getAbsolute()`
(a plain magnitude — no sign, no ×10):

```
0xCB 0x01  (19 bytes, or 21 when the watch supports humidity+UV)
  [2] today condition   [3] 0
  [4] current temp      [5] max temp      [6] min temp
  [7..8] pm2.5 (BE)     [9..10] aqi (BE)
  [11..18] city name, GB2312 (original only fills this for zh locales)
  [19] humidity         [20] uv index            ← 21-byte variant only

0xCB 0x02  (18 bytes) days 2-5: 4 × [code, 0, max, min]
0xCB 0x03  (10 bytes) days 6-7: 2 × [code, 0, max, min]
```

WMO codes → Noise `conditionCode` via `WeatherConditionCode.fromWmo()`.

> An earlier version of this doc described a single 21-byte `0xCB` frame with
> ×10 temperatures, wind direction/level and visibility. That was wrong and the
> watch ignored it.

## Capability bitfield ("function list")

**Not** a command response — despite `0xF9` looking like a capability query,
the feature bitfield is obtained by **reading the vendor _write_
characteristics** during connection bring-up
(`BluetoothLeService.writeNotifyCommandIndex` cases 11/12 →
`onCharacteristicRead`). Each returns a 20-byte blob, and the words are
indexed from the **end**:

```
0x33F1  (legacy write char, aliased PASS_WORD_CHARACTERISTIC_UUID)
  [0..1] word 7   [2..4] word 6   [5..7] word 5   [8..10] word 4
  [11..13] word 3 [14..16] word 2 [17..19] word 1

0x34F1  (BLE 5 write char)
  [0..1] maxCommunicationLength   ← max payload the watch accepts
  [2..4] word 13  [5..7] word 12  [8..10] word 11
  [11..13] word 10 [14..16] word 9 [17..19] word 8
```

Each word is a big-endian unsigned integer (3 bytes, except word 7 = 2).
`GetFunctionList.isSupportFunction*(bit)` is `(word & bit) == bit`, where
`isSupportFunction` = word 1, `_Second` = word 2, … `_Seven` = word 7.

Bits that change the wire format:

| Check | Meaning |
|---|---|
| `isSupportFunction(4)` | full character set → push text as UTF-16BE; **otherwise GB2312** |
| `isSupportFunction_Sixth(2048)` | 21-byte weather packet 1 (adds humidity+UV) on the BLE 5 channel; otherwise 19-byte |
| `isSupportFunction_Second(4096)` | height-based stride for distance |
| `isSupportFunction_Third(64)` | profile carries fractional weight |
| `isSupportFunction_Fifth(2048)` | per-app push-display gating applies |

Implemented in `vendor/VendorCapabilities.kt`. Until the blob arrives the app
assumes the conservative baseline (GB2312 text, 19-byte weather).

## Verified wire formats

These were re-derived from the decompiled source after the first round of
"connects but shows garbage" bugs. Prefer these over anything above.

**Battery** — `DataProcessing.getBleBattery()`:
`Math.min(bArr[1] & 255, 100)`. Always the single byte at `[1]`, clamped to
100. Reading `[1..2]` as 16-bit LE is what produced readings like "334%".

**Steps, realtime `0xB1` and historical `0xB2`** — identical layout. The watch
reports **per hour**, never a daily total:

```
[1..2] year, BIG-endian, full year (0x07E8 = 2024) — not year-2000
[3] month (1-based)   [4] day   [5] hour
[6..7] steps in THAT hour, big-endian
```

Daily total = sum of every hour's bucket
(`mStepCount = tempSteps + currentHourStep`). `B2 FD` ends the history stream.
**Distance and calories are not sent by the watch** — the phone computes them
(`PedometerUtils`): `distance_km = steps × (height_cm × 0.418) / 100000`,
`kcal = weight_kg × 0.708 × metres / 1000`.

**Heart rate `0xE5`** — `[E5, sub, validFlag, bpm]`; valid when `bArr[2] == 0`,
value at `bArr[3]`. `sub == 0x11` is the live stream, `sub == 0x00` means the
detection finished.

**SpO2 `0x34`** — same shape as HR: `[34, sub, validFlag, value]`.
`sub == 0x11` = test opened (no value yet); `sub == 0x00` = finished, value at
`bArr[3]`, invalid if `bArr[2] != 0`. `bArr[1]` is the sub-opcode, **not** the
percentage.

**Blood pressure `0xC7`** — `sendBloodPressureTestCommand`: `0xC7 0x11` starts,
`0xC7 0x00` stops (NOT `0xC7 0x01`). Result frames share the HR/SpO2 shape:
`[C7, finishFlag, validFlag, systolic, diastolic]` — finished when byte[1] == 0
(non-zero while measuring), valid when byte[2] == 0, systolic at byte[3] and
diastolic at byte[4] (mmHg). Short frames `C7 FD`/`C7 FF`/bare `C7 11` and config
acks `C7 02..05` are status only. A separate "EL" BP variant (opcode `0x55`,
`bloodPressureRealTimeDataOperate2`, systolic/diastolic/HR at bytes 5/6/7) exists
for licensed-algorithm watches and is not used here.

**Start/stop HR** — `sendRateTestCommand`: `0xE5 0x11` starts, `0xE5 0x00`
stops. `0xD6 0x02` is a *different* command
(`sendKeyOpenDynamicOrStaticRate`) that switches sensor mode and cancels a
running test — never send it alongside `0xE5 0x11`.

**Time sync `0xA3`** — confirmed correct as documented above:
`[A3, year_hi, year_lo, month(1-based), day, hour24, minute, second]`.

**Notification text push** — `sendTextSectionKey()` + `sendC5FD()`:
- Text is UTF-16 **big-endian** (`GBUtils.string2unicode` emits `'A'` → `"0041"`).
- Chunks are `[0xC5, index] + 18 text bytes`; the **final partial chunk is sent
  at its real length, not zero-padded** to 20.
- A **`[0xC5, 0xFD]` terminator must follow the last chunk** — without it the
  watch buffers the chunks and never renders the message.
- Characters below 0x20 (including `\n`), U+007F–U+009F, U+2005 and anything
  outside the BMP (emoji) are stripped by the encoder.

**Short alert `0xAB`** — `sendSmsCommand(type)`:
`[AB, 0, 0, 0, 1, type, 0, 0]`. The alert **type** is in byte[5] (5 = SMS-style,
1 = chat/app-style) and bytes[6..7] are zero. It is *not* the app bitfield.

## Open questions / follow-ups

- **Account ID** (`0x33` opcode, 4-byte LE id) — newer firmwares may
  require this BEFORE password. Watch `0xF9` response bitmask to detect.
- **Captcha display encoding** — bytes after `D5 01` look like BCD
  in NoiseFit Prime source but we treat them as hex until we see a
  real captcha flow.
- **Notification capability** — once we receive `0xDB` response, only
  push apps whose bits the watch has enabled (don't spam the user's
  watch with apps they turned off in NoiseFit Prime settings).
- **Watch face push** (`0x26` series) — needs the BLE 5 channel for
  >20-byte chunks. Documented in Noise app but not used by Colorfit
  Vivid Call 2 out of the box. Lower priority.

## Source-of-truth references

- `decomplied/sources/com/yc/pedometer/sdk/WriteCommandToBLE.java` — every
  command packet and timeout type.
- `decomplied/sources/com/yc/pedometer/sdk/BluetoothLeService.java` —
  response dispatch (~50 `strSubstring.equals("XX")` branches).
- `decomplied/sources/com/yc/pedometer/utils/UUIDUtils.java` — service
  and characteristic UUIDs.
- `decomplied/sources/com/yc/pedometer/utils/PushMessageUtil.java` —
  notification app bitfield.
- `decomplied/sources/com/yc/pedometer/utils/CRCUtils.java` — CRC8 used
  by BP calibration commands.
- `decomplied/sources/com/yc/pedometer/oxygen/OxygenUtil.java` — SpO2
  measurement flow.
- `decomplied/sources/com/yc/pedometer/weather/GetWeather.java` —
  weather command paths (legacy `0xCA` + multi-day `0xCB`).
